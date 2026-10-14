package com.horsebreeder.client.logic;

/**
 * AGENT NOTES —— 每份存档的 state 文件名。
 *
 * <p>{@code key()} = 类型 + 对 identity 做 nameUUID 哈希，避免把 IP / 绝对路径直接写进文件名。
 * 单人 identity 用的是 world 根目录绝对路径 —— 所以<b>移动/重命名存档目录会导致状态丢失</b>
 * （可接受：这份数据只是冷却时间之类的推算值，丢了最多是重新观察一轮）。
 *
 * <p>没有旧格式兼容：这个 mod 尚未发布，不存在需要迁移的存量存档。
 */
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class WorldIdentity {
    private WorldIdentity() { }

    static String key(String type, String identity) {
        return type + "_" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }
}
