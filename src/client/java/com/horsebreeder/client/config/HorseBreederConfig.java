package com.horsebreeder.client.config;

/**
 * AGENT NOTES —— 全局单例配置（{@code config/horsebreeder.json}）。
 *
 * <p><b>新增字段的三步（漏一步就会静默出问题）</b>：
 * <ol>
 *   <li>必须是 <b>public 非 static 非 final</b> 字段，类型限 boolean / int / double / String / Set&lt;String&gt;。
 *       ConfigStore 用反射 {@code getField} 判断 int 字段，非 public 会直接抛 IllegalStateException；</li>
 *   <li>给出合理的默认值（默认值树就是“缺失时补什么”和“非法时回退到什么”的依据）；</li>
 *   <li>在 {@code sanitize()} 里加边界，在 {@code ModMenuIntegration} 里加控件，
 *       并往两份 lang json 里补 {@code key} 与 {@code key.description}。</li>
 * </ol>
 *
 * <p>取值一律 {@code get()}（初始化-on-demand holder 单例）。允许直接改字段，
 * 但改完必须 {@code save()} —— YACL 退出时会整体保存，而 ModMenu 之外的修改（如容器标记）需要立即落盘。
 *
 * <p>{@code sanitize()} 会在 load 和 save 时各跑一遍，所以它是“唯一的真值收敛点”：
 * 非法值不抛异常，而是静默回默认值并在日志里列出被忽略的键。
 */
import com.horsebreeder.HorseBreeder;
import com.horsebreeder.client.logic.Genetics;
import com.horsebreeder.client.logic.Scoring;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.equine.Markings;
import net.minecraft.world.entity.animal.equine.Variant;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

public final class HorseBreederConfig {
    private static final String FILE = "horsebreeder.json";

    private static final class Holder {
        private static final HorseBreederConfig INSTANCE = ConfigStore.load(getPath());
    }

    public boolean enabled = false;
    public boolean shortenGrowth = true;
    public int maxHorses = 20;
    public int targetCount = 2;
    public double reach = 5.9;
    public double detectionRange = 16;
    public boolean walkToTarget = true;
    public boolean protectNamed = true;
    public boolean criticals = true;
    public boolean ignoreHorsePush = true;
    public boolean hudEnabled = true;
    public boolean glowMarkers = true;

    public double speedThreshold = 14.0;
    public double jumpThreshold = 5.0;
    public double healthThreshold = 28.0;
    public boolean dropLeather = true;
    public double variancePenalty = 0.0;

    public boolean autoEat = true;
    public boolean feedToTame = false;
    public int tameFeedTarget = 60;

    public boolean restockFromContainers = true;
    public String goldenCarrotContainer = "";
    public String carrotContainer = "";

    public Set<String> appearanceWhitelist = new LinkedHashSet<>();

    public int hudX = 6;
    public int hudY = 6;
    public double dropYaw = 0.0;
    public boolean verboseLogging = false;

    public static HorseBreederConfig get() {
        return Holder.INSTANCE;
    }

    public boolean hasAppearanceFilter() {
        return appearanceWhitelist != null && !appearanceWhitelist.isEmpty();
    }

    void sanitize() {
        HorseBreederConfig defaults = new HorseBreederConfig();
        reach = clamp(reach, 3.0, 6.0, defaults.reach);
        detectionRange = clamp(detectionRange, 6.0, 64.0, defaults.detectionRange);
        maxHorses = Math.max(2, maxHorses);
        targetCount = Math.max(1, targetCount);
        speedThreshold = clamp(speedThreshold, Scoring.MIN_SPEED_BLOCKS_PER_SECOND,
            Scoring.MAX_SPEED_BLOCKS_PER_SECOND, defaults.speedThreshold);
        jumpThreshold = clamp(jumpThreshold, Scoring.MIN_JUMP_BLOCKS, Scoring.MAX_JUMP_BLOCKS, defaults.jumpThreshold);
        healthThreshold = clamp(healthThreshold, Scoring.MIN_HEALTH_POINTS, Scoring.MAX_HEALTH_POINTS,
            defaults.healthThreshold);
        variancePenalty = clamp(variancePenalty, 0.0, Double.MAX_VALUE, defaults.variancePenalty);
        tameFeedTarget = Math.min(100, Math.max(0, tameFeedTarget));
        hudX = Math.max(0, hudX);
        hudY = Math.max(0, hudY);
        dropYaw = Double.isFinite(dropYaw) ? ((dropYaw % 360.0) + 360.0) % 360.0 : defaults.dropYaw;
        goldenCarrotContainer = goldenCarrotContainer == null ? "" : goldenCarrotContainer.trim();
        carrotContainer = carrotContainer == null ? "" : carrotContainer.trim();
        appearanceWhitelist = appearanceWhitelist == null
            ? new LinkedHashSet<>() : new LinkedHashSet<>(appearanceWhitelist);
        appearanceWhitelist.removeIf(entry -> !Genetics.isValid(entry));
    }

    private static double clamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }

    public static BlockPos parseBlockPos(String text) {
        if (text == null) return null;
        String[] parts = text.trim().split("[\\s,]+");
        if (parts.length != 3) return null;
        try {
            return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String formatBlockPos(BlockPos pos) {
        return pos == null ? "" : pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    public void save() {
        Path path = getPath();
        try {
            ConfigStore.save(path, this);
        } catch (IOException | RuntimeException e) {
            HorseBreeder.LOGGER.warn("[horsebreeder] failed to save config", e);
        }
    }

    public static void saveNow() {
        get().save();
    }

    private static Path getPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE);
    }
}
