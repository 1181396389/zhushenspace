package com.zhushen.space.data;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * 新手试炼的试用角色卡（服务端应用 / 客户端选择界面共用）。
 * 属性顺序：力量 敏捷 耐力 智力 感知 决心 风度 操作 沉着。试用角色不消耗 XP，离开试炼即还原。
 */
public enum TrialTemplate {
    WARRIOR("warrior", 0xFFE8584A,
            new int[]{4, 3, 4, 2, 3, 2, 1, 1, 2},
            new SkillType[]{SkillType.ATHLETICS, SkillType.BRAWL, SkillType.BLADE}, new int[]{3, 4, 3},
            FeatType.MARTIAL_PRODIGY,
            new ArtSkill[]{ArtSkill.BASIC_PALM}),
    RANGER("ranger", 0xFF5BD46A,
            new int[]{2, 4, 3, 2, 4, 2, 1, 2, 3},
            new SkillType[]{SkillType.ATHLETICS, SkillType.SURVIVAL, SkillType.OCCULTISM, SkillType.BLADE, SkillType.FIREARMS},
            new int[]{4, 3, 3, 2, 3},
            FeatType.CHAKRA_CONSTITUTION,
            new ArtSkill[]{ArtSkill.PHOENIX_FIRE}),
    PSION("psion", 0xFFB070FF,
            new int[]{1, 2, 2, 3, 3, 4, 2, 1, 4},
            new SkillType[]{SkillType.OCCULTISM, SkillType.FEELING, SkillType.ATHLETICS}, new int[]{3, 3, 2},
            FeatType.TELEKINESIS_TALENT,
            new ArtSkill[]{ArtSkill.TK_ATTACK, ArtSkill.TK_MANIP}),
    MAGE("mage", 0xFF58B8FF,
            new int[]{1, 2, 2, 4, 3, 3, 4, 1, 2},
            new SkillType[]{SkillType.OCCULTISM, SkillType.SCIENCE, SkillType.FEELING}, new int[]{4, 2, 2},
            FeatType.MAGIC_CONSTITUTION,
            new ArtSkill[]{ArtSkill.FROST_CLAW, ArtSkill.THUNDER_SWORD, ArtSkill.ILLUMINATION});

    public static final TrialTemplate[] VALUES = values();

    public final String id;
    public final int color;
    public final int[] attrs;
    private final SkillType[] skillTypes;
    private final int[] skillLevels;
    public final FeatType feat;
    public final ArtSkill[] arts;

    TrialTemplate(String id, int color, int[] attrs, SkillType[] st, int[] sl, FeatType feat, ArtSkill[] arts) {
        this.id = id;
        this.color = color;
        this.attrs = attrs;
        this.skillTypes = st;
        this.skillLevels = sl;
        this.feat = feat;
        this.arts = arts;
    }

    public String nameKey() { return "trial.zhushenspace.tpl." + id; }

    /** 角色卡附带的能量池（与 FeatEffects.Pool 的 id 一致） */
    public String poolId() {
        return switch (this) {
            case WARRIOR -> "neili";
            case RANGER -> "chakra";
            case PSION -> "telekinesis";
            case MAGE -> "magic";
        };
    }

    public String roleKey() { return "trial.zhushenspace.tpl." + id + ".role"; }

    public String descKey() { return "trial.zhushenspace.tpl." + id + ".desc"; }

    public int[] skills() {
        int[] s = new int[SkillType.COUNT];
        for (int i = 0; i < skillTypes.length; i++) s[skillTypes[i].ordinal()] = skillLevels[i];
        return s;
    }

    public SkillType[] skillTypes() { return skillTypes; }

    public int[] skillLevels() { return skillLevels; }

    /** 专长掩码：最低等级到最高等级全部点亮 */
    public int[] featMask() {
        int[] m = new int[FeatType.COUNT];
        int v = 0;
        for (int l = feat.minLevel; l <= feat.maxLevel; l++) v |= 1 << l;
        m[feat.ordinal()] = v;
        return m;
    }

    /** 战斗预设第一栏：技艺在前，其后为技能点已解锁的主动能力 */
    public int[] bar(int[] skillPts) {
        List<Integer> ids = new ArrayList<>();
        for (ArtSkill a : arts) ids.add(a.ability.ordinal());
        if (feat == FeatType.MARTIAL_PRODIGY) ids.add(SkillAbility.NEILI_BREATH.ordinal());
        for (SkillAbility a : SkillAbility.values()) {
            if (ids.size() >= 9) break;
            if (a == SkillAbility.GRAPPLE || a.owner() == null) continue;
            if (a.unlockedBy(skillPts) && !ids.contains(a.ordinal())) ids.add(a.ordinal());
        }
        int[] bar = new int[9];
        java.util.Arrays.fill(bar, -1);
        for (int i = 0; i < Math.min(9, ids.size()); i++) bar[i] = ids.get(i);
        return bar;
    }

    /** 试炼装备（离开试炼时随背包一起还原，无法带出） */
    public List<ItemStack> kit() {
        List<ItemStack> l = new ArrayList<>();
        switch (this) {
            case WARRIOR -> l.add(new ItemStack(Items.IRON_SWORD));
            case RANGER -> {
                l.add(new ItemStack(Items.BOW));
                l.add(new ItemStack(Items.STONE_SWORD));
                l.add(new ItemStack(Items.ARROW, 32));
            }
            default -> { }
        }
        l.add(new ItemStack(Items.BREAD, 8));
        return l;
    }

    /** 建卡 XP 形式的属性值（属性 L 需要 1+2+…+(L−1) XP） */
    public int[] attrXp() {
        int[] x = new int[attrs.length];
        for (int i = 0; i < attrs.length; i++) x[i] = attrs[i] * (attrs[i] - 1) / 2;
        return x;
    }
}
