package com.opentools.qqexport;

import java.util.ArrayList;
import java.util.List;

/**
 * 最近出现过的 {@code AIOMsgItem} 原对象池（有界，先进先出）。
 *
 * <p>为什么需要它：QQ 判断「这条消息勾没勾」的权威接口是</p>
 *
 * <pre>
 *   multifoward.b.j(Lcom/tencent/aio/api/runtime/a; Lcom/tencent/mobileqq/aio/msg/AIOMsgItem;)Z
 * </pre>
 *
 * <p>也就是「给 ctx 和列表项，返回是否被勾选」。只要我们手里还留着列表项对象，
 * 导出时就能逐条问 QQ 自己，完全不依赖任何事件旁听——这是 v3.0 的第四级兜底，
 * 也是最能对上「QQ 界面上明明勾了」的一级。</p>
 *
 * <p>池子有界（默认 800 条），因为滑动历史消息时 AIOMsgItem 会不断被 new 出来；
 * 强引用数量不设上限的话，长会话里就是稳定泄漏。超出后丢最早的，
 * 多选场景一般只关心当前屏附近的消息。</p>
 */
public final class Items {

    private static final int CAP = 800;

    private static final ArrayList<Object> POOL = new ArrayList<>();

    private Items() {
    }

    public static synchronized void put(Object item) {
        if (item == null) return;
        if (POOL.size() >= CAP) POOL.subList(0, CAP / 4).clear();
        POOL.add(item);
    }

    /** 快照，调用方随便遍历 */
    public static synchronized List<Object> snapshot() {
        return new ArrayList<>(POOL);
    }

    public static synchronized int size() {
        return POOL.size();
    }

    public static synchronized void clear() {
        POOL.clear();
    }
}
