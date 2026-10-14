package com.horsebreeder.client.logic;

/**
 * AGENT NOTES —— 毛色/花纹的<i>精确</i>遗传概率模型。改动前请先看依据。
 *
 * <p>依据 MC 26.1.2 {@code Horse#getBreedOffspring} 的两段独立 9 选 / 5 选：
 * 颜色 = 4/9 父 + 4/9 母 + 1/9 均匀随机；花纹 = 2/5 + 2/5 + 1/5 均匀随机。
 * 因此“随机命中某一特定颜色” = (1/9)/7，“随机命中某一特定花纹” = (1/5)/5。
 * 颜色和花纹是<b>两次独立掷点</b>，这是 Selection.Wanted.pairChance 能把联合概率拆成四项展开的前提。
 *
 * <p>{@code key()} 格式为「Variant 的序列化名 + 冒号 + Markings 的枚举名」，
 * 它同时被用作 {@code appearanceWhitelist} 的元素与 {@code Combination} 的反查键 —— 一处改格式，
 * 存档、配置、本地化三处同时失效，别动。
 *
 * <p>只有 {@code Horse} 有 Variant/Markings，驴/骡/僵尸马 {@code keyOf} 返回空串。
 */
import com.horsebreeder.client.util.Lang;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.entity.animal.equine.Markings;
import net.minecraft.world.entity.animal.equine.Variant;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class Genetics {
    // 以下四个常量直接抄自 MC 26.1.2 Horse#getBreedOffspring 的两段随机：
    // 颜色 = 4/9 父 + 4/9 母 + 1/9 随机；花纹 = 2/5 + 2/5 + 1/5 随机。
    // 任何版本升级都必须重新核对这两个方法，否则整个 pairChance 概率模型失效。
    static final double COLOR_FROM_PARENT = 4.0 / 9.0;
    private static final double COLOR_MUTATION = 1.0 / 9.0;
    static final double MARKING_FROM_PARENT = 2.0 / 5.0;
    private static final double MARKING_MUTATION = 1.0 / 5.0;

    private static final Variant[] COLORS = Variant.values();
    private static final Markings[] MARKINGS = Markings.values();
    static final double RANDOM_COLOR_CHANCE = COLOR_MUTATION / COLORS.length;
    static final double RANDOM_MARKING_CHANCE = MARKING_MUTATION / MARKINGS.length;
    private static final Map<String, Combination> COMBINATIONS_BY_KEY = new HashMap<>();
    private static final String[][] KEYS = new String[COLORS.length][MARKINGS.length];

    static {
        for (Variant color : COLORS) {
            for (Markings marking : MARKINGS) {
                String key = color.getSerializedName() + ":" + marking.name();
                KEYS[color.ordinal()][marking.ordinal()] = key;
                COMBINATIONS_BY_KEY.put(key, new Combination(color, marking));
            }
        }
    }

    public record Combination(Variant color, Markings marking) {
        public Combination {
            Objects.requireNonNull(color, "color");
            Objects.requireNonNull(marking, "marking");
        }

        public String key() {
            return Genetics.key(color, marking);
        }
    }

    private Genetics() {
    }

    public static String keyOf(AbstractHorse horse) {
        return horse instanceof Horse horseEntity ? key(horseEntity.getVariant(), horseEntity.getMarkings()) : "";
    }

    public static Combination combinationOf(AbstractHorse horse) {
        return parse(keyOf(horse));
    }

    public static String key(Variant color, Markings marking) {
        return KEYS[color.ordinal()][marking.ordinal()];
    }

    public static Combination parse(String entry) {
        return COMBINATIONS_BY_KEY.get(entry);
    }

    public static boolean isValid(String entry) {
        return parse(entry) != null;
    }

    public static String coatName(Variant coat) {
        return Lang.text("horsebreeder.coat." + coat.getSerializedName());
    }

    public static String markingName(Markings marking) {
        return Lang.text("horsebreeder.marking." + marking.name().toLowerCase(Locale.ROOT));
    }

    public static String labelOf(AbstractHorse horse) {
        if (!(horse instanceof Horse horseEntity)) return "";
        return coatName(horseEntity.getVariant()) + "/" + markingName(horseEntity.getMarkings());
    }

}
