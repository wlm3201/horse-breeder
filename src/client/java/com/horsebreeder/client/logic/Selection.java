package com.horsebreeder.client.logic;

/**
 * AGENT NOTES —— 两个决策：跟谁配（bestPair）、宰谁（cullTarget）。
 *
 * <p><b>打分被人为分成两个数量级</b>：三维 ≈ 0..1e6（Scoring.STAT_SCALE），
 * 外观再乘 1e9（APPEARANCE_SCALE）。目的是让“外观是否命中”绝对压过三维差异，
 * 从而实现“先看外观”的取舍；代价是 keepValue 的数值本身没有直观含义，不要跨函数比较。
 *
 * <p>{@link Wanted} 是一个针对当前未完成组合预编译的查表结构（ordinal 下标 + exact 位图），
 * 目的只是把 every tick 的 O(n²) 配对估价压成数组访问。热点，勿改成 map 实现。
 *
 * <p><b>cullTarget 的粘性</b>：传进来的 {@code keepTarget} 是上一 tick 选中的目标，
 * 遍历到它就立刻 return（前提是它仍然可宰）。这让同分时目标保持稳定；
 * 副作用是淘汰目标一旦锁定就不会因为“出现更差的马”而升级，除非原目标失效。
 */
import com.horsebreeder.client.config.HorseBreederConfig;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.Markings;
import net.minecraft.world.entity.animal.equine.Variant;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class Selection {
    private static final double APPEARANCE_SCALE = 1_000_000_000.0;

    public record Pair(AbstractHorse a, AbstractHorse b, double score) {
    }

    public static final class Wanted {
        private final List<Genetics.Combination> combinations;
        private final int[] colors = new int[Variant.values().length];
        private final int[] markings = new int[Markings.values().length];
        private final boolean[][] exact = new boolean[colors.length][markings.length];

        public Wanted(List<Genetics.Combination> combinations) {
            this.combinations = List.copyOf(new LinkedHashSet<>(combinations));
            for (Genetics.Combination combination : this.combinations) {
                int color = combination.color().ordinal();
                int marking = combination.marking().ordinal();
                colors[color]++;
                markings[marking]++;
                exact[color][marking] = true;
            }
        }

        public boolean isEmpty() {
            return combinations.isEmpty();
        }

        public double pairChance(Genetics.Combination a, Genetics.Combination b) {
            if (a == null || b == null) return 1.0;
            int colorA = a.color().ordinal();
            int colorB = b.color().ordinal();
            int markingA = a.marking().ordinal();
            int markingB = b.marking().ordinal();
            int inherited = match(colorA, markingA) + match(colorA, markingB)
                + match(colorB, markingA) + match(colorB, markingB);
            double total = combinations.size() * Genetics.RANDOM_COLOR_CHANCE * Genetics.RANDOM_MARKING_CHANCE
                + (colors[colorA] + colors[colorB]) * Genetics.COLOR_FROM_PARENT * Genetics.RANDOM_MARKING_CHANCE
                + (markings[markingA] + markings[markingB]) * Genetics.RANDOM_COLOR_CHANCE * Genetics.MARKING_FROM_PARENT
                + inherited * Genetics.COLOR_FROM_PARENT * Genetics.MARKING_FROM_PARENT;
            return Math.min(1.0, total);
        }

        public double appearanceValue(Genetics.Combination appearance) {
            if (isEmpty()) return 0.0;
            if (appearance == null) return 1.0;
            int color = appearance.color().ordinal();
            int marking = appearance.marking().ordinal();
            double oneColor = Genetics.COLOR_FROM_PARENT + Genetics.RANDOM_COLOR_CHANCE;
            double twoColors = 2.0 * Genetics.COLOR_FROM_PARENT + Genetics.RANDOM_COLOR_CHANCE;
            double oneMarking = Genetics.MARKING_FROM_PARENT + Genetics.RANDOM_MARKING_CHANCE;
            double twoMarkings = 2.0 * Genetics.MARKING_FROM_PARENT + Genetics.RANDOM_MARKING_CHANCE;
            if (exact[color][marking]) return twoColors * twoMarkings;
            double best = oneColor * oneMarking;
            if (colors[color] > 0) best = Math.max(best, twoColors * oneMarking);
            if (markings[marking] > 0) best = Math.max(best, oneColor * twoMarkings);
            return best;
        }

        private int match(int color, int marking) {
            return exact[color][marking] ? 1 : 0;
        }
    }

    private Selection() {
    }

    public static Wanted wanted(HorseBreederConfig config, Set<String> openCombos) {
        List<Genetics.Combination> combinations = new ArrayList<>();
        for (String entry : config.appearanceWhitelist) {
            if (!openCombos.isEmpty() && !openCombos.contains(entry)) continue;
            Genetics.Combination combination = Genetics.parse(entry);
            if (combination != null) combinations.add(combination);
        }
        return new Wanted(combinations);
    }

    public static double rank(AbstractHorse horse) {
        return Scoring.statScore(Scoring.read(horse));
    }

    public static double keepValue(AbstractHorse horse, HorseBreederConfig config, Wanted wanted) {
        return rank(horse) + appearanceValue(horse, config, wanted) * APPEARANCE_SCALE;
    }

    public static double appearanceValue(AbstractHorse horse, HorseBreederConfig config, Wanted wanted) {
        if (!config.hasAppearanceFilter() || wanted.isEmpty()) return 0.0;
        return wanted.appearanceValue(Genetics.combinationOf(horse));
    }

    public static double pairPureChance(AbstractHorse a, AbstractHorse b, HorseBreederConfig config, Wanted wanted) {
        return wanted.pairChance(Genetics.combinationOf(a), Genetics.combinationOf(b));
    }

    public static double pairValue(AbstractHorse a, AbstractHorse b, HorseBreederConfig config, Wanted wanted) {
        double appearance = config.hasAppearanceFilter() ? pairPureChance(a, b, config, wanted) : 1.0;
        return pairValue(Scoring.read(a), Scoring.read(b), appearance, config.variancePenalty);
    }

    public static double pairValue(Scoring.Stats a, Scoring.Stats b, double appearance, double variancePenalty) {
        double quality = 1.0 + Scoring.expectedChild(a, b).average();
        double uncertainty = Scoring.priorityUncertainty(a, b) / Scoring.STAT_SCALE;
        return appearance * (quality - variancePenalty * uncertainty);
    }

    public static Pair bestPair(List<AbstractHorse> candidates, HorseBreederConfig config,
                                Wanted wanted,
                                double range, Map<PairKey, Long> retries, long now) {
        if (candidates.size() < 2) return null;
        Scoring.Stats[] stats = new Scoring.Stats[candidates.size()];
        Genetics.Combination[] appearances = new Genetics.Combination[candidates.size()];
        boolean filtered = config.hasAppearanceFilter();
        for (int i = 0; i < candidates.size(); i++) {
            stats[i] = Scoring.read(candidates.get(i));
            if (filtered) appearances[i] = Genetics.combinationOf(candidates.get(i));
        }
        double rangeSquared = range * range;
        Pair best = null;
        for (int i = 0; i < candidates.size(); i++) {
            for (int j = i + 1; j < candidates.size(); j++) {
                AbstractHorse a = candidates.get(i);
                AbstractHorse b = candidates.get(j);
                if (a.distanceToSqr(b) > rangeSquared) continue;
                Long retryAfter = retries.isEmpty() ? null : retries.get(PairKey.of(a.getUUID(), b.getUUID()));
                if (retryPending(retryAfter, now)) continue;
                double appearance = filtered ? wanted.pairChance(appearances[i], appearances[j]) : 1.0;
                double score = pairValue(stats[i], stats[j], appearance, config.variancePenalty);
                if (best == null || score > best.score()) best = new Pair(a, b, score);
            }
        }
        return best;
    }

    static boolean retryPending(Long retryAfter, long now) {
        return retryAfter != null && retryAfter > now;
    }

    /**
     * 挑一批最差的宰掉。过滤顺序即优先级：命名保护 → 有人骑 → 保底成年数 →
     * {@code Goal.Protection} 的三个保护集合 → 正在配对的父母 → 粘性目标。
     */
    public static AbstractHorse cullTarget(List<AbstractHorse> horses, HorseBreederConfig config,
                                           Wanted wanted, Goal.Protection protection,
                                           Set<UUID> busy, UUID keepTarget, int minAdults) {
        AbstractHorse worst = null;
        double worstValue = Double.MAX_VALUE;
        boolean filtered = config.hasAppearanceFilter();
        int adults = 0;
        for (AbstractHorse horse : horses) {
            if (!horse.isBaby()) adults++;
        }
        for (AbstractHorse horse : horses) {
            if (config.protectNamed && horse.hasCustomName()) continue;
            if (horse.isVehicle() || horse.isPassenger()) continue;
            if (!horse.isBaby() && adults <= minAdults) continue;
            if (protection.protects(horse.getUUID())) continue;
            if (busy.contains(horse.getUUID())) continue;
            // 粘性目标：上一 tick 选中的马只要还活着且仍可宰，就直接沿用，
            // 避免同分时反复改目标导致“永远走过去却永远打不到”。
            if (horse.getUUID().equals(keepTarget) && horse.isAlive()) return horse;
            double value = rank(horse) + (filtered ? wanted.appearanceValue(Genetics.combinationOf(horse)) : 0.0) * APPEARANCE_SCALE;
            if (value < worstValue) {
                worstValue = value;
                worst = horse;
            }
        }
        return worst;
    }

}
