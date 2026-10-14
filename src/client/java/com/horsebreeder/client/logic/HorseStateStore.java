package com.horsebreeder.client.logic;

/**
 * AGENT NOTES —— HorseStates 的持久化格式（每份存档一个 json）。
 *
 * <p>格式是<b>列式</b>的（顶层 key 是字段名 cooldown / baby / temper ...，value 是 uuid→tick 的映射）
 * 而不是行式数组，
 * 目的是大部分马其实只带一两个非零字段，列 group 天然稀疏，文件很小。
 * 代价：新增一个字段必须同时改写 {@code write} 的 group 列表<b>和</b> {@code readFile} 的 restore 列表，
 * 否则该字段会静默丢失。
 *
 * <p>容错策略：<b>读不懂就当文件不存在</b> —— 直接删掉它，本 mod 从空状态重新开始，
 * 下次落盘用新值覆盖一份干净的。不做备份、不做旧格式兼容：这个 mod 还没发布，
 * 没有需要保护的存量数据，多一层容错就多一份永远没人走到的代码。
 * UUID 键值仍会校验规范性（防止手写垃圾键塞进内存）。
 */
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.horsebreeder.HorseBreeder;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ObjIntConsumer;

final class HorseStateStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    record SavedHorse(int cooldown, int doubt, int baby, int temper, int love) { }

    private HorseStateStore() { }

    /** 所有持久化字段的组名。新增字段必须同时出现在 {@code restore} 的调用列表里，否则会静默丢失。 */
    private static final List<String> GROUPS = List.of("cooldown", "doubt", "baby", "temper", "love");

    /**
     * 读取存档状态。文件不存在 → 空；文件存在但读不懂（损坏 / 旧格式 / 手改坏了）→
     * 记一条日志、删掉文件、返回空。调用方不需要 try/catch。
     */
    static Map<UUID, SavedHorse> read(Path path) {
        if (!Files.isRegularFile(path)) return Map.of();
        try {
            return readFile(path);
        } catch (IOException | RuntimeException e) {
            HorseBreeder.LOGGER.warn("[horsebreeder] unreadable horse state {}, discarding it", path, e);
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
            }
            return Map.of();
        }
    }

    static void write(Path path, Map<UUID, SavedHorse> horses) throws IOException {
        Map<String, Map<String, Integer>> data = new LinkedHashMap<>();
        for (String group : GROUPS) {
            data.put(group, new LinkedHashMap<>());
        }
        horses.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(row -> {
            String uuid = row.getKey().toString();
            SavedHorse horse = row.getValue();
            putPositive(data.get("cooldown"), uuid, horse.cooldown());
            putPositive(data.get("doubt"), uuid, horse.doubt());
            putPositive(data.get("baby"), uuid, horse.baby());
            putPositive(data.get("temper"), uuid, horse.temper());
            putPositive(data.get("love"), uuid, horse.love());
        });

        writeAtomically(path, GSON.toJson(data));
    }

    private static Map<UUID, SavedHorse> readFile(Path path) throws IOException {
        JsonObject data;
        try {
            JsonElement json = JsonParser.parseString(Files.readString(path));
            if (!json.isJsonObject()) throw new IOException("Horse state must be a JSON object: " + path);
            data = json.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Invalid horse state JSON: " + path, e);
        }

        Map<UUID, MutableHorse> restored = new HashMap<>();
        restore(data, "cooldown", HorseStates.BREED_COOLDOWN_TICKS, restored, (horse, value) -> horse.cooldown = value);
        restore(data, "doubt", HorseStates.BREED_COOLDOWN_TICKS, restored, (horse, value) -> horse.doubt = value);
        restore(data, "baby", -HorseStates.BABY_START_AGE, restored, (horse, value) -> horse.baby = value);
        restore(data, "temper", HorseStates.TEMPER_MAX, restored, (horse, value) -> horse.temper = value);
        restore(data, "love", HorseStates.LOVE_TICKS, restored, (horse, value) -> horse.love = value);
        Map<UUID, SavedHorse> result = new HashMap<>();
        restored.forEach((uuid, horse) -> result.put(uuid, horse.snapshot()));
        return Map.copyOf(result);
    }

    private static void restore(JsonObject root, String name, int maximum,
                                Map<UUID, MutableHorse> result, ObjIntConsumer<MutableHorse> apply) {
        JsonElement group = root.get(name);
        if (group == null || !group.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> row : group.getAsJsonObject().entrySet()) {
            try {
                UUID uuid = UUID.fromString(row.getKey());
                if (!uuid.toString().equalsIgnoreCase(row.getKey())) continue;
                JsonElement value = row.getValue();
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) continue;
                int ticks = value.getAsBigDecimal().intValueExact();
                if (ticks <= 0) continue;
                apply.accept(result.computeIfAbsent(uuid, ignored -> new MutableHorse()), Math.min(maximum, ticks));
            } catch (IllegalArgumentException | ArithmeticException ignored) {
            }
        }
    }

    private static void putPositive(Map<String, Integer> group, String uuid, int value) {
        if (value > 0) group.put(uuid, value);
    }

    private static void writeAtomically(Path path, String contents) throws IOException {
        Path target = path.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "horse-state-", ".tmp");
        try {
            Files.writeString(temporary, contents);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            moveIntoPlace(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveIntoPlace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static final class MutableHorse {
        int cooldown;
        int doubt;
        int baby;
        int temper;
        int love;

        SavedHorse snapshot() {
            return new SavedHorse(cooldown, doubt, baby, temper, love);
        }
    }
}
