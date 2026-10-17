package com.opentools.qqexport;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * QQ9.x(NT 内核) 的消息实体 AIOMsgItem 里持有 MsgRecord，但字段名被混淆（当前版本是 d1）。
 * 所以这里不用字段名，而是按“字段类型”找，并把结果缓存起来，避免每次反射遍历。
 */
public final class MsgItem {

    private static final String RECORD = "com.tencent.qqnt.kernel.nativeinterface.MsgRecord";
    private static final Map<Class<?>, Field> CACHE = new HashMap<>();

    private MsgItem() {
    }

    /** AIOMsgItem -> MsgRecord，找不到返回 null */
    public static Object record(Object item) {
        if (item == null) return null;
        Class<?> c = item.getClass();

        Field f = CACHE.get(c);
        if (f != null) {
            try {
                return f.get(item);
            } catch (Throwable ignored) {
                return null;
            }
        }

        Class<?> k = c;
        while (k != null && k != Object.class) {
            for (Field fd : k.getDeclaredFields()) {
                if (!RECORD.equals(fd.getType().getName())) continue;
                try {
                    fd.setAccessible(true);
                    Object v = fd.get(item);
                    if (v != null) {
                        CACHE.put(c, fd);
                        return v;
                    }
                } catch (Throwable ignored) {
                }
            }
            k = k.getSuperclass();
        }
        return null;
    }

    /** 直接取 msgId，取不到返回 0 */
    public static long msgId(Object item) {
        Object rec = record(item);
        return rec == null ? 0L : Reflect.L(rec, "msgId");
    }
}
