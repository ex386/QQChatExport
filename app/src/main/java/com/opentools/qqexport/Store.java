package com.opentools.qqexport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 会话级消息缓存：key = peerUin|isTroop，value = msgId -> Msg */
public final class Store {

    private static final Map<String, LinkedHashMap<Long, Msg>> SESS = new LinkedHashMap<>();
    private static volatile String current = "";

    private static volatile int limit = -1;
    private static volatile long limitAt;
    /** 缓存上限（来自设置页）。每 3 秒允许重新读一次，改完设置不用重启 QQ */
    static int limit() {
        long now = System.currentTimeMillis();
        int v = limit;
        if (v <= 0 || now - limitAt > 3000L) {
            int nv = Prefs.num(Prefs.K_LIMIT, Prefs.D_LIMIT);
            if (nv <= 0) nv = Prefs.D_LIMIT;
            limit = nv;
            limitAt = now;
            return nv;
        }
        return v;
    }

    static void invalidateLimit() {
        limit = -1;
    }

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
        map.remove(m.uniseq);        // 重新插入，保持“最近出现”的顺序
        map.put(m.uniseq, m);
        trim(map, limit());
    }

    /** 超上限时丢弃最早写入的（LinkedHashMap 的插入顺序） */
    private static void trim(LinkedHashMap<Long, Msg> map, int max) {
        if (max <= 0) return;
        int over = map.size() - max;
        if (over <= 0) return;
        Iterator<Long> it = map.keySet().iterator();
        while (over-- > 0 && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    public static String latest() {
        return current;
    }

    public static synchronized boolean hasSession(String key) {
        return SESS.containsKey(key);
    }

    public static synchronized List<Msg> session(String key) {
        List<Msg> out = new ArrayList<>();
        LinkedHashMap<Long, Msg> map = SESS.get(key);
        if (map != null) out.addAll(map.values());
        out.sort(Comparator.comparingLong(a -> a.time));
        return out;
    }

    public static synchronized Map<String, Integer> sessions() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashMap<Long, Msg>> e : SESS.entrySet()) {
            out.put(e.getKey(), e.getValue().size());
        }
        return out;
    }
}