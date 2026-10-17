package com.opentools.qqexport;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 多选栏改造（QQ 9.2.35 实测路径）。
 *
 * 两件事：
 *   A. 在 MultiSelectBarVB 的操作栏里插一个“导出”按钮；
 *   B. 点按钮时**现取**QQ 当前勾选的消息列表，不再旁听事件。
 *
 * 为什么不再旁听事件：旧实现 hook 了 MultiSelectEvent$* 共 5 个类的构造器（24 个），
 * 靠“按字段类型猜”把勾选状态镜像下来，既脆弱（字段名/类型一变就错），
 * 又容易把长按菜单带崩。改成现取后，这条链路完全依赖 QQ 自己的读接口：
 *
 *   multifoward.b.a                       （静态单例）
 *   multifoward.b.g(com.tencent.aio.api.runtime.a) -> List<AIOMsgItem>
 *   FrameworkVM.getMContext() -> com.tencent.mvi.api.runtime.b（实际是 aio runtime ctx）
 *
 * 其中 g 的真实签名（classes5.dex 实测）：
 *   M inst  g  (Lcom/tencent/aio/api/runtime/a;)Ljava/util/List;
 * 运行时 ctx 由 MultiSelectBarVM（FrameworkVM 子类）的 getMContext() 拿到，
 * 同时我们也 hook 住 g() 自身，把最近一次的入参/返回值留作兜底——
 * 这样即使 VM 那条路走不通，也能用上一次 QQ 自己算出来的勾选列表。
 *
 * 布局来源：MultiSelectBarVB$mOperationLayout$2.invoke() 返回
 *   LinearLayout（classes5.dex 实测），这是真正造操作栏的地方，直接插进去最稳。
 */
public final class MultiSelectHook {

    private static final String CLS_BAR = "com.tencent.mobileqq.aio.input.multiselect.MultiSelectBarVB";
    private static final String CLS_BAR_LAMBDA = CLS_BAR + "$mOperationLayout$2";
    private static final String CLS_FWD = "com.tencent.mobileqq.aio.msglist.holder.component.multifoward.b";

    private static final String TAG = "qq-export-multiselect-btn";

    /** VB 实例 -> 操作栏根布局（弱引用，页面销毁自动回收） */
    private static final Map<Object, ViewGroup> ROOTS =
            Collections.synchronizedMap(new WeakHashMap<Object, ViewGroup>());

    /** 最近一个 MultiSelectBarVB 及其 VM */
    private static volatile Object sBar;
    private static volatile Object sBarVM;
    /** multifoward.b 单例（懒读，见 forward()） */
    private static volatile Object sFwd;
    /** multifoward.b 里的静态自身字段 a；只记字段不读值 */
    private static volatile Field sFwdField;
    /** 读取失败一次就别再试，避免每次都触发类初始化异常 */
    private static volatile boolean sFwdBroken;
    /** 最近一次 g(ctx) 的入参（aio runtime context）与返回（勾选列表） */
    private static volatile Object sLastCtx;
    private static volatile List<?> sLastPicked;

    private MultiSelectHook() {
    }

    public static void install(ClassLoader cl) {
        try {
            hookBar(cl);
        } catch (Throwable t) {
            L.e(t);
        }
        try {
            hookForward(cl);
        } catch (Throwable t) {
            L.e(t);
        }
    }

    /* ==================== A. 多选栏按钮 ==================== */

    private static void hookBar(ClassLoader cl) {
        final Class<?> bar;
        try {
            bar = XposedHelpers.findClass(CLS_BAR, cl);
        } catch (Throwable t) {
            L.e("MultiSelectBarVB 不存在: " + t);
            return;
        }

        int n = 0;

        // A.1 无参构造器：VB 一建好就记下实例
        for (Constructor<?> c : bar.getDeclaredConstructors()) {
            if (c.getParameterCount() != 0) continue;
            try {
                XposedBridge.hookMethod(c, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        sBar = p.thisObject;
                        attach(p.thisObject);
                    }
                });
                n++;
            } catch (Throwable ignored) {
            }
        }

        // A.2 操作栏真正的产出点：MultiSelectBarVB$mOperationLayout$2.invoke()
        try {
            Class<?> lam = XposedHelpers.findClass(CLS_BAR_LAMBDA, cl);
            for (Method m : lam.getDeclaredMethods()) {
                if (!"invoke".equals(m.getName()) || m.getParameterCount() != 0) continue;
                if (!ViewGroup.class.isAssignableFrom(m.getReturnType())) continue;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (!(p.getResult() instanceof ViewGroup)) return;
                            ViewGroup root = (ViewGroup) p.getResult();
                            Object vb = outerOf(p.thisObject);
                            if (vb != null) {
                                ROOTS.put(vb, root);
                                sBar = vb;
                            }
                            attach(vb, root);
                        }
                    });
                    n++;
                    L.i("已挂 mOperationLayout lambda");
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            L.e("mOperationLayout lambda 不可用: " + t);
        }

        // A.3 缓存 VM，供点击时取 aio runtime context
        for (Method m : allMethods(bar)) {
            if (!"createVM".equals(m.getName()) || m.getParameterCount() != 0) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        if (p.getResult() != null) sBarVM = p.getResult();
                    }
                });
                n++;
            } catch (Throwable ignored) {
            }
        }

        // A.4 bindViewAndData 会遍历子 View 调 setAlpha/setVisibility，绑完要重新亮出来
        for (Method m : allMethods(bar)) {
            String name = m.getName();
            if (!"bindViewAndData".equals(name) && !"onCreateView".equals(name)) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        attach(p.thisObject);
                    }
                });
                n++;
            } catch (Throwable ignored) {
            }
        }

        XposedBridge.log("[QQExport] MultiSelectBarVB hooked: " + n);
    }

    private static void attach(Object vb) {
        attach(vb, rootOf(vb));
    }

    private static void attach(Object vb, ViewGroup root) {
        try {
            if (root == null) return;

            View old = root.findViewWithTag(TAG);
            boolean want = Prefs.bool(Prefs.K_MULTI, Prefs.D_MULTI);

            if (old != null) {
                old.setVisibility(want ? View.VISIBLE : View.GONE);
                old.setAlpha(1f);
                return;
            }
            if (!want) return;

            Context ctx = root.getContext();
            if (ctx == null) return;

            final TextView btn = new TextView(ctx);
            btn.setTag(TAG);
            btn.setText("导出");
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(12);
            btn.setGravity(Gravity.CENTER);

            int p = dp(ctx, 6);
            btn.setPadding(dp(ctx, 10), p, dp(ctx, 10), p);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF3482FF);
            bg.setCornerRadius(dp(ctx, 13));
            btn.setBackground(bg);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_VERTICAL;
            lp.leftMargin = dp(ctx, 4);
            lp.rightMargin = dp(ctx, 8);
            btn.setLayoutParams(lp);

            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        open(v);
                    } catch (Throwable t) {
                        L.e(t);
                        Toast.makeText(v.getContext(), "导出失败: " + t, Toast.LENGTH_SHORT).show();
                    }
                }
            });

            root.addView(btn);
            L.i("多选栏按钮已插入，children=" + root.getChildCount());
        } catch (Throwable t) {
            L.e(t);
        }
    }

    /**
     * 取多选栏操作栏根布局：
     *  1) lambda 已经产出过就直接用缓存；
     *  2) 退化到 K1()/C1()/J1()（J1 是 static，invoke 时 target 会被忽略）；
     *  3) 最后按“无参且返回 ViewGroup、子 View 最多”扫描。
     */
    private static ViewGroup rootOf(Object vb) {
        if (vb == null) return null;

        ViewGroup cached = ROOTS.get(vb);
        if (cached != null) return cached;

        for (Method m : allMethods(vb.getClass())) {
            if (m.getParameterCount() != 0) continue;
            if (!ViewGroup.class.isAssignableFrom(m.getReturnType())) continue;
            String name = m.getName();
            if (!"K1".equals(name) && !"C1".equals(name) && !"J1".equals(name)) continue;
            Object r = call(vb, m);
            if (r instanceof ViewGroup && ((ViewGroup) r).getChildCount() > 0) {
                ROOTS.put(vb, (ViewGroup) r);
                return (ViewGroup) r;
            }
        }

        ViewGroup best = null;
        for (Method m : allMethods(vb.getClass())) {
            if (m.getParameterCount() != 0) continue;
            if (!ViewGroup.class.isAssignableFrom(m.getReturnType())) continue;
            Object r = call(vb, m);
            if (!(r instanceof ViewGroup)) continue;
            ViewGroup g = (ViewGroup) r;
            if (g.getChildCount() < 2) continue;
            if (best == null || g.getChildCount() > best.getChildCount()) best = g;
        }
        if (best != null) ROOTS.put(vb, best);
        return best;
    }

    private static void open(View v) {
        Activity act = activityOf(v.getContext());
        if (act == null) {
            L.e("找不到 Activity，无法弹窗");
            return;
        }
        Prefs.reload();
        List<Msg> picked = fetchPicked();
        L.i("点击导出，现取勾选 " + picked.size() + " 条");
        if (!picked.isEmpty()) {
            Store.ingest(picked);
            PickedExport.show(act, picked);
        } else {
            Toast.makeText(act, "没读到勾选列表，改为按时间范围导出", Toast.LENGTH_SHORT).show();
            ExportDialog.show(act);
        }
    }

    /* ==================== B. 现取勾选列表 ==================== */

    private static void hookForward(ClassLoader cl) {
        Class<?> fwd;
        try {
            fwd = XposedHelpers.findClass(CLS_FWD, cl);
        } catch (Throwable t) {
            L.e("multifoward.b 不存在: " + t);
            return;
        }

        // 静态单例字段 a：这里只记字段，绝不读值。
        // 9.2.35 实测：multifoward.b 的 <clinit> 里会碰 ViewUtils，而 ViewUtils
        // 的 <clinit> 依赖 ApplicationDelegate.getResources()。handleLoadPackage
        // 阶段 Application 还没建好，这里读一次就会 ExceptionInInitializerError，
        // 类被永久标记为 errored，之后 QQ 自己的线程碰到它直接 NoClassDefFoundError
        // -> 启动即闪退（crash_20261018_022046 就是这么来的）。
        // getDeclaredFields/hookMethod 都不会触发 <clinit>，所以这段是安全的。
        for (Field fd : fwd.getDeclaredFields()) {
            if (!Modifier.isStatic(fd.getModifiers())) continue;
            if (fd.getType() != fwd) continue;
            try {
                fd.setAccessible(true);
                sFwdField = fd;
            } catch (Throwable ignored) {
            }
        }

        int n = 0;
        for (Method m : fwd.getDeclaredMethods()) {
            if (m.getParameterCount() != 1) continue;
            if (!List.class.isAssignableFrom(m.getReturnType())) continue;
            if (!m.getParameterTypes()[0].getName().startsWith("com.tencent.aio.api.runtime")) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (p.args.length > 0 && p.args[0] != null) sLastCtx = p.args[0];
                            Object r = p.getResult();
                            if (r instanceof List) sLastPicked = (List<?>) r;
                        } catch (Throwable ignored) {
                        }
                    }
                });
                n++;
                L.i("已挂 " + m.getName() + " 取勾选列表");
            } catch (Throwable ignored) {
            }
        }
        XposedBridge.log("[QQExport] multifoward hooked: " + n);
    }

    /** 现取 + 兜底 + 转换去重 */
    private static List<Msg> fetchPicked() {
        List<?> raw = null;
        String from = "none";

        try {
            raw = queryPicked();
            if (raw != null) from = "live";
        } catch (Throwable t) {
            L.e("现取失败: " + t);
        }

        if (raw == null || raw.isEmpty()) {
            raw = sLastPicked;
            from = "cached";
        }

        LinkedHashMap<Long, Msg> map = new LinkedHashMap<>();
        if (raw != null) {
            for (Object o : raw) {
                Msg m = Msg.from(o);
                if (m == null || m.uniseq == 0L) continue;
                map.put(m.uniseq, m);
            }
        }
        L.i("勾选来源=" + from + " raw=" + (raw == null ? -1 : raw.size()) + " -> " + map.size());
        return new ArrayList<>(map.values());
    }

    /**
     * 懒取 multifoward.b 的单例。只在用户点“导出”时调用，
     * 那时 Application/Resources 早已就绪，不会踩 <clinit> 的坑。
     */
    private static Object forward() {
        if (sFwdBroken) return null;
        Field fd = sFwdField;
        if (fd == null) return null;
        try {
            Object v = fd.get(null);
            if (v != null) sFwd = v;
            return v;
        } catch (Throwable t) {
            sFwdBroken = true;
            L.e("读 multifoward 单例失败，本次会话不再尝试: " + t);
            return null;
        }
    }

    /** multifoward.b.a.g(ctx) —— QQ 自己算出来的当前勾选列表 */
    private static List<?> queryPicked() {
        Object inst = sFwd;
        if (inst == null) inst = forward();
        if (inst == null) return null;

        Object ctx = sLastCtx;
        if (!isAioCtx(ctx)) ctx = vmContext();
        if (!isAioCtx(ctx)) {
            L.i("拿不到 aio runtime context，跳过现取");
            return null;
        }

        Method g = msgListMethod(inst.getClass());
        if (g == null) {
            L.e("multifoward 里找不到取列表方法");
            return null;
        }
        try {
            Object r = g.invoke(inst, ctx);
            return r instanceof List ? (List<?>) r : null;
        } catch (Throwable t) {
            L.e("调用 g(ctx) 失败: " + t);
            return null;
        }
    }

    private static Object vmContext() {
        Object vm = sBarVM;
        if (vm == null) return null;
        Object fallback = null;
        for (Method m : allMethods(vm.getClass())) {
            if (m.getParameterCount() != 0) continue;
            String name = m.getName();
            if (!"getMContext".equals(name) && !"getSafetyContext".equals(name)) continue;
            Object r = call(vm, m);
            if (r == null) continue;
            if (isAioCtx(r)) return r;
            if (fallback == null) fallback = r;
        }
        return fallback;
    }

    private static Method msgListMethod(Class<?> c) {
        for (Method m : allMethods(c)) {
            if (m.getParameterCount() != 1) continue;
            if (!List.class.isAssignableFrom(m.getReturnType())) continue;
            if (!m.getParameterTypes()[0].getName().startsWith("com.tencent.aio.api.runtime")) continue;
            return m;
        }
        return null;
    }

    private static boolean isAioCtx(Object o) {
        if (o == null) return false;
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            if (k.getName().startsWith("com.tencent.aio.api.runtime")) return true;
        }
        return false;
    }

    /* ==================== 反射小工具 ==================== */

    private static List<Method> allMethods(Class<?> c) {
        List<Method> out = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) out.add(m);
        }
        return out;
    }

    private static Object call(Object target, Method m) {
        try {
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** lambda 里的 this$0，即外部 MultiSelectBarVB */
    private static Object outerOf(Object lambda) {
        if (lambda == null) return null;
        for (Class<?> k = lambda.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!"this$0".equals(f.getName())) continue;
                try {
                    f.setAccessible(true);
                    return f.get(lambda);
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static Activity activityOf(Context c) {
        Context k = c;
        int guard = 0;
        while (k != null && guard++ < 12) {
            if (k instanceof Activity) return (Activity) k;
            if (k instanceof ContextWrapper) k = ((ContextWrapper) k).getBaseContext();
            else return null;
        }
        return null;
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }
}
