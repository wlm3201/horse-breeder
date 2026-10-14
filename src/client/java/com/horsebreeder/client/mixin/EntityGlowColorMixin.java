package com.horsebreeder.client.mixin;

/**
 * AGENT NOTES —— 让“被标记的马”发光并显示指定轮廓色。
 *
 * <p>为什么劫持 {@code getTeamColor}：它同时被 {@code EntityRenderer} 用来决定
 * outline color 且是 common 侧方法，一处改动即可同时拿到颜色 + 高亮。
 * 它<b>只在渲染线程</b>调用（已核对 26.1.2 全部调用点），因此可以安全读
 * {@code HorseDisplay#markerColors} 这个非线程安全 Map。
 *
 * <p>哨兵约定：0 = 未标记，保持原语义；同时意味着任何 Markers 常量都不得为纯黑。
 */
import com.horsebreeder.client.HorseBreederClient;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class EntityGlowColorMixin {
    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void horsebreeder$markerColor(CallbackInfoReturnable<Integer> cir) {
        int color = HorseBreederClient.controller().glowColor((Entity) (Object) this);
        if (color != 0) {
            cir.setReturnValue(color);
        }
    }
}
