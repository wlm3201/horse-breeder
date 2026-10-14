package com.horsebreeder.client.logic;

/**
 * AGENT NOTES —— 本 tick 马群的一次性快照（all / adults / foals / tamed / untamed + uuid 索引）。
 *
 * <p>tamed 与 untamed <b>只统计成年</b>：幼驹一律归入 {@code foals}。
 * 依赖这个划分的代码（配种、治疗）因此不需要再判 isBaby —— 但淘汰需要自己拼 {@code grown + foals}。
 *
 * <p>{@code byId} 存在的意义：跨 tick 的 UUID（Session 的父母）需要在本 tick 的人群中查回实体，
 * 而实体对象不能跨 tick 保存（可能已被回收），所以一律存 UUID。
 */
import net.minecraft.world.entity.animal.equine.AbstractHorse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class HorsePopulation {
    final List<AbstractHorse> all;
    final List<AbstractHorse> adults = new ArrayList<>();
    final List<AbstractHorse> tamed = new ArrayList<>();
    final List<AbstractHorse> untamed = new ArrayList<>();
    final List<AbstractHorse> foals = new ArrayList<>();
    private final Map<UUID, AbstractHorse> byId = new HashMap<>();

    HorsePopulation(List<AbstractHorse> horses) {
        all = horses;
        for (AbstractHorse horse : horses) {
            byId.put(horse.getUUID(), horse);
            if (horse.isBaby()) {
                foals.add(horse);
            } else {
                adults.add(horse);
                (horse.isTamed() ? tamed : untamed).add(horse);
            }
        }
    }

    AbstractHorse get(UUID id) { return byId.get(id); }
}
