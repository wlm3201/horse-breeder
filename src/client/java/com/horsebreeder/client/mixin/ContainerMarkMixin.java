package com.horsebreeder.client.mixin;

/**
 * AGENT NOTES —— “手持胡萝卜/金萝卜左键箱子 = 标记该容器为补货点”。
 *
 * <p>为什么不做一套独立 GUI：容器在客户端侧是只读的 BlockEntity，
 * 没有第二次点击授权的回合制 UI 可以挂标记；这里选了最短路径（拦截破坏）。
 *
 * <p><b>副作用（已知且刻意，但值得复查）</b>：手持这两样东西时无法挖掉任何容器，
 * 只能标记。若将来改破坏性（比如要求潜行），记得同步改
 * {@code horsebreeder.option.goldenCarrotContainer.description} 那份文案（两种语言各一处）。
 */
import com.horsebreeder.client.HorseBreederClient;
import com.horsebreeder.client.config.HorseBreederConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public class ContainerMarkMixin {
    @Inject(method = "startDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z", at = @At("HEAD"), cancellable = true)
    private void horsebreeder$markContainer(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;

        ItemStack held = client.player.getMainHandItem();
        boolean golden = held.is(Items.GOLDEN_CARROT);
        if (!golden && !held.is(Items.CARROT)) return;
        if (!(client.level.getBlockEntity(pos) instanceof Container)) return;
        // 必须潜行：否则拿着胡萝卜时永远挖不掉任何容器（这是一个很容易踩到的坑）。
        if (!client.player.isShiftKeyDown()) return;
        // 这个 mixin 会取消一次原版破坏动作，属于“mod 主动改了游戏”。
        // HUD 与养马都关时应当完全隐身，交还给原版行为。
        if (!HorseBreederConfig.get().hudEnabled && !HorseBreederConfig.get().enabled) return;

        HorseBreederClient.controller().markContainer(pos, golden);
        cir.setReturnValue(false);
        cir.cancel();
    }
}
