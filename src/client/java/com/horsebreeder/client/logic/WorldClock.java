package com.horsebreeder.client.logic;

/**
 * AGENT NOTES —— 把 {@code level.getGameTime()} 转成“本 tick 跨过了多少个游戏 tick”，并估算 tps。
 *
 * <p>为什么不直接用 {@code gameTime} 差：服务端可能降速/卡顿/换维度，差值会被 {@code MAX_DELTA} 截断，
 * 这既防止一次性把倒计时推到无穷远，也防止进出维度时的巨跳。
 *
 * <p>tps 只用作<b>秒数换算</b>（HUD 里 “xx s left”），不参与任何倒计时推进；
 * 它是 EMA 平滑过的，所以 HUD 秒数会有轻微滞后，这是刻意的。
 */
final class WorldClock {
    private static final int MAX_DELTA = 1000;
    private static final long SAMPLE_MS = 200;
    private long lastGameTime = -1;
    private long sampleStartedAt = -1;
    private long sampleTicks;
    private int elapsedTicks;
    private double ticksPerSecond = 20.0;

    int advance(long gameTime, long now) {
        if (lastGameTime < 0 || gameTime < lastGameTime) {
            lastGameTime = gameTime;
            sampleStartedAt = now;
            sampleTicks = 0;
            return 0;
        }
        long jump = gameTime - lastGameTime;
        lastGameTime = gameTime;
        int delta = (int) Math.min(jump, MAX_DELTA);
        elapsedTicks += delta;
        if (now < sampleStartedAt) {
            sampleStartedAt = now;
            sampleTicks = 0;
        } else {
            sampleTicks += jump;
            long elapsed = now - sampleStartedAt;
            if (elapsed >= SAMPLE_MS) {
                if (sampleTicks > 0) {
                    double sample = Math.max(1.0, Math.min(1000.0, sampleTicks * 1000.0 / elapsed));
                    ticksPerSecond = ticksPerSecond * 0.7 + sample * 0.3;
                }
                sampleStartedAt = now;
                sampleTicks = 0;
            }
        }
        return delta;
    }

    int elapsedTicks() { return elapsedTicks; }

    long seconds(int ticks) { return Math.max(0, Math.round(ticks / ticksPerSecond)); }

    void reset() {
        lastGameTime = -1;
        sampleStartedAt = -1;
        sampleTicks = 0;
        elapsedTicks = 0;
        ticksPerSecond = 20.0;
    }
}
