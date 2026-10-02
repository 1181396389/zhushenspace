package com.zhushen.space.data;

import com.zhushen.space.data.PlayerHealthData.Severity;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;
import java.util.Set;

/**
 * 基础冷兵器模板（主神商城：每件 {@link #PRICE} 奖励点数）。规则见 docs/cold-weapons-v1.md。
 * <p>
 * 数值即模板原文：武器伤害 + 伤势等级（L = 严重，B = 冲击）+ 伤害类型 + 破甲；体积为模板原文，
 * 重量（公斤，负重用）按实物估算，【沉重】的翻倍已计入。
 * <p>
 * 本类只放数据（客户端 / 服务端共用）；攻击结算见 CombatFormula / WeaponRules，物品见 ZsWeaponItem。
 */
public enum MeleeWeapon {
    /** 手指虎：提升双拳天生武器 1L；【肉搏武器】；钝击武器 */
    KNUCKLE("knuckle", WeaponCategory.KNUCKLE, 1, 0.3f, 1, Severity.L, DamageKind.BLUNT, 0, -1.0f, 0,
            EnumSet.of(Trait.BRAWL_WEAPON, Trait.BLUNT_ONLY)),
    /** 短棍：2L 钝击；【眩晕】；冲击武器 */
    SHORT_STAFF("short_staff", WeaponCategory.SHORT_STAFF, 2, 1.0f, 2, Severity.L, DamageKind.BLUNT, 0, -2.2f, 0,
            EnumSet.of(Trait.STUN, Trait.IMPACT)),
    /** 长棍：2L 钝击；【长柄武器】【沉重】【双手】；冲击武器 */
    LONG_STAFF("long_staff", WeaponCategory.LONG_STAFF, 4, 2.5f, 2, Severity.L, DamageKind.BLUNT, 0, -2.9f, 0,
            EnumSet.of(Trait.REACH, Trait.HEAVY, Trait.TWO_HANDED, Trait.IMPACT)),
    /** 战锤：2L 钝击；【威猛】 */
    WAR_HAMMER("war_hammer", WeaponCategory.WAR_HAMMER, 2, 2.0f, 2, Severity.L, DamageKind.BLUNT, 0, -2.8f, 0,
            EnumSet.of(Trait.MIGHTY)),
    /** 巨锤：2L 破甲 1 钝击；【威猛】【沉重】【双手】；重武器 */
    GREAT_HAMMER("great_hammer", WeaponCategory.GREAT_HAMMER, 4, 8.0f, 2, Severity.L, DamageKind.BLUNT, 1, -3.2f, 0,
            EnumSet.of(Trait.MIGHTY, Trait.HEAVY, Trait.TWO_HANDED, Trait.HEAVY_WEAPON)),
    /** 匕首：1L 穿刺，基本投掷射程 10 米；【轻投掷武器】；轻型武器 */
    DAGGER("dagger", WeaponCategory.DAGGER, 1, 0.5f, 1, Severity.L, DamageKind.PIERCE, 0, -1.6f, 10,
            EnumSet.of(Trait.LIGHT_THROWN, Trait.LIGHT_WEAPON));

    /** 模板价格（奖励点数） */
    public static final int PRICE = 200;

    /** 武器关键词（【】）与模板特殊属性（special = true） */
    public enum Trait {
        BRAWL_WEAPON("brawl_weapon", false),
        STUN("stun", false),
        REACH("reach", false),
        HEAVY("heavy", false),
        TWO_HANDED("two_handed", false),
        MIGHTY("mighty", false),
        LIGHT_THROWN("light_thrown", false),
        /** 钝击武器（手指虎） */
        BLUNT_ONLY("blunt_only", true),
        /** 冲击武器：可选择造成冲击伤害 */
        IMPACT("impact", true),
        /** 重武器：−6 器械减值，然后 +2 附加成功 */
        HEAVY_WEAPON("heavy_weapon", true),
        /** 轻型武器：近战白刃攻击可用敏捷代替力量 */
        LIGHT_WEAPON("light_weapon", true);

        public final String key;
        public final boolean special;

        Trait(String key, boolean special) {
            this.key = key;
            this.special = special;
        }

        public String nameKey() { return "weapon_trait.zhushenspace." + key; }

        public String descKey() { return "weapon_trait.zhushenspace." + key + ".desc"; }
    }

    public final String key;
    public final WeaponCategory category;
    /** 体积（模板原文） */
    public final int volume;
    /** 重量（公斤） */
    public final float weight;
    /** 武器伤害（手指虎：天生武器伤害的提升值） */
    public final int damage;
    public final Severity severity;
    public final DamageKind kind;
    public final int armorPierce;
    /** 原版攻击速度修饰（基础 4.0 之上） */
    public final float attackSpeed;
    /** 基本投掷射程（米），0 = 不能投掷 */
    public final int throwRange;
    private final Set<Trait> traits;

    MeleeWeapon(String key, WeaponCategory category, int volume, float weight, int damage, Severity severity,
                DamageKind kind, int armorPierce, float attackSpeed, int throwRange, Set<Trait> traits) {
        this.key = key;
        this.category = category;
        this.volume = volume;
        this.weight = weight;
        this.damage = damage;
        this.severity = severity;
        this.kind = kind;
        this.armorPierce = armorPierce;
        this.attackSpeed = attackSpeed;
        this.throwRange = throwRange;
        this.traits = traits;
    }

    public static final int COUNT = values().length;

    public boolean has(Trait t) { return traits.contains(t); }

    public Set<Trait> traits() { return traits; }

    /** 原版攻击伤害修饰：总值 = 1（空手）+ 修饰 = 武器伤害；手指虎为天生武器 1 + 提升值 */
    public double attackModifier() { return this == KNUCKLE ? damage : damage - 1; }

    /** 【长柄武器】：触及范围 +2 米 */
    public double reachBonus() { return has(Trait.REACH) ? 2.0 : 0.0; }

    public String nameKey() { return "item.zhushenspace." + key; }

    public String descKey() { return "item.zhushenspace." + key + ".desc"; }

    /** 物品堆叠对应的模板（非本模组冷兵器 = null） */
    public static MeleeWeapon of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return stack.getItem() instanceof com.zhushen.space.item.ZsWeaponItem w ? w.weapon() : null;
    }

    /** 冲击武器当前是否选择造成冲击伤害（物品自定义数据 ZsImpact） */
    public static boolean impactMode(ItemStack stack) {
        var data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBoolean("ZsImpact");
    }

    /** 本次攻击的伤势等级：冲击武器选择冲击伤害时为 B */
    public Severity severityFor(ItemStack stack) {
        return has(Trait.IMPACT) && impactMode(stack) ? Severity.B : severity;
    }
}
