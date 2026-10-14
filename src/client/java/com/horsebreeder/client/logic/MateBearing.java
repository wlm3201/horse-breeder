package com.horsebreeder.client.logic;

/**
 * AGENT NOTES —— 用「广播者的头部朝向」反推它到底和谁交配的。
 *
 * <p>为什么可行：vanilla 广播 entity event 18 时只发<b>目标那一匹</b>，包里没有任何字段指出伴侣是谁
 * （{@code finalizeSpawnChildFromBreeding} 把幼驹 snap 到广播者身上，经验球也生成在那儿，
 * {@code loveCause} 从不出服务端）。但 {@code BreedGoal.tick()} 会调
 * {@code getLookControl().setLookAt(this.partner)} —— 与 {@code breed()} 读的是<b>同一个字段</b>。
 * 所以广播者那颗头正指着它交配的对象，这不是相关性，是构造性。
 * 马用的正是标准 {@code BreedGoal}（{@code AbstractHorse#addBehaviourGoals} 里
 * {@code new BreedGoal(this, 1.0, AbstractHorse.class)}），因此本类对马有效。
 *
 * <p>头部 yaw 是同步的：{@code ServerEntity.sendChanges} 用 {@code Mth.packDegrees} 打包成
 * {@code ClientboundRotateHeadPacket}。而 {@code age} / {@code inLove} / {@code loveCause} 都不同步，
 * 这也是为什么只能用朝向。
 *
 * <p><b>可观测 ≠ 精确，下面两个常量就是全部的安全论证。</b> yaw 被量化到 360/256 = 1.40625 度，
 * 最多每三 tick 才发一次，客户端还要再插值；两头马还在动时头会滞后最多 10 度/tick；
 * {@code Mob.getMaxHeadYRot()} 还把它夹在离身体 75 度内。所以只在读数「不接近」时才下结论：
 * 既要对准，又要大幅领先第二名；否则一律返回 -1，调用方继续怀疑。
 * <b>它只会收窄怀疑，永远不会新增一种错法</b> —— 唯一不能有的失败模式是认错马，
 * 因为那会把 5 分钟冷却安到一个没配种的头上。
 *
 * <p>刻意不引用 Minecraft 类型，以便在无世界的情况下单测。
 */
final class MateBearing {

    /**
     * 偏差多少度以内还算「指着它」。给得宽松是因为读数会滞后：交配瞬间距对象不到 3 格，
     * 连这个角度都偏出去的马，说明目标根本没在指它。
     */
    static final double MAX_OFF_DEGREES = 45.0;

    /**
     * 第二名要落后多少度。比 1.40625 度的传输量化高一个数量级，
     * 所以这里不可能出现「因为取整而分出胜负」的结果；靠得这么近的两个候选就是抛硬币，交给怀疑。
     */
    static final double MIN_MARGIN_DEGREES = 20.0;

    private MateBearing() {
    }

    /**
     * 站在原点的动物要指向偏移 {@code (dx, dz)} 所需的 yaw，用 vanilla 自己的约定
     * （yaw 0 面向 +Z，东为 -90），与 {@code LookControl} 的算法一致。
     */
    static double bearingDegrees(double dx, double dz) {
        return Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
    }

    /**
     * 广播者的头指向了哪个候选。
     *
     * @param headYawDegrees    广播者的头部 yaw
     * @param candidateBearings 指向每个候选的 yaw，顺序由调用方决定
     * @return 伴侣的下标；读数不足以确定时返回 -1
     */
    static int name(double headYawDegrees, double[] candidateBearings) {
        int best = -1;
        double bestOff = Double.MAX_VALUE;
        double runnerUpOff = Double.MAX_VALUE;

        for (int i = 0; i < candidateBearings.length; i++) {
            double off = Math.abs(angleDifference(headYawDegrees, candidateBearings[i]));
            if (off < bestOff) {
                runnerUpOff = bestOff;
                bestOff = off;
                best = i;
            } else if (off < runnerUpOff) {
                runnerUpOff = off;
            }
        }

        if (best < 0 || bestOff > MAX_OFF_DEGREES) return -1;
        if (runnerUpOff - bestOff < MIN_MARGIN_DEGREES) return -1;
        return best;
    }

    /**
     * 两个 yaw 的有符号差，卷绕到 (-180, 180]。不卷绕的话，179 与 -179 会读成相差 358 度，
     * 而那正好是「认出对面那匹」的翻车点。
     */
    private static double angleDifference(double from, double to) {
        double diff = (to - from) % 360.0;
        if (diff > 180.0) diff -= 360.0;
        if (diff <= -180.0) diff += 360.0;
        return diff;
    }
}
