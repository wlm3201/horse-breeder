package com.horsebreeder.client.util;

/**
 * AGENT NOTES —— 翻译键的薄封装。
 *
 * <p>{@code text()} 会立刻把 Component 解析成 String。这在 HUD（每帧）里是有成本的，
 * 并且会丢掉样式；仅在需要拼接/比较时用。展示场景优先 {@code component()}。
 *
 * <p>约定：所有键集中在 {@code assets/horsebreeder/lang/*.json}，
 * 新增 hard-coded 文本等于新增本地化债 —— 中英两份都要补。
 */
import net.minecraft.network.chat.Component;

public final class Lang {
    private Lang() {
    }

    public static String text(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    public static Component component(String key, Object... args) {
        return Component.translatable(key, args);
    }
}
