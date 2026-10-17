package com.opentools.qqexport;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * QQ9.x(NT 内核) 的 AIOMsgItem -> MsgRecord。
 *
 * 取法优先级（9.2.35 实测）：
 *  1) 方法 getMsgRecord()：签名 ()Lcom/tencent/qqnt/kernel/nativeinterface/MsgRecord;
 *     **方法名未混淆**，是最稳的一条路（classes3.dex 实测）。
 *  2) 退化按字段类型找：类里同时存在 inst 字段 e 和 static 字段 d1，
 *     静态字段是模板/共享值，必须跳过，否则会读到别人的消息。
 *
 * 两条路都可能被新版本改掉，所以结果都做缓存；字段路径只在真取到值时才缓存，
 * 避免某次对象还没填好就把“空字段”记成首选。
 */
public final class MsgItem {

    private static final String RECORD = "com.tencent.qqnt.kernel.nativeinterface.MsgRecord";

    private static final Map<Class<?>, Method> M_CACHE = new HashMap<>();
    private static final Set<Class<?>> M_MISS = new HashSet<>();
    private static final Map<Class<?>, Field> F_CACHE = new HashMap<>();

    private MsgItem() {
    }

    /** AIOMsgItem -> MsgRecord，找不到返回 null */
    public static Object record(Object item) {
        if (item == null) return null;
        Class<?> c = item.getClass();

        Method m = M_CACHE.get(c);
        if (m == null && !M_MISS.contains(c)) {
            m = findGetter(c);
            if (m != null) M_CACHE.put(c, m);
            else M_MISS.add(c);
        }
        if (m != null) {
            try {
                Object v = m.invoke(item);
                if (v != null) return v;
            } catch (Throwable t) {
                M_MISS.add(c);
                M_CACHE.remove(c);
            }
        }
        return recordByField(item, c);
    }

    private static Method findGetter(Class<?> c) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                String n = m.getName();
                if (!"getMsgRecord".equals(n) && !"getMsgRecords".equals(n)) continue;
                if (!RECORD.equals(m.getReturnType().getName())) continue;
                try {
                    m.setAccessible(true);
                } catch (Throwable ignored) {
                }
                return m;
            }
        }
        return null;
    }

    private static Object recordByField(Object item, Class<?> c) {
        Field cached = F_CACHE.get(c);
        if (cached != null) {
            try {
                return cached.get(item);
            } catch (Throwable ignored) {
            }
        }

        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field fd : k.getDeclaredFields()) {
                if (Modifier.isStatic(fd.getModifiers())) continue;
                if (fd.getName().startsWith("$")) continue;
                if (!RECORD.equals(fd.getType().getName())) continue;
                try {
                    fd.setAccessible(true);
                    Object v = fd.get(item);
                    if (v != null) {
                        F_CACHE.put(c, fd);
                        return v;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** 直接取 msgId，取不到返回 0 */
    public static long msgId(Object item) {
        Object rec = record(item);
        return rec == null ? 0L : Reflect.L(rec, "msgId");
    }
}