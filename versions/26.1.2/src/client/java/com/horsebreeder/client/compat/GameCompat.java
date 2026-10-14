package com.horsebreeder.client.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;

/** Minecraft 26.1.2 API boundary; breeding behavior stays in shared sources. */
public final class GameCompat {
    private GameCompat() {}

    public static Screen currentScreen(Minecraft client) {
        return client.screen;
    }

    public static void setScreen(Minecraft client, Screen screen) {
        client.setScreen(screen);
    }

    public static void setOverlayMessage(Minecraft client, Component message, boolean animate) {
        client.gui.setOverlayMessage(message, animate);
    }

    public static void swingAfterInteraction(LocalPlayer player) {
        player.swing(InteractionHand.MAIN_HAND);
    }

    public static void swingAfterAttack(LocalPlayer player) {
        player.swing(InteractionHand.MAIN_HAND);
    }
}
