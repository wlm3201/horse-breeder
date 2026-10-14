package com.horsebreeder.client.mixin;

import com.horsebreeder.client.hud.HudOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Minecraft 26.3: append the shared overlay after the vanilla HUD. */
@Mixin(Hud.class)
public class GuiMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V", at = @At("TAIL"))
    private void horsebreeder$drawHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        HudOverlay.render(graphics, deltaTracker);
    }
}
