package com.opentools.qqexport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * “多选模式”里当前被勾选的消息（由 {@link EventWatch} 旁听 QQ 自己的事件写入）。
 *
 * <pre>
 *   SetSelectMsgList(List)  全选 / 批量设置 → reset
 *   AddSelectMsg(item)      勾上一条        → add
 *   RemoveSelectMsg(item)   取消一条        → remove
 *   RemoveAllSelectMsg()    清空           → clear
 *   SetSelectMode(false)    退出多选        → clear
 * </pre>
 *
 * <p>用 msgId(uniseq) 做 key：列表刷新后 AIOMsgItem 对象会被替换，但 msgId 不变，
 * 这样既不会重复也不会丢。</p>
 *
 * <p>{@link #ageMs()} 用来判断这份镜像还新不新鲜——万一 QQ 退出多选的时刻我们没收到
 * （比如事件类名变了），过期数据就不会被当成当前勾选。</p>
 */
public final class Selection {

    private static final LinkedHashMap<Long, Msg> SEL = new LinkedHashMap<>();

    /** 最后一次被写入的时间戳（毫秒）；0 表示当前没有勾选集 */
    private static volatile long sTouched = 0L;

    private Selection() {
    }

    public static synchronized void reset(List<?> items) {
        SEL.clear();
        if (items != null) {
            for (Object o : items) addInner(o);
        }
        touch();
        L.i("selection reset -> " + SEL.size());
    }

    public static synchronized void add(Object item) {
        addInner(item);
        touch();
        L.d("selection add -> " + SEL.size());
    }

    private static void addInner(Object item) {
        if (item == null) return;
        Msg m = Msg.from(item);
        if (m == null || m.uniseq == 0L) return;
        SEL.put(m.uniseq, m);
    }

    public static synchronized void remove(Object item) {
        long id = 0L;
        Object rec = MsgItem.record(item);
        if (rec != null) id = Reflect.L(rec, "msgId");
        if (id == 0L && item instanceof Msg) id = ((Msg) item).uniseq;
        if (id != 0L) SEL.remove(id);
        touch();
        L.d("selection remove(" + id + ") -> " + SEL.size());
    }

    public static synchronized void clear() {
        int n = SEL.size();
        SEL.clear();
        sTouched = 0L;
        if (n > 0) L.i("selection clear（原有 " + n + " 条）");
    }

    public static synchronized List<Msg> list() {
        return new ArrayList<>(SEL.values());
    }

    public static synchronized int size() {
        return SEL.size();
    }

    /** 距离上次被 QQ 事件写入过去了多少毫秒；从未写过返回 -1 */
    public static long ageMs() {
        long t = sTouched;
        if (t == 0L) return -1L;
        return System.currentTimeMillis() - t;
    }

    private static void touch() {
        sTouched = System.currentTimeMillis();
    }
}