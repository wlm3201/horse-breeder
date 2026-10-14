package com.horsebreeder.client.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AGENT NOTES —— 把「我们喂了一次」变成关于冷却的证据。
 *
 * <p><b>前提（已对着 26.1.2 / 26.3 源码核对）</b>：马走
 * {@code Horse#mobInteract → fedFood → handleEating}，金萝卜分支的条件是
 * {@code !isClientSide && isTamed && getAge() == 0 && !isInLove()}。所以对于一个
 * 已驯服、满血、成年的马：<b>没来爱心 ⇒ getAge() != 0（冷却中）或 isInLove()（已在求爱）</b>。
 *
 * <p><b>两个必须记住的纠正</b>：
 * <ul>
 *   <li><b>不会浪费金萝卜。</b>{@code fedFood} 里是 {@code if (ateFood) itemStack.consume(1, player);}
 *       —— 被拒时 {@code ateFood == false}，物品不会被消耗。探针的代价只是几个 tick，不是一根萝卜。</li>
 *   <li><b>沉默有两个原因，不只是冷却。</b>已在求爱中的马也会拒绝（{@code !isInLove()} 不成立），
 *       而且这种情况下的沉默和冷却<b>完全无法区分</b>。所以调用方只有在确信它没在求爱时
 *       才能把沉默读成冷却 —— 这也是 {@link #onFeedAttempt} 之外必须由
 *       {@code HorseStates} 再挡一道 {@code loveLeft <= 0} 的原因。</li>
 * </ul>
 *
 * <p>只让 UUID 进出本类，整条规则可以在没有世界的情况下单测。
 */
final class FeedProbe {

    /**
     * 等爱心多久算「没回音」。20 tick = 1 秒：单人下是即时的，远程服务器也够跑一个来回，
     * 同时短到让结论还像是这次点击的一部分。
     */
    static final int PROBE_TICKS = 20;

    /** 等待回答的喂食。除了玩家刚喂完的那一秒，这里基本是空的。 */
    private final Map<UUID, Integer> waiting = new HashMap<>();

    /** 本地玩家刚给这匹成年马递了食物。 */
    void onFeedAttempt(UUID uuid) {
        waiting.put(uuid, PROBE_TICKS);
    }

    /** 爱心来了，说明服务器接受了：它当时并没有冷却。 */
    void onLoveSeen(UUID uuid) {
        waiting.remove(uuid);
    }

    /**
     * 推进等待中的喂食，返回那些始终没人回答的。
     *
     * @return 现在可以判定「被拒绝」的马；注意调用方还要排除「它本来就在求爱」这种情况
     */
    List<UUID> tick(int delta) {
        if (waiting.isEmpty()) return List.of();
        List<UUID> refused = null;
        Iterator<Map.Entry<UUID, Integer>> entries = waiting.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<UUID, Integer> entry = entries.next();
            int left = entry.getValue() - delta;
            if (left > 0) {
                entry.setValue(left);
                continue;
            }
            if (refused == null) refused = new ArrayList<>();
            refused.add(entry.getKey());
            entries.remove();
        }
        return refused == null ? List.of() : refused;
    }

    void clear() {
        waiting.clear();
    }
}
