package com.zhushen.space.data;

import net.minecraft.world.entity.EquipmentSlot;

/**
 * 人物装备位（见 docs/equipment-slots-v1.md）。
 * <pre>
 * 头盔 ×1 · 1 分钟 · 原版头部栏          项链 ×1 · 整轮 · Curios necklace
 * 披风 ×1 · 1 分钟 · Curios back         盔甲（上衣 + 裤子）· 只能穿一件 · 原版胸甲 / 护腿栏 · 无穿脱时间
 * 护腕 ×1 · 整轮 · 需要一对手臂 · Curios bracelet
 * 手套 ×1 · 整轮 · 需要一对手 · Curios hands
 * 腰带 ×1 · 1 分钟 · Curios belt          鞋 ×1 · 1 分钟 · 需要腿 · 原版靴子栏
 * 戒指 ×2 · 整轮 · 一只手一个 · Curios ring
 * 饰品 / 护符 ×1 · 整轮 · Curios charm
 * 概念武装 ×1 · 占据概念武装位与自身装备位 · 只能在短休 / 长休时更换 · 永不损坏 · Curios concept
 * </pre>
 * 1 轮 = 3 秒（60 刻）；「整轮」= 1 轮。除武器、盾牌、戒指、盔甲、插件、可搭载的装置与道具外，穿脱都需要一个标准动作
 * （无法行动时穿脱进度暂停）。
 */
public enum GearSlot {
    HELMET("helmet", EquipmentSlot.HEAD, null, 1, GearSlot.MINUTE, true),
    NECKLACE("necklace", null, "necklace", 1, GearSlot.ROUND, true),
    CLOAK("cloak", null, "back", 1, GearSlot.MINUTE, true),
    ARMOR_TOP("armor_top", EquipmentSlot.CHEST, null, 1, 0, false),
    ARMOR_PANTS("armor_pants", EquipmentSlot.LEGS, null, 1, 0, false),
    BRACERS("bracers", null, "bracelet", 1, GearSlot.ROUND, true),
    GLOVES("gloves", null, "hands", 1, GearSlot.ROUND, true),
    BELT("belt", null, "belt", 1, GearSlot.MINUTE, true),
    BOOTS("boots", EquipmentSlot.FEET, null, 1, GearSlot.MINUTE, true),
    RING("ring", null, "ring", 2, GearSlot.ROUND, false),
    CHARM("charm", null, "charm", 1, GearSlot.ROUND, true),
    CONCEPT("concept", null, "concept", 1, 0, false);

    /** 1 轮 = 3 秒 */
    public static final int ROUND = 60;
    /** 1 分钟 */
    public static final int MINUTE = 1200;

    public final String key;
    /** 原版装备栏（null = Curios 栏位） */
    public final EquipmentSlot vanilla;
    /** Curios 栏位 id（null = 原版装备栏） */
    public final String curio;
    /** 基础数量 */
    public final int base;
    /** 穿 / 脱所需刻数（0 = 立即） */
    public final int ticks;
    /** 穿脱是否需要一个标准动作 */
    public final boolean standard;

    GearSlot(String key, EquipmentSlot vanilla, String curio, int base, int ticks, boolean standard) {
        this.key = key;
        this.vanilla = vanilla;
        this.curio = curio;
        this.base = base;
        this.ticks = ticks;
        this.standard = standard;
    }

    public String nameKey() { return "gear.zhushenspace." + key; }

    public static GearSlot ofCurio(String id) {
        for (GearSlot s : values()) if (s.curio != null && s.curio.equals(id)) return s;
        return null;
    }

    public static GearSlot ofVanilla(EquipmentSlot e) {
        for (GearSlot s : values()) if (s.vanilla == e) return s;
        return null;
    }

    /** 位置键：原版 "v:head" / Curios "c:ring:1" */
    public static String vanillaKey(EquipmentSlot e) { return "v:" + e.getName(); }

    public static String curioKey(String id, int index) { return "c:" + id + ":" + index; }

    /** 由位置键反查装备位 */
    public static GearSlot ofKey(String key) {
        if (key == null) return null;
        if (key.startsWith("v:")) {
            String n = key.substring(2);
            for (EquipmentSlot e : EquipmentSlot.values()) if (e.getName().equals(n)) return ofVanilla(e);
            return null;
        }
        if (key.startsWith("c:")) {
            int i = key.lastIndexOf(':');
            return i > 2 ? ofCurio(key.substring(2, i)) : null;
        }
        return null;
    }
}
