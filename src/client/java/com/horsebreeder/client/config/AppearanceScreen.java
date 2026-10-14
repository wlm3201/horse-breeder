package com.horsebreeder.client.config;

import com.horsebreeder.client.compat.GameCompat;

/**
 * AGENT NOTES —— 毛色 × 花纹的 7×5 勾选网格（ModMenu 之外是独立 Screen）。
 *
 * <p>注意它的保存语义与 YACL 不一致：<b>每次勾选都立即写 {@code appearanceWhitelist} 并 save()</b>，
 * 因此从 YACL 打开后再次 Cancel，这里的修改也不会回滚。这是刻意的（避免玩家忘了点保存），
 * 但如果要统一语义，改成 {@code onClose} 里一次性提交即可。
 *
 * <p>{@code selection} 是 LinkedHashSet，顺序即 {@code appearanceWhitelist} 的顺序，
 * 会影响 Goal 输出行的排列，别替换成无序集合。
 *
 * <p>布局在 {@code init()} 里随 {@code width} 重算；{@code cells} 会被 {@code refreshSelection()}
 * 整体刷新，不要缓存 Button 引用到字段外。
 */
import com.horsebreeder.client.logic.Genetics;
import com.horsebreeder.client.logic.Goal;
import com.horsebreeder.client.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.animal.equine.Markings;
import net.minecraft.world.entity.animal.equine.Variant;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class AppearanceScreen extends Screen {
    private static final int CELL_WIDTH = 56;
    private static final int CELL_HEIGHT = 20;
    private static final int GAP = 2;
    private static final int LABEL_WIDTH = 84;
    private static final int WHITE = 0xFFFFFFFF;

    private final Screen parent;
    private final Set<String> selection;
    private final Map<String, Button> cells = new LinkedHashMap<>();
    private int cellWidth;
    private int startX;
    private int startY;

    public AppearanceScreen(Screen parent) {
        super(Lang.component("horsebreeder.option.appearance"));
        this.parent = parent;
        this.selection = new LinkedHashSet<>(HorseBreederConfig.get().appearanceWhitelist);
    }

    @Override
    protected void init() {
        Variant[] coats = Variant.values();
        Markings[] markings = Markings.values();
        cells.clear();
        cellWidth = Math.max(20, Math.min(CELL_WIDTH, (this.width - 12 - LABEL_WIDTH) / coats.length - GAP));
        int gridWidth = LABEL_WIDTH + coats.length * (cellWidth + GAP);
        startX = Math.max(6, (this.width - gridWidth) / 2);
        int headerHeight = this.font.lineHeight;
        for (Variant coat : coats) {
            headerHeight = Math.max(headerHeight,
                this.font.wordWrapHeight(Component.literal(Genetics.coatName(coat)), cellWidth));
        }
        startY = 30 + headerHeight + 6;

        for (int row = 0; row < markings.length; row++) {
            for (int column = 0; column < coats.length; column++) {
                String key = Genetics.key(coats[column], markings[row]);
                int x = startX + LABEL_WIDTH + column * (cellWidth + GAP);
                int y = startY + row * (CELL_HEIGHT + GAP);
                Component label = Component.literal(Genetics.coatName(coats[column]) + "/" + Genetics.markingName(markings[row]));

                Button cell = this.addRenderableWidget(Button.builder(
                    Component.literal(selection.contains(key) ? "[x]" : "[ ]"),
                    button -> {
                        if (!selection.remove(key)) selection.add(key);
                        save();
                        button.setMessage(selectionLabel(key));
                    }
                ).pos(x, y).size(cellWidth, CELL_HEIGHT).tooltip(Tooltip.create(label)).build());
                cells.put(key, cell);
            }
        }

        int bottomY = startY + markings.length * (CELL_HEIGHT + GAP) + 8;
        this.addRenderableWidget(Button.builder(Lang.component("horsebreeder.appearance.clear"), button -> {
            selection.clear();
            save();
            refreshSelection();
        }).pos(startX, bottomY).size(80, 20).build());

        this.addRenderableWidget(Button.builder(Lang.component("horsebreeder.appearance.all"), button -> {
            for (Variant coat : coats) {
                for (Markings marking : markings) {
                    selection.add(Genetics.key(coat, marking));
                }
            }
            save();
            refreshSelection();
        }).pos(startX + 84, bottomY).size(80, 20).build());

        this.addRenderableWidget(Button.builder(Lang.component("horsebreeder.appearance.done"), button -> this.onClose())
            .pos(startX + gridWidth - 80, bottomY).size(80, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        Variant[] coats = Variant.values();
        Markings[] markings = Markings.values();
        Font font = this.font;
        graphics.centeredText(font, this.title, this.width / 2, 10, WHITE);
        graphics.text(font, Lang.component("horsebreeder.appearance.axis"), startX, 30, WHITE, false);
        for (int column = 0; column < coats.length; column++) {
            graphics.textWithWordWrap(font, Component.literal(Genetics.coatName(coats[column])),
                startX + LABEL_WIDTH + column * (cellWidth + GAP), 30, cellWidth, WHITE, false);
        }
        for (int row = 0; row < markings.length; row++) {
            graphics.text(font, font.plainSubstrByWidth(Genetics.markingName(markings[row]), LABEL_WIDTH - GAP),
                startX, startY + row * (CELL_HEIGHT + GAP) + 6, WHITE, false);
        }

        int bottomY = startY + markings.length * (CELL_HEIGHT + GAP) + 34;
        graphics.text(font, Lang.component("horsebreeder.appearance.selected", selection.size()),
            startX, bottomY, WHITE, false);
        // 让“全选会把养殖规模推到 targetCount × 外观数”这件事在界面上可见，避免用户意外踩坑。
        int needed = Goal.totalFor(HorseBreederConfig.get(), selection.size());
        graphics.text(font, Lang.component("horsebreeder.appearance.cap", needed),
            startX, bottomY + 12, WHITE, false);
    }

    @Override
    public void onClose() {
        save();
        GameCompat.setScreen(Minecraft.getInstance(), parent);
    }

    private void save() {
        HorseBreederConfig config = HorseBreederConfig.get();
        config.appearanceWhitelist = new LinkedHashSet<>(selection);
        config.save();
    }

    private Component selectionLabel(String key) {
        return Component.literal(selection.contains(key) ? "[x]" : "[ ]");
    }

    private void refreshSelection() {
        cells.forEach((key, button) -> button.setMessage(selectionLabel(key)));
    }
}
