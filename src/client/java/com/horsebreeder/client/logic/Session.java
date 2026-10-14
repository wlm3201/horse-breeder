package com.horsebreeder.client.logic;

/**
 * AGENT NOTES —— 配对会话的纯状态机（无 Minecraft 依赖，可单测）。
 *
 * <p>相位：FEED_A → FEED_B → WAIT_FOAL。每进入一个新相位都会重置 deadline 和 fed。
 *
 * <p>{@code advance()} 的语义：一次调用可以连续跨相位（A 已进入恋爱 → 立即=B 相位），
 * 所以调用方每次只能拿到<b>一个</b>动作，且必须下一 tick 再问一次。
 *
 * <p>{@code fed} 由调用方回填（{@code BreedingManager#recordFeed}）=“这一喂服务器是否认账”。
 * 未认账时会返回 FEED 让上层继续尝试，直到 {@code deadlineAt} 到期。
 *
 * <p>注意区分两种失败：EXPIRED（WAIT_FOAL 超时 → 记进重试黑名单）与 ABANDON（马不见了 / 喂不进去 → 静默放弃）。
 */
import java.util.UUID;

final class Session {
    enum Phase { FEED_A, FEED_B, WAIT_FOAL }
    enum Action { FEED, WAIT_ACK, WAIT_FOAL, ABANDON, EXPIRED }
    /**
     * @param cooling 这匹马已被判定处在繁殖冷却中。喂进去被服务器拒绝后就会变成 true，
     *                此时这一对应当立刻放弃，而不是干等回执到超时。
     */
    record Parent(boolean present, boolean inLove, boolean feedPending, boolean cooling) { }
    record Step(Action action, UUID target) {
        static Step of(Action action) { return new Step(action, null); }
    }

    final UUID a;
    final UUID b;
    Phase phase;
    long deadlineAt;
    boolean fed;

    Session(UUID a, UUID b, Phase phase, long deadlineAt) {
        this.a = a;
        this.b = b;
        this.phase = phase;
        this.deadlineAt = deadlineAt;
    }

    static Session feeding(UUID a, UUID b, long now, long feedTimeoutMs) {
        return new Session(a, b, Phase.FEED_A, now + feedTimeoutMs);
    }

    void moveTo(Phase next, long now, long timeoutMs) {
        this.phase = next;
        this.deadlineAt = now + timeoutMs;
        this.fed = false;
    }

    void expireAt(long instant) {
        this.deadlineAt = Math.min(this.deadlineAt, instant);
    }

    boolean involves(UUID uuid) {
        return uuid.equals(a) || uuid.equals(b);
    }

    Step advance(Parent first, Parent second, long now, long feedTimeout, long mateTimeout) {
        if (!first.present || !second.present) return Step.of(Action.ABANDON);
        if (now >= deadlineAt) return Step.of(phase == Phase.WAIT_FOAL ? Action.EXPIRED : Action.ABANDON);
        if (phase == Phase.FEED_A && first.inLove) moveTo(Phase.FEED_B, now, feedTimeout);
        if (phase == Phase.FEED_B && second.inLove) moveTo(Phase.WAIT_FOAL, now, mateTimeout);
        if (phase == Phase.WAIT_FOAL) return Step.of(Action.WAIT_FOAL);
        Parent target = phase == Phase.FEED_A ? first : second;
        // 目标已经被判成冷却中（喂了但服务器不认）：再等回执毫无意义，立刻放弃这一对，
        // 外层下一 tick 就会换一个伙伴 —— 否则要白等满 FEED_TIMEOUT 才动。
        if (target.cooling) return Step.of(Action.ABANDON);
        if (!fed) return new Step(Action.FEED, phase == Phase.FEED_A ? a : b);
        return Step.of(target.feedPending ? Action.WAIT_ACK : Action.ABANDON);
    }
}
