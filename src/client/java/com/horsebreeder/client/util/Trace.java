package com.horsebreeder.client.util;

import com.horsebreeder.HorseBreeder;
import com.horsebreeder.client.config.HorseBreederConfig;

/**
 * AGENT NOTES —— 诊断探针的统一出口。
 *
 * <p>存在的原因：本 mod 有大量“只能靠运行时观察”的不确定性（喂食有没有被服务器认账、
 * 配对到底是哪条路径判定成功的、马驹是被哪只父母识别出来的）。这些无法用单测覆盖，
 * 只能在玩家真跑起来之后看日志。
 *
 * <p>用法：{@code Trace.log("session {} ended: {}", uuid, reason);}
 * 默认关闭，由配置 {@code verboseLogging}（ModMenu → 高级）打开。
 * 探针只允许 {@code info} 级别，且调用频率必须与“每 tick 可能发生”的事件挂钩时加节流，
 * 否则打开后会把日志文件刷满。
 */
public final class Trace {
    private Trace() {
    }

    public static void log(String message, Object... args) {
        if (HorseBreederConfig.get().verboseLogging) {
            HorseBreeder.LOGGER.info("[horsebreeder] " + message, args);
        }
    }

    /**
     * 只在状态发生<b>变化</b>时打印，用于“否则每 tick 都会打”的场景。
     * 用法：{@code if (Trace.onChange("source", current)) Trace.log(...);}
     *
     * @return 本次调用是否为一次变化
     */
    public static boolean onChange(String key, Object value) {
        if (!HorseBreederConfig.get().verboseLogging) return false;
        return !value.equals(LAST.put(key, value));
    }

    private static final java.util.Map<String, Object> LAST = new java.util.HashMap<>();
}
