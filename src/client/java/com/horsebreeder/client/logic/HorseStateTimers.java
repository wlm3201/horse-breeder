package com.horsebreeder.client.logic;

/**
 * AGENT NOTES —— tick 计时器的公共算术。全部是纯函数，刻意不引用 Minecraft。
 *
 * <p>三条约定：
 * <ul>
 *   <li>倒计时类字段一律 <b>&gt;= 0</b>，0 表示已结束；被 {@code remaining} 截尾。</li>
 *   <li>age 是<b>负数 = 幼驹</b>、正数 = 繁殖冷却，正负号有业务含义，所以用 {@code advanceAge} 单独处理，
 *       不要用 {@code remaining} 推 age。</li>
 *   <li>{@code Integer.MIN_VALUE} 表示“未知”，任何算术都必须先吃掉它再计算。</li>
 * </ul>
 * 全部做了 int / long 边界收敛，因为 delta 可能很大（卡顿、进出维度）。
 */
final class HorseStateTimers {
    static final int UNKNOWN_AGE = Integer.MIN_VALUE;

    private HorseStateTimers() { }

    static int remaining(int value, long elapsed) {
        if (elapsed <= 0) return Math.max(0, value);
        return elapsed >= value ? 0 : value - (int) elapsed;
    }

    static int advanceAge(int age, long elapsed) {
        if (age == UNKNOWN_AGE || elapsed <= 0) return age;
        if (age < 0) return -remaining(-age, elapsed);
        return remaining(age, elapsed);
    }

    static int addCapped(int value, int increment, int maximum) {
        return (int) Math.min(maximum, Math.max(0L, (long) value + Math.max(0, increment)));
    }
}
