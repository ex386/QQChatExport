package com.opentools.qqexport;

import java.lang.reflect.Field;

/** 按字段名逐个候选探测，兼容不同版本的字段命名差异 */
public final class Reflect {

    public static Object get(Object o, String... names) {
        if (o == null) return null;
        Class<?> c = o.getClass();
        while (c != null) {
            for (String n : names) {
                try {
                    Field f = c.getDeclaredField(n);
                    f.setAccessible(true);
                    return f.get(o);
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    public static long L(Object o, String... n) {
        Object v = get(o, n);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    public static String S(Object o, String... n) {
        Object v = get(o, n);
        return v == null ? null : String.valueOf(v);
    }
}
