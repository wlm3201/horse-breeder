package com.horsebreeder.client.logic;

/**
 * AGENT NOTES —— 无序配对键（UUID 按无符号字典序规范化），用于“某一对马的失败重试计时”。
 *
 * <p>{@code PairKey.of(a, b) == PairKey.of(b, a)} 必须恒成立，否则重试黑名单会失效。
 * 必须用无符号比较：UUID 语义上是无符号量，而 long 是有符号的，直接用 compareTo 会让高位为 1 的 UUID 排序翻转。
 */
import java.util.Objects;
import java.util.UUID;

public record PairKey(UUID low, UUID high) {
    public PairKey {
        Objects.requireNonNull(low, "low");
        Objects.requireNonNull(high, "high");
        if (compare(low, high) > 0) {
            UUID swap = low;
            low = high;
            high = swap;
        }
    }

    public static PairKey of(UUID first, UUID second) {
        return new PairKey(first, second);
    }

    private static int compare(UUID first, UUID second) {
        int most = Long.compareUnsigned(first.getMostSignificantBits(), second.getMostSignificantBits());
        return most != 0 ? most : Long.compareUnsigned(first.getLeastSignificantBits(), second.getLeastSignificantBits());
    }
}
