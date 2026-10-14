package com.horsebreeder.client.logic;

import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AGENT NOTES —— 判定一个 entity event 18 究竟是「进入求爱」还是「完成繁殖」。
 *
 * <p>vanilla 从两个地方广播 byte 18：{@code Animal.setInLove}（喂食成功）和
 * {@code Animal.finalizeSpawnChildFromBreeding}（真的生了）。包里<b>没有任何字段</b>区分二者。
 *
 * <p><b>不能用「我们自己是不是以为它在求爱」来猜。</b>那是在猜自己的账本，不是在观察游戏，
 * 在围栏里会错得很稳定：几匹马一起被喂时第二只父母经常被认错；而一匹马背上背着不该有的冷却后，
 * 它真正的繁殖事件会被当成一次新的求爱，把真实的 5 分钟冷却替换成幻影般的 30 秒。
 *
 * <p>改用<b>幼驹</b>这个信号：{@code spawnChildFromBreeding} 会先把幼驹 snap 到广播者自己的位置上
 * 再 {@code addFreshEntityWithPassengers}，所以「同一时间、同一位置出现一匹小马」<b>就是</b>这次繁殖，
 * 是观察出来的，不是推断出来的。
 *
 * <p>两个数字都来自源码：广播在 {@code addFreshEntityWithPassengers} <b>之前</b>发出，
 * entity tracker 在服务端 tick 末尾才 flush 它的 add 包，所以两者可能差一两 tick ——
 * 窗口给 6 tick 很宽裕，又短到无关幼驹几乎不可能落进来。
 * 半径 2 格（平方 4.0）只是给「两只挤开一点 / 父母走了两步」留的余量。
 */
final class BreedingAttribution {

    /**
     * 一个待定事件等它的幼驹多久。单位是<b>游戏 tick</b>而不是毫秒：
     * 卡顿时墙钟会被拉长，而窗口的长度必须是服务端视角的。
     */
    static final int BIRTH_WINDOW_TICKS = 6;

    /** 幼驹离事件来源多远还算数，平方值。幼驹出生点与广播者位置完全相同。 */
    static final double BIRTH_RADIUS_SQ = 4.0;

    /** 一个已结算的事件最终是什么。 */
    enum Verdict { BRED, IN_LOVE }

    /** 一个还没定论的事件。{@code wasInLove} 是事件到达时我们是否以为它在求爱，只作兜底用。 */
    private record Pending(Vec3 pos, boolean wasInLove, int ticksLeft) {}

    /**
     * 以广播者为键：vanilla 不可能在窗口内给它发第二个事件 18 —— {@code BreedGoal} 需要 60 tick 求偶，
     * 而 {@code inLove} 还在跑时 {@code setInLove} 不可达。用 LinkedHashMap 保证并列时的取舍是确定的。
     */
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();

    /**
     * 记录一个还不知道含义的事件 18。
     *
     * @param wasInLove 事件到达时我们是否以为这匹马在求爱（仅作为窗口超时后的兜底）
     */
    void onLoveEvent(UUID parent, Vec3 pos, boolean wasInLove) {
        pending.put(parent, new Pending(pos, wasInLove, BIRTH_WINDOW_TICKS));
    }

    /**
     * 把一次幼驹目击交给还在等待的事件，返回它能解释的那个父母。
     *
     * <p>匹配即消费：一次繁殖只产一个幼驹，所以一头已经结算了某个父母的小马，
     * 不能再去结算同一瞬间在旁边求爱的另一匹。
     *
     * @return 现在确定繁殖了的父母；没有事件对得上时返回 null
     */
    UUID claimBirth(Vec3 pos) {
        UUID best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Map.Entry<UUID, Pending> entry : pending.entrySet()) {
            double distSq = entry.getValue().pos().distanceToSqr(pos);
            if (distSq > BIRTH_RADIUS_SQ || distSq >= bestDistSq) continue;
            bestDistSq = distSq;
            best = entry.getKey();
        }
        if (best != null) pending.remove(best);
        return best;
    }

    /**
     * 推进所有待定窗口，返回这一 tick 定论的那些。
     *
     * @return 本 tick 结算出的结论；绝大多数 tick 是空的
     */
    Map<UUID, Verdict> tick(int delta) {
        Map<UUID, Verdict> resolved = null;
        Iterator<Map.Entry<UUID, Pending>> entries = pending.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<UUID, Pending> entry = entries.next();
            Pending event = entry.getValue();
            int left = event.ticksLeft() - delta;
            if (left > 0) {
                entry.setValue(new Pending(event.pos(), event.wasInLove(), left));
                continue;
            }
            if (resolved == null) resolved = new HashMap<>();
            resolved.put(entry.getKey(), event.wasInLove() ? Verdict.BRED : Verdict.IN_LOVE);
            entries.remove();
        }
        return resolved == null ? Map.of() : resolved;
    }

    /**
     * 是否还有事件在等结论。绝大多数时候是 false —— 这让调用方可以跳过「扫一遍所有实体找幼驹」
     * 这件只在极少数 tick 才有意义的事。
     */
    boolean hasPending() {
        return !pending.isEmpty();
    }

    void clear() {
        pending.clear();
    }
}
