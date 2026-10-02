package com.zhushen.space.data;

/**
 * 武器分类（物品分类）。决定攻击判定使用的属性 / 技能、是否需要专业、以及远程规则。
 * <ul>
 *   <li>枪械（均为远程 / 实弹 / 人造）：手枪、冲锋枪、散弹枪、机枪、步枪（含狙击）、炮 —— 敏捷（炮为智力）+ 枪械</li>
 *   <li>白刃（人造近战）：长剑（原版剑）、重剑、突刺剑、扇子 —— 力量 + 白刃</li>
 *   <li>弓（含弩）：敏捷 + 运动，射程上限 8 倍射程单位，必须双手</li>
 *   <li>投掷：敏捷 + 运动，射程上限 = 射程单位 × 力量</li>
 *   <li>未分类人造物品：力量 + 白刃，不需要专业；天生武器（空手）：力量 + 肉搏</li>
 *   <li>基础冷兵器：短棍、长棍、战锤、巨锤、匕首、短剑、弯刀、刀、长刀、斧（白刃组专业）；拳套（肉搏组专业）</li>
 *   <li>专业以位掩码存档（int），分类序号必须 &lt; 32</li>
 * </ul>
 * 注意：按 key 存档（专业选择），新分类可以追加。
 */
public enum WeaponCategory {
    PISTOL(Group.GUN, AttributeType.AGILITY, SkillType.FIREARMS, "pistol"),
    SMG(Group.GUN, AttributeType.AGILITY, SkillType.FIREARMS, "smg"),
    SHOTGUN(Group.GUN, AttributeType.AGILITY, SkillType.FIREARMS, "shotgun"),
    MACHINE_GUN(Group.GUN, AttributeType.AGILITY, SkillType.FIREARMS, "machine_gun"),
    RIFLE(Group.GUN, AttributeType.AGILITY, SkillType.FIREARMS, "rifle"),
    CANNON(Group.GUN, AttributeType.INTELLIGENCE, SkillType.FIREARMS, "cannon"),
    LONGSWORD(Group.BLADE, AttributeType.STRENGTH, SkillType.BLADE, "longsword"),
    GREATSWORD(Group.BLADE, AttributeType.STRENGTH, SkillType.BLADE, "greatsword"),
    RAPIER(Group.BLADE, AttributeType.STRENGTH, SkillType.BLADE, "rapier"),
    FAN(Group.BLADE, AttributeType.STRENGTH, SkillType.BLADE, "fan"),
    BOW(Group.BOW, AttributeType.AGILITY, SkillType.ATHLETICS, "bow"),
    THROWN(Group.THROWN, AttributeType.AGILITY, SkillType.ATHLETICS, "thrown"),
    /** 未分类的人造物品（斧、镐、杂物……）：白刃攻击，无专业要求 */
    GENERIC(Group.BLADE, AttributeType.STRENGTH, SkillType.BLADE, "generic"),
    /** 天生武器（拳头 / 肢体） */
    NATURAL(Group.BRAWL, AttributeType.STRENGTH, SkillType.BRAWL, "natural"),
    // ===== 基础冷兵器模板（见 MeleeWeapon；按 key 存档，追加在末尾） =====
    /** 拳套：【肉搏武器】，攻击视为肉搏攻击（力量 + 肉搏），专业属于肉搏组 */
    KNUCKLE(Group.BRAWL, AttributeType.STRENGTH, SkillType.BRAWL, "knuckle"),
    SHORT_STAFF(Group.BLADE, AttributeType.STRENGTH, SkillType.BLADE, "short_staff"),
    LONG_STAFF(Group.BLADE, AttributeType.STRENGTH, SkillType.BLADE, "long_staff"),
    WAR_HAMMER(Group.BLADE, AttributeType.STRENGTH, SkillType.BLADE, "war_hammer"),
    GREAT_HAMMER(Group.BLADE, AttributeType.STRENGTH, SkillType.BLADE, "great_hammer"),
    DAGGER(Group.BLADE, AttributeType.STRENGTH, SkillType.BLADE, "dagger"),
    SHORT_SWORD(Group.BLADE, AttributeType.STRENGTH, SkillType.BLADE, "short_sword"),
    SCIMITAR(Group.BLADE, AttributeType.STRENGTH, SkillType.BLADE, "scimitar"),
    /** 刀（中国传统大环刀） */
    SABER(Group.BLADE, AttributeType.STRENGTH, SkillType.BLADE, "saber"),
    /** 长刀（青龙偃月刀） */
    GLAIVE(Group.BLADE, AttributeType.STRENGTH, SkillType.BLADE, "glaive"),
    /** 斧（原版斧头也归入此类，见 weapons/axe 标签） */
    AXE(Group.BLADE, AttributeType.STRENGTH, SkillType.BLADE, "axe");

    public enum Group { GUN, BLADE, BOW, THROWN, BRAWL }

    /** 可选专业的技能组：白刃 / 枪械 / 肉搏（技能达到 3、4 点各可选择一个；序号即存档 / 网络中的组号） */
    public enum ProfGroup {
        BLADE(SkillType.BLADE), GUN(SkillType.FIREARMS), BRAWL(SkillType.BRAWL);
        public final SkillType skill;
        ProfGroup(SkillType skill) { this.skill = skill; }
        public static final int COUNT = 3;
    }

    public static final int PROFESSION_LEVEL = 3;
    /** 技能达到 3 与 4 时各免费获得一个专业 */
    public static final int SECOND_PROFESSION_LEVEL = 4;
    /** 通过加点最多获得的专业总数（特殊效果可额外增加） */
    public static final int MAX_PROFESSIONS = 3;

    /** 该组已获得的专业选择次数 */
    public static int earned(int level) {
        return (level >= PROFESSION_LEVEL ? 1 : 0) + (level >= SECOND_PROFESSION_LEVEL ? 1 : 0);
    }

    /** 该组尚可选择的专业数：min(本组获得次数 − 本组已选, 总上限 + 额外 − 全部已选) */
    public static int pending(int level, int groupMask, int allMasksCount, int extra) {
        return Math.max(0, Math.min(earned(level) - Integer.bitCount(groupMask),
                MAX_PROFESSIONS + extra - allMasksCount));
    }
    /** 属性 / 技能前提每差 1 点的器械减值 */
    public static final int REQ_PENALTY = 6;
    /** 没有专业惩罚 */
    public static final int NO_PROFESSION_PENALTY = 9;
    /** 前提差值超过此数则无法使用 */
    public static final int MAX_DEFICIT = 3;
    /** 每超出一个射程单位的距离减值 */
    public static final int RANGE_PENALTY = 6;

    public final Group group;
    public final AttributeType attribute;
    public final SkillType skill;
    public final String key;

    WeaponCategory(Group group, AttributeType attribute, SkillType skill, String key) {
        this.group = group;
        this.attribute = attribute;
        this.skill = skill;
        this.key = key;
    }

    /** 该分类是否需要（可选）专业 */
    public ProfGroup profGroup() {
        if (this == GENERIC || this == NATURAL) return null;
        if (this == KNUCKLE) return ProfGroup.BRAWL;
        return group == Group.GUN ? ProfGroup.GUN : group == Group.BLADE ? ProfGroup.BLADE : null;
    }

    public String nameKey() {
        return "weapon_category.zhushenspace." + key;
    }

    public static WeaponCategory byKey(String key) {
        for (WeaponCategory c : values()) if (c.key.equals(key)) return c;
        return null;
    }

    /** 某专业组可选的分类 */
    public static java.util.List<WeaponCategory> choices(ProfGroup g) {
        java.util.List<WeaponCategory> l = new java.util.ArrayList<>();
        for (WeaponCategory c : values()) if (c.profGroup() == g) l.add(c);
        return l;
    }
}
