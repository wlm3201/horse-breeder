package com.horsebreeder.client.logic;

/**
 * AGENT NOTES —— 一次“配对→喂两只→等马驹”过程的外部循环。
 *
 * <p>拆成两个类的原因：{@link Session} 是纯状态转移（可单独推理），
 * 本类负责把它接到会变的东西上（人口、PlayerActions、超时惩罚）。
 *
 * <p>生命周期：{@code begin(pair)} → 每 tick {@code observe()}（被动：看有没有幼马）
 * → {@code advance()}（主动：下一步做什么）→ {@code abandon()} 或 EXPIRED 结束。
 * 入口不变量：{@code begin} 里发现还在进行中就抛 IllegalStateException，
 * 意味着上层必须先查 {@code active()} 再开新配对。
 *
 * <p>两个决定是否成功的方式：
 * <ol>
 *   <li>看到父母 6 格内出现<b>新登记</b>的幼驹（{@code newborns}窗口，见 observe）；</li>
 *   <li>收到服务器广播的恋爱事件（18 号 entity event，经 BreederController#onLoveEvent 转入
 *       {@code onLoveEvent}）。</li>
 * </ol>
 * 两种方式都调用 {@code states.onBred(a,b)} 把两只马压成满冷却。
 *
 * <p>{@code retries} 会给失败的配对加 60s 冷却，并回传给 Selection.bestPair 把这一对排除掉，
 * 否则同一对被 Session 持续重试，会锁死整个 tick 循环。
 */
import com.horsebreeder.client.util.Trace;
import net.minecraft.world.entity.animal.equine.AbstractHorse;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class BreedingManager {
    private static final long FEED_TIMEOUT_MS = 5000;
    private static final long MATE_TIMEOUT_MS = 40_000;
    private static final long PAIR_GRACE_MS = 2000;
    private static final long PAIR_RETRY_MS = 60_000;
    private static final long NEWBORN_WINDOW_MS = 3000;
    private static final double NEWBORN_RADIUS_SQ = 36.0;

    enum Action { NONE, FEED, WAIT_ACK, WAIT_FOAL, EXPIRED }
    record Step(Action action, AbstractHorse target) {
        static Step of(Action action) { return new Step(action, null); }
    }

    private final HorseStates states;
    private final Map<PairKey, Long> retries = new HashMap<>();
    private final Map<UUID, Long> knownBabies = new HashMap<>();
    private final Map<UUID, Long> newborns = new HashMap<>();
    private boolean scanned;
    private Session session;

    BreedingManager(HorseStates states) { this.states = states; }

    void reset() {
        session = null;
        retries.clear();
        knownBabies.clear();
        newborns.clear();
        scanned = false;
    }

    void begin(Selection.Pair pair, long now) {
        if (active()) throw new IllegalStateException("A breeding session is already active");
        newborns.clear();
        session = Session.feeding(pair.a().getUUID(), pair.b().getUUID(), now, FEED_TIMEOUT_MS);
    }

    boolean active() { return session != null; }
    boolean waitingForFoal() { return active() && session.phase == Session.Phase.WAIT_FOAL; }
    boolean involves(UUID id) { return active() && session.involves(id); }
    Set<UUID> parents() { return active() ? Set.of(session.a, session.b) : Set.of(); }
    int loveLeft() { return active() ? Math.min(states.loveLeft(session.a), states.loveLeft(session.b)) : 0; }
    void abandon() { session = null; }

    Map<PairKey, Long> retries(long now) {
        retries.values().removeIf(until -> until <= now);
        return retries;
    }

    void observe(HorsePopulation population, long now) {
        for (AbstractHorse foal : population.foals) {
            UUID id = foal.getUUID();
            if (scanned && !knownBabies.containsKey(id)) {
                newborns.put(id, now + NEWBORN_WINDOW_MS);
                states.markNewborn(id);
            }
            knownBabies.put(id, now + 300_000);
        }
        newborns.values().removeIf(until -> until <= now);
        knownBabies.values().removeIf(until -> until <= now);
        scanned = true;

        if (!active()) return;
        AbstractHorse a = population.get(session.a);
        AbstractHorse b = population.get(session.b);
        if (waitingForFoal() || (session.phase == Session.Phase.FEED_B && session.fed)) {
            for (AbstractHorse foal : population.foals) {
                if (!newborns.containsKey(foal.getUUID())) continue;
                if ((a != null && foal.distanceToSqr(a) <= NEWBORN_RADIUS_SQ)
                    || (b != null && foal.distanceToSqr(b) <= NEWBORN_RADIUS_SQ)) {
                    states.onBred(session.a, session.b);
                    Trace.log("session {} x {} confirmed by newborn foal {}",
                        session.a, session.b, foal.getUUID());
                    abandon();
                    return;
                }
            }
        }
        if (waitingForFoal() && (!states.isInLove(session.a) || !states.isInLove(session.b))) {
            session.expireAt(now + PAIR_GRACE_MS);
        }
    }

    boolean onLoveEvent(UUID id) {
        if (!involves(id) || states.isFeedPending(id) || !states.isInLove(id)) return false;
        states.onBred(session.a, session.b);
        Trace.log("session {} x {} confirmed by love event", session.a, session.b);
        abandon();
        return true;
    }

    Step advance(HorsePopulation population, long now) {
        if (!active()) return Step.of(Action.NONE);
        AbstractHorse a = population.get(session.a);
        AbstractHorse b = population.get(session.b);
        Session.Step step = session.advance(parent(a), parent(b), now, FEED_TIMEOUT_MS, MATE_TIMEOUT_MS);
        return switch (step.action()) {
            case FEED -> new Step(Action.FEED, population.get(step.target()));
            case WAIT_ACK -> Step.of(Action.WAIT_ACK);
            case WAIT_FOAL -> Step.of(Action.WAIT_FOAL);
            case EXPIRED -> {
                states.onPairFailed(session.a, session.b);
                retries.put(PairKey.of(session.a, session.b), now + PAIR_RETRY_MS);
                Trace.log("session {} x {} expired while WAIT_FOAL; retry in 60s", session.a, session.b);
                abandon();
                yield Step.of(Action.EXPIRED);
            }
            case ABANDON -> {
                Trace.log("session {} x {} abandoned (phase {})", session.a, session.b, session.phase);
                // 放弃也是一次失败：不记惩罚的话同一对会被立刻重新选中，形成"每 5 秒重试一次"的死循环。
                retries.put(PairKey.of(session.a, session.b), now + PAIR_RETRY_MS);
                abandon();
                yield Step.of(Action.NONE);
            }
        };
    }

    private Session.Parent parent(AbstractHorse horse) {
        return horse == null ? new Session.Parent(false, false, false, false)
            : new Session.Parent(horse.isAlive(), states.isInLove(horse.getUUID()),
                states.isFeedPending(horse.getUUID()), states.isCoolingDown(horse.getUUID()));
    }

    void recordFeed(AbstractHorse horse) {
        if (involves(horse.getUUID())) session.fed = states.isFeedPending(horse.getUUID());
    }
}
