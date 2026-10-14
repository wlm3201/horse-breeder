package com.horsebreeder.client.config;

import com.horsebreeder.client.compat.GameCompat;

/**
 * AGENT NOTES —— YACL + ModMenu 的配置界面。
 *
 * <p>本文件是纯“表单布局”：每个选项 = key + description key + binding(getter/setter) + controller。
 * 所有 {@code defaults.xxx} 都来自新建的 HorseBreederConfig 实例，用来给 “Reset” 按钮提供基准值；
 * 不要引用 {@code get()} 的当前值当默认值，否则 reset 会退化成 no-op。
 *
 * <p>滑块的 min/max 必须和 {@code HorseBreederConfig#sanitize} 的 clamp 区间保持一致，
 * 否则会出现“滑块拉不动 / 手改 json 被静默夹住”的割裂手感。Scoring 里那两套单位常量就是唯一真值来源。
 *
 * <p>YACL 只在点击 Save 时才调用 {@code HorseBreederConfig::saveNow}，
 * 而 AppearanceScreen 是即时写盘 —— 两者并存是有意的，但也是最容易引来 bug 报告的错位点。
 */
import com.horsebreeder.client.logic.Scoring;
import com.horsebreeder.client.util.Lang;
import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.DoubleSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;
import java.util.function.Supplier;

public final class YaclConfigScreen {
    private YaclConfigScreen() {}

    public static Screen create(Screen parent) {
        HorseBreederConfig config = HorseBreederConfig.get();
        HorseBreederConfig defaults = new HorseBreederConfig();

        ConfigCategory.Builder general = ConfigCategory.createBuilder()
            .name(Lang.component("horsebreeder.category.general"))
            .option(bool("horsebreeder.option.enabled", defaults.enabled, () -> config.enabled, value -> config.enabled = value))
            .option(intSlider("horsebreeder.option.maxHorses", defaults.maxHorses, 2, 48, 1, () -> config.maxHorses, value -> config.maxHorses = value))
            .option(doubleSlider("horsebreeder.option.detectionRange", defaults.detectionRange, 6.0, 64.0, 1.0, () -> config.detectionRange, value -> config.detectionRange = value))
            .option(intSlider("horsebreeder.option.targetCount", defaults.targetCount, 1, 8, 1, () -> config.targetCount, value -> config.targetCount = value))
            .option(doubleSlider("horsebreeder.option.speedThreshold", defaults.speedThreshold, Scoring.MIN_SPEED_BLOCKS_PER_SECOND, Scoring.MAX_SPEED_BLOCKS_PER_SECOND, 0.1, () -> config.speedThreshold, value -> config.speedThreshold = value))
            .option(doubleSlider("horsebreeder.option.jumpThreshold", defaults.jumpThreshold, Scoring.MIN_JUMP_BLOCKS, Scoring.MAX_JUMP_BLOCKS, 0.1, () -> config.jumpThreshold, value -> config.jumpThreshold = value))
            .option(doubleSlider("horsebreeder.option.healthThreshold", defaults.healthThreshold, Scoring.MIN_HEALTH_POINTS, Scoring.MAX_HEALTH_POINTS, 0.5, () -> config.healthThreshold, value -> config.healthThreshold = value))
            .option(bool("horsebreeder.option.shortenGrowth", defaults.shortenGrowth, () -> config.shortenGrowth, value -> config.shortenGrowth = value))
            .option(bool("horsebreeder.option.feedToTame", defaults.feedToTame, () -> config.feedToTame, value -> config.feedToTame = value))
            .option(intSlider("horsebreeder.option.tameFeedTarget", defaults.tameFeedTarget, 0, 100, 5, () -> config.tameFeedTarget, value -> config.tameFeedTarget = value))
            .option(bool("horsebreeder.option.restockFromContainers", defaults.restockFromContainers, () -> config.restockFromContainers, value -> config.restockFromContainers = value))
            .option(string("horsebreeder.option.goldenCarrotContainer", () -> config.goldenCarrotContainer, value -> config.goldenCarrotContainer = value))
            .option(string("horsebreeder.option.carrotContainer", () -> config.carrotContainer, value -> config.carrotContainer = value))
            .option(ButtonOption.createBuilder()
                .name(Lang.component("horsebreeder.option.appearance"))
                .text(Lang.component("horsebreeder.option.appearance.open"))
                .description(description("horsebreeder.option.appearance"))
                .action((screen, button) -> GameCompat.setScreen(Minecraft.getInstance(), new AppearanceScreen(screen)))
                .build());

        ConfigCategory.Builder advanced = ConfigCategory.createBuilder()
            .name(Lang.component("horsebreeder.category.advanced"))
            .option(doubleSlider("horsebreeder.option.variancePenalty", defaults.variancePenalty, 0.0, 2.0, 0.05, () -> config.variancePenalty, value -> config.variancePenalty = value))
            .option(bool("horsebreeder.option.protectNamed", defaults.protectNamed, () -> config.protectNamed, value -> config.protectNamed = value))
            .option(doubleSlider("horsebreeder.option.reach", defaults.reach, 3.0, 6.0, 0.1, () -> config.reach, value -> config.reach = value))
            .option(bool("horsebreeder.option.autoEat", defaults.autoEat, () -> config.autoEat, value -> config.autoEat = value))
            .option(bool("horsebreeder.option.walkToTarget", defaults.walkToTarget, () -> config.walkToTarget, value -> config.walkToTarget = value))
            .option(bool("horsebreeder.option.criticals", defaults.criticals, () -> config.criticals, value -> config.criticals = value))
            .option(bool("horsebreeder.option.ignoreHorsePush", defaults.ignoreHorsePush, () -> config.ignoreHorsePush, value -> config.ignoreHorsePush = value))
            .option(bool("horsebreeder.option.dropLeather", defaults.dropLeather, () -> config.dropLeather, value -> config.dropLeather = value))
            .option(doubleSlider("horsebreeder.option.dropYaw", defaults.dropYaw, 0.0, 360.0, 45.0, () -> config.dropYaw, value -> config.dropYaw = value))
            .option(bool("horsebreeder.option.glowMarkers", defaults.glowMarkers, () -> config.glowMarkers, value -> config.glowMarkers = value))
            .option(bool("horsebreeder.option.hudEnabled", defaults.hudEnabled, () -> config.hudEnabled, value -> config.hudEnabled = value))
            .option(intField("horsebreeder.option.hudX", defaults.hudX, () -> config.hudX, value -> config.hudX = value))
            .option(intField("horsebreeder.option.hudY", defaults.hudY, () -> config.hudY, value -> config.hudY = value))
            .option(bool("horsebreeder.option.verboseLogging", defaults.verboseLogging, () -> config.verboseLogging, value -> config.verboseLogging = value));

        return YetAnotherConfigLib.createBuilder()
            .title(Component.translatable("horsebreeder.title"))
            .category(general.build())
            .category(advanced.build())
            .save(HorseBreederConfig::saveNow)
            .build()
            .generateScreen(parent);
    }

    private static OptionDescription description(String key) {
        return OptionDescription.of(Lang.component(key + ".description"));
    }

    private static Option<Boolean> bool(String key, boolean defaultValue, Supplier<Boolean> getter, Consumer<Boolean> setter) {
        return Option.<Boolean>createBuilder()
            .name(Lang.component(key))
            .description(description(key))
            .binding(defaultValue, getter, setter)
            .controller(BooleanControllerBuilder::create)
            .build();
    }

    private static Option<Integer> intSlider(String key, int defaultValue, int min, int max, int step,
                                            Supplier<Integer> getter, Consumer<Integer> setter) {
        return Option.<Integer>createBuilder()
            .name(Lang.component(key))
            .description(description(key))
            .binding(defaultValue, getter, setter)
            .controller(option -> IntegerSliderControllerBuilder.create(option).range(min, max).step(step))
            .build();
    }

    private static Option<Integer> intField(String key, int defaultValue, Supplier<Integer> getter, Consumer<Integer> setter) {
        return Option.<Integer>createBuilder()
            .name(Lang.component(key))
            .description(description(key))
            .binding(defaultValue, getter, setter)
            .controller(IntegerFieldControllerBuilder::create)
            .build();
    }

    private static Option<String> string(String key, Supplier<String> getter, Consumer<String> setter) {
        return Option.<String>createBuilder()
            .name(Lang.component(key))
            .description(description(key))
            .binding("", getter, setter)
            .controller(StringControllerBuilder::create)
            .build();
    }

    private static Option<Double> doubleSlider(String key, double defaultValue, double min, double max, double step,
                                               Supplier<Double> getter, Consumer<Double> setter) {
        return Option.<Double>createBuilder()
            .name(Lang.component(key))
            .description(description(key))
            .binding(defaultValue, getter, setter)
            .controller(option -> DoubleSliderControllerBuilder.create(option).range(min, max).step(step))
            .build();
    }
}
