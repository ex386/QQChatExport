package com.opentools.qqexport;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 旁听 QQ 自己的多选事件，把「当前勾选集」镜像进 {@link Selection}。
 *
 * <p>v2.0 把这条链路删了，只留下 {@code Selection.list()} 这个读口，
 * 于是 {@code fetchPicked()} 的第三级兜底永远返回空——用户勾了 9 条消息点导出，
 * 依旧弹「没读到勾选列表」。v3.0 把它补回来，但这次不靠猜：</p>
 *
 * <p>9.2.35 实测（classes5.dex / classes11.dex）这些事件是 Kotlin data class，
 * 类名和字段名都没有混淆，字段布局固定：</p>
 *
 * <pre>
 *   MultiSelectEvent$AddSelectMsg      d:AIOMsgItem  e:Z
 *   MultiSelectEvent$RemoveSelectMsg   d:AIOMsgItem
 *   MultiSelectEvent$SetSelectMsgList  d:List
 *   MultiSelectEvent$RemoveAllSelectMsg  （无实例字段）
 *   MultiSelectEvent$SetSelectMode     d:Z
 * </pre>
 *
 * <p>所以按「字段类型」取值即可，不需要记住混淆名：字段类型是 AIOMsgItem 就是消息，
 * 是 List 就是整批，是 boolean 就是模式开关。字段读不到就跳过，绝不让异常冒到 QQ 那边。</p>
 *
 * <p>挂的是构造器而不是 getter：事件对象一 new 出来勾选就已经定了，
 * 此时读取最稳；而 getter 可能被多次调用、也可能根本不调。</p>
 */
public final class EventWatch {

    private static final String BASE = "com.tencent.mobileqq.aio.event.MultiSelectEvent$";
    private static final String ITEM = "com.tencent.mobileqq.aio.msg.AIOMsgItem";

    private static final String ADD = "AddSelectMsg";
    private static final String REMOVE = "RemoveSelectMsg";
    private static final String SET_LIST = "SetSelectMsgList";
    private static final String CLEAR = "RemoveAllSelectMsg";
    private static final String MODE = "SetSelectMode";

    private static final String[] NAMES = {ADD, REMOVE, SET_LIST, CLEAR, MODE};

    /** Class -> (fieldName -> Field)，避免每次事件都 getDeclaredFields */
    private static final Map<Class<?>, Map<String, Field>> FIELDS = new HashMap<>();

    private EventWatch() {
    }

    public static void install(ClassLoader cl) {
        int total = 0;
        StringBuilder ok = new StringBuilder();
        for (final String name : NAMES) {
            Class<?> c;
            try {
                c = Class.forName(BASE + name, false, cl);
            } catch (Throwable t) {
                L.i("多选事件类缺失: " + name);
                continue;
            }
            int k = 0;
            for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                try {
                    XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            on(name, p.thisObject);
                        }
                    });
                    k++;
                } catch (Throwable ignored) {
                }
            }
            total += k;
            ok.append(name).append('=').append(k).append(' ');
        }
        XposedBridge.log("[QQExport] MultiSelectEvent hooked: " + total + " (" + ok.toString().trim() + ")");
    }

    /** 单个事件落到 Selection 上；任何异常都在这里吃掉 */
    private static void on(String name, Object ev) {
        if (ev == null) return;
        try {
            if (CLEAR.equals(name)) {
                Selection.clear();
                return;
            }
            if (MODE.equals(name)) {
                Object b = field(ev, boolean.class);
                // SetSelectMode(false) = 退出多选：这时勾选已经没意义了，必须清掉，
                // 否则下次进别的会话会把上一轮的残留带进来
                if (b instanceof Boolean && !((Boolean) b)) Selection.clear();
                return;
            }
            if (SET_LIST.equals(name)) {
                Object l = field(ev, List.class);
                if (l instanceof List) Selection.reset((List<?>) l);
                else L.i("SetSelectMsgList 没读到列表");
                return;
            }
            Object item = field(ev, null);
            if (item == null) {
                L.i(name + " 没读到 AIOMsgItem");
                return;
            }
            if (ADD.equals(name)) Selection.add(item);
            else if (REMOVE.equals(name)) Selection.remove(item);
        } catch (Throwable t) {
            L.e("多选事件处理失败 " + name + ": " + t);
        }
    }

    /**
     * 取事件里第一个「符合类型」的实例字段。
     *
     * @param want null 表示要 AIOMsgItem
     */
    private static Object field(Object ev, Class<?> want) {
        Class<?> c = ev.getClass();
        Map<String, Field> cache = cached(c);
        for (Field f : cache.values()) {
            Class<?> t = f.getType();
            boolean hit;
            if (want == null) hit = ITEM.equals(t.getName());
            else if (want == List.class) hit = List.class.isAssignableFrom(t);
            else hit = t == want;
            if (!hit) continue;
            try {
                return f.get(ev);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static synchronized Map<String, Field> cached(Class<?> c) {
        Map<String, Field> m = FIELDS.get(c);
        if (m != null) return m;
        m = new HashMap<>();
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            try {
                f.setAccessible(true);
            } catch (Throwable ignored) {
            }
            m.put(f.getName(), f);
        }
        FIELDS.put(c, m);
        return m;
    }
}
