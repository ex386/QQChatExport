package com.opentools.qqexport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * “多选模式”里当前被勾选的消息。
 * 数据来源是 QQ 自己发的事件对象（只是被我们旁听到，不改变它的行为）：
 *   SetSelectMsgList(List)  全选/批量设置
 *   AddSelectMsg(item)      勾上一条
 *   RemoveSelectMsg(item)   取消一条
 *   RemoveAllSelectMsg()    清空
 *   SetSelectMode(false)    退出多选
 * 用 msgId 做 key，避免同一消息在列表刷新后对象被替换导致重复。
 */
public final class Selection {

    private static final LinkedHashMap<Long, Msg> SEL = new LinkedHashMap<>();

    private Selection() {
    }

    public static synchronized void reset(List<?> items) {
        SEL.clear();
        if (items != null) {
            for (Object o : items) addInner(o);
        }
        L.i("selection reset -> " + SEL.size());
    }

    public static synchronized void add(Object item) {
        addInner(item);
        L.i("selection add -> " + SEL.size());
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
        L.i("selection remove(" + id + ") -> " + SEL.size());
    }

    public static synchronized void clear() {
        SEL.clear();
        L.i("selection clear");
    }

    public static synchronized List<Msg> list() {
        return new ArrayList<>(SEL.values());
    }

    public static synchronized int size() {
        return SEL.size();
    }
}
