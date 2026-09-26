package com.zhushen.space.data;

/**
 * 武学流派（可兑换的完整武学体系）。
 *
 * 解锁条件：支付对应支线与奖励点数，且前提技能达到要求等级。
 */
public enum SchoolType {
    /**
     * 太极拳：以养气为根基，以相应等级的内力推动才能体现真正威力。
     * 价格 B + 4000，前提肉搏技能等级 2 以上，依赖内力能量池。
     */
    TAI_CHI("tai_chi", 2, 1, 4000, SkillType.BRAWL, 2);

    public static final int COUNT = values().length;

    private final String key;
    /** 支线等级索引（0=S 1=A 2=B 3=C 4=D） */
    private final int branchTier;
    private final int branchCost;
    private final int scoreCost;
    /** 前提技能 */
    private final SkillType reqSkill;
    private final int reqLevel;

    SchoolType(String key, int branchTier, int branchCost, int scoreCost,
               SkillType reqSkill, int reqLevel) {
        this.key = key;
        this.branchTier = branchTier;
        this.branchCost = branchCost;
        this.scoreCost = scoreCost;
        this.reqSkill = reqSkill;
        this.reqLevel = reqLevel;
    }

    public String key() {
        return key;
    }

    public int branchTier() {
        return branchTier;
    }

    public int branchCost() {
        return branchCost;
    }

    public int scoreCost() {
        return scoreCost;
    }

    public SkillType reqSkill() {
        return reqSkill;
    }

    public int reqLevel() {
        return reqLevel;
    }

    public String nameKey() {
        return "school.zhushenspace." + key;
    }

    /** 名称字符串解析（忽略大小写） */
    public static SchoolType parse(String name) {
        for (SchoolType school : values()) {
            if (school.key.equalsIgnoreCase(name) || school.name().equalsIgnoreCase(name)) {
                return school;
            }
        }
        return null;
    }
}
