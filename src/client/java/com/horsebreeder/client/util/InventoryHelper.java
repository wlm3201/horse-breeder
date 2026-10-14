package com.horsebreeder.client.util;

/**
 * AGENT NOTES —— 背包槽位索引的工具函数 + 少量走 ClickType 服务器的移动操作。
 *
 * <p><b>索引有两套，别混</b>：{@code getInventory()} 用 0..35（0..8 热键栏，9..35 主背包）；
 * 而 GUI/menu 用 {@code InventoryMenu.INV_SLOT_START + n}，{@code throwSlot} 用的是后者。
 * {@code findMainInventory} 返回的是 0..26 的主背包下标（已减去 9），和 swap 的参数约定一致。
 *
 * <p>{@code swap / throwSlot} 走 {@code gameMode.handleContainerInput}，
 * 依赖当前 {@code player.containerMenu.containerId} 与服务端一致；
 * 若此时玩家开着别的容器会静默失败。
 */
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

public final class InventoryHelper {
    private InventoryHelper() {
    }

    public static int findHotbar(LocalPlayer player, Predicate<ItemStack> matcher) {
        for (int i = 0; i < 9; i++) {
            if (matcher.test(player.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    public static int findMainInventory(LocalPlayer player, Predicate<ItemStack> matcher) {
        for (int i = 9; i < 36; i++) {
            if (matcher.test(player.getInventory().getItem(i))) return i - 9;
        }
        return -1;
    }

    public static int findEmptyHotbar(LocalPlayer player) {
        return findHotbar(player, ItemStack::isEmpty);
    }

    public static int findEmptyMainInventory(LocalPlayer player) {
        return findMainInventory(player, ItemStack::isEmpty);
    }

    public static void swap(Minecraft mc, LocalPlayer player, int mainInventoryIndex, int hotbarIndex) {
        if (mainInventoryIndex < 0 || mainInventoryIndex > 26 || hotbarIndex < 0 || hotbarIndex > 8) return;
        int menuSlot = InventoryMenu.INV_SLOT_START + mainInventoryIndex;
        mc.gameMode.handleContainerInput(player.containerMenu.containerId, menuSlot, hotbarIndex,
            ContainerInput.SWAP, player);
    }

    public static void throwSlot(Minecraft mc, LocalPlayer player, int menuSlot, boolean wholeStack) {
        mc.gameMode.handleContainerInput(player.containerMenu.containerId, menuSlot, wholeStack ? 1 : 0,
            ContainerInput.THROW, player);
    }
}
