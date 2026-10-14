package com.horsebreeder.client.mixin;

/**
 * AGENT NOTES —— 捕捉“有玩家喂了这匹马”的事件，用于推断 Temper / 催熟 / 恋爱。
 *
 * <p>注入点是 {@code AbstractHorse#fedFood}（{@code Horse#mobInteract} 会先判 {@code isFood} 再调它），
 * 注意 {@code AbstractHorse#mobInteract} 自身<b>不</b>处理食物 —— 该路径只有驴/骡会走到。
 *
 * <p>26.1.2 / 26.2 / 26.3 源码均声明相同的 fedFood 描述符；匹配失败应直接报错，
 * 避免喂食观察悄然失效。升级 MC 后必须重新核对该方法。
 *
 * <p>HEAD 注入意味着<b>东西还没被吃掉</b>就被回调，因此 self 的状态还是旧的；
 * 区分“我们自己喂的”由上层 {@code isOwnFeed} 负责。
 */
import com.horsebreeder.client.HorseBreederClient;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractHorse.class)
public class AbstractHorseFeedMixin {
    @Inject(method = "fedFood(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/InteractionResult;", at = @At("HEAD"))
    private void horsebreeder$onFed(Player player, ItemStack stack, CallbackInfoReturnable<InteractionResult> cir) {
        AbstractHorse self = (AbstractHorse) (Object) this;
        if (!self.level().isClientSide()) return;
        HorseBreederClient.controller().onHorseFed(self, stack);
    }
}
