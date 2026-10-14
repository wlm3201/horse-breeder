package com.horsebreeder.client.logic;

/**
 * AGENT NOTES —— 三维（生命 / 跳跃 / 速度）的读取与打分。所有换算都源于这里的两段标定。
 *
 * <p><b>两套单位并存，别搞混</b>：{@code MIN/MAX_HEALTH|JUMP|SPEED} 是<b>属性原始值</b>，
 * 用于归一化；{@code MIN_*_BLOCKS_PER_SECOND / MIN_*_BLOCKS / MIN_*_POINTS} 是<b>给玩家看的单位</b>，
 * 用于配置项上下限与展示。
 *
 * <p>{@code jumpToBlocks} 用逐 tick 累加还原重力/阻尼（0.08 与 0.98），而不是闭式解，
 * 为了让它和玩家体感的跳跃高度一致。它会被 HUD 每帧调用，别在里面加重 Hashtable 级别的操作。
 *
 * <p>{@code priorityUncertainty} 表达“父母差异越大、子代方差越大”的惩罚，
 * 由 {@code config.variancePenalty} 加权；0 = 只看期望最优。它是“追求进化”与“追求稳定”的唯一切换。
 */
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.equine.AbstractHorse;

public final class Scoring {
    public static final double MIN_HEALTH = 15.0;
    public static final double MAX_HEALTH = 30.0;
    public static final double MIN_JUMP = 0.4;
    public static final double MAX_JUMP = 1.0;
    public static final double MIN_SPEED = 0.1125;
    public static final double MAX_SPEED = 0.3375;

    public static final double SPEED_TO_BLOCKS_PER_SECOND = 43.17;

    public static final double MIN_SPEED_BLOCKS_PER_SECOND = 9.0;
    public static final double MAX_SPEED_BLOCKS_PER_SECOND = 14.5;
    public static final double MIN_JUMP_BLOCKS = 3.0;
    public static final double MAX_JUMP_BLOCKS = 5.9;
    public static final double MIN_HEALTH_POINTS = 20.0;
    public static final double MAX_HEALTH_POINTS = 30.0;

    private static final double QUALITY_STD = 1.0 / 6.0;

    private Scoring() {
    }

    public record Stats(double health, double jump, double speed) {
        public double normHealth() {
            return normalize(health, MIN_HEALTH, MAX_HEALTH);
        }

        public double normJump() {
            return normalize(jump, MIN_JUMP, MAX_JUMP);
        }

        public double normSpeed() {
            return normalize(speed, MIN_SPEED, MAX_SPEED);
        }

        public double blocksPerSecond() {
            return speedToBlocksPerSecond(speed);
        }

        public double jumpBlocks() {
            return jumpToBlocks(jump);
        }

        public double average() {
            return (normHealth() + normJump() + normSpeed()) / 3.0;
        }
    }

    public static Stats read(AbstractHorse horse) {
        return new Stats(
            horse.getAttributeBaseValue(Attributes.MAX_HEALTH),
            horse.getAttributeBaseValue(Attributes.JUMP_STRENGTH),
            horse.getAttributeBaseValue(Attributes.MOVEMENT_SPEED)
        );
    }

    public static double normalize(double value, double min, double max) {
        if (!Double.isFinite(value) || !Double.isFinite(min) || !Double.isFinite(max) || max <= min) return 0.0;
        double v = (value - min) / (max - min);
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    public static double speedToBlocksPerSecond(double speed) {
        return speed * SPEED_TO_BLOCKS_PER_SECOND;
    }

    public static double jumpToBlocks(double jumpStrength) {
        if (!Double.isFinite(jumpStrength)) return 0.0;
        double velocity = jumpStrength;
        double height = 0.0;
        while (velocity > 0.0) {
            height += velocity;
            velocity = (velocity - 0.08) * 0.98;
        }
        return height;
    }

    public static final double STAT_SCALE = 1_000_000.0;

    public static double statScore(Stats stats) {
        return stats.average() * STAT_SCALE;
    }

    public static Stats expectedChild(Stats a, Stats b) {
        return new Stats((a.health() + b.health()) / 2.0, (a.jump() + b.jump()) / 2.0, (a.speed() + b.speed()) / 2.0);
    }

    public static double childStd(double rangeMin, double rangeMax, double a, double b) {
        double margin = 0.15 * (rangeMax - rangeMin);
        double range = Math.abs(a - b) + margin * 2.0;
        return range * QUALITY_STD;
    }

    public static double priorityUncertainty(Stats a, Stats b) {
        double penalty =
            childStd(MIN_HEALTH, MAX_HEALTH, a.health(), b.health()) / (MAX_HEALTH - MIN_HEALTH) +
            childStd(MIN_JUMP, MAX_JUMP, a.jump(), b.jump()) / (MAX_JUMP - MIN_JUMP) +
            childStd(MIN_SPEED, MAX_SPEED, a.speed(), b.speed()) / (MAX_SPEED - MIN_SPEED);
        return penalty * STAT_SCALE;
    }
}
