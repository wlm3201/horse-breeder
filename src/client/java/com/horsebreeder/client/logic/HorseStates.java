package com.horsebreeder.client.logic;

/**
 * AGENT NOTES —— “服务端权威状态”的客户端缓存 / 估计器。整个 mod 的认知基础，改动风险最高。
 *
 * <p><b>为什么需要它</b>：客户端拿不到马的繁殖冷却、恋爱剩余 tick、Temper、精确 Age。
 * 于是这里维护 {@link Entry} 并用两套数据源喂养：
 * <ul>
 *   <li>{@link Source#EXACT}：单人世界能通过 {@code IntegratedServer} 拿到 ServerLevel，
 *       直接用 {@code getAge()/getTemper()/isInLove()}（见 {@code readExact}）。</li>
 *   <li>{@link Source#INFERRED}：多人服务器，只能靠“我们知道自己喂了什么 + 观察到的事件”推算。</li>
 * </ul>
 * 上层必须能通过 HUD 的 “exact/estimated” 判断当前数据可信度，不要把 INFERRED 当真值用。
 *
 * <p><b>Entry 里几个字段的含义（名字有误导性，务必看这里）</b>：
 * <ul>
 *   <li>{@code cooldownLeft} —— 繁殖冷却剩余 tick。精确模式下 = {@code max(0, age)}（age&gt;0 就是冷却）。</li>
 *   <li>{@code cooldownDoubt} —— 明确怀疑它已经进入冷却，但不知道确切进度。</li>
 *   <li>{@code exactAge} —— 精确模式的真实 age（负数 = 幼驹）。{@code Integer.MIN_VALUE} = 未知，勿当 0。</li>
 *   <li>{@code babyTicksLeft} —— 推测模式下幼驹还需 tick（正数）。</li>
 *   <li>{@code trustUntil} —— 精确数据的时间窗，窗内禁止被事件推断覆盖。</li>
 * </ul>
 *
 * <p><b>“喂-确认”握手</b>：我们喂金萝卜后并不知道服务器有没有成功（可能被 reach / 朝向 / 服务器判定拒绝），
 * 所以用 {@code pendingFeeds}（等待服务器的恋爱事件）+ {@code feedLocks}（等待期不许重复喂）做超时纠正。
 * 判定链条由三个彼此独立的类承担，各自都写清了"为什么"和"什么时候不能信"：
 * {@link BreedingAttribution}（事件 18 的定论）、{@link FeedProbe}（被拒即冷却）、
 * {@link MateBearing}（用朝向命名伴侣）。改这三个类之前务必先读它们的类注释。
 *
 * <p><b>落盘</b>：每份存档一个文件，见 {@link #savePath}；{@link HorseStateStore} 负责原子写。
 * 不做备份、不做旧格式兼容 —— 文件坏了就读不出来，直接删掉重来。
 */
import com.horsebreeder.HorseBreeder;
import com.horsebreeder.client.util.Trace;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class HorseStates {
    public static final int BABY_START_AGE = -24000;
    public static final int TEMPER_MAX = 100;
    public static final int TEMPER_PER_BUCK = 5;
    public static final int LOVE_TICKS = 600;
    public static final int BREED_COOLDOWN_TICKS = 6000;
    public static final int CARROT_TEMPER = 3;
    public static final int GOLDEN_CARROT_TEMPER = 5;
    public static final int CARROT_AGE_TICKS = 60 * 20;
    /**
     * 遗忘离场马匹的阈值。给得比冷却（6000）长得多是刻意的：大围栏里一匹马跑到检测范围外
     * 十几分钟再回来，若已被遗忘就会被当成新马重新播种，于是我们会对一匹其实还在冷却的马下手。
     */
    private static final int FORGET_AFTER_UNSEEN_TICKS = 72000;
    private static final long FEED_ANSWER_MS = 2500;
    private static final long FEED_LOCK_MS = 3000;
    private static final long OWN_FEED_MS = 2000;
    private static final long NEWBORN_SEEN_MS = 3000;
    private static final long TRUST_MS = 6000;
    private static final double MATE_BREED_DISTANCE = 3.0;

    public enum Source { EXACT, INFERRED }

    public enum State { BABY, BLOCKED, READY, IN_LOVE, COOLDOWN }

    public enum Blocked { UNTAMED, HURT, MOUNTED }

    public record Snapshot(State state, Blocked blocked, int ticksLeft, boolean uncertain) {
    }

    public static final class Entry {
        public int estimatedTemper;
        public int loveLeft;
        public int cooldownLeft;
        public int cooldownDoubt;
        public int babyTicksLeft;
        public long trustUntil;
        public int exactAge = Integer.MIN_VALUE;
        public int unseenTicks;
        private boolean babyObserved;
    }

    private final Map<UUID, Entry> entries = new HashMap<>();
    private final Map<UUID, Long> pendingFeeds = new HashMap<>();
    private final Map<UUID, Long> feedLocks = new HashMap<>();
    private final Map<UUID, Long> ownFeeds = new HashMap<>();
    private final Map<UUID, Long> newbornUntil = new HashMap<>();

    /** entity event 18 的待定判定 —— 见 {@link BreedingAttribution}。 */
    private final BreedingAttribution attribution = new BreedingAttribution();
    /** 本地玩家递出的食物，等爱心来回答 —— 见 {@link FeedProbe}。 */
    private final FeedProbe feedProbe = new FeedProbe();
    /** 可能处在冷却中、但还只是「怀疑」的马，带着它若真的冷却所剩的时间。 */
    private final Map<UUID, Integer> maybeCooldown = new HashMap<>();
    /**
     * 每匹马在它的事件 18 到达那一刻的头部朝向。必须在<b>事件到达时</b>采集：
     * {@code resetLove()} 在发事件前就结束了 BreedGoal，之后头就开始自由漂移了。
     */
    private final Map<UUID, Float> eventHeadYaw = new HashMap<>();
    private Source source = Source.INFERRED;
    private final LongSupplier clock;
    /** 上一次真正写进文件的内容，用于跳过无变化的落盘。 */
    private Map<UUID, HorseStateStore.SavedHorse> lastWritten;

    public HorseStates() {
        this(ElapsedTime::millis);
    }

    HorseStates(LongSupplier clock) {
        this.clock = clock;
    }

    public Source source() {
        return source;
    }

    private long now() {
        return clock.getAsLong();
    }

    public void clear() {
        entries.clear();
        pendingFeeds.clear();
        feedLocks.clear();
        ownFeeds.clear();
        newbornUntil.clear();
        attribution.clear();
        feedProbe.clear();
        maybeCooldown.clear();
        eventHeadYaw.clear();
        source = Source.INFERRED;
        lastWritten = null;
    }

    public void tick(List<AbstractHorse> horses, int delta) {
        delta = Math.max(0, delta);
        Minecraft client = Minecraft.getInstance();
        IntegratedServer server = client.getSingleplayerServer();
        ServerLevel serverLevel = server != null && client.level != null ? server.getLevel(client.level.dimension()) : null;
        source = serverLevel != null ? Source.EXACT : Source.INFERRED;
        // 探针：数据源切换会直接改变所有倒计时的可信度，值得在日志里看到。
        if (Trace.onChange("state-source", source)) Trace.log("state source -> {}", source);

        Map<UUID, AbstractHorse> visible = new HashMap<>();
        for (AbstractHorse horse : horses) {
            visible.put(horse.getUUID(), horse);
            Entry entry = getEntry(horse.getUUID());
            observeLifeStage(entry, horse.isBaby());
            // 精确读取成功时不走推测衰减；任一步缺失都会退回 INFERRED。
            // 注意 loveLeft 无论如何都要按 delta 衰减（readExact 只负责“重新赋值/清零”）。
            if (serverLevel == null || !readExact(serverLevel, horse, entry)) advance(entry, delta);
            entry.loveLeft = HorseStateTimers.remaining(entry.loveLeft, delta);
        }
        Iterator<Map.Entry<UUID, Entry>> known = entries.entrySet().iterator();
        while (known.hasNext()) {
            Map.Entry<UUID, Entry> row = known.next();
            if (visible.containsKey(row.getKey())) {
                row.getValue().unseenTicks = 0;
            } else {
                row.getValue().unseenTicks = HorseStateTimers.addCapped(
                        row.getValue().unseenTicks, delta, FORGET_AFTER_UNSEEN_TICKS + 1);
                if (row.getValue().unseenTicks > FORGET_AFTER_UNSEEN_TICKS) known.remove();
            }
        }
        pendingFeeds.keySet().removeIf(uuid -> !visible.containsKey(uuid));
        ownFeeds.values().removeIf(until -> until <= now());
        newbornUntil.values().removeIf(until -> until <= now());

        tickPending(horses, delta);
    }

    private void tickLocks() {
        long now = now();
        feedLocks.values().removeIf(deadline -> deadline <= now);
    }

    private boolean readExact(ServerLevel serverLevel, AbstractHorse horse, Entry entry) {
        Entity serverEntity = serverLevel.getEntity(horse.getUUID());
        if (!(serverEntity instanceof AbstractHorse serverHorse)) return false;

        int age = serverHorse.getAge();
        entry.exactAge = age;
        entry.babyTicksLeft = age < 0 ? -age : 0;
        entry.estimatedTemper = serverHorse.getTemper();
        entry.cooldownDoubt = 0;
        maybeCooldown.remove(horse.getUUID());
        entry.trustUntil = now() + TRUST_MS;

        if (serverHorse.isBaby()) {
            entry.cooldownLeft = 0;
            entry.loveLeft = 0;
            entry.cooldownDoubt = 0;
            return true;
        }
        entry.cooldownLeft = Math.max(0, age);
        entry.cooldownDoubt = 0;
        if (serverHorse.isInLove()) {
            if (entry.loveLeft <= 0) entry.loveLeft = LOVE_TICKS;
        } else {
            entry.loveLeft = 0;
        }
        return true;
    }

    private static void advance(Entry entry, long delta) {
        entry.cooldownLeft = HorseStateTimers.remaining(entry.cooldownLeft, delta);
        entry.cooldownDoubt = HorseStateTimers.remaining(entry.cooldownDoubt, delta);
        entry.exactAge = HorseStateTimers.advanceAge(entry.exactAge, delta);
        entry.babyTicksLeft = HorseStateTimers.remaining(entry.babyTicksLeft, delta);
    }

    private static void observeLifeStage(Entry entry, boolean baby) {
        if (baby && !entry.babyObserved) {
            entry.babyTicksLeft = -BABY_START_AGE;
            entry.exactAge = Integer.MIN_VALUE;
        } else if (!baby) {
            entry.babyTicksLeft = 0;
            if (entry.exactAge < 0 && entry.exactAge != Integer.MIN_VALUE) entry.exactAge = 0;
        }
        entry.babyObserved = baby;
    }

    /**
     * 一个 entity event 18 到了。
     *
     * <p><b>这里绝对不能立刻下结论。</b>18 同时来自 {@code setInLove} 和
     * {@code finalizeSpawnChildFromBreeding}，包里不区分；靠「我们是不是以为它在求爱」去猜，
     * 是在猜自己的账本而不是观察游戏，围栏里会稳定地认错第二只父母。
     * 一律挂起，交给 {@link BreedingAttribution} 用幼驹来定论。
     */
    public void onLoveEvent(AbstractHorse horse) {
        UUID uuid = horse.getUUID();
        // 爱心回答了任何还在等着的喂食：setInLove 只有在服务器接受了食物后才可达。
        feedProbe.onLoveSeen(uuid);
        // 注意 pendingFeeds 故意不在这里清：Session 用 isFeedPending 判断"这一喂还在等回执"。
        // 若在爱心到达时就清掉，而 BreedingAttribution 的 6 tick 判定窗口还没出结论
        // （loveLeft 尚未写入），Session 会读成"喂了却没回音"而提前放弃这一对。
        // 让它自然等到 FEED_ANSWER_MS 超时即可，期间 feedLocks 也保证不会被重复喂。
        maybeCooldown.remove(uuid);
        // 立刻采集朝向，稍后可能要用它命名伴侣 —— 见 MateBearing。
        eventHeadYaw.put(uuid, horse.getYHeadRot());
        attribution.onLoveEvent(uuid, horse.position(), isInLove(uuid));
    }

    private void tickPending(List<AbstractHorse> horses, int delta) {
        tickLocks();

        long now = now();
        Iterator<Map.Entry<UUID, Long>> feeds = pendingFeeds.entrySet().iterator();
        while (feeds.hasNext()) {
            Map.Entry<UUID, Long> pending = feeds.next();
            if (pending.getValue() > now) continue;
            feeds.remove();
        }

        // 先让刚出现的幼驹认领挂起的事件，再结算窗口耗尽的那些。顺序不能换：
        // 下面播种新幼驹的循环跑完之后，一头新生小马和一头一直站在围栏里的小马就无法区分了。
        if (attribution.hasPending()) {
            for (AbstractHorse foal : horses) {
                if (!foal.isBaby()) continue;
                Long until = newbornUntil.get(foal.getUUID());
                if (until == null || until <= now) continue;
                UUID parent = attribution.claimBirth(foal.position());
                if (parent != null) applyBreeding(parent, horses);
            }
        }
        for (Map.Entry<UUID, BreedingAttribution.Verdict> resolved : attribution.tick(delta).entrySet()) {
            UUID uuid = resolved.getKey();
            if (resolved.getValue() == BreedingAttribution.Verdict.BRED) {
                applyBreeding(uuid, horses);
                continue;
            }
            // 走到这里说明没有任何证据指向繁殖，才允许把冷却清掉：
            // vanilla 不可能在真实冷却还在跑的时候把它放进求爱状态，
            // 所以一次货真价实的 setInLove 证明我们之前的冷却判断是错的。
            Entry entry = getEntry(uuid);
            entry.loveLeft = LOVE_TICKS;
            entry.cooldownLeft = 0;
            entry.cooldownDoubt = 0;
            maybeCooldown.remove(uuid);
            eventHeadYaw.remove(uuid);
        }

        // 没人回答的喂食 = 服务器拒绝了。见 FeedProbe 顶部那两条纠正：
        // 不消耗金萝卜，且沉默也可能是「它本来就在求爱」—— 所以这里还要再排除一次。
        for (UUID refused : feedProbe.tick(delta)) {
            Entry entry = entries.get(refused);
            if (entry == null || entry.trustUntil > now) continue;
            if (entry.loveLeft > 0) {
                Trace.log("feed unanswered for {} but it is in love; not a cooldown", refused);
                continue;
            }
            // 有怀疑就用怀疑里记下的数（那是繁殖发生时的真实读数），没有就只能取满冷却 ——
            // 高估只是多等一会儿，低估会导致反复被拒。
            Integer doubted = maybeCooldown.remove(refused);
            int remaining = doubted != null ? doubted : BREED_COOLDOWN_TICKS;
            entry.cooldownLeft = Math.max(entry.cooldownLeft, remaining);
            entry.cooldownDoubt = 0;
            Trace.log("feed refused by {}; cooldown now {} (from {})",
                refused, entry.cooldownLeft, doubted != null ? "doubt" : "full cap");
        }
    }

    /**
     * 给一匹已确定繁殖了的马记上冷却，并在能确定的前提下把伴侣一起记上 ——
     * 先靠「只有唯一候选」的排除法，再靠繁殖目标当时让它头指向的那一个（{@link MateBearing}）。
     * 两者都定不下来时，所有候选背上「怀疑」而不是被随便挑一个。
     */
    private void applyBreeding(UUID parentUuid, List<AbstractHorse> horses) {
        confirmBreeding(parentUuid);
        Float headYaw = eventHeadYaw.remove(parentUuid);

        AbstractHorse parent = null;
        for (AbstractHorse horse : horses) {
            if (horse.getUUID().equals(parentUuid)) {
                parent = horse;
                break;
            }
        }
        // 事件与定论之间这一两 tick 里它没了（被宰 / 出检测范围）。
        // 广播者自己的冷却上面已经记了，只是没有可搜索的起点。
        if (parent == null) return;

        List<AbstractHorse> candidates = inLoveMates(parent);
        if (candidates.isEmpty()) return;

        AbstractHorse named = null;
        if (candidates.size() == 1) {
            named = candidates.get(0);
        } else if (headYaw != null) {
            double[] bearings = new double[candidates.size()];
            for (int i = 0; i < candidates.size(); i++) {
                AbstractHorse candidate = candidates.get(i);
                bearings[i] = MateBearing.bearingDegrees(
                    candidate.getX() - parent.getX(), candidate.getZ() - parent.getZ());
            }
            int index = MateBearing.name(headYaw, bearings);
            if (index >= 0) named = candidates.get(index);
        }

        if (named == null) {
            // 这里面有一匹配了种，但游戏不肯说是谁。只记怀疑，不动 loveLeft：
            // 没配上的那些仍在求爱中（只有 600 tick 超时或真的配上才会退出），
            // 清掉它们的 loveLeft 会让我们去喂它们、被拒、然后误判成冷却。
            for (AbstractHorse candidate : candidates) {
                maybeCooldown.put(candidate.getUUID(), BREED_COOLDOWN_TICKS);
            }
            Trace.log("bred {} with {} candidates, none nameable; doubting all", parentUuid, candidates.size());
            return;
        }
        Trace.log("bred {} with mate {}", parentUuid, named.getUUID());
        confirmBreeding(named.getUUID());
        maybeCooldown.remove(named.getUUID());
    }

    private List<AbstractHorse> inLoveMates(AbstractHorse broadcaster) {
        List<AbstractHorse> mates = new ArrayList<>();
        for (AbstractHorse other : broadcaster.level().getEntitiesOfClass(AbstractHorse.class,
                broadcaster.getBoundingBox().inflate(MATE_BREED_DISTANCE),
                horse -> horse != broadcaster && !horse.isBaby() && isInLove(horse.getUUID())
                        && horse.distanceToSqr(broadcaster) <= MATE_BREED_DISTANCE * MATE_BREED_DISTANCE)) {
            mates.add(other);
        }
        return mates;
    }

    public boolean isInLove(UUID uuid) {
        Entry entry = entries.get(uuid);
        return entry != null && entry.loveLeft > 0;
    }

    public boolean isCoolingDown(UUID uuid) {
        Entry entry = entries.get(uuid);
        return entry != null && (entry.cooldownLeft > 0 || entry.cooldownDoubt > 0);
    }

    public int loveLeft(UUID uuid) {
        Entry entry = entries.get(uuid);
        return entry == null ? 0 : entry.loveLeft;
    }

    public boolean isFeedPending(UUID uuid) {
        return pendingFeeds.containsKey(uuid);
    }

    public boolean isFeedLocked(UUID uuid) {
        Long deadline = feedLocks.get(uuid);
        return deadline != null && deadline > now();
    }

    public void onBred(UUID a, UUID b) {
        for (UUID uuid : new UUID[]{a, b}) {
            confirmBreeding(uuid);
        }
    }

    private void confirmBreeding(UUID uuid) {
        Entry entry = getEntry(uuid);
        entry.loveLeft = 0;
        entry.cooldownDoubt = 0;
        entry.cooldownLeft = BREED_COOLDOWN_TICKS;
        maybeCooldown.remove(uuid);
        eventHeadYaw.remove(uuid);
        feedLocks.remove(uuid);
        pendingFeeds.remove(uuid);
    }

    /**
     * 一次配对尝试作废。这里<b>只</b>能清掉"这次尝试"自己写下的东西（锁 / 待回执）。
     *
     * <p><b>绝不能动 {@code loveLeft}。</b>求爱状态只有两条退出路径 —— 600 tick 超时，或者真的配上
     * （{@code resetLove}）—— 服务端不同步 {@code inLove}，所以我们的 {@code loveLeft} 是唯一知道
     * "它还在求爱中"的东西。把它清零会拆掉 {@code canBreed} 里 {@code !isInLove} 这道护栏：
     * 我们会去喂一匹其实在求爱中的马，服务器因为 {@code !isInLove()} 不成立而拒绝，
     * 探针超时后又因为守卫已失效而把它误判成 6000 tick 冷却 —— 白白锁死一匹本来可用的马。
     */
    public void onPairFailed(UUID a, UUID b) {
        for (UUID uuid : new UUID[]{a, b}) {
            feedLocks.remove(uuid);
            pendingFeeds.remove(uuid);
        }
    }

    public int estimatedTemper(AbstractHorse horse) {
        Entry entry = entries.get(horse.getUUID());
        return entry == null ? 0 : entry.estimatedTemper;
    }

    public int remainingBabyTicks(AbstractHorse horse) {
        if (!horse.isBaby()) return 0;
        Entry entry = entries.get(horse.getUUID());
        if (entry == null) return -BABY_START_AGE;
        if (entry.exactAge != Integer.MIN_VALUE) return Math.max(0, -entry.exactAge);
        return entry.babyTicksLeft;
    }

    public Snapshot describe(AbstractHorse horse) {
        Entry entry = entries.get(horse.getUUID());
        boolean tracked = entry != null && entry.trustUntil > now();

        if (horse.isBaby()) {
            return new Snapshot(State.BABY, null, remainingBabyTicks(horse), !tracked);
        }
        Blocked blocked = blockedReason(horse);
        if (blocked != null) return new Snapshot(State.BLOCKED, blocked, 0, false);
        if (entry == null) return new Snapshot(State.READY, null, 0, !tracked);
        if (entry.loveLeft > 0) return new Snapshot(State.IN_LOVE, null, entry.loveLeft, false);
        if (entry.cooldownLeft > 0) {
            return new Snapshot(State.COOLDOWN, null, entry.cooldownLeft, maybeCooldown.containsKey(horse.getUUID()));
        }
        if (entry.cooldownDoubt > 0) return new Snapshot(State.COOLDOWN, null, entry.cooldownDoubt, true);
        return new Snapshot(State.READY, null, 0, !tracked);
    }

    private Blocked blockedReason(AbstractHorse horse) {
        if (!horse.isTamed()) return Blocked.UNTAMED;
        if (horse.getHealth() < horse.getMaxHealth()) return Blocked.HURT;
        if (horse.isVehicle() || horse.isPassenger()) return Blocked.MOUNTED;
        return null;
    }

    public void onOurFeed(AbstractHorse horse, boolean breeding, int temperGain, int growthTicks) {
        Entry entry = getEntry(horse.getUUID());
        ownFeeds.put(horse.getUUID(), now() + OWN_FEED_MS);
        entry.estimatedTemper = HorseStateTimers.addCapped(entry.estimatedTemper, temperGain, TEMPER_MAX);
        if (horse.isBaby() && !horse.isAgeLocked()) applyGrowth(entry, growthTicks);
        if (breeding) {
            UUID uuid = horse.getUUID();
            long now = now();
            pendingFeeds.put(uuid, now + FEED_ANSWER_MS);
            feedLocks.put(uuid, now + FEED_LOCK_MS);
            // 递出食物的同时开启探针：爱心会来回答它，不来就说明服务器拒绝了。
            // 必须在喂食这一刻开，不能等 pendingFeeds 超时 —— 那时爱心早就来过了，
            // 探针会误判成「被拒」。
            feedProbe.onFeedAttempt(uuid);
        }
    }

    public void markNewborn(UUID uuid) {
        newbornUntil.put(uuid, now() + NEWBORN_SEEN_MS);
    }

    public boolean isOwnFeed(UUID uuid) {
        Long until = ownFeeds.get(uuid);
        return until != null && until > now();
    }

    public void finishOurFeed(UUID uuid) {
        ownFeeds.remove(uuid);
    }

    public void onPlayerFeed(AbstractHorse horse, ItemStack stack) {
        boolean golden = stack.is(Items.GOLDEN_CARROT);
        boolean carrot = stack.is(Items.CARROT);
        if (!golden && !carrot) return;
        if (!foodWouldTakeEffect(horse, golden)) return;

        Entry entry = getEntry(horse.getUUID());
        entry.estimatedTemper = HorseStateTimers.addCapped(entry.estimatedTemper,
                golden ? GOLDEN_CARROT_TEMPER : CARROT_TEMPER, TEMPER_MAX);
        if (horse.isBaby() && !horse.isAgeLocked()) applyGrowth(entry, CARROT_AGE_TICKS);
        if (golden && horse.isTamed() && !horse.isBaby() && !isInLove(horse.getUUID())) {
            pendingFeeds.put(horse.getUUID(), now() + FEED_ANSWER_MS);
        }
    }

    private boolean foodWouldTakeEffect(AbstractHorse horse, boolean golden) {
        if (horse.getHealth() < horse.getMaxHealth()) return true;
        if (horse.isBaby() && !horse.isAgeLocked()) return true;
        if (golden && horse.isTamed() && !horse.isBaby() && !isInLove(horse.getUUID())) return true;
        return !horse.isTamed();
    }

    public void onBuck(AbstractHorse horse) {
        Entry entry = getEntry(horse.getUUID());
        if (entry.trustUntil > now()) return;
        entry.estimatedTemper = HorseStateTimers.addCapped(entry.estimatedTemper, TEMPER_PER_BUCK, TEMPER_MAX);
    }

    private static void applyGrowth(Entry entry, int ticks) {
        observeLifeStage(entry, true);
        entry.babyTicksLeft = HorseStateTimers.remaining(entry.babyTicksLeft, ticks);
        if (entry.exactAge < 0) entry.exactAge = HorseStateTimers.advanceAge(entry.exactAge, ticks);
    }

    public static Path savePath(String worldId) {
        return FabricLoader.getInstance().getConfigDir().resolve("horsebreeder").resolve(worldId + ".json");
    }

    public void save(Path path) {
        Map<UUID, HorseStateStore.SavedHorse> data = new HashMap<>();
        for (Map.Entry<UUID, Entry> row : entries.entrySet()) {
            Entry entry = row.getValue();
            if (entry.unseenTicks > FORGET_AFTER_UNSEEN_TICKS) continue;
            int baby = entry.babyObserved ? entry.babyTicksLeft : 0;
            data.put(row.getKey(), new HorseStateStore.SavedHorse(entry.cooldownLeft,
                    entry.cooldownDoubt, baby, entry.estimatedTemper, entry.loveLeft));
        }
        // 状态每 30s 落盘一次，但绝大多数时候什么都没变：内容相同就跳过两次文件写入。
        if (data.equals(lastWritten)) return;
        try {
            HorseStateStore.write(path, data);
            lastWritten = data;
        } catch (IOException e) {
            HorseBreeder.LOGGER.warn("[horsebreeder] failed to save horse state", e);
        }
    }

    /**
     * 载入存档状态。读不到 / 读不懂都由 {@link HorseStateStore#read} 内部处理
     * （返回空并删掉坏文件），这里只负责把结果搬进内存。
     */
    public void load(Path path) {
        Map<UUID, HorseStateStore.SavedHorse> saved = HorseStateStore.read(path);
        clear();
        saved.forEach((uuid, value) -> {
            Entry entry = new Entry();
            entry.cooldownLeft = value.cooldown();
            entry.cooldownDoubt = value.doubt();
            entry.babyTicksLeft = value.baby();
            entry.babyObserved = value.baby() > 0;
            entry.exactAge = entry.babyObserved ? -value.baby() : Integer.MIN_VALUE;
            entry.estimatedTemper = value.temper();
            entry.loveLeft = value.love();
            entries.put(uuid, entry);
        });
    }

    private Entry getEntry(UUID uuid) {
        return entries.computeIfAbsent(uuid, ignored -> {
            Entry entry = new Entry();
            entry.babyTicksLeft = -BABY_START_AGE;
            return entry;
        });
    }

}
