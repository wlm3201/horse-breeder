package com.horsebreeder.client;

/**
 * AGENT NOTES —— B 键键位（Fabric KeyMapping API）。
 *
 * <p>静态持有而非每次 new：KeyMapping 注册一次即写入 options.txt，重复注册会造成重复键位条目。
 * 点击的“消费”由 {@code keyMapping.consumeClick()} 在 END_CLIENT_TICK 里完成，
 * 不要改成在 {@code RenderTick} 里读 —— 后者每帧频率不同，会漏掉或重复触发。
 */
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public final class ToggleKey {
    public static final KeyMapping.Category CATEGORY =
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath("horsebreeder", "general"));

    private static final KeyMapping TOGGLE = new KeyMapping(
        "key.horsebreeder.toggle", InputConstants.KEY_B, CATEGORY);

    private ToggleKey() {
    }

    public static KeyMapping toggle() {
        return TOGGLE;
    }
}
