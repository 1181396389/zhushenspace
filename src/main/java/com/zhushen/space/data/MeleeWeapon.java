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
    KNUCKLE("knuckle", WeaponCategory.KNUCKLE, 1, 0.3f, 1, Severity.L, K(DamageKind.BLUNT), 0, -1.0f, 0,
            EnumSet.of(Trait.BRAWL_WEAPON, Trait.BLUNT_ONLY)),
    /** 短棍：2L 钝击；【眩晕】；冲击武器 */
    SHORT_STAFF("short_staff", WeaponCategory.SHORT_STAFF, 2, 1.0f, 2, Severity.L, K(DamageKind.BLUNT), 0, -2.2f, 0,
            EnumSet.of(Trait.STUN, Trait.IMPACT)),
    /** 长棍：2L 钝击；【长柄武器】【沉重】【双手】；冲击武器 */
    LONG_STAFF("long_staff", WeaponCategory.LONG_STAFF, 4, 2.5f, 2, Severity.L, K(DamageKind.BLUNT), 0, -2.9f, 0,
            EnumSet.of(Trait.REACH, Trait.HEAVY, Trait.TWO_HANDED, Trait.IMPACT)),
    /** 战锤：2L 钝击；【威猛】 */
    WAR_HAMMER("war_hammer", WeaponCategory.WAR_HAMMER, 2, 2.0f, 2, Severity.L, K(DamageKind.BLUNT), 0, -2.8f, 0,
            EnumSet.of(Trait.MIGHTY)),
    /** 巨锤：2L 破甲 1 钝击；【威猛】【沉重】【双手】；重武器 */
    GREAT_HAMMER("great_hammer", WeaponCategory.GREAT_HAMMER, 4, 8.0f, 2, Severity.L, K(DamageKind.BLUNT), 1, -3.2f, 0,
            EnumSet.of(Trait.MIGHTY, Trait.HEAVY, Trait.TWO_HANDED, Trait.HEAVY_WEAPON)),
    /** 匕首：1L 穿刺，基本投掷射程 10 米；【轻投掷武器】；轻型武器 */
    DAGGER("dagger", WeaponCategory.DAGGER, 1, 0.5f, 1, Severity.L, K(DamageKind.PIERCE), 0, -1.6f, 10,
            EnumSet.of(Trait.LIGHT_THROWN, Trait.LIGHT_WEAPON)),
    /** 短剑：1L 破甲 1 穿刺；轻型武器 */
    SHORT_SWORD("short_sword", WeaponCategory.SHORT_SWORD, 2, 1.0f, 1, Severity.L, K(DamageKind.PIERCE), 1, -1.8f, 0,
            EnumSet.of(Trait.LIGHT_WEAPON)),
    /** 长剑：2L 破甲 1，穿刺或挥砍 */
    LONGSWORD("longsword", WeaponCategory.LONGSWORD, 3, 1.5f, 2, Severity.L, K(DamageKind.PIERCE, DamageKind.SLASH), 1, -2.4f, 0,
            EnumSet.noneOf(Trait.class)),
    /** 重剑（巨剑）：2L，钝击或挥砍；【威猛】【沉重】【双手】 */
    GREATSWORD("greatsword", WeaponCategory.GREATSWORD, 4, 6.0f, 2, Severity.L, K(DamageKind.BLUNT, DamageKind.SLASH), 0, -3.0f, 0,
            EnumSet.of(Trait.MIGHTY, Trait.HEAVY, Trait.TWO_HANDED)),
    /** 弯刀：1L 破甲 1 挥砍；轻型武器 */
    SCIMITAR("scimitar", WeaponCategory.SCIMITAR, 3, 1.5f, 1, Severity.L, K(DamageKind.SLASH), 1, -2.0f, 0,
            EnumSet.of(Trait.LIGHT_WEAPON)),
    /** 刀（大环刀）：2L 挥砍；【威猛】 */
    SABER("saber", WeaponCategory.SABER, 3, 2.0f, 2, Severity.L, K(DamageKind.SLASH), 0, -2.5f, 0,
            EnumSet.of(Trait.MIGHTY)),
    /** 长刀（青龙偃月刀）：1L 挥砍；【威猛】【长柄武器】【沉重】【双手】；重武器 */
    GLAIVE("glaive", WeaponCategory.GLAIVE, 4, 10.0f, 1, Severity.L, K(DamageKind.SLASH), 0, -3.2f, 0,
            EnumSet.of(Trait.MIGHTY, Trait.REACH, Trait.HEAVY, Trait.TWO_HANDED, Trait.HEAVY_WEAPON)),
    /** 飞斧：2L 挥砍，基本投掷射程 10 米；【重投掷武器】 */
    THROWING_AXE("throwing_axe", WeaponCategory.AXE, 2, 1.0f, 2, Severity.L, K(DamageKind.SLASH), 0, -2.6f, 10,
            EnumSet.of(Trait.HEAVY_THROWN)),
    /** 飞锤（飞斧的锤形）：2L 钝击，基本投掷射程 10 米；【重投掷武器】 */
    THROWING_HAMMER("throwing_hammer", WeaponCategory.WAR_HAMMER, 2, 1.0f, 2, Severity.L, K(DamageKind.BLUNT), 0, -2.6f, 10,
            EnumSet.of(Trait.HEAVY_THROWN)),
    /** 战斧：2L 破甲 2 挥砍 */
    BATTLE_AXE("battle_axe", WeaponCategory.AXE, 3, 2.0f, 2, Severity.L, K(DamageKind.SLASH), 2, -2.9f, 0,
            EnumSet.noneOf(Trait.class)),
    /** 短矛：2L 穿刺，基本投掷射程 10 米；【重投掷武器】 */
    SHORT_SPEAR("short_spear", WeaponCategory.SHORT_SPEAR, 2, 1.0f, 2, Severity.L, K(DamageKind.PIERCE), 0, -2.4f, 10,
            EnumSet.of(Trait.HEAVY_THROWN)),
    /** 长矛：2L 破甲 1 穿刺；【长柄武器】【沉重】【双手】 */
    LONG_SPEAR("long_spear", WeaponCategory.LONG_SPEAR, 4, 9.0f, 2, Severity.L, K(DamageKind.PIERCE), 1, -2.9f, 0,
            EnumSet.of(Trait.REACH, Trait.HEAVY, Trait.TWO_HANDED)),
    /** 戟（方天画戟）：2L，挥砍或穿刺；【威猛】【沉重】【双手】；重武器 */
    HALBERD("halberd", WeaponCategory.LONG_SPEAR, 4, 10.0f, 2, Severity.L, K(DamageKind.SLASH, DamageKind.PIERCE), 0, -3.2f, 0,
            EnumSet.of(Trait.MIGHTY, Trait.HEAVY, Trait.TWO_HANDED, Trait.HEAVY_WEAPON)),
    /** 鞭子：1L 穿刺；【软兵器】；剧痛 */
    WHIP("whip", WeaponCategory.WHIP, 4, 1.5f, 1, Severity.L, K(DamageKind.PIERCE), 0, -2.0f, 0,
            EnumSet.of(Trait.SOFT, Trait.AGONY)),
    /** 镰刀（科技本质）：2L 破甲 2 挥砍 */
    SICKLE("sickle", WeaponCategory.SICKLE, 2, 2.0f, 2, Severity.L, K(DamageKind.SLASH), 2, -2.4f, 0,
            EnumSet.of(Trait.TECH_ESSENCE)),
    /** 巨镰（科技本质）：1L 挥砍；【威猛】【长柄武器】【沉重】【双手】；重武器 */
    SCYTHE("scythe", WeaponCategory.SICKLE, 5, 10.0f, 1, Severity.L, K(DamageKind.SLASH), 0, -3.2f, 0,
            EnumSet.of(Trait.MIGHTY, Trait.REACH, Trait.HEAVY, Trait.TWO_HANDED, Trait.HEAVY_WEAPON, Trait.TECH_ESSENCE)),
    /** 重弩：2L 破甲 2 穿刺，基本射程 20 米；【沉重】【双手】；装填；弹药（弩矢） */
    HEAVY_CROSSBOW("heavy_crossbow", WeaponCategory.CROSSBOW, 4, 9.0f, 2, Severity.L, K(DamageKind.PIERCE), 2, 0f, 20,
            EnumSet.of(Trait.HEAVY, Trait.TWO_HANDED, Trait.RELOAD, Trait.AMMO)),
    /** 轻弩（十字弓）：2L 破甲 1 穿刺，基本射程 20 米；【双手】；装填；弹药（弩矢） */
    LIGHT_CROSSBOW("light_crossbow", WeaponCategory.CROSSBOW, 3, 2.5f, 2, Severity.L, K(DamageKind.PIERCE), 1, 0f, 20,
            EnumSet.of(Trait.TWO_HANDED, Trait.RELOAD, Trait.AMMO)),
    /** 手弩：2L 穿刺，基本射程 20 米；装填（需要一只空手）；弹药（弩矢） */
    HAND_CROSSBOW("hand_crossbow", WeaponCategory.CROSSBOW, 1, 2.5f, 2, Severity.L, K(DamageKind.PIERCE), 0, 0f, 20,
            EnumSet.of(Trait.RELOAD_HAND, Trait.AMMO)),
    /** 弓箭：前提力量 3；2L 穿刺，基本射程 20 米；弹药（箭矢） */
    BOW("bow", WeaponCategory.BOW, 3, 1.0f, 2, Severity.L, K(DamageKind.PIERCE), 0, 0f, 20,
            EnumSet.of(Trait.ARROWS), 3),
    /** 飞针（暗器）：1L 穿刺，基本射程 10 米；【轻投掷武器】【暗器】；消耗品 */
    FLYING_NEEDLE("flying_needle", WeaponCategory.HIDDEN_WEAPON, 0, 0.05f, 1, Severity.L, K(DamageKind.PIERCE), 0, 0f, 10,
            EnumSet.of(Trait.LIGHT_THROWN, Trait.HIDDEN)),
    /** 飞镖（暗器）：1L 穿刺，基本射程 10 米；【轻投掷武器】【暗器】；消耗品 */
    DART("dart", WeaponCategory.HIDDEN_WEAPON, 0, 0.05f, 1, Severity.L, K(DamageKind.PIERCE), 0, 0f, 10,
            EnumSet.of(Trait.LIGHT_THROWN, Trait.HIDDEN)),
    /** 手里剑（暗器）：1L 穿刺，基本射程 10 米；【轻投掷武器】【暗器】；消耗品 */
    SHURIKEN("shuriken", WeaponCategory.HIDDEN_WEAPON, 0, 0.05f, 1, Severity.L, K(DamageKind.PIERCE), 0, 0f, 10,
            EnumSet.of(Trait.LIGHT_THROWN, Trait.HIDDEN)),
    /** 铁蒺藜（暗器）：1L 穿刺，基本射程 10 米；【轻投掷武器】【暗器】；消耗品 */
    CALTROP("caltrop", WeaponCategory.HIDDEN_WEAPON, 0, 0.05f, 1, Severity.L, K(DamageKind.PIERCE), 0, 0f, 10,
            EnumSet.of(Trait.LIGHT_THROWN, Trait.HIDDEN)),
    /** 铜钱镖（暗器）：1L 穿刺，基本射程 10 米；【轻投掷武器】【暗器】；消耗品 */
    COIN_DART("coin_dart", WeaponCategory.HIDDEN_WEAPON, 0, 0.05f, 1, Severity.L, K(DamageKind.PIERCE), 0, 0f, 10,
            EnumSet.of(Trait.LIGHT_THROWN, Trait.HIDDEN)),
    /** 细针（暗器）：1L 穿刺，基本射程 10 米；【轻投掷武器】【暗器】；消耗品 */
    FINE_NEEDLE("fine_needle", WeaponCategory.HIDDEN_WEAPON, 0, 0.05f, 1, Severity.L, K(DamageKind.PIERCE), 0, 0f, 10,
            EnumSet.of(Trait.LIGHT_THROWN, Trait.HIDDEN));

    private static DamageKind[] K(DamageKind... k) { return k; }

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
        HEAVY_THROWN("heavy_thrown", false),
        SOFT("soft", false),
        /** 钝击武器（手指虎） */
        BLUNT_ONLY("blunt_only", true),
        /** 冲击武器：可选择造成冲击伤害 */
        IMPACT("impact", true),
        /** 重武器：−6 器械减值，然后 +2 附加成功 */
        HEAVY_WEAPON("heavy_weapon", true),
        /** 轻型武器：近战白刃攻击可用敏捷代替力量 */
        LIGHT_WEAPON("light_weapon", true),
        /** 剧痛：最终伤害超过目标耐力 → 武器伤害点剧痛 */
        AGONY("agony", true),
        /** 本质：科技本质（说明用） */
        TECH_ESSENCE("tech_essence", true),
        /** 装填：每次发射需要一个移动动作 */
        RELOAD("reload", true),
        /** 弹药：弩矢 */
        AMMO("ammo", true),
        /** 装填（手弩）：每次发射需要一个移动动作，并需要一只空手 */
        RELOAD_HAND("reload_hand", true),
        /** 弹药：箭矢（弓箭） */
        ARROWS("arrows", true),
        /** 【暗器】：必然是轻投掷武器；目标感知检定 DC 2 失败则对本次攻击措手不及 */
        HIDDEN("hidden", false);

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
    /** 主要伤害类型（= kinds[0]） */
    public final DamageKind kind;
    /** 可选择的伤害类型（如长剑：穿刺或挥砍） */
    public final DamageKind[] kinds;
    public final int armorPierce;
    /** 原版攻击速度修饰（基础 4.0 之上） */
    public final float attackSpeed;
    /** 前提：需求力量（0 = 无） */
    public final int strReq;
    /** 基本投掷射程（米），0 = 不能投掷；弩为基本射程 */
    public final int throwRange;
    private final Set<Trait> traits;

    MeleeWeapon(String key, WeaponCategory category, int volume, float weight, int damage, Severity severity,
                DamageKind[] kinds, int armorPierce, float attackSpeed, int throwRange, Set<Trait> traits) {
        this(key, category, volume, weight, damage, severity, kinds, armorPierce, attackSpeed, throwRange, traits, 0);
    }

    MeleeWeapon(String key, WeaponCategory category, int volume, float weight, int damage, Severity severity,
                DamageKind[] kinds, int armorPierce, float attackSpeed, int throwRange, Set<Trait> traits, int strReq) {
        this.strReq = strReq;
        this.key = key;
        this.category = category;
        this.volume = volume;
        this.weight = weight;
        this.damage = damage;
        this.severity = severity;
        this.kind = kinds[0];
        this.kinds = kinds;
        this.armorPierce = armorPierce;
        this.attackSpeed = attackSpeed;
        this.throwRange = throwRange;
        this.traits = traits;
    }

    public static final int COUNT = values().length;

    public boolean has(Trait t) { return traits.contains(t); }

    /** 远程武器（弓 / 弩）：不参与近战公式 */
    public boolean ranged() { return category.group == WeaponCategory.Group.BOW; }

    /** 暗器：消耗品，只能投掷（拿在手里打人视为普通物品） */
    public boolean hidden() { return category == WeaponCategory.HIDDEN_WEAPON; }

    /** 商城价格（奖励点数）：暗器 100 点一次 1000 个，其余每件 PRICE */
    public int price() { return hidden() ? 100 : PRICE; }

    /** 商城一次购买的数量 */
    public int shopCount() { return hidden() ? 1000 : 1; }

    /** 可以投掷（右键蓄力投出） */
    public boolean throwable() { return throwRange > 0 && !ranged(); }

    /** 投出后首尾翻滚（斧 / 锤），其余沿轴滚转 */
    public boolean tumbles() { return category == WeaponCategory.AXE || category == WeaponCategory.WAR_HAMMER; }

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
        return stack.getItem() instanceof ZsWeapon w ? w.weapon() : null;
    }

    /** 手持物对应的近战模板（弩等远程武器 = null） */
    public static MeleeWeapon melee(ItemStack stack) {
        MeleeWeapon w = of(stack);
        return w != null && !w.ranged() && !w.hidden() ? w : null;
    }

    // ===== 攻击方式（潜行 + 右键切换）：伤害类型 × 伤势等级（冲击武器可选 B） =====

    /** 可切换的攻击方式数（1 = 不可切换） */
    public int modeCount() { return kinds.length * (has(Trait.IMPACT) ? 2 : 1); }

    /** 物品当前的攻击方式序号（自定义数据 ZsMode；旧版 ZsImpact = true 视为 1） */
    public int mode(ItemStack stack) {
        var data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (data == null) return 0;
        var tag = data.copyTag();
        int m = tag.contains("ZsMode") ? tag.getInt("ZsMode") : tag.getBoolean("ZsImpact") ? 1 : 0;
        return Math.floorMod(m, modeCount());
    }

    public DamageKind kindOf(int mode) { return kinds[has(Trait.IMPACT) ? mode / 2 : mode]; }

    public Severity severityOf(int mode) { return has(Trait.IMPACT) && mode % 2 == 1 ? Severity.B : severity; }

    /** 本次攻击的伤害类型 */
    public DamageKind kindFor(ItemStack stack) { return kindOf(mode(stack)); }

    /** 本次攻击的伤势等级：冲击武器选择冲击伤害时为 B */
    public Severity severityFor(ItemStack stack) { return severityOf(mode(stack)); }
}
