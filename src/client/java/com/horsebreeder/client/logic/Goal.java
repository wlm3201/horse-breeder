package com.horsebreeder.client.logic;

/**
 * AGENT NOTES —— “什么时候算达标，以及为了达标要保护谁”。
 *
 * <p>目标 = “每种外观各若干匹达标”（{@code perCombination} × {@code combinations}）。
 * 没有外观白名单时退化成“只要 N 匹达标”，此时 {@link Line#key()} 为 null。
 *
 * <p>三个保护集合的区别（很容易混淆）：
 * <ul>
 *   <li>{@code protectedHorses} —— 已经达标、且被保留在最优 N 匹里的个体，<b>不可淘汰</b>；</li>
 *   <li>{@code soleCarriers} —— 某个目标颜色的<b>唯一/极少数</b>携带者（≤2），丢了就得重新突变出来；</li>
 *   <li>{@code reservedSlots} —— 给每个尚未达成的组合预留的繁育席位，避免还没达标就被自己人挤光。</li>
 * </ul>
 * reservedSlots 只在目标组合“互不相交”（不同颜色 <b>且</b> 不同花纹）时生效 —— 见 {@code disjoint}，
 * 那个方法返回 true 才表示“可以安全分区”，名字读起来像相反意思，别理解错。
 *
 * <p>{@code populationCap} 会被目标抬高到 {@code targetCount * 外观数}，
 * 所以用户把 targetCount 拉到 8、勾 35 种外观时上限会变成 280：这是设计结果，不是 bug。
 */
import com.horsebreeder.client.config.HorseBreederConfig;
import com.horsebreeder.client.util.Lang;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.Markings;
import net.minecraft.world.entity.animal.equine.Variant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class Goal {
    private static final int CARRIER_FLOOR = 2;

    public record Line(String label, String key, int have, int need) {
        public boolean done() {
            return have >= need;
        }

        public String text() {
            return label + " " + Math.min(have, need) + "/" + need;
        }
    }

    public record Evaluation(List<Line> progress, Set<UUID> protectedHorses) {
        public Evaluation {
            progress = List.copyOf(progress);
            protectedHorses = Set.copyOf(protectedHorses);
        }
    }

    /**
     * 一次 tick 内“哪些马不能宰”的统一结论。三个字段互不重叠但必须一起看：
     * <ul>
     *   <li>{@code finished} —— 已达标的（按 quota 保留最优的 N 匹）；</li>
     *   <li>{@code carriers} —— 目标颜色/花纹的稀缺携带者（≤2 个），丢了只能等突变；</li>
     *   <li>{@code reserved} —— 给尚未达成的外观预留的繁育席位。</li>
     * </ul>
     * 集成为单个对象的好处：三者都在同一次人口遍历里得出，调用方不再有机会只用其中一个而忘了另一个。
     */
    public record Protection(Set<UUID> finished, Set<UUID> carriers, Set<UUID> reserved) {
        public static Protection none() {
            return new Protection(Set.of(), Set.of(), Set.of());
        }

        public boolean protects(UUID uuid) {
            return finished.contains(uuid) || carriers.contains(uuid) || reserved.contains(uuid);
        }
    }

    record Finished(UUID id, String combination, double rank) {}
    record Completion(Map<String, Integer> counts, Set<UUID> protectedHorses) {}
    record Carrier(UUID id, Genetics.Combination appearance) {}

    private Goal() {
    }

    public static boolean isFinished(AbstractHorse horse, HorseBreederConfig config) {
        if (horse.isBaby() || !horse.isTamed()) return false;
        if (config.hasAppearanceFilter()) {
            String appearance = Genetics.keyOf(horse);
            if (appearance.isEmpty() || !config.appearanceWhitelist.contains(appearance)) return false;
        }
        return meetsThresholds(Scoring.read(horse), config);
    }

    private static boolean meetsThresholds(Scoring.Stats stats, HorseBreederConfig config) {
        return stats.blocksPerSecond() >= config.speedThreshold
            && stats.jumpBlocks() >= config.jumpThreshold
            && stats.health() >= config.healthThreshold;
    }

    public static Evaluation evaluate(List<AbstractHorse> horses, HorseBreederConfig config) {
        Map<String, Genetics.Combination> targets = new LinkedHashMap<>();
        for (String entry : config.appearanceWhitelist) {
            Genetics.Combination combination = Genetics.parse(entry);
            if (combination != null) targets.put(entry, combination);
        }
        boolean filtered = !targets.isEmpty();
        List<Finished> finished = new ArrayList<>();
        for (AbstractHorse horse : horses) {
            if (horse.isBaby() || !horse.isTamed()) continue;
            String key = Genetics.keyOf(horse);
            if (filtered && !targets.containsKey(key)) continue;
            Scoring.Stats stats = Scoring.read(horse);
            if (meetsThresholds(stats, config)) finished.add(new Finished(horse.getUUID(), key, Scoring.statScore(stats)));
        }
        int need = perCombination(config);
        Completion completion = summarizeFinished(finished, filtered, need);
        List<Line> lines = new ArrayList<>();
        if (!filtered) {
            lines.add(new Line(Lang.text("horsebreeder.goal.finished"), null, completion.counts().getOrDefault("", 0), need));
        }
        for (Map.Entry<String, Genetics.Combination> target : targets.entrySet()) {
            Genetics.Combination combination = target.getValue();
            String label = Genetics.coatName(combination.color()) + "/" + Genetics.markingName(combination.marking());
            lines.add(new Line(label, target.getKey(), completion.counts().getOrDefault(target.getKey(), 0), need));
        }
        return new Evaluation(lines, completion.protectedHorses());
    }

    public static boolean reached(List<Line> lines) {
        if (lines.isEmpty()) return false;
        for (Line line : lines) {
            if (!line.done()) return false;
        }
        return true;
    }

    public static Set<String> open(List<Line> lines) {
        Set<String> open = new HashSet<>();
        for (Line line : lines) {
            if (!line.done() && line.key() != null) open.add(line.key());
        }
        return open;
    }

    public static int perCombination(HorseBreederConfig config) {
        return Math.max(1, config.targetCount);
    }

    public static int total(HorseBreederConfig config) {
        return perCombination(config) * combinations(config);
    }

    public static int populationCap(HorseBreederConfig config) {
        return Math.max(config.maxHorses, total(config));
    }

    public static int combinations(HorseBreederConfig config) {
        if (!config.hasAppearanceFilter()) return 1;
        return config.appearanceWhitelist.size();
    }

    /**
     * 在还没写回配置、只知道“选中了几种外观”时，预测达成目标所需的总马数。
     * 供 AppearanceScreen 在落盘前给出提示（targetCount × 选中数）。
     * {@code selected} 为 0 表示不限外观 = 1 种。
     */
    public static int totalFor(HorseBreederConfig config, int selected) {
        return perCombination(config) * Math.max(1, selected);
    }

    /**
     * 一次算完三个保护集合。{@code horses} 应传<b>全体</b>（含幼驹）。
     * <p>{@code finished} 直接沿用本 tick 已经算好的 {@link Evaluation#protectedHorses()}：
     * {@link #evaluate} 会遍历全体马并读属性，一个 tick 内跑两次是纯浪费。
     */
    public static Protection protection(List<AbstractHorse> horses, HorseBreederConfig config,
                                        Set<UUID> finished, Set<String> openCombos,
                                        Selection.Wanted wanted) {
        if (openCombos.isEmpty()) return new Protection(finished, Set.of(), Set.of());
        return new Protection(finished, soleCarriers(horses, openCombos),
            reservedSlots(horses, config, openCombos, wanted));
    }

    private static Set<UUID> reservedSlots(List<AbstractHorse> horses, HorseBreederConfig config,
                                           Set<String> openCombos, Selection.Wanted wanted) {
        int capacity = populationCap(config);
        if (openCombos.isEmpty()) return Set.of();
        // 只在“每种外观各自拿得到独立批次”时分区，否则它们会互相抢同一批马。
        if (openCombos.size() > 1 && !disjoint(openCombos)) return Set.of();
        int share = reserveShare(openCombos.size(), capacity);
        if (share * openCombos.size() > capacity) return Set.of();

        Map<String, List<AbstractHorse>> groups = new HashMap<>();
        for (AbstractHorse horse : horses) {
            String key = Genetics.keyOf(horse);
            if (key.isEmpty() || !openCombos.contains(key)) continue;
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(horse);
        }
        Set<UUID> kept = new HashSet<>();
        for (List<AbstractHorse> group : groups.values()) {
            group.sort(Comparator.comparingDouble(horse -> -Selection.keepValue(horse, config, wanted)));
            for (int i = 0; i < Math.min(share, group.size()); i++) kept.add(group.get(i).getUUID());
        }
        return kept;
    }

    /**
     * 每种未完成外观分到多少繁育席位。
     * 只剩一种时给它一半围栏 —— 它需要足以自我繁衍的种群，否则会先被自己人挤光。
     */
    private static int reserveShare(int openCount, int capacity) {
        if (openCount <= 1) return Math.max(2, capacity / 2);
        return Math.max(2, capacity / openCount);
    }

    /**
     * 返回 true 表示这些目标组合“互不重叠”：颜色两两不同 <b>且</b> 花纹两两不同。
     * 只有在这种情况下，给每个组合划分育种群（reservedSlots）才不会互相抢同一批马。
     * 名字容易读反：true = 可安全分区，不是“有冲突”。
     */
    static boolean disjoint(Set<String> combos) {
        if (combos.size() < 2) return false;
        Set<Variant> colors = new HashSet<>();
        Set<Markings> markings = new HashSet<>();
        for (String key : combos) {
            Genetics.Combination combination = Genetics.parse(key);
            if (combination == null) return false;
            if (!colors.add(combination.color()) || !markings.add(combination.marking())) return false;
        }
        return true;
    }

    static Completion summarizeFinished(List<Finished> finished, boolean filtered, int need) {
        Map<String, List<Finished>> byCombination = new HashMap<>();
        for (Finished horse : finished) {
            String key = filtered ? horse.combination() : "";
            byCombination.computeIfAbsent(key, ignored -> new ArrayList<>()).add(horse);
        }
        Map<String, Integer> counts = new HashMap<>();
        Set<UUID> kept = new HashSet<>();
        for (Map.Entry<String, List<Finished>> entry : byCombination.entrySet()) {
            List<Finished> group = entry.getValue();
            counts.put(entry.getKey(), group.size());
            group.sort((a, b) -> Double.compare(b.rank(), a.rank()));
            for (int i = 0; i < Math.min(need, group.size()); i++) kept.add(group.get(i).id());
        }
        return new Completion(Map.copyOf(counts), Set.copyOf(kept));
    }

    private static Set<UUID> soleCarriers(List<AbstractHorse> horses, Set<String> openCombos) {
        List<Carrier> population = new ArrayList<>();
        for (AbstractHorse horse : horses) {
            Genetics.Combination appearance = Genetics.combinationOf(horse);
            if (appearance != null) population.add(new Carrier(horse.getUUID(), appearance));
        }
        Set<Genetics.Combination> wanted = new HashSet<>();
        for (String entry : openCombos) {
            Genetics.Combination combination = Genetics.parse(entry);
            if (combination != null) wanted.add(combination);
        }
        return scarceCarriers(population, wanted);
    }

    static Set<UUID> scarceCarriers(List<Carrier> population, Set<Genetics.Combination> wanted) {
        if (wanted.isEmpty()) return Set.of();
        Map<Variant, List<UUID>> byColor = new EnumMap<>(Variant.class);
        Map<Markings, List<UUID>> byMarking = new EnumMap<>(Markings.class);
        for (Carrier horse : population) {
            byColor.computeIfAbsent(horse.appearance().color(), ignored -> new ArrayList<>()).add(horse.id());
            byMarking.computeIfAbsent(horse.appearance().marking(), ignored -> new ArrayList<>()).add(horse.id());
        }
        Set<UUID> carriers = new HashSet<>();
        for (Genetics.Combination combination : wanted) {
            keepIfScarce(carriers, byColor.get(combination.color()));
            keepIfScarce(carriers, byMarking.get(combination.marking()));
        }
        return Set.copyOf(carriers);
    }

    private static void keepIfScarce(Set<UUID> carriers, List<UUID> group) {
        if (group == null || group.size() > CARRIER_FLOOR) return;
        carriers.addAll(group);
    }

    public static String text(List<Line> lines) {
        StringBuilder text = new StringBuilder();
        for (Line line : lines) {
            if (!text.isEmpty()) text.append("，");
            text.append(line.text());
        }
        return text.toString();
    }
}
