package com.zhushen.space.data;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * 能力 / 现象的等级（D &lt; C &lt; B &lt; BB &lt; BBB &lt; A &lt; AA &lt; AAA &lt; S &lt; SS &lt; SSS）。
 * <p>
 * 来源判定（见 {@code AdaptationManager}）：
 * <ul>
 *   <li>技艺：按其商城支线等级（D / C / B / A / S）；不需要支线的基础技艺视为无等级。</li>
 *   <li>生物：持久化数据 {@code ZsRank}（等级名，如 "BB"）优先；其次实体标签
 *       {@code zhushenspace:rank_above_b}（视为 BB）。原版生物与未标注的生物没有等级。</li>
 *   <li>无等级（null）的伤害 / 能力总是可以被适应。</li>
 * </ul>
 */
public enum PowerRank {
    D, C, B, BB, BBB, A, AA, AAA, S, SS, SSS;

    /** 实体标签：标注为 B 级以上（BB）的生物 */
    public static final TagKey<EntityType<?>> ABOVE_B =
            TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "rank_above_b"));

    /** 持久化数据键（实体 getPersistentData）：等级名 */
    public static final String NBT_KEY = "ZsRank";

    public boolean atMost(PowerRank other) { return ordinal() <= other.ordinal(); }

    public String label() { return name(); }

    /** 能否被 B 级的适应能力适应（无等级 = 可以） */
    public static boolean adaptableByB(PowerRank r) { return r == null || r.atMost(B); }

    public static PowerRank parse(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT)); } catch (IllegalArgumentException e) { return null; }
    }

    /** 商城支线等级序号（0 S · 1 A · 2 B · 3 C · 4 D；负数 = 不需要支线 → 无等级） */
    public static PowerRank ofBranchTier(int tier) {
        return switch (tier) {
            case 0 -> S;
            case 1 -> A;
            case 2 -> B;
            case 3 -> C;
            case 4 -> D;
            default -> null;
        };
    }

    /** 生物本身的等级（玩家 / 原版生物 / 未标注的生物 = null） */
    public static PowerRank ofEntity(Entity e) {
        if (e == null) return null;
        PowerRank r = parse(e.getPersistentData().getString(NBT_KEY));
        if (r != null) return r;
        if (e.getType().is(ABOVE_B)) return BB;
        return null;
    }
}
