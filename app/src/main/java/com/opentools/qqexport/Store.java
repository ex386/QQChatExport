package com.opentools.qqexport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 会话级消息缓存：key = friendUin|isTroop，value = uniseq -> Msg */
public final class Store {

    private static final Map<String, LinkedHashMap<Long, Msg>> SESS = new LinkedHashMap<>();
    private static volatile String current = "";

    public static synchronized void ingest(List<?> list) {
        if (list == null || list.isEmpty()) return;
        for (Object o : list) ingestSingle(o);
    }

    public static synchronized void ingestSingle(Object rec) {
        Msg m = Msg.from(rec);
        if (m == null) return;
        String k = m.sessionKey();
        current = k;
        LinkedHashMap<Long, Msg> map = SESS.get(k);
        if (map == null) {
            map = new LinkedHashMap<>();
            SESS.put(k, map);
        }
        map.put(m.uniseq, m);            // 以 uniseq 去重
    }

    public static String latest() {
        return current;
    }

    public static synchronized List<Msg> session(String key) {
        List<Msg> out = new ArrayList<>();
        LinkedHashMap<Long, Msg> map = SESS.get(key);
        if (map != null) out.addAll(map.values());
        out.sort(Comparator.comparingLong(a -> a.time));
        return out;
    }
}
