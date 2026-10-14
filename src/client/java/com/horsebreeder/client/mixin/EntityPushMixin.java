package com.horsebreeder.client.mixin;

/**
 * AGENT NOTES —— 站在马群里时不被马挤开。
 *
 * <p>为什么必须有它：10×10 围栏里挤 20+ 匹马时，实体推力会把玩家推得到处漂移，
 * 这是“够不到目标 → 一直走”这类问题的根因之一。
 *
 * <p>只拦 {@code LocalPlayer} 被 {@code AbstractHorse} 推的情况，不影响：
 * 玩家推其他实体、马受攻击被击退、PVP。
 */
import com.horsebreeder.client.config.HorseBreederConfig;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public class EntityPushMixin {
    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void horsebreeder$ignoreHorsePush(Entity entity, CallbackInfo ci) {
        // 两闸都关时 mod 应当完全隐身：马照常推玩家（原版行为）。
        HorseBreederConfig config = HorseBreederConfig.get();
        if (!config.hudEnabled && !config.enabled) return;
        if (!config.ignoreHorsePush) return;
        if ((Object) this instanceof LocalPlayer && entity instanceof AbstractHorse) {
            ci.cancel();
        }
    }
}
