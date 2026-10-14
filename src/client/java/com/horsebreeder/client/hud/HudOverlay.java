package com.horsebreeder.client.hud;

/**
 * AGENT NOTES —— HUD 文本。由 {@code GuiMixin} 挂在 {@code Gui#extractRenderState} 尾部，
 * 因此它在<b>渲染线程每帧</b>被调用，而不是每 tick。
 *
 * <p>性能约束（这里最容易被忽视）：{@code entries()} 每帧都会拼 String 并调用
 * {@code Lang.text}（内部会新建 Component 并查一次当前语言的翻译缓存）。
 * 若将来要加行，优先让 {@link com.horsebreeder.client.logic.BreederController} 在 tick 里算好，
 * 这里只取结果。
 *
 * <p>渲染还有两个约定：绘制顺序是原样写在 {@code render} 里（stratum 未切换），
 * 所以后来者会覆盖前绘内容；且这里做的是 <i>extract</i> 而非 <i>draw</i>，
 * 不要试图在此处调用真正的绘制 API。
 */import com.horsebreeder.client.HorseBreederClient;
import com.horsebreeder.client.config.HorseBreederConfig;
import com.horsebreeder.client.logic.BreederController;
import com.horsebreeder.client.logic.Goal;
import com.horsebreeder.client.logic.HorseStates;
import com.horsebreeder.client.util.Lang;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

public final class HudOverlay {
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TITLE = 0xFFFFFFFF;
    private static final int WARN = 0xFFFFD555;

    public record Line(String text, boolean warn) {
    }

    private HudOverlay() {
    }

    /**
     * HUD 文本缓存。控制器每 tick 自增版本号，这里只在版本号变化时重建 ——
     * 文本里含 8 次 {@code Lang.text}（每次新建 Component 并查翻译），
     * 放在渲染帧里做会是 60 次/秒的无谓开销。
     */
    private static int cachedVersion = Integer.MIN_VALUE;
    private static List<Line> cachedLines = List.of();

    public static List<Line> entries(BreederController controller, HorseBreederConfig config) {
        int version = controller.hudVersion();
        if (version != cachedVersion) {
            cachedVersion = version;
            cachedLines = build(controller, config);
        }
        return cachedLines;
    }

    private static List<Line> build(BreederController controller, HorseBreederConfig config) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(Lang.text("horsebreeder.hud.header",
            config.enabled ? Lang.text("horsebreeder.hud.on") : Lang.text("horsebreeder.hud.off"),
            stageName(controller.stage()),
            sourceName(controller.states().source())), false));

        BreederController.Counts counts = controller.counts();
        StringBuilder summary = new StringBuilder(Lang.text("horsebreeder.hud.counts",
            Goal.populationCap(config), controller.adults(), counts.ready(), counts.cooling(),
            counts.inLove(), counts.untamed(), controller.babies()));
        if (counts.mounted() > 0) summary.append(Lang.text("horsebreeder.hud.mounted", counts.mounted()));
        lines.add(new Line(summary.toString(), false));
        String target = controller.targetLabel();
        if (!controller.bestLabel().isEmpty()) {
            lines.add(new Line(Lang.text("horsebreeder.hud.best", controller.bestLabel()), false));
        }
        if (!controller.worstLabel().isEmpty()) {
            lines.add(new Line(Lang.text("horsebreeder.hud.worst", controller.worstLabel()), false));
        }
        if (controller.detail() != null && !controller.detail().isEmpty()) {
            lines.add(new Line(controller.detail(), false));
        }
        if (!target.isEmpty()) {
            lines.add(new Line(Lang.text("horsebreeder.hud.target", target), false));
        }
        if (!controller.pauseReason().isEmpty()) {
            lines.add(new Line(Lang.text("horsebreeder.hud.paused", controller.pauseReason()), true));
        }
        if (!controller.warning().isEmpty()) {
            lines.add(new Line(Lang.text("horsebreeder.hud.warning", controller.warning()), true));
        }
        // idleReason 单独一行：以前它挤在 detail 里，会被 warning 顶掉，
        // 于是“已达上限”“无可用配对”这类关键状态根本看不到。
        String idle = controller.idleReason();
        if (!idle.isEmpty()) {
            lines.add(new Line(Lang.text("horsebreeder.hud.idle", idle), true));
        }
        return lines;
    }

    public static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        HorseBreederConfig config = HorseBreederConfig.get();
        if (!config.hudEnabled) return;

        BreederController controller = HorseBreederClient.controller();
        Font font = Minecraft.getInstance().font;

        List<Line> lines = entries(controller, config);

        int x = config.hudX;
        int y = config.hudY;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            int colour = i == 0 ? TITLE : line.warn() ? WARN : TEXT;
            graphics.text(font, line.text(), x, y, colour, true);
            y += 11;
        }
    }

    private static String sourceName(HorseStates.Source source) {
        return Lang.text(source == HorseStates.Source.EXACT
            ? "horsebreeder.source.exact" : "horsebreeder.source.inferred");
    }

    private static String stageName(BreederController.Stage stage) {
        return switch (stage) {
            case IDLE -> Lang.text("horsebreeder.stage.idle");
            case TAMING -> Lang.text("horsebreeder.stage.taming");
            case BREEDING -> Lang.text("horsebreeder.stage.breeding");
            case GROWING -> Lang.text("horsebreeder.stage.growing");
            case HEALING -> Lang.text("horsebreeder.stage.healing");
            case CULLING -> Lang.text("horsebreeder.stage.culling");
            case WALKING -> Lang.text("horsebreeder.stage.walking");
            case EATING -> Lang.text("horsebreeder.stage.eating");
            case PAUSED -> Lang.text("horsebreeder.stage.paused");
        };
    }
}
