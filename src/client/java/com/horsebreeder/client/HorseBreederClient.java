package com.horsebreeder.client;

import com.horsebreeder.client.compat.GameCompat;

/**
 * AGENT NOTES —— 模组装配点（仅客户端线程）。持有全局唯一的 {@link BreederController}。
 *
 * <p>三个入口：
 * <ol>
 *   <li>连接/断开 {@code ClientPlayConnectionEvents} → {@code onWorldJoin / onWorldLeave}，
 *       负责切换每个存档的马匹状态文件并清空所有缓存（这是唯一会清空状态的地方）。</li>
 *   <li>B 键 → 翻转 {@code config.enabled} 并立即存盘。注意 enabling 时不需要额外初始化，
 *       {@code BreederController#onTick} 自身会把所有 transient 状态复位。</li>
 *   <li>{@code ClientTickEvents.END_CLIENT_TICK} → {@code controller.onTick()}。</li>
 * </ol>
 *
 * <p><b>关键约束</b>：{@code client.isPaused()} 时不 tick。这意味着玩家打开暂停菜单时，
 * 自动化会冻在半路，且 {@code PlayerActions} 按住的 {@code keyUp / keyUse} 不会被释放，
 * 直到恢复游戏后被下一次 tick 的 {@code stop()} 接管。这是有意的（防止在菜单里继续操作玩家），
 * 但也是“暂停后角色还在走”这类 bug 报告的根因。
 */
import com.horsebreeder.client.config.HorseBreederConfig;
import com.horsebreeder.client.logic.BreederController;
import com.horsebreeder.client.util.Lang;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

public class HorseBreederClient implements ClientModInitializer {
    private static final BreederController CONTROLLER = new BreederController();

    public static BreederController controller() {
        return CONTROLLER;
    }

    @Override
    public void onInitializeClient() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> CONTROLLER.onWorldJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> CONTROLLER.onWorldLeave());

        KeyMappingHelper.registerKeyMapping(ToggleKey.toggle());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (ToggleKey.toggle().consumeClick()) {
                HorseBreederConfig config = HorseBreederConfig.get();
                config.enabled = !config.enabled;
                config.save();
                if (!config.enabled) CONTROLLER.reset();
                announce(client, config.enabled);
            }
            if (client.player != null && client.level != null && !client.isPaused()) {
                CONTROLLER.onTick();
            } else if (client.player != null) {
                // 暂停期间不会走 onTick，但玩家角色的按键状态还留在 client.options 里，
                // 必须主动松开，否则会出现“人在暂停菜单里还在往前走”。
                CONTROLLER.releaseInput();
            }
        });

    }

    private static void announce(Minecraft client, boolean enabled) {
        if (client.gui == null) return;
        GameCompat.setOverlayMessage(client,
            Lang.component(enabled ? "horsebreeder.message.enabled" : "horsebreeder.message.disabled"), false);
    }
}
