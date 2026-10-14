package com.horsebreeder.client.config;

/**
 * AGENT NOTES —— 配置的读写实现，含一套“宽容”的错误纠正策略。
 *
 * <p>读取策略（不是直接 Gson.fromJson 到目标类）：先序列化一个<b>默认实例</b>得到 JsonObject 骨架，
 * 再逐键用 {@code checkedValue} 校验、替换。这样做的三个收益：
 * <ul>
 *   <li>新版本新增字段时，老存档自动补默认值，无需迁移代码；</li>
 *   <li>单个字段类型不符/越界只丢弃该字段，不会整体回退到出厂默认；</li>
 *   <li>被拒绝的键会统一汇总到一条 log 里，便于玩家报 bug。</li>
 * </ul>
 * 代价：这套逻辑和 {@code HorseBreederConfig} 的字段声明<b>强耦合</b>，见那里的“三步”。
 *
 * <p>写入用 tempfile + ATOMIC_MOVE（失败回落普通 MOVE）再 {@code deleteIfExists} 兜底，
 * 避免断电/崩溃留下半个 json。
 */
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.horsebreeder.HorseBreeder;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class ConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ConfigStore() {
    }

    static HorseBreederConfig load(Path path) {
        if (!Files.exists(path)) return new HorseBreederConfig();
        try (Reader reader = Files.newBufferedReader(path)) {
            return read(reader);
        } catch (IOException | RuntimeException e) {
            HorseBreeder.LOGGER.warn("[horsebreeder] failed to read config {}, using defaults", path, e);
            return new HorseBreederConfig();
        }
    }

    static HorseBreederConfig read(Reader reader) {
        JsonElement document = JsonParser.parseReader(reader);
        if (!document.isJsonObject()) throw new JsonParseException("Config must be a JSON object");
        JsonObject input = document.getAsJsonObject();
        JsonObject values = GSON.toJsonTree(new HorseBreederConfig()).getAsJsonObject();
        List<String> invalid = new ArrayList<>();

        for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
            String key = entry.getKey();
            if (!input.has(key)) continue;
            JsonElement replacement = checkedValue(key, input.get(key), entry.getValue());
            if (replacement == null) {
                invalid.add(key);
            } else {
                entry.setValue(replacement);
                // 过滤后数组变短说明原配置里有非法元素，标记为 ignored（保留过滤结果）。
                if (replacement.isJsonArray() && replacement.getAsJsonArray().size() != input.getAsJsonArray(key).size()) {
                    invalid.add(key);
                }
            }
        }
        if (!invalid.isEmpty()) {
            HorseBreeder.LOGGER.warn("[horsebreeder] ignored invalid config values: {}", String.join(", ", invalid));
        }
        HorseBreederConfig config = GSON.fromJson(values, HorseBreederConfig.class);
        config.sanitize();
        return config;
    }

    private static JsonElement checkedValue(String key, JsonElement value, JsonElement defaultValue) {
        if (value == null || value.isJsonNull()) return null;
        if (defaultValue.isJsonArray()) {
            if (!value.isJsonArray()) return null;
            JsonArray strings = new JsonArray();
            for (JsonElement element : value.getAsJsonArray()) {
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) strings.add(element);
            }
            return strings;
        }
        if (!value.isJsonPrimitive()) return null;
        JsonPrimitive expected = defaultValue.getAsJsonPrimitive();
        JsonPrimitive actual = value.getAsJsonPrimitive();
        if (expected.isBoolean()) return actual.isBoolean() ? actual : null;
        if (expected.isString()) return actual.isString() ? actual : null;
        if (!actual.isNumber()) return null;
        try {
            if (isIntegerField(key)) {
                return new JsonPrimitive(actual.getAsBigDecimal().intValueExact());
            }
            return Double.isFinite(actual.getAsDouble()) ? actual : null;
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    private static boolean isIntegerField(String key) {
        try {
            return HorseBreederConfig.class.getField(key).getType() == int.class;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("Serialized config field has no public declaration: " + key, e);
        }
    }

    static void save(Path path, HorseBreederConfig config) throws IOException {
        config.sanitize();
        String json = GSON.toJson(config) + System.lineSeparator();
        Path destination = path.toAbsolutePath();
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), destination.getFileName() + ".", ".tmp");
        try {
            Files.writeString(temporary, json);
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
