package com.horsebreeder.client.mixin;

/**
 * AGENT NOTES —— 让被标记的马进入 {@code shouldEntityAppearGlowing == true} 分支。
 *
 * <p>与 {@code EntityGlowColorMixin} 成对出现：这边决定“要不要发光”，那边决定“发什么颜色”。
 * 少了这一个，颜色被算出来也不会启用 outline。
 *
 * <p>注意这里是每帧、对视野内每个实体都会被调用的热路径，
 * 因此 {@code controller.glowColor} 必须保持 O(1) 的 HashMap 查找，别改。
 */
import com.horsebreeder.client.HorseBreederClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class MinecraftGlowMixin {
    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
    private void horsebreeder$glowMarked(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (HorseBreederClient.controller().glowColor(entity) != 0) {
            cir.setReturnValue(true);
        }
    }
}
