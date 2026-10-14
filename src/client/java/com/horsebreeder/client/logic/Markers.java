package com.horsebreeder.client.logic;

/**
 * AGENT NOTES —— 发光标记的 ARGB 颜色常量。
 *
 * <p>{@code NONE == 0} 被当作哨兵值：两个 mixin（EntityGlowColorMixin / MinecraftGlowMixin）
 * 都以 {@code == 0} 判断“这匹马没有被标记”，所以任何角色色都<b>不能</b>是纯黑 0x000000。
 *
 * <p>具体含义与优先级见 HorseDisplay#markerColor —— 颜色的语义由那边决定，这里只是调色板。
 */
public final class Markers {
    public static final int NONE = 0;

    public static final int READY = 0x55FF55;
    public static final int COOLING = 0xFF5555;
    public static final int BABY = 0x55FFFF;
    public static final int IN_LOVE = 0xFF55FF;
    public static final int BLOCKED = 0xAAAAAA;

    public static final int NEXT_PAIR = 0xAA00AA;
    public static final int CULL_CANDIDATE = 0xFFAA00;
    public static final int FINISHED = 0xFFD700;

    public static final int ACTING = 0xFFFF00;

    private Markers() {
    }
}
