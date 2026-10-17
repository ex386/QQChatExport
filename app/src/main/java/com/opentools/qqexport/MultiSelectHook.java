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
 *   B. 点按钮时取出 QQ 当前勾选的消息列表。
 *
 * <p><b>取勾选：四条路，从快到硬依次兜底（v3.0）</b></p>
 * <pre>
 *   1) live   multifoward.b.g(aioCtx) -> List&lt;AIOMsgItem&gt;   现取
 *   2) cached 上次 g() 的返回值（我们 hook 了 g 自己，留一份）
 *   3) selection {@link Selection} —— {@link EventWatch} 旁听 MultiSelectEvent$*
 *                 （AddSelectMsg / RemoveSelectMsg / SetSelectMsgList /
 *                  RemoveAllSelectMsg / SetSelectMode）攒下来的镜像
 *   4) scan   {@link Items} 池里的 AIOMsgItem 逐条问
 *             multifoward.b.j(aioCtx, AIOMsgItem)Z —— QQ 自己的“这条勾了没”
 * </pre>
 *
 * <p>v2.0 只留了 1/2/3，却把 3 的写入端（事件旁听）删了，于是
 * {@code Selection.list()} 恒为空、第三级兜底事实上不存在。v3.0 把写入端补回来，
 * 并新增第 4 级：不依赖任何事件是否被接住，只要用户还停在多选模式，
 * 直接问 QQ 内存里的状态。</p>
 *
 * <p>签名实证（classes5.dex / 9.2.35）：</p>
 * <pre>
 *   M inst  g (Lcom/tencent/aio/api/runtime/a;)Ljava/util/List;
 *   Z inst  j (Lcom/tencent/aio/api/runtime/a;Lcom/tencent/…/AIOMsgItem;)
 * </pre>
 * 运行时 aio ctx 从 MultiSelectBarVB.createVM() 返回的 VM 上按方法名
 * （getMContext / getSafetyContext）扫出来，并用包名 com.tencent.aio.api.runtime
 * 校验类型——该类在 36 个 dex 里都找不到独立声明，说明是接口/外部实现，
 * 只能靠包名前缀判。
 *
 * 布局来源：MultiSelectBarVB$mOperationLayout$2.invoke() 返回
 *   LinearLayout（classes5.dex 实测），这是真正造操作栏的地方，直接插进去最稳。
 */
public final class MultiSelectHook {

    private static final String CLS_BAR = "com.tencent.mobileqq.aio.input.multiselect.MultiSelectBarVB";
    private static final String CLS_BAR_LAMBDA = CLS_BAR + "$mOperationLayout$2";
    private static final String CLS_FWD = "com.tencent.mobileqq.aio.msglist.holder.component.multifoward.b";
    /** NT 列表项实体（multifoward.b.j 的第二个参数就是它） */
    private static final String CLS_ITEM = "com.tencent.mobileqq.aio.msg.AIOMsgItem";

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
    /** multifoward.b.j(ctx, AIOMsgItem)Z —— 逐条问“这条勾了没”，加锁缓存避免反复扫方法表 */
    private static volatile Method sJMethod;
    private static volatile boolean sJBroken;
    /** Selection 镜像超过这个年龄就认为不是当前勾选（12 小时；跨天残留兜底） */
    private static final long STALE_MS = 12L * 60L * 60L * 1000L;

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
        // v3.0 第三兜底：EventWatch 旁听到的多选事件攒进 Selection 的集合。
        // 场景=刚点开多选栏就按导出，live 查询可能还没返回、sLastPicked 也还是空的，
        // 但 AddSelectMsg 事件早就把勾选集记下来了，只有这条路能读到。
        long age = Selection.ageMs();
        if (map.isEmpty() && Selection.size() > 0 && age >= 0 && age < STALE_MS) {
            for (Msg m : Selection.list()) {
                if (m == null || m.uniseq == 0L) continue;
                map.put(m.uniseq, m);
            }
            if (!map.isEmpty()) from = from + "+selection";
        }
        // v3.0 第四兜底：直接问 QQ 自己。
        // Items 里攒着列表滑过的 AIOMsgItem，逐条调 multifoward.b.j(ctx, item)
        // 问“这条勾了没”。这条不依赖任何事件是否被我们接住——只要 QQ 的多选状态
        // 还在内存里就能读到，是四条路里最硬的一条。
        if (map.isEmpty()) {
            for (Msg m : scanByItems()) {
                if (m == null || m.uniseq == 0L) continue;
                map.put(m.uniseq, m);
            }
            if (!map.isEmpty()) from = from + "+scan";
        }
        L.i("勾选来源=" + from + " raw=" + (raw == null ? -1 : raw.size())
                + " selection=" + Selection.size() + "(age=" + age + "ms)"
                + " items=" + Items.size() + " -> " + map.size());
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
        // 回写：第四级兜底 scanByItems() 也吃这个 ctx，这里取到的就是最可信的一份
        sLastCtx = ctx;

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

    /**
     * 第四兜底：拿 {@link Items} 池里攒下的 AIOMsgItem，逐条问 QQ「这条勾了没」。
     *
     * <p>为什么这条最硬：前三条路（g(ctx) 现取 / 上次 g 的缓存 / 事件旁听）都需要
     * “某个时机刚好被我们看到”或“QQ 的列表接口已经算好”，而这条只依赖两件事——
     * 列表里的 AIOMsgItem 对象（我们在构造器里就收下了）和 QQ 自己的判定函数
     * {@code multifoward.b.j(aioCtx, AIOMsgItem)Z}。只要用户还停在多选模式，
     * QQ 内存里的勾选状态就一定还在，问谁都不如问它自己。</p>
     */
    private static List<Msg> scanByItems() {
        List<Msg> out = new ArrayList<>();
        List<?> pool;
        try {
            pool = Items.snapshot();
        } catch (Throwable t) {
            return out;
        }
        if (pool.isEmpty()) return out;
        Object inst = sFwd;
        if (inst == null) inst = forward();
        if (inst == null) return out;
        Object ctx = sLastCtx;
        if (!isAioCtx(ctx)) ctx = vmContext();
        if (!isAioCtx(ctx)) {
            L.i("没有 aio ctx，跳过 Items 逐条判定");
            return out;
        }
        Method j = pickedOneMethod(inst.getClass());
        if (j == null) {
            L.i("multifoward 里没有 j(aioCtx, AIOMsgItem)，跳过逐条判定");
            return out;
        }
        int hit = 0;
        for (Object it : pool) {
            if (it == null) continue;
            try {
                Object r = j.invoke(inst, ctx, it);
                if (!(r instanceof Boolean) || !((Boolean) r).booleanValue()) continue;
                Msg m = Msg.from(it);
                if (m == null || m.uniseq == 0L) continue;
                out.add(m);
                hit++;
            } catch (Throwable ignored) {
            }
        }
        L.i("Items 逐条判定: 池=" + pool.size() + " 命中=" + hit);
        return out;
    }

    /**
     * 在 multifoward.b 上找 {@code boolean j(aioCtx, AIOMsgItem)}。
     *
     * <p>不按方法名硬编码：混淆名会随版本漂移，但“2 个参数、返回 boolean、
     * 第一个是 com.tencent.aio.api.runtime.*、第二个是 AIOMsgItem”这个签名不会。</p>
     */
    private static Method pickedOneMethod(Class<?> c) {
        if (sJBroken) return null;
        Method cached = sJMethod;
        if (cached != null) return cached;
        for (Method m : allMethods(c)) {
            if (m.getParameterCount() != 2) continue;
            Class<?> r = m.getReturnType();
            if (r != boolean.class && r != Boolean.class) continue;
            Class<?>[] p = m.getParameterTypes();
            if (!p[0].getName().startsWith("com.tencent.aio.api.runtime")) continue;
            if (!p[1].getName().equals(CLS_ITEM)) continue;
            try {
                m.setAccessible(true);
            } catch (Throwable ignored) {
            }
            sJMethod = m;
            try {
                XposedBridge.log("[QQExport] 勾选查询方法: " + m.getName()
                        + "(aioCtx, AIOMsgItem)Z");
            } catch (Throwable ignored) {
            }
            return m;
        }
        sJBroken = true;
        return null;
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
