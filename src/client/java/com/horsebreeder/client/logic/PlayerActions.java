package com.horsebreeder.client.logic;

import com.horsebreeder.client.compat.GameCompat;
import com.horsebreeder.client.util.Trace;

/**
 * AGENT NOTES —— 唯一允许伪造玩家输入的地方。改这里 = 改和服务器的契约。
 *
 * <p>原则：<b>尽量调用 {@code GameMode}，而不是手搓包</b>。只有两处必须手搓：
 * <ul>
 *   <li>下马：发 {@code ServerboundPlayerInputPacket(shift=true)}，与 vanilla 玩家按 Shift 完全一致，
 *       服务端 {@code Player#rideTick} 会据此 {@code stopRiding()}。</li>
 *   <li>丢皮革：用 {@code ContainerInput.THROW}，直接操作 menu slot。</li>
 * </ul>
 *
 * <p><b>本类是带状态的</b>：{@code walking / eating / lastPosition / stuckSinceMs}。
 * 因此每个 tick 结束时，上层必须保证走到了 {@link #stop(LocalPlayer)} 或
 * {@link #stopEating(LocalPlayer)}，否则会把 {@code keyUp / keyUse} 永久按住。
 * 注意 {@code HorseBreederClient} 在 {@code client.isPaused()} 时不 tick，
 * 所以暂停时这两个键不会被松开 —— 这是已知行为，别在没改上层的前提下“修复”它。
 */
import com.horsebreeder.client.util.InventoryHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

public final class PlayerActions {
    private static final long STUCK_WARN_MS = 2000;
    private static final double STUCK_MOVE_SQ = 0.0004;
    private static final long DROP_LEATHER_INTERVAL_MS = 250;

    /** 起跳后要等到达顶点再砍：只有开始下坠（fallDistance > 0）服务端才判暴击。 */

    public enum Reach { IN_RANGE, WALKING, UNREACHABLE }

    public enum HandState { READY, SWITCHING, MISSING }

    private final Minecraft mc;
    private boolean walking;
    private boolean eating;
    private Vec3 lastPosition;
    private long stuckSinceMs = -1;
    private long lastLeatherDropAt = -1;
    private Entity critTarget;
    private boolean critWaitingForPeak;
    private double critLastY;

    public PlayerActions(Minecraft mc) {
        this.mc = mc;
    }

    public Reach approach(LocalPlayer player, AbstractHorse horse, double reach, boolean walkToTarget) {
        if (horse.getBoundingBox().distanceToSqr(player.getEyePosition()) <= reach * reach) {
            stop(player);
            stuckSinceMs = -1;
            return Reach.IN_RANGE;
        }
        if (!walkToTarget) {
            stop(player);
            return Reach.UNREACHABLE;
        }
        walkTowards(player, horse.position());
        return Reach.WALKING;
    }

    public void approach(LocalPlayer player, Vec3 target) {
        walkTowards(player, target);
    }

    public static double distanceTo(LocalPlayer player, AbstractHorse horse) {
        return Math.sqrt(horse.getBoundingBox().distanceToSqr(player.getEyePosition()));
    }

    public boolean isStuck() {
        return stuckSinceMs >= 0 && now() - stuckSinceMs > STUCK_WARN_MS;
    }

    public void stop(LocalPlayer player) {
        stopEating(player);
        if (walking) {
            mc.options.keyUp.setDown(false);
            walking = false;
            player.setSprinting(false);
        }
        lastPosition = null;
        stuckSinceMs = -1;
    }

    private void walkTowards(LocalPlayer player, Vec3 target) {
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;

        face(player, yaw, player.getXRot());
        mc.options.keyUp.setDown(true);
        walking = true;
        player.setSprinting(canSprint(player));

        Vec3 current = player.position();
        if (lastPosition != null && current.distanceToSqr(lastPosition) < STUCK_MOVE_SQ) {
            if (stuckSinceMs < 0) stuckSinceMs = now();
        } else {
            stuckSinceMs = -1;
        }
        lastPosition = current;
    }

    private static boolean canSprint(LocalPlayer player) {
        return player.getFoodData().hasEnoughFood()
            && !player.isUnderWater()
            && !player.isShiftKeyDown()
            && !player.horizontalCollision;
    }

    public void face(LocalPlayer player, float yaw, float pitch) {
        player.setYRot(yaw);
        player.setYBodyRot(yaw);
        player.setXRot(pitch);
        mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, player.onGround(), player.horizontalCollision));
    }

    public void dismount(LocalPlayer player) {
        mc.getConnection().send(new ServerboundPlayerInputPacket(new Input(false, false, false, false, false, true, false)));
    }

    public void interact(LocalPlayer player, AbstractHorse horse) {
        mc.gameMode.interact(player, horse, new EntityHitResult(horse, horse.getBoundingBox().getCenter()),
            InteractionHand.MAIN_HAND);
        GameCompat.swingAfterInteraction(player);
    }

    private static final float EAT_PITCH = -90.0F;

    public void eat(LocalPlayer player) {
        if (!eating) {
            stop(player);
            face(player, player.getYRot(), EAT_PITCH);
            mc.options.keyUse.setDown(true);
            eating = true;
            return;
        }
        face(player, player.getYRot(), EAT_PITCH);
    }

    public void stopEating(LocalPlayer player) {
        if (!eating) return;
        mc.options.keyUse.setDown(false);
        eating = false;
        if (player != null) face(player, player.getYRot(), 0.0F);
    }

    /**
     * 发起一次攻击。{@code criticals} 打开时先做一次<b>原生跳跃</b>，等到达顶点
     * 开始下坠后才把攻击包发出去（此时 {@code fallDistance > 0}，服务端判定为暴击）。
     *
     * <p>为什么要分多个 tick、且必须返回状态：起跳、等顶点、挥砍不在同一 tick，
     * 上层得知道“这一 tick 还没打出去”，否则会以为攻击已经完成。
     *
     * @return 本次调用是否真的把攻击包发了出去；false = 还在起跳/等待顶点，下一 tick 再调用一次
     */
    public boolean attack(LocalPlayer player, Entity target, boolean criticals) {
        if (critTarget != null && (critTarget != target || !critTarget.isAlive())) {
            cancelPendingAttack();
        }
        if (critTarget != null) {
            if (critWaitingForPeak) {
                double y = player.getY();
                // Y 不再上升 = 到顶点了；下一次调用（下一 tick）才挥砍，确保已经开始下坠。
                if (y <= critLastY) critWaitingForPeak = false;
                critLastY = y;
                return false;
            }
            Entity pending = critTarget;
            critTarget = null;
            strike(player, pending);
            return true;
        }
        if (!criticals || !canJump(player)) {
            strike(player, target);
            return true;
        }
        critTarget = target;
        critWaitingForPeak = true;
        critLastY = player.getY();
        player.jumpFromGround();
        return false;
    }

    /**
     * 只有站在地上、且不在液体里/不在梯子上/没在骑乘/没在飞时才起跳。
     * 已经离地说明本来就有下坠距离，直接砍（服务端可能已经在算暴击）。
     */
    private static boolean canJump(LocalPlayer player) {
        return player.onGround()
            && !player.isInLiquid()
            && !player.onClimbable()
            && !player.isPassenger()
            && !player.getAbilities().flying;
    }

    /** 取消一次尚未落下的暴击（关闭自动化 / 重进世界时用，避免残留一个 Entity 引用）。 */
    public void cancelPendingAttack() {
        critTarget = null;
        critWaitingForPeak = false;
        critLastY = 0.0;
    }

    private void strike(LocalPlayer player, Entity target) {
        mc.gameMode.attack(player, target);
        GameCompat.swingAfterAttack(player);
    }

    /**
     * 松开手里会引诱马的物品。
     *
     * <p>原因：{@code AbstractHorse#addBehaviourGoals} 注册了
     * {@code new TemptGoal(this, 1.25, i -> i.is(ItemTags.HORSE_TEMPT_ITEMS), false)}。
     * 只要手里拿着金萝卜（或金苹果），整群马的目标就变成"跟着玩家"，
     * 全挤在玩家身边反而让要配对的那两匹走不到一起 —— 而 {@code BreedGoal} 要求它们相距 3 格以内。
     *
     * <p>优先切到空手；背包全满没有空格时退一步，切到任意一件<b>不会</b>引诱马的物品
     * （比如斧头），因为引诱的根源是"手里拿着什么"，不是"手里有没有东西"。
     *
     * @return 是否还需要下一 tick 再确认（切物品要一个 tick 才生效）
     */
    public boolean releaseTemptingItem(LocalPlayer player) {
        Predicate<ItemStack> tempting = stack -> stack.is(ItemTags.HORSE_TEMPT_ITEMS);
        if (!tempting.test(player.getMainHandItem())) {
            Trace.onChange("tempt-stuck", false);
            return false;
        }
        if (ensureHand(mc, player, ItemStack::isEmpty, true) != HandState.MISSING) return true;
        if (ensureHand(mc, player, stack -> !stack.isEmpty() && !tempting.test(stack), false) == HandState.MISSING) {
            // 只在状态刚变化时报一次：背包塞满诱惑物时这会每 tick 都成立。
            if (Trace.onChange("tempt-stuck", true)) {
                Trace.log("inventory is all tempting food; horses will keep crowding the player");
            }
        }
        return true;
    }

    public static boolean hasItem(LocalPlayer player, Predicate<ItemStack> matcher) {
        for (int i = 0; i < 36; i++) {
            if (matcher.test(player.getInventory().getItem(i))) return true;
        }
        return false;
    }

    public static HandState ensureHand(Minecraft mc, LocalPlayer player, Predicate<ItemStack> matcher,
                                       boolean allowEmptyHand) {
        if (matcher.test(player.getMainHandItem())) return HandState.READY;

        int hotbar = InventoryHelper.findHotbar(player, matcher);
        if (hotbar >= 0) {
            selectHotbar(mc, player, hotbar);
            return HandState.SWITCHING;
        }
        int inventory = InventoryHelper.findMainInventory(player, matcher);
        if (inventory >= 0) {
            InventoryHelper.swap(mc, player, inventory, player.getInventory().getSelectedSlot());
            return HandState.SWITCHING;
        }
        if (allowEmptyHand) {
            int empty = InventoryHelper.findEmptyMainInventory(player);
            if (empty >= 0) {
                InventoryHelper.swap(mc, player, empty, player.getInventory().getSelectedSlot());
                return HandState.SWITCHING;
            }
            int emptyHotbar = InventoryHelper.findEmptyHotbar(player);
            if (emptyHotbar >= 0) {
                selectHotbar(mc, player, emptyHotbar);
                return HandState.SWITCHING;
            }
        }
        return HandState.MISSING;
    }

    private static void selectHotbar(Minecraft mc, LocalPlayer player, int slot) {
        player.getInventory().setSelectedSlot(slot);
        mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
    }

    /**
     * 丢掉全部皮革。限流 + 每次只丢一格是刻意的：
     * 服务端要 1~2 tick 才同步背包，若每 tick 对每个皮革槽都发 THROW，
     * 同一堆皮革会被重复请求丢弃（反作弊噪音，也可能引起丢包重试）。
     */
    public void dropLeather(LocalPlayer player, double yaw) {
        long now = now();
        if (now - lastLeatherDropAt < DROP_LEATHER_INTERVAL_MS) return;
        face(player, (float) yaw, 0.0F);
        for (int menuSlot = InventoryMenu.INV_SLOT_START; menuSlot < InventoryMenu.USE_ROW_SLOT_END; menuSlot++) {
            int inventoryIndex = menuSlot >= InventoryMenu.USE_ROW_SLOT_START
                ? menuSlot - InventoryMenu.USE_ROW_SLOT_START
                : menuSlot;
            if (player.getInventory().getItem(inventoryIndex).is(Items.LEATHER)) {
                InventoryHelper.throwSlot(mc, player, menuSlot, true);
                lastLeatherDropAt = now;
                return;
            }
        }
    }

    public static boolean carryingLeather(LocalPlayer player) {
        for (int slot = 0; slot < 36; slot++) {
            if (player.getInventory().getItem(slot).is(Items.LEATHER)) return true;
        }
        return false;
    }

    private static long now() {
        return ElapsedTime.millis();
    }
}
