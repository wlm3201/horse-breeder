package com.horsebreeder.client.logic;

/**
 * AGENT NOTES —— 所有毫秒计时的统一时钟。
 *
 * <p>用 {@code nanoTime} 的相对时间而非 {@code currentTimeMillis}：
 * 后者会受玩家改系统时间 / NTP 跳变影响，所有超时（喂食握手、补货、坐骑超时）会比较级失效。
 * 本值是<b>进程相对</b>的，因此只能用于差值，不能用于跨会话持久化。
 */
final class ElapsedTime {
    private static final long ORIGIN = System.nanoTime();

    private ElapsedTime() { }

    static long millis() { return (System.nanoTime() - ORIGIN) / 1_000_000L; }
}
