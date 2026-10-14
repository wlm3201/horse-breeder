package com.horsebreeder.client.logic;

import com.horsebreeder.client.compat.GameCompat;

/**
 * AGENT NOTES —— 从已标记容器补货（胡萝卜 / 金萝卜）的一次性任务。
 *
 * <p>为什么是独立的“任务对象”而不是几个 if：它会跨多个 tick 执行（走过去 → 右键开箱 →
 * 快速移动 → 关箱），并且<b>只要它在跑，BreederController 会屏蔽掉所有其他动作</b>。
 * 因此它的每一步都必须有明确终态：{@link Step#done} / {@link Step#failed}，
 * 绝不允许“既没完成也没失败”地挂着（会导致整个 mod 永久让位）。
 *
 * <p>三个易踩的坑：
 * <ul>
 *   <li>检测“箱子是否开了”用的是 {@code player.containerMenu != player.inventoryMenu}，
 *       而区分“是不是我开的那一个”用 {@code transferMenuId}；玩家先开了别的箱子会被判 interrupted。</li>
 *   <li>成功判定是“食物数量变多了”，而不依赖任何服务端返回结果，
 *       因为同一 tick 内客户端背包可能还没同步。</li>
 *   <li>{@code countFood} 只数 0..35（玩家主背包），不数副手/马背包裹。</li>
 * </ul>
 *
 * <p><b>失败原因必须“说得出为什么”</b>：容器坐标是<b>全局配置</b>，不会随存档切换失效。
 * 换世界后旧坐标几乎必然落在未加载区域，此时 {@code isLoaded(pos)} 为 false、
 * “是不是容器”的校验会被短路，任务会一路走到超时。以前的结果是玩家被拖着朝上一个世界的
 * 坐标冲刺 30 秒，最后只看到一句笼统的“超时”。现在的处理是走路前先过三道闸
 * （太远 / 关了自动移动 / 卡住），任何一道不过就立刻报 {@code unreachable} 而不是硬走。
 */
import com.horsebreeder.client.config.HorseBreederConfig;
import com.horsebreeder.client.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

final class Restock {
    private static final long TIMEOUT_MS = 30_000;
    private static final long OPEN_MS = 3000;
    /**
     * 超过这个距离就判定“去不了”。冲刺约 5.6 格/秒 × 30 秒 ≈ 168 格，
     * 取 128 留了余量：真正标记在马圈旁边的容器（通常 20 格内）离这个上限还很远，
     * 而换个存档后指向虚空的旧坐标会远远超出 —— 那时硬走没有任何意义。
     */
    private static final double MAX_WALK_DISTANCE = 128.0;
    private static final double MAX_WALK_SQ = MAX_WALK_DISTANCE * MAX_WALK_DISTANCE;

    record Step(boolean running, String failure, String detail) {
        static Step running(String detail) {
            return new Step(true, null, detail);
        }

        static Step failed(String reason) {
            return new Step(false, reason, reason);
        }

        static Step done(String detail) {
            return new Step(false, null, detail);
        }
    }

    final boolean golden;
    final BlockPos pos;
    /** 快照自 config.walkToTarget：补货的步行同样必须尊重这个开关。 */
    final boolean walkToTarget;
    final long deadlineAt;
    boolean arrived;
    long openStartedAt = -1;
    long lastOpenAttempt = -1;
    long transferStartedAt = -1;
    int transferMenuId = -1;
    int foodBeforeTransfer;

    private Restock(boolean golden, BlockPos pos, boolean walkToTarget, long deadlineAt) {
        this.golden = golden;
        this.pos = pos;
        this.walkToTarget = walkToTarget;
        this.deadlineAt = deadlineAt;
    }

    static Restock begin(HorseBreederConfig config, boolean golden, long now) {
        BlockPos pos = HorseBreederConfig.parseBlockPos(
            golden ? config.goldenCarrotContainer : config.carrotContainer);
        return pos == null ? null : new Restock(golden, pos, config.walkToTarget, now + TIMEOUT_MS);
    }

    String label() {
        return Lang.text(golden ? "horsebreeder.food.goldenCarrot" : "horsebreeder.food.carrot");
    }

    Item item() {
        return golden ? Items.GOLDEN_CARROT : Items.CARROT;
    }

    Step tick(Minecraft mc, LocalPlayer player, PlayerActions actions, long now) {
        String label = label();

        if (transferStartedAt >= 0) {
            if (countFood(player, item()) > foodBeforeTransfer) {
                closeOwnMenu(player);
                return Step.done(Lang.text("horsebreeder.restock.taken", label));
            }
            if (player.containerMenu.containerId != transferMenuId) {
                return Step.failed(Lang.text("horsebreeder.restock.interrupted"));
            }
            if (now - transferStartedAt >= OPEN_MS) {
                closeOwnMenu(player);
                return Step.failed(Lang.text("horsebreeder.restock.transferFailed", label));
            }
            return Step.running(Lang.text("horsebreeder.restock.taking", label));
        }

        if (player.containerMenu != player.inventoryMenu) {
            actions.stop(player);
            if (openStartedAt < 0) return Step.failed(Lang.text("horsebreeder.restock.interrupted"));
            transferMenuId = player.containerMenu.containerId;
            Slot food = findFood(player, item());
            if (food == null) {
                closeOwnMenu(player);
                return Step.failed(Lang.text("horsebreeder.restock.empty", label, label));
            }
            if (!hasSpace(player, food.getItem())) {
                closeOwnMenu(player);
                return Step.failed(Lang.text("horsebreeder.restock.inventoryFull"));
            }
            foodBeforeTransfer = countFood(player, item());
            transferStartedAt = now;
            mc.gameMode.handleContainerInput(transferMenuId, food.index, 0, ContainerInput.QUICK_MOVE, player);
            return Step.running(Lang.text("horsebreeder.restock.taking", label));
        }

        if (GameCompat.currentScreen(mc) != null) {
            actions.stop(player);
            return Step.failed(Lang.text("horsebreeder.restock.interrupted"));
        }

        if (now >= deadlineAt) {
            String where = HorseBreederConfig.formatBlockPos(pos);
            return Step.failed(Lang.text("horsebreeder.restock.timeout", label, where));
        }

        if (mc.level != null && mc.level.isLoaded(pos)
            && !(mc.level.getBlockEntity(pos) instanceof Container)) {
            return Step.failed(Lang.text("horsebreeder.restock.notContainer", HorseBreederConfig.formatBlockPos(pos)));
        }

        Vec3 target = Vec3.atCenterOf(pos);
        double reach = player.blockInteractionRange() * 0.9;
        double distanceSq = player.getEyePosition().distanceToSqr(target);
        boolean inReach = distanceSq <= reach * reach
            || (arrived && distanceSq <= reach * reach * 2.25);
        if (!inReach) {
            // 够不着就等于去不了：立刻失败并说清原因，而不是把玩家拖出去跑 30 秒。
            // 三道闸 —— 远到跑不到 / 关了自动移动 / 走不动（撞墙或掉坑里原地磨）。
            if (distanceSq > MAX_WALK_SQ || !walkToTarget || actions.isStuck()) {
                return Step.failed(Lang.text("horsebreeder.restock.unreachable", label,
                    HorseBreederConfig.formatBlockPos(pos)));
            }
            actions.approach(player, target);
            return Step.running(Lang.text("horsebreeder.restock.walking", label,
                String.format("%.1f", Math.sqrt(distanceSq))));
        }

        arrived = true;
        if (openStartedAt < 0) {
            openStartedAt = now;
        } else if (now - openStartedAt > OPEN_MS) {
            return Step.failed(Lang.text("horsebreeder.restock.blocked", label, HorseBreederConfig.formatBlockPos(pos)));
        }
        actions.stop(player);
        if (lastOpenAttempt < 0 || now - lastOpenAttempt >= 500) {
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(target, Direction.UP, pos, false));
            lastOpenAttempt = now;
        }
        return Step.running(Lang.text("horsebreeder.restock.taking", label));
    }

    private static Slot findFood(LocalPlayer player, Item wanted) {
        AbstractContainerMenu menu = player.containerMenu;
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()) continue;
            if (!slot.hasItem() || !slot.getItem().is(wanted)) continue;
            if (!slot.mayPickup(player)) continue;
            return slot;
        }
        return null;
    }

    private void closeOwnMenu(LocalPlayer player) {
        if (player.containerMenu != player.inventoryMenu && player.containerMenu.containerId == transferMenuId) {
            player.closeContainer();
        }
    }

    private static int countFood(LocalPlayer player, Item wanted) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(wanted)) count += stack.getCount();
        }
        return count;
    }

    private static boolean hasSpace(LocalPlayer player, ItemStack food) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty() || (ItemStack.isSameItemSameComponents(stack, food)
                && stack.getCount() < stack.getMaxStackSize())) return true;
        }
        return false;
    }
}
