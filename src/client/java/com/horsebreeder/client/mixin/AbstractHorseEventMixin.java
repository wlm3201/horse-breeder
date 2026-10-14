package com.horsebreeder.client.mixin;

/**
 * AGENT NOTES —— 捕捉“马成功繁殖”瞬间的客户端广播。
 *
 * <p>依据：{@code Animal#finalizeSpawnChildFromBreeding} 在成功后对本只动物
 * {@code broadcastEntityEvent((byte)18)}，经 {@code AbstractHorse#handleEntityEvent}
 * 落到 {@code Animal#handleEntityEvent} 处理。18 是“爱心粒子”事件，也是客户端唯一能拿到的
 * “这一对真的交配了”的信号 —— 服务端不会同步 inLove 余量。
 *
 * <p>只在“某一只”父母身上广播：<b>广播的是哪一只并不确定</b>，
 * 所以下游不能假设，补全另一只的工作在 {@code HorseStates#bookBreeding}（按距离找伴侣）里做。
 */
import com.horsebreeder.client.HorseBreederClient;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractHorse.class)
public class AbstractHorseEventMixin {
    @Inject(method = "handleEntityEvent", at = @At("HEAD"))
    private void horsebreeder$onEntityEvent(byte id, CallbackInfo ci) {
        if (id != 18) return;
        AbstractHorse horse = (AbstractHorse) (Object) this;
        if (!horse.level().isClientSide()) return;
        HorseBreederClient.controller().onLoveEvent(horse);
    }
}
