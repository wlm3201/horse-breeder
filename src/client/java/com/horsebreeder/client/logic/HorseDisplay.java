package com.horsebreeder.client.logic;

/**
 * AGENT NOTES —— “渲染侧”的缓存，只服务于发光标记 + HUD 的最佳/最差两行。
 *
 * <p>为什么必须缓存而不是按需计算：取色会被 {@code EntityGlowColorMixin} 在
 * <b>渲染线程每帧对每匹马</b>调用，里面任何分配量偏重的操作（读属性 / 查 Map / 取翻译）都会直接变成帧率问题。
 * 这里只保留两个 HashMap 与两个字符串。
 *
 * <p>标记是有优先级的（见 {@code markerColor} 的 return 顺序）：ACTING &gt; IN_LOVE(parents) &gt;
 * FINISHED &gt; NEXT_PAIR &gt; CULL &gt; BABY &gt; 状态色。改顺序会改玩家看到的含义。
 *
 * <p>{@code actingMarkers} 是短时高亮（600ms），用于“刚踩了这一匹”，不要改成持久标记。
 */
import com.horsebreeder.client.config.HorseBreederConfig;
import com.horsebreeder.client.util.Lang;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class HorseDisplay {
    private static final long ACTING_MARKER_MS = 600;
    // 就地复用：这个 Map 每 tick 重建一次，而渲染线程每帧会查它很多次。
    private final Map<UUID, Integer> markerColors = new HashMap<>();
    private final Map<UUID, Long> actingMarkers = new HashMap<>();
    private String bestLabel = "";
    private String worstLabel = "";

    int glowColor(Entity entity) { return markerColors.getOrDefault(entity.getUUID(), Markers.NONE); }
    String bestLabel() { return bestLabel; }
    String worstLabel() { return worstLabel; }
    void hideMarkers() { markerColors.clear(); }
    void clearMarkers() { hideMarkers(); actingMarkers.clear(); }
    void clearLabels() { bestLabel = worstLabel = ""; }
    private static long now() { return ElapsedTime.millis(); }

    void updateMarkers(List<AbstractHorse> horses, HorseBreederConfig config, HorseStates states,
                       Set<UUID> parents, Selection.Pair candidatePair, UUID cullCandidateId) {
        tickActingMarkers();
        markerColors.clear();
        if (!config.glowMarkers) return;

        for (AbstractHorse horse : horses) {
            int color = markerColor(horse, config, states, parents, candidatePair, cullCandidateId);
            if (color != Markers.NONE) markerColors.put(horse.getUUID(), color);
        }
    }

    private int markerColor(AbstractHorse horse, HorseBreederConfig config, HorseStates states,
                            Set<UUID> parents, Selection.Pair candidatePair, UUID cullCandidateId) {
        UUID uuid = horse.getUUID();
        if (actingMarkers.containsKey(uuid)) return Markers.ACTING;
        if (parents.contains(uuid)) return Markers.IN_LOVE;
        if (Goal.isFinished(horse, config)) return Markers.FINISHED;
        if (candidatePair != null
            && (uuid.equals(candidatePair.a().getUUID()) || uuid.equals(candidatePair.b().getUUID()))) {
            return Markers.NEXT_PAIR;
        }
        if (uuid.equals(cullCandidateId)) return Markers.CULL_CANDIDATE;
        if (horse.isBaby()) return Markers.BABY;
        return switch (states.describe(horse).state()) {
            case COOLDOWN -> Markers.COOLING;
            case IN_LOVE -> Markers.IN_LOVE;
            case BLOCKED -> Markers.BLOCKED;
            default -> Markers.READY;
        };
    }

    void markActing(AbstractHorse horse) {
        actingMarkers.put(horse.getUUID(), now() + ACTING_MARKER_MS);
    }

    private void tickActingMarkers() {
        long now = now();
        actingMarkers.values().removeIf(until -> until <= now);
    }

    void updateRankLabels(List<AbstractHorse> grown) {
        AbstractHorse worst = null;
        AbstractHorse best = null;
        double worstValue = Double.MAX_VALUE;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (AbstractHorse horse : grown) {
            double value = Selection.rank(horse);
            if (value < worstValue) {
                worstValue = value;
                worst = horse;
            }
            if (value > bestValue) {
                bestValue = value;
                best = horse;
            }
        }
        worstLabel = worst == null ? "" : label(worst);
        bestLabel = best == null ? "" : label(best);
    }

    /**
     * 最强/最差那两行的内容。顺序是<b>先三维与评分、再花色</b>：四项数字都是定宽格式，
     * 放在前面可以让两行左对齐；花色长度不定（而且驴/骡为空），放末尾才不会顶乱排版。
     */
    static String label(AbstractHorse horse) {
        Scoring.Stats stats = Scoring.read(horse);
        StringBuilder text = new StringBuilder(Lang.text("horsebreeder.label.stats",
            String.format("%.0f", stats.health()),
            String.format("%.2f", Scoring.jumpToBlocks(stats.jump())),
            String.format("%.2f", stats.speed() * Scoring.SPEED_TO_BLOCKS_PER_SECOND),
            String.format("%.0f%%", stats.average() * 100.0)));
        String coat = Genetics.labelOf(horse);
        if (!coat.isEmpty()) text.append(' ').append(coat);
        return text.toString();
    }
}
