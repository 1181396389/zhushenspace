package com.zhushen.space.data;

import java.util.EnumSet;
import java.util.Set;

/**
 * 伤害类型。一次伤害可以是多种类型的混合（EnumSet）。
 * <pre>
 * 物理（钝击 / 挥砍 / 穿刺）：受硬度、物理伤害减免
 * 能量：纯能量 / 火焰 / 寒冰 / 雷电 / 腐蚀 / 光明 / 黑暗：硬度抵消后，对生物造成余量全部（对物体 1/2）；受能量抗力，不受物理减免
 *       音波 / 光能：无视硬度
 * 精神：无视硬度、伤害减免、物品的伤害降低（对物体无效）
 * 力场：无视硬度、伤害减免（不是物理也不是能量）
 * 毒素：同精神
 * </pre>
 */
public enum DamageKind {
    BLUNT(Family.PHYSICAL, "blunt"),
    SLASH(Family.PHYSICAL, "slash"),
    PIERCE(Family.PHYSICAL, "pierce"),
    PURE_ENERGY(Family.ENERGY, "pure_energy"),
    FIRE(Family.ENERGY, "fire"),
    COLD(Family.ENERGY, "cold"),
    LIGHTNING(Family.ENERGY, "lightning"),
    ACID(Family.ENERGY, "acid"),
    HOLY(Family.ENERGY, "holy"),
    UNHOLY(Family.ENERGY, "unholy"),
    SONIC(Family.ENERGY, "sonic"),
    LIGHT(Family.ENERGY, "light"),
    PSYCHIC(Family.PSYCHIC, "psychic"),
    FORCE(Family.FORCE, "force"),
    TOXIN(Family.TOXIN, "toxin");

    public enum Family { PHYSICAL, ENERGY, PSYCHIC, FORCE, TOXIN }

    public final Family family;
    public final String key;

    DamageKind(Family family, String key) { this.family = family; this.key = key; }

    public boolean physical() { return family == Family.PHYSICAL; }

    public boolean energy() { return family == Family.ENERGY; }

    /** 是否无视硬度 */
    public boolean ignoresHardness() {
        return this == SONIC || this == LIGHT || family == Family.PSYCHIC || family == Family.FORCE || family == Family.TOXIN;
    }

    /** 是否无视伤害减免（物理减免 / 能量抗力） */
    public boolean ignoresReduction() {
        return family == Family.PSYCHIC || family == Family.FORCE || family == Family.TOXIN;
    }

    /** 是否无视物品带来的伤害降低（护甲 / 附魔 / 物品的吸收、忽略、抵消、减免与降级） */
    public boolean ignoresItems() {
        return family == Family.PSYCHIC || family == Family.TOXIN;
    }

    /** 对物体（方块 / 载具）时的伤害系数：能量（除音波光能）1/2，精神 / 毒素 0 */
    public float objectFactor() {
        if (family == Family.PSYCHIC || family == Family.TOXIN) return 0f;
        if (energy() && !ignoresHardness()) return 0.5f;
        return 1f;
    }

    public String nameKey() { return "damage_kind.zhushenspace." + key; }

    public static Set<DamageKind> of(DamageKind first, DamageKind... rest) { return EnumSet.of(first, rest); }
}
