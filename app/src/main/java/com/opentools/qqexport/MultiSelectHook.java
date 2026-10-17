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
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 多选栏改造。
 *
 * 两个独立部分：
 *  A. UI：在 MultiSelectBarVB 的根 LinearLayout（无参方法 J1() 的返回值）右侧插入“导出”按钮。
 *     注意 bindViewAndData() 会遍历该 LinearLayout 的所有子 View 调 setAlpha/setVisibility，
 *     所以每次绑定完数据都要把我们的按钮重新置为可见，否则会被一起藏掉。
 *
 *  B. 数据：旁听 MultiSelectEvent 的子类构造器，把“当前勾选了哪些消息”镜像到 Selection。
 *     只读不写，不改变 QQ 自身行为。
 *
 * 事件里字段名是混淆的（d/e/F...），所以一律按“字段类型”认：
 *     SetSelectMsgList  → 字段类型 List<...>
 *     AddSelectMsg      → 字段类型 AIOMsgItem
 *     RemoveSelectMsg   → 字段类型 AIOMsgItem
 *     RemoveAllSelectMsg→ 无字段
 *     SetSelectMode     → 字段类型 boolean
 */
public final class MultiSelectHook {

    private static final String CLS_BAR = "com.tencent.mobileqq.aio.input.multiselect.MultiSelectBarVB";
    private static final String CLS_ITEM = "com.tencent.mobileqq.aio.msg.AIOMsgItem";
    private static final String EV = "com.tencent.mobileqq.aio.event.MultiSelectEvent$";

    private static final String TAG = "qq-export-multiselect-btn";

    private MultiSelectHook() {
    }

    public static void install(ClassLoader cl) {
        try {
            hookBar(cl);
        } catch (Throwable t) {
            L.e(t);
        }
        try {
            hookEvents(cl);
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

        // A.1 无参构造器：建好就挂
        int n = 0;
        for (Constructor<?> c : bar.getDeclaredConstructors()) {
            if (c.getParameterCount() != 0) continue;
            try {
                XposedBridge.hookMethod(c, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        attach(p.thisObject);
                    }
                });
                n++;
            } catch (Throwable ignored) {
            }
        }

        // A.2 bindViewAndData / onCreateView 之后都要重新确认可见
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
        try {
            ViewGroup root = rootOf(vb);
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
            bg.setColor(0xFF3B7CF6);
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

    /** 取多选栏根 LinearLayout：优先已知方法名 J1()，退化按“无参且返回 ViewGroup”挑子 View 最多的 */
    private static ViewGroup rootOf(Object vb) {
        if (vb == null) return null;

        for (Method m : allMethods(vb.getClass())) {
            if (!"J1".equals(m.getName()) || m.getParameterCount() != 0) continue;
            Object r = call(vb, m);
            if (r instanceof ViewGroup) return (ViewGroup) r;
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
        return best;
    }

    private static void open(View v) {
        Activity act = activityOf(v.getContext());
        if (act == null) {
            L.e("找不到 Activity，无法弹窗");
            return;
        }
        Prefs.reload();
        List<Msg> picked = Selection.list();
        L.i("点击导出，已勾选 " + picked.size());
        if (!picked.isEmpty()) {
            PickedExport.show(act, picked);
        } else {
            ExportDialog.show(act);
        }
    }

    /* ==================== B. 勾选状态 ==================== */

    private static void hookEvents(ClassLoader cl) {
        hookEvent(cl, EV + "SetSelectMsgList", Kind.LIST);
        hookEvent(cl, EV + "AddSelectMsg", Kind.ADD);
        hookEvent(cl, EV + "RemoveSelectMsg", Kind.REMOVE);
        hookEvent(cl, EV + "RemoveAllSelectMsg", Kind.CLEAR);
        hookEvent(cl, EV + "SetSelectMode", Kind.MODE);
    }

    private enum Kind {LIST, ADD, REMOVE, CLEAR, MODE}

    private static void hookEvent(ClassLoader cl, String fqn, final Kind kind) {
        Class<?> c;
        try {
            c = Class.forName(fqn, false, cl);
        } catch (Throwable t) {
            L.e("事件类缺失: " + fqn);
            return;
        }
        int n = 0;
        for (Constructor<?> ct : c.getDeclaredConstructors()) {
            try {
                XposedBridge.hookMethod(ct, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            onEvent(kind, p.thisObject);
                        } catch (Throwable t) {
                            L.e(t);
                        }
                    }
                });
                n++;
            } catch (Throwable ignored) {
            }
        }
        L.i("事件已挂: " + fqn + " ctors=" + n);
    }

    private static void onEvent(Kind kind, Object ev) {
        switch (kind) {
            case LIST: {
                Object v = firstOfType(ev, List.class);
                if (v instanceof List) {
                    List<?> l = (List<?>) v;
                    Selection.reset(l);
                    Store.ingest(l);
                }
                break;
            }
            case ADD: {
                Object v = firstOfTypeName(ev, CLS_ITEM);
                if (v != null) {
                    Selection.add(v);
                    Store.ingestSingle(v);
                }
                break;
            }
            case REMOVE: {
                Object v = firstOfTypeName(ev, CLS_ITEM);
                if (v != null) Selection.remove(v);
                break;
            }
            case CLEAR:
                Selection.clear();
                break;
            case MODE: {
                Object v = firstPrimitiveBoolean(ev);
                if (v instanceof Boolean && !((Boolean) v)) Selection.clear();
                break;
            }
            default:
                break;
        }
    }

    /* ==================== 反射小工具 ==================== */

    private static List<Method> allMethods(Class<?> c) {
        List<Method> out = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) out.add(m);
        }
        return out;
    }

    private static List<Field> allFields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) out.add(f);
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

    private static Object get(Field f, Object o) {
        try {
            f.setAccessible(true);
            return f.get(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object firstOfType(Object o, Class<?> type) {
        for (Field f : allFields(o.getClass())) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (f.getName().startsWith("$")) continue;
            if (!type.isAssignableFrom(f.getType())) continue;
            Object v = get(f, o);
            if (v != null) return v;
        }
        return null;
    }

    private static Object firstOfTypeName(Object o, String typeName) {
        for (Field f : allFields(o.getClass())) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (f.getName().startsWith("$")) continue;
            if (!typeName.equals(f.getType().getName())) continue;
            Object v = get(f, o);
            if (v != null) return v;
        }
        return null;
    }

    private static Object firstPrimitiveBoolean(Object o) {
        for (Field f : allFields(o.getClass())) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (f.getName().startsWith("$")) continue;
            if (f.getType() != boolean.class) continue;
            Object v = get(f, o);
            if (v != null) return v;
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