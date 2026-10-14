package com.horsebreeder.client.logic;

import com.horsebreeder.client.compat.GameCompat;

/**
 * AGENT NOTES —— 自动养马的“决策层”。每个客户端 tick 由
 * {@code HorseBreederClient} 调用一次 {@link #onTick()}。
 *
 * <p><b>职责边界</b>：本类只做决策与编排，不允许直接发包、不允许直接读写 Json。
 * · 所有会影响服务器状态的动作必须走 {@link PlayerActions}（移动 / 交互 / 攻击 / 丢物品 / 切手持）。
 * · 所有“马匹认知”（是否冷却中、是否在恋爱、还差多少 tick 长大）必须走 {@link HorseStates}。
 * · 单次“配对过程”的不变式封装在 {@link BreedingManager} + {@link Session} 里。
 *
 * <p><b>两个闸 = 两个层次，别混</b>：
 * <ul>
 *   <li><b>动作层</b>（{@code config.enabled}，B 键）：只有它开着才会操作玩家 —— 走位、喂食、
 *       上马、挥砍、丢皮革。关掉时玩家输入必须被完全释放。</li>
 *   <li><b>观察层</b>（{@code config.hudEnabled || config.enabled}）：只要任一开着，
 *       才会去扫描实体、推算马匹状态、算达标进度、更新发光标记。</li>
 *   <li><b>两个都关</b>：{@code tick()} 在扫描之前就 return，整条流水线不执行，
 *       mod 对游戏零影响（不发任何包、不做实体查询、不写文件）。
 *       刚关掉的那一 tick 会做一次清理并重置 {@link WorldClock} 基线
 *       —— 不重置的话，下次开启时 gameTime 的巨跳会被当成一大段 tick，把所有冷却一次性推掉。</li>
 * </ul>
 * 注意观察层不能只挂在 {@code hudEnabled} 上：养马开着而 HUD 关着时，自动化仍然必须看得见马群。
 *
 * <p><b>onTick 的固定顺序 = 行为的一部分，禁止随意重排</b>：
 * <ol>
 *   <li>两闸检查（见上）；</li>
 *   <li>{@link WorldClock#advance} → delta（本 tick 跨过的游戏 tick 数），所有倒计时都靠它推进；</li>
 *   <li>{@code collect()} → {@link HorsePopulation}。只收集 detectionRange 内的
 *       {@code net.minecraft...equine.Horse}（不含驴/骡/僵尸马），因为只有 Horse 才有 Variant/Markings；</li>
 *   <li>{@code states.tick(horses, delta)} —— 更新每匹马的 cooldown / love / baby / temper 估计；</li>
 *   <li>{@code breeding.observe(...)} —— 判断是否出现了本次配对的马驹；</li>
 *   <li>{@code maybeSave()} —— 30s 落盘一次；</li>
 *   <li>{@code updateGoal(...)} —— 计算达标进度与“受保护名单”；</li>
 *   <li>此后才是动作层：Restock &gt; 有 GUI &gt; 骑乘中 &gt; 丢皮革 &gt; Session &gt;
 *       淘汰 &gt; 驯服 &gt; 催熟 &gt; 治疗 &gt; 配种 &gt; Idle。</li>
 * </ol>
 * 注意动作层之前 {@code config.enabled == false} 会提前 return：此时上面各步照常执行，
 * 这就是“关闭自动化但仍继续观察/累计状态”的模式，改动时不要把观察也一并关掉。
 *
 * <p><b>act* 方法的返回契约（最容易写错的地方）</b>：
 * <ul>
 *   <li>{@code true} = 本 tick 已被占用，立即 return，不得继续尝试后续动作；</li>
 *   <li>{@code false} = 本动作无事可做，交给下一个 act*。</li>
 * </ul>
 * 它们的“输出”不是返回值而是一组字段：{@code stage} / {@code detail} /
 * {@code pauseReason | warning | idleReason}（三者语义互斥，由 {@link com.horsebreeder.client.hud.HudOverlay} 消费）。
 *
 * <p><b>非显然的不变量</b>：
 * <ul>
 *   <li>{@code stage} 不会被自动复位：新增分支必须显式赋值，否则 HUD 会残留上一 tick 的阶段。</li>
 *   <li>{@code candidatePair / cullTarget / currentTarget} 每 tick 重算；
 *       {@code cullCandidateId} 是“粘住淘汰目标”的机制（见 Selection.cullTarget），
 *       否则马群抖动时会在两只同分个体之间反复横跳、永远砍不下去。</li>
 *   <li>{@code detail / pauseReason / warning / idleReason} 在每 tick 开头被清空，
 *       所以任何想持续展示给用户的话，必须在当帧重新写入。</li>
 * </ul>
 */
import com.horsebreeder.client.config.HorseBreederConfig;
import com.horsebreeder.client.util.InventoryHelper;
import com.horsebreeder.client.util.Lang;
import com.horsebreeder.client.util.Trace;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public final class BreederController {
    public enum Stage { IDLE, TAMING, BREEDING, GROWING, HEALING, CULLING, WALKING, EATING, PAUSED }

    private static final int HARD_MIN_ADULTS = 2;
    private static final long RIDE_TIMEOUT_MS = 30_000;
    private static final long RIDE_RETRY_MS = 60_000;
    private static final long SAVE_INTERVAL_MS = 30_000;
    /** 一次性事件提示（配对超时等）的存活时间：比一帧长得多，又短到不会盖住后续真正的故障。 */
    private static final long NOTICE_MS = 5_000;
    private static final Selection.Wanted WANTED_EMPTY = new Selection.Wanted(List.of());

    private static final long RESTOCK_RETRY_MS = 60_000;

    private final HorseDisplay display = new HorseDisplay();
    private Selection.Pair candidatePair;
    private final Minecraft mc = Minecraft.getInstance();
    private final HorseStates states = new HorseStates();
    private final BreedingManager breeding = new BreedingManager(states);
    private final WorldClock clock = new WorldClock();
    private final PlayerActions actions = new PlayerActions(mc);
    private Counts counts = new Counts(0, 0, 0, 0, 0);

    public record Counts(int ready, int inLove, int cooling, int untamed, int mounted) {
    }

    private List<Goal.Line> goal = List.of();
    private Goal.Evaluation evaluation = new Goal.Evaluation(List.of(), Set.of());
    private Set<String> openCombos = Set.of();
    private Selection.Wanted wanted = WANTED_EMPTY;
    private Set<String> wantedSource = Set.of();
    private Set<String> wantedOpen = Set.of();
    private AbstractHorse cullTarget;
    /**
     * 淘汰保护结论。每 tick 置 null，按需惰性计算一次（见 {@link #protection}），
     * 这样“没超容”的普通 tick 不会白白跑三次人口遍历。
     */
    private Goal.Protection protectionCache;
    private Stage stage = Stage.IDLE;
    private String detail = "";
    private String pauseReason = "";
    private String warning = "";
    private String idleReason = "";
    private int adults;
    private int babies;
    private UUID cullCandidateId;
    private AbstractHorse ridingHorse;
    private UUID tamingRide;
    private long rideStartedMs = -1;
    private UUID dismountedByUs;
    private final Map<UUID, Long> rideBlockedUntil = new HashMap<>();
    private Restock restock;
    private long restockRetryGoldenAt;
    private long restockRetryCarrotAt;
    /**
     * 一条带存活时间的提示。
     *
     * <p><b>为什么需要它</b>：HUD 的 {@code pauseReason} / {@code warning} 都是<b>单槽、
     * 最后写入者获胜</b>，而动作链上靠后的 act* 会把靠前的提示盖掉。凡是“来一下就没了”
     * 的事件（补货失败、配对超时）都必须自己记住，并在存活期内压过常规的缺货提示 ——
     * 否则玩家从头到尾只看到“缺少金萝卜”，既不知道容器那边出了什么事，
     * 也不知道刚刚那一对为什么被放弃了。
     */
    private static final class Notice {
        String text;
        long until;

        void set(String text, long now, long millis) {
            this.text = text;
            this.until = now + millis;
        }

        /** 自己还新鲜就返回自己，否则退回调用方给的常规提示。 */
        String or(String fallback, long now) {
            return text != null && now < until ? text : fallback;
        }

        boolean fresh(long now) {
            return text != null && now < until;
        }
    }
    /** 胡萝卜 / 金萝卜各自的“最近一次补货失败原因”，存活期 = 补货冷却 60 秒。 */
    private final Notice carrotNotice = new Notice();
    private final Notice goldenNotice = new Notice();
    /** 一次性事件提示（如“配对超时”），存活期见 {@link #NOTICE_MS}。 */
    private final Notice eventNotice = new Notice();
    private String saveWorldId;
    private long lastSaveMillis;
    private AbstractHorse currentTarget;
    /** 观察层是否在运行（HUD 或 养马 至少一个开着）。仅用于“刚关掉时清理一次”。 */
    private boolean observingActive;
    /** 动作层是否在运行（养马开着）。 */
    private boolean automationActive;
    /**
     * 本 tick 是否超容。超容时禁止发起新配对 —— 否则当所有马都被保护
     * （例如全部命名）时淘汰砍不动，配种却会继续，种群会无上限膨胀。
     */
    private boolean overCap;
    /**
     * 每完成一次 tick 就自增。HUD 用它判断缓存是否过期：
     * 渲染是每帧的、而数据是每 tick 的，文本必须在这里缓存而不是每帧重建。
     */
    private int hudVersion;

    public int hudVersion() {
        return hudVersion;
    }

    public int glowColor(Entity entity) {
        return display.glowColor(entity);
    }

    public Stage stage() {
        return stage;
    }

    public String detail() {
        return detail;
    }

    public String pauseReason() {
        return pauseReason;
    }

    public String warning() {
        return warning;
    }

    public String idleReason() {
        return idleReason;
    }

    public int adults() {
        return adults;
    }

    public int babies() {
        return babies;
    }

    public Counts counts() {
        return counts;
    }

    public int currentTick() {
        return clock.elapsedTicks();
    }

    public String targetLabel() {
        return currentTarget == null || !currentTarget.isAlive() ? "" : HorseDisplay.label(currentTarget);
    }

    public String worstLabel() {
        return display.worstLabel();
    }

    public String bestLabel() {
        return display.bestLabel();
    }

    public HorseStates states() {
        return states;
    }

    private String worldId() {
        if (mc.getCurrentServer() != null) {
            return WorldIdentity.key("mp", mc.getCurrentServer().ip);
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server != null) {
            return WorldIdentity.key("sp", server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString());
        }
        return null;
    }

    private void maybeSave() {
        if (saveWorldId == null) return;
        long now = now();
        if (now - lastSaveMillis < SAVE_INTERVAL_MS) return;
        lastSaveMillis = now;
        states.save(HorseStates.savePath(saveWorldId));
    }

    public void onWorldLeave() {
        if (saveWorldId != null) states.save(HorseStates.savePath(saveWorldId));
        saveWorldId = null;
        lastSaveMillis = 0;
        restart();
        states.clear();
        clock.reset();
        clearSummary();
    }

    public void onWorldJoin() {
        restart();
        states.clear();
        clock.reset();
        clearSummary();
        saveWorldId = worldId();
        if (saveWorldId != null) states.load(HorseStates.savePath(saveWorldId));
        lastSaveMillis = now();
    }

    private void clearSummary() {
        counts = new Counts(0, 0, 0, 0, 0);
        adults = babies = 0;
        goal = List.of();
        evaluation = new Goal.Evaluation(List.of(), Set.of());
        openCombos = Set.of();
        wanted = WANTED_EMPTY;
        wantedSource = wantedOpen = Set.of();
        protectionCache = null;
        display.clearLabels();
    }

    /**
     * 松开所有被本 mod 按住的玩家输入（移动 / 使用）。
     * 供“暂停、掉线、关闭自动化”时不走 {@link #onTick()} 的场景调用，避免按键粘连。
     */
    public void releaseInput() {
        if (mc.player != null) actions.stop(mc.player);
    }

    public void reset() {
        restart();
    }

    private void restart() {
        automationActive = false;
        actions.cancelPendingAttack();
        if (mc.player != null) actions.stop(mc.player);
        display.clearMarkers();
        candidatePair = null;
        breeding.reset();
        cullCandidateId = null;
        cullTarget = null;
        ridingHorse = null;
        tamingRide = null;
        rideStartedMs = -1;
        dismountedByUs = null;
        rideBlockedUntil.clear();
        restock = null;
        currentTarget = null;
        restockRetryGoldenAt = restockRetryCarrotAt = 0;
        carrotNotice.text = goldenNotice.text = eventNotice.text = null;
        carrotNotice.until = goldenNotice.until = eventNotice.until = 0;
        stage = Stage.IDLE;
        detail = pauseReason = warning = idleReason = "";
    }

    public void onTick() {
        tick();
        hudVersion++;
    }

    private void tick() {
        HorseBreederConfig config = HorseBreederConfig.get();

        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) {
            actions.stopEating(player);
            display.hideMarkers();
            return;
        }

        // 观察层总闸：只有“显示 HUD”或“养马”至少一个开着，才需要知道马群状态。
        // 注意不能只挂在 hudEnabled 上 —— 养马开着而 HUD 关着时，自动化仍然必须看得见马群，
        // 否则它就是瞎的。反过来两个都关时，下面整段（实体扫描 / 状态推算 / 落盘 / 标记）
        // 一律不执行，mod 对游戏零影响。
        if (!config.hudEnabled && !config.enabled) {
            if (observingActive) {
                observingActive = false;
                // 只在“刚关掉”的这一 tick 做一次清理，之后每 tick 都是空转。
                restart();
                display.clearLabels();
                // 时钟基线必须重置：否则下次开启时 gameTime 的巨跳会被当成一大段 tick 推进，
                // 把所有冷却一次性推掉。
                clock.reset();
                stage = Stage.IDLE;
                detail = "";
            }
            return;
        }
        observingActive = true;

        int delta = clock.advance(level.getGameTime(), now());
        // stage 和 detail 也在这里复位。以前它们只靠 idleDetail() 兜底，
        // 但 act* 一旦 return true 占住 tick 就走不到那里，会残留上一 tick 的阶段名和说明。
        stage = Stage.IDLE;
        detail = pauseReason = warning = idleReason = "";
        currentTarget = null;
        overCap = false;
        protectionCache = null;
        HorsePopulation population = new HorsePopulation(collect(player, level, config));
        List<AbstractHorse> horses = population.all;
        List<AbstractHorse> grown = population.adults;
        List<AbstractHorse> tamed = population.tamed;
        adults = grown.size();
        babies = population.foals.size();
        states.tick(horses, delta);
        counts = countStates(horses);
        breeding.observe(population, now());
        maybeSave();
        display.updateRankLabels(grown);
        updateGoal(horses, config);

        // 发光标记只认 HUD 开关：关掉养马但留着 HUD 时，仍然要看得到马群的状态分布。
        // 放在动作层总闸之前，这样"只观察"模式下也能刷新。
        List<AbstractHorse> foals = population.foals;
        refreshMarkers(horses, grown, foals, config);

        if (!config.enabled) {
            if (automationActive) restart();
            actions.stop(player);
            stage = Stage.IDLE;
            detail = Lang.text("horsebreeder.status.disabled");
            return;
        }
        automationActive = true;
        if (restock != null) {
            if (advanceRestock(player, config)) return;
        }
        if (GameCompat.currentScreen(mc) != null) {
            actions.stop(player);
            stage = Stage.PAUSED;
            pauseReason = Lang.text("horsebreeder.status.closeScreen");
            return;
        }

        if (player.getVehicle() instanceof AbstractHorse vehicle) {
            actions.stop(player);
            ridingHorse = vehicle;
            stage = Stage.TAMING;
            if (rideStartedMs < 0) rideStartedMs = now();

            boolean rideTamed = vehicle.isTamed();
            boolean overdue = now() - rideStartedMs > RIDE_TIMEOUT_MS;
            if (rideTamed || overdue) {
                actions.dismount(player);
                dismountedByUs = vehicle.getUUID();
                if (rideTamed) {
                    detail = Lang.text(vehicle.getUUID().equals(tamingRide)
                        ? "horsebreeder.status.tamedDismount"
                        : "horsebreeder.status.playerRiding");
                } else {
                    rideBlockedUntil.put(vehicle.getUUID(), now() + RIDE_RETRY_MS);
                    warning = Lang.text("horsebreeder.status.rideStuck");
                    // 探针：卡在马背上通常是围栏太幼马跑不动、或它其实已经驯服但同步滞后。
                    Trace.log("ride timed out on {} (tamed={}); blocked for 60s",
                        vehicle.getUUID(), rideTamed);
                }
                return;
            }
            detail = Lang.text("horsebreeder.status.waitingBuck");
            return;
        }
        if (ridingHorse != null) {
            if (!ridingHorse.getUUID().equals(dismountedByUs)
                && ridingHorse.isAlive() && !ridingHorse.isTamed()) {
                states.onBuck(ridingHorse);
                // 探针：每被甩一次 temper +5。日志里的数字可以用来验证驯服进度是否可信。
                Trace.log("buck from {}; temper now {}",
                    ridingHorse.getUUID(), states.estimatedTemper(ridingHorse));
            }
            ridingHorse = null;
            tamingRide = null;
            rideStartedMs = -1;
            dismountedByUs = null;
        }

        maybeDropLeather(player, config);

        List<AbstractHorse> untamed = population.untamed;
        if (!breeding.active()) candidatePair = null;

        if (Goal.reached(goal)) {
            breeding.abandon();
            actions.stop(player);
            stage = Stage.IDLE;
            detail = Lang.text("horsebreeder.status.goalDone", Goal.text(goal));
            return;
        }

        if (actEat(player, config)) return;

        if (advanceSession(player, population, config)) return;
        if (actCull(player, grown, foals, config)) return;
        if (actTame(player, untamed, config)) return;
        if (config.shortenGrowth && actGrow(player, foals, config)) return;
        if (actHeal(player, tamed, config)) return;
        if (actBreed(player, tamed, config)) return;

        actions.stop(player);
        detail = idleDetail(config);
    }

    /**
     * 什么都没干时该显示什么。
     *
     * <p>{@code idleReason} 不再从这里走 —— 它已经有独立的 HUD 行了。
     * 挤在这里会被 warning 顶掉（“已达上限”“无可用配对”就是这样消失的）。
     */
    private String idleDetail(HorseBreederConfig config) {
        if (breeding.active()) {
            stage = Stage.BREEDING;
            return breeding.waitingForFoal()
                ? Lang.text("horsebreeder.status.waitingFoal", seconds(breeding.loveLeft()))
                : Lang.text("horsebreeder.status.breeding");
        }
        stage = Stage.IDLE;
        if (eventNotice.fresh(now())) return eventNotice.text;
        if (!warning.isEmpty()) return warning;
        return Lang.text("horsebreeder.stage.idle");
    }

    /**
     * 重算淘汰候选并重绘发光标记。
     *
     * <p>标记的唯一开关是 {@code config.hudEnabled} —— 不是 {@code enabled}。
     * 关闭养马但开着 HUD 时仍要能看到马群状态（这正是"只观察"模式的意义），
     * 而 HUD 关掉时就完全不画，和养马开不开无关。
     */
    private void refreshMarkers(List<AbstractHorse> horses, List<AbstractHorse> grown,
                                List<AbstractHorse> foals, HorseBreederConfig config) {
        // 只有超容时才计算淘汰目标：未超容时保持 null，避免发光标记/HUD 误把它当成候选。
        overCap = grown.size() + foals.size() > Goal.populationCap(config);
        if (!overCap) Trace.onChange("cull-stalled", false);
        cullTarget = overCap ? pickCullTarget(horses, grown, foals, config) : null;
        cullCandidateId = cullTarget == null ? null : cullTarget.getUUID();

        if (config.hudEnabled) {
            display.updateMarkers(horses, config, states, breeding.parents(), candidatePair, cullCandidateId);
        } else {
            display.hideMarkers();
        }
    }

    private void updateGoal(List<AbstractHorse> all, HorseBreederConfig config) {
        evaluation = Goal.evaluate(all, config);
        goal = evaluation.progress();
        openCombos = Goal.open(goal);
        // Wanted 是预编译的查表结构，配置没动时就别重建（它每 tick 都会被 O(n²) 配对估价反复读）。
        if (!config.appearanceWhitelist.equals(wantedSource) || !openCombos.equals(wantedOpen)) {
            wantedSource = new LinkedHashSet<>(config.appearanceWhitelist);
            wantedOpen = openCombos;
            wanted = Selection.wanted(config, openCombos);
        }
    }

    /**
     * 本 tick 的淘汰保护结论，惰性计算并缓存到 tick 结束。
     * 三个集合必须一起算 —— 单独使用其中任何一个都会漏掉保护。
     */
    private Goal.Protection protection(List<AbstractHorse> all, HorseBreederConfig config) {
        if (protectionCache == null) {
            protectionCache = Goal.protection(all, config, evaluation.protectedHorses(), openCombos, wanted);
        }
        return protectionCache;
    }

    private Counts countStates(List<AbstractHorse> horses) {
        int ready = 0;
        int inLove = 0;
        int cooling = 0;
        int untamed = 0;
        int mounted = 0;

        for (AbstractHorse horse : horses) {
            HorseStates.Snapshot snapshot = states.describe(horse);
            switch (snapshot.state()) {
                case READY -> ready++;
                case IN_LOVE -> inLove++;
                case COOLDOWN -> cooling++;
                case BLOCKED -> {
                    if (snapshot.blocked() == HorseStates.Blocked.UNTAMED) untamed++;
                    else if (snapshot.blocked() == HorseStates.Blocked.HURT) ready++;
                    else mounted++;
                }
                case BABY -> {
                }
            }
        }
        return new Counts(ready, inLove, cooling, untamed, mounted);
    }

    public void onHorseFed(AbstractHorse horse, ItemStack stack) {
        if (states.isOwnFeed(horse.getUUID())) return;
        states.onPlayerFeed(horse, stack);
    }

    public void onLoveEvent(AbstractHorse horse) {
        if (!breeding.onLoveEvent(horse.getUUID())) states.onLoveEvent(horse);
    }

    private boolean advanceSession(LocalPlayer player, HorsePopulation population, HorseBreederConfig config) {
        BreedingManager.Step step = breeding.advance(population, now());
        return switch (step.action()) {
            case NONE -> false;
            case EXPIRED -> {
                // 这里 yield false 之后动作链还在继续，后面的 act* 会把 warning 盖掉，
                // 所以除了当场写一次，还要存进 eventNotice 让它活过整个 NOTICE_MS。
                eventNotice.set(Lang.text("horsebreeder.status.pairExpired"), now(), NOTICE_MS);
                warning = Lang.text("horsebreeder.status.pairExpired");
                yield false;
            }
            case WAIT_FOAL -> {
                stage = Stage.BREEDING;
                detail = Lang.text("horsebreeder.status.waitingFoal", seconds(breeding.loveLeft()));
                yield false;
            }
            case WAIT_ACK -> {
                actions.stop(player);
                stage = Stage.BREEDING;
                detail = Lang.text("horsebreeder.status.waitingFeedAck");
                yield true;
            }
            case FEED -> {
                feedForLove(player, step.target(), config);
                breeding.recordFeed(step.target());
                yield true;
            }
        };
    }

    private void feedForLove(LocalPlayer player, AbstractHorse horse, HorseBreederConfig config) {
        stage = Stage.BREEDING;
        detail = Lang.text("horsebreeder.status.breeding");
        if (!PlayerActions.hasItem(player, stack -> stack.is(Items.GOLDEN_CARROT))) {
            if (startRestock(config, true)) return;
            actions.stop(player);
            stage = Stage.PAUSED;
            pauseReason = shortageReason(true, Lang.text("horsebreeder.status.needGoldenCarrot"));
            return;
        }
        if (horse.getHealth() < horse.getMaxHealth()) {
            if (!PlayerActions.hasItem(player, stack -> stack.is(Items.CARROT))) {
                if (startRestock(config, false)) return;
                warn(shortageReason(false, Lang.text("horsebreeder.status.hurtNoCarrot")));
                return;
            }
            if (!holdItem(player, stack -> stack.is(Items.CARROT), false)) {
                Trace.log("breed feed skipped: carrot not in hand yet");
                return;
            }
            if (approach(player, horse, config) != PlayerActions.Reach.IN_RANGE) {
                Trace.log("breed feed skipped: healing target not in reach");
                return;
            }
            detail = Lang.text("horsebreeder.status.healing");
            feed(player, horse, HorseStates.CARROT_TEMPER, HorseStates.CARROT_AGE_TICKS, false);
            return;
        }
        if (!holdItem(player, stack -> stack.is(Items.GOLDEN_CARROT), false)) {
            Trace.log("breed feed skipped: golden carrot not in hand yet");
            return;
        }
        if (approach(player, horse, config) != PlayerActions.Reach.IN_RANGE) {
            Trace.log("breed feed skipped: {} not in reach ({} blocks)", horse.getUUID(),
                String.format("%.1f", PlayerActions.distanceTo(player, horse)));
            return;
        }

        stage = Stage.BREEDING;
        detail = Lang.text("horsebreeder.status.breeding");
        feed(player, horse, HorseStates.GOLDEN_CARROT_TEMPER, HorseStates.CARROT_AGE_TICKS, true);
        // 喂完立刻松开金萝卜：手持诱惑物会让整群马围过来，把要配对的两匹挤散。
        // 之后的 WAIT_ACK / WAIT_FOAL 阶段同理，必须在空手状态下等它们自己走到一起。
        actions.releaseTemptingItem(player);
        return;
    }

    private PlayerActions.Reach approach(LocalPlayer player, AbstractHorse target, HorseBreederConfig config) {
        currentTarget = target;
        PlayerActions.Reach reach = actions.approach(player, target, config.reach, config.walkToTarget);
        if (reach == PlayerActions.Reach.UNREACHABLE) {
            stage = Stage.PAUSED;
            pauseReason = Lang.text("horsebreeder.status.outOfReach");
        } else if (reach == PlayerActions.Reach.WALKING) {
            stage = Stage.WALKING;
            detail = Lang.text("horsebreeder.status.walking",
                String.format("%.1f", PlayerActions.distanceTo(player, target)));
            if (actions.isStuck()) warn(Lang.text("horsebreeder.status.maybeStuck"));
        }
        return reach;
    }

    private boolean actBreed(LocalPlayer player, List<AbstractHorse> tamed, HorseBreederConfig config) {
        if (breeding.active()) return false;
        if (overCap) {
            // 已超容：先让 actCull 腾位置，这一 tick 不再主动增加人口。
            idleReason = Lang.text("horsebreeder.status.overCap", Goal.populationCap(config));
            return false;
        }
        if (tamed.size() < 2) {
            idleReason = tamed.isEmpty()
                ? Lang.text("horsebreeder.status.noTamedAdult")
                : Lang.text("horsebreeder.status.onlyOneTamedAdult");
            return false;
        }
        if (!PlayerActions.hasItem(player, stack -> stack.is(Items.GOLDEN_CARROT))) {
            if (startRestock(config, true)) return true;
            actions.stop(player);
            stage = Stage.PAUSED;
            pauseReason = shortageReason(true, Lang.text("horsebreeder.status.needGoldenCarrot"));
            return true;
        }

        List<AbstractHorse> free = filter(tamed, h -> canBreed(h));
        candidatePair = Selection.bestPair(free, config, wanted,
            8.0, breeding.retries(now()), now());
        if (candidatePair == null) {
            actions.stop(player);
            idleReason = free.size() < 2
                ? Lang.text("horsebreeder.status.waitingBreedable")
                : Lang.text("horsebreeder.status.noPair");
            return false;
        }

        breeding.begin(candidatePair, now());
        stage = Stage.BREEDING;
        detail = Lang.text("horsebreeder.status.breeding");
        return false;
    }

    private boolean actEat(LocalPlayer player, HorseBreederConfig config) {
        if (!config.autoEat) return false;
        boolean hungry = player.getFoodData().needsFood();
        if (player.hasEffect(MobEffects.HUNGER)) hungry = false;
        if (!hungry || !PlayerActions.hasItem(player, stack -> stack.is(Items.GOLDEN_CARROT))) {
            actions.stopEating(player);
            // 只在没有配对进行中时才松开金萝卜。若同一 tick 里 feedForLove 正要拿金萝卜去喂，
            // 这里先切走、它再切回来，手就会在"金萝卜 ↔ 空手"之间反复横跳，喂永远发不出去
            // （表现为会话每 5 秒 ABANDON 一次、同一对马无限重试）。
            if (!breeding.active()) actions.releaseTemptingItem(player);
            return false;
        }
        PlayerActions.HandState hand = PlayerActions.ensureHand(mc, player, stack -> stack.is(Items.GOLDEN_CARROT), false);
        if (hand == PlayerActions.HandState.MISSING) {
            actions.stopEating(player);
            if (!breeding.active()) actions.releaseTemptingItem(player);
            return false;
        }
        stage = Stage.EATING;
        if (hand == PlayerActions.HandState.SWITCHING) {
            detail = Lang.text("horsebreeder.status.eatingReach");
            return true;
        }
        actions.eat(player);
        detail = Lang.text("horsebreeder.status.eating");
        return true;
    }

    private boolean actCull(LocalPlayer player, List<AbstractHorse> grown, List<AbstractHorse> foals,
                            HorseBreederConfig config) {
        int population = grown.size() + foals.size();
        if (population <= Goal.populationCap(config) || population <= HARD_MIN_ADULTS) return false;

        AbstractHorse target = cullTarget;
        if (target == null) {
            warn(Lang.text("horsebreeder.status.noCullTarget"));
            // 探针：超容却砍不动（通常是全都命名了 / 全是稀缺携带者）。
            // 此时配种也被 overCap 挡住，mod 会整体停摆，必须在日志里能看到原因。
            if (Trace.onChange("cull-stalled", true)) {
                Trace.log("over cap {} but nothing is cullable; check protectNamed / appearance whitelist",
                    Goal.populationCap(config));
            }
            return false;
        }
        Trace.onChange("cull-stalled", false);
        if (!PlayerActions.hasItem(player, stack -> stack.is(ItemTags.AXES))) {
            actions.stop(player);
            stage = Stage.PAUSED;
            pauseReason = Lang.text("horsebreeder.status.needAxe");
            return true;
        }
        if (approach(player, target, config) != PlayerActions.Reach.IN_RANGE) return stage != Stage.PAUSED;

        stage = Stage.CULLING;
        if (!holdItem(player, stack -> stack.is(ItemTags.AXES), false)) return true;

        if (player.getAttackStrengthScale(0.0F) < 1.0F) {
            detail = Lang.text("horsebreeder.status.charging");
            return true;
        }

        detail = target.isBaby()
            ? Lang.text("horsebreeder.status.cullingFoal")
            : Lang.text("horsebreeder.stage.culling");
        // 暴击是“先跳起来再砍”，attack 返回 false 表示这一 tick 只是在起跳。
        if (!actions.attack(player, target, config.criticals)) {
            detail = Lang.text("horsebreeder.status.critJump");
        }
        display.markActing(target);
        return true;
    }

    private boolean actTame(LocalPlayer player, List<AbstractHorse> untamed, HorseBreederConfig config) {
        rideBlockedUntil.values().removeIf(until -> until <= now());
        AbstractHorse target = pickMostValuable(player,
            filter(untamed, h -> !h.isVehicle() && !h.isPassenger()
                && !rideBlockedUntil.containsKey(h.getUUID())), config);
        if (target == null) return false;

        // 这里判的是“手上有东西且没地方放”，与下面 holdItem(ItemStack::isEmpty) 的失败条件相同。
        // 放在入口提前判一次，是为了让 HUD 明确报出原因；下面那处只负责执行换手，不再重复报错。
        if (!player.getMainHandItem().isEmpty()
            && InventoryHelper.findEmptyMainInventory(player) < 0
            && InventoryHelper.findEmptyHotbar(player) < 0) {
            actions.stop(player);
            stage = Stage.PAUSED;
            pauseReason = Lang.text("horsebreeder.status.inventoryFull");
            return true;
        }
        if (approach(player, target, config) != PlayerActions.Reach.IN_RANGE) return stage != Stage.PAUSED;

        int temper = states.estimatedTemper(target);
        if (config.feedToTame && temper < Math.min(config.tameFeedTarget, HorseStates.TEMPER_MAX)) {
            if (!PlayerActions.hasItem(player, stack -> stack.is(Items.CARROT))) {
                if (startRestock(config, false)) return true;
                warn(Lang.text("horsebreeder.status.tameByRiding"));
            } else {
                // stage 必须等 ensureHand 通过后再写：否则“没胡萝卜”时 HUD 会显示 Taming 却什么都没做。
                if (!holdItem(player, stack -> stack.is(Items.CARROT), false)) return true;
                stage = Stage.TAMING;
                detail = Lang.text("horsebreeder.status.feedingTemper", temper,
                    Math.min(temper + HorseStates.CARROT_TEMPER, HorseStates.TEMPER_MAX));
                feed(player, target, HorseStates.CARROT_TEMPER, 0, false);
                return true;
            }
        }

        if (!holdItem(player, ItemStack::isEmpty, true)) return true;
        stage = Stage.TAMING;
        detail = Lang.text("horsebreeder.status.taming");
        tamingRide = target.getUUID();
        actions.interact(player, target);
        display.markActing(target);
        return true;
    }

    private boolean actGrow(LocalPlayer player, List<AbstractHorse> foals, HorseBreederConfig config) {
        foals = filter(foals, horse -> !horse.isAgeLocked());
        if (foals.isEmpty()) return false;
        if (!PlayerActions.hasItem(player, stack -> stack.is(Items.CARROT))) {
            if (startRestock(config, false)) return true;
            warn(shortageReason(false, Lang.text("horsebreeder.status.needCarrotGrowth")));
            return false;
        }
        AbstractHorse target = pickMostValuable(player, foals, config);
        if (approach(player, target, config) != PlayerActions.Reach.IN_RANGE) return stage != Stage.PAUSED;
        if (!holdItem(player, stack -> stack.is(Items.CARROT), false)) return true;

        stage = Stage.GROWING;
        detail = Lang.text("horsebreeder.status.growing", seconds(states.remainingBabyTicks(target)));
        feed(player, target, HorseStates.CARROT_TEMPER, HorseStates.CARROT_AGE_TICKS, false);
        return true;
    }

    private boolean actHeal(LocalPlayer player, List<AbstractHorse> tamed, HorseBreederConfig config) {
        List<AbstractHorse> hurt = filter(tamed, h -> h.getHealth() < h.getMaxHealth()
            && !h.isVehicle() && !h.isPassenger());
        AbstractHorse target = pickNearest(player, hurt);
        if (target == null) return false;
        if (!PlayerActions.hasItem(player, stack -> stack.is(Items.CARROT))) return false;
        if (approach(player, target, config) != PlayerActions.Reach.IN_RANGE) return stage != Stage.PAUSED;
        if (!holdItem(player, stack -> stack.is(Items.CARROT), false)) return true;

        stage = Stage.HEALING;
        detail = Lang.text("horsebreeder.status.healing");
        feed(player, target, HorseStates.CARROT_TEMPER, 0, false);
        return true;
    }

    public void markContainer(BlockPos pos, boolean golden) {
        HorseBreederConfig config = HorseBreederConfig.get();
        if (golden) config.goldenCarrotContainer = HorseBreederConfig.formatBlockPos(pos);
        else config.carrotContainer = HorseBreederConfig.formatBlockPos(pos);
        config.save();
        if (mc.gui != null) {
            GameCompat.setOverlayMessage(mc, Lang.component("horsebreeder.message.containerMarked",
                Lang.text(golden ? "horsebreeder.food.goldenCarrot" : "horsebreeder.food.carrot"),
                HorseBreederConfig.formatBlockPos(pos)), false);
        }
    }

    private boolean startRestock(HorseBreederConfig config, boolean golden) {
        if (!config.restockFromContainers) return false;
        if (restock != null) return true;
        if (now() < (golden ? restockRetryGoldenAt : restockRetryCarrotAt)) return false;
        restock = Restock.begin(config, golden, now());
        return restock != null;
    }

    /**
     * 缺食物时该报哪句话。
     *
     * <p>补货刚失败过、还处在 60 秒冷却里时报<b>具体原因</b>（容器为空 / 打不开 / 背包已满 /
     * 不可达 …）；冷却过了就退回调用方给的笼统提示。没有这一层的话，具体原因只在失败那一 tick
     * 露过面，之后整整 60 秒显示的全是“缺少金萝卜”。
     */
    private String shortageReason(boolean golden, String fallback) {
        return (golden ? goldenNotice : carrotNotice).or(fallback, now());
    }

    /**
     * 写 warning 的统一入口，先让位给还新鲜的一次性事件提示。
     *
     * <p>动作链上靠后的 act*（驯服 / 催熟）会把靠前事件留下的 warning 盖掉 ——
     * “配对超时，已更换”就是这样永远看不到。走这里的话，事件在存活期内始终优先。
     */
    private void warn(String text) {
        warning = eventNotice.or(text, now());
    }

    private boolean advanceRestock(LocalPlayer player, HorseBreederConfig config) {
        if (!config.restockFromContainers) {
            restock = null;
            return false;
        }
        Restock.Step step = restock.tick(mc, player, actions, now());
        if (step.failure() != null) {
            Notice shortage = restock.golden ? goldenNotice : carrotNotice;
            if (restock.golden) restockRetryGoldenAt = now() + RESTOCK_RETRY_MS;
            else restockRetryCarrotAt = now() + RESTOCK_RETRY_MS;
            // 冷却期内所有 act* 都只会报“缺少金萝卜”，把真正的原因存起来顶替它。
            shortage.set(step.failure(), now(), RESTOCK_RETRY_MS);
            // 探针：补货失败会让整个自动化停摆，必须能在日志里看到具体是哪一步。
            Trace.log("restock {} failed: {}", restock.item(), step.failure());
            actions.stop(player);
            stage = Stage.PAUSED;
            pauseReason = step.failure();
        } else {
            stage = Stage.WALKING;
            detail = step.detail();
        }
        if (!step.running()) restock = null;
        return true;
    }

    private List<AbstractHorse> collect(LocalPlayer player, Level level, HorseBreederConfig config) {
        AABB box = player.getBoundingBox().inflate(config.detectionRange + 2.0);
        return new ArrayList<>(
            level.getEntitiesOfClass(Horse.class, box, horse -> horse.isAlive() && !horse.isRemoved()));
    }

    private AbstractHorse pickNearest(LocalPlayer player, List<AbstractHorse> candidates) {
        AbstractHorse best = null;
        double bestDistance = Double.MAX_VALUE;
        for (AbstractHorse horse : candidates) {
            double distance = horse.getBoundingBox().distanceToSqr(player.getEyePosition());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = horse;
            }
        }
        return best;
    }

    private AbstractHorse pickMostValuable(LocalPlayer player, List<AbstractHorse> candidates,
                                           HorseBreederConfig config) {
        AbstractHorse best = null;
        double bestValue = Double.NEGATIVE_INFINITY;
        double bestDistance = Double.MAX_VALUE;
        for (AbstractHorse horse : candidates) {
            double value = Selection.keepValue(horse, config, wanted);
            double distance = horse.getBoundingBox().distanceToSqr(player.getEyePosition());
            if (value > bestValue || (value == bestValue && distance < bestDistance)) {
                best = horse;
                bestValue = value;
                bestDistance = distance;
            }
        }
        return best;
    }

    private AbstractHorse pickCullTarget(List<AbstractHorse> all, List<AbstractHorse> grown,
                                         List<AbstractHorse> foals, HorseBreederConfig config) {
        List<AbstractHorse> candidates = concat(grown, foals);
        Goal.Protection protection = protection(all, config);
        Set<UUID> busy = breeding.parents();
        return Selection.cullTarget(candidates, config, wanted, protection, busy,
            cullCandidateId, HARD_MIN_ADULTS);
    }

    /**
     * 把某样东西切到主手上。返回 false 只表示<b>这一 tick 还没拿好</b>（正在切热键栏 / 等服务器确认换手），
     * 调用方直接 return 让出这一 tick 即可，下一 tick 再来一次就能拿到 READY。
     *
     * <p><b>为什么没有“缺失”分支</b>：每个调用点上方都有一道 {@code PlayerActions.hasItem} 前置检查，
     * 它负责“没有就去补货 / 报警 / 降级”。而 {@code hasItem} 扫 0..35，与 ensureHand 内部的
     * {@code findHotbar(0..8) + findMainInventory(9..35)} 覆盖范围完全一致 —— 所以前置检查过了之后，
     * 这里只会拿到 READY 或 SWITCHING，拿不到 MISSING。
     *
     * <p>以前这里挂了一份“缺少胡萝卜 / 缺少斧头 / 背包已满”的文案并带 {@code fatal} 开关，
     * 那是四个永远显示不出来的死 key，而且 {@code fatal=true} 会在不可达分支上暂停整个自动化。
     * 现在改成探针：万一将来两处判定漂移，日志里能看到，而不是静默卡死。
     */
    private boolean holdItem(LocalPlayer player, Predicate<ItemStack> matcher, boolean allowEmptyHand) {
        PlayerActions.HandState hand = PlayerActions.ensureHand(mc, player, matcher, allowEmptyHand);
        if (hand == PlayerActions.HandState.MISSING) {
            Trace.log("ensureHand MISSING although hasItem passed; holding {}",
                player.getMainHandItem().getItem());
        }
        return hand == PlayerActions.HandState.READY;
    }

    private void feed(LocalPlayer player, AbstractHorse horse, int temperGain, int ageTicks, boolean breeding) {
        states.onOurFeed(horse, breeding, temperGain, ageTicks);
        try {
            actions.interact(player, horse);
        } finally {
            states.finishOurFeed(horse.getUUID());
        }
        display.markActing(horse);
    }

    private void maybeDropLeather(LocalPlayer player, HorseBreederConfig config) {
        if (!config.dropLeather) return;
        if (!PlayerActions.carryingLeather(player)) return;
        actions.dropLeather(player, config.dropYaw);
    }

    private boolean canBreed(AbstractHorse horse) {
        if (horse.isBaby() || !horse.isTamed()) return false;
        if (horse.isVehicle() || horse.isPassenger()) return false;
        if (horse.getHealth() < horse.getMaxHealth()) return false;
        UUID uuid = horse.getUUID();
        if (states.isFeedPending(uuid) || states.isFeedLocked(uuid)) return false;
        return !states.isInLove(uuid) && !states.isCoolingDown(uuid);
    }

    private static List<AbstractHorse> filter(List<AbstractHorse> source, Predicate<AbstractHorse> predicate) {
        List<AbstractHorse> result = new ArrayList<>();
        for (AbstractHorse horse : source) {
            if (predicate.test(horse)) result.add(horse);
        }
        return result;
    }

    private static List<AbstractHorse> concat(List<AbstractHorse> first, List<AbstractHorse> second) {
        List<AbstractHorse> result = new ArrayList<>(first.size() + second.size());
        result.addAll(first);
        result.addAll(second);
        return result;
    }

    private static long now() {
        return ElapsedTime.millis();
    }

    private String seconds(int ticks) {
        return String.valueOf(clock.seconds(ticks));
    }

}
