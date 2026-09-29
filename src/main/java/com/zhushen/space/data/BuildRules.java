package com.zhushen.space.data;

/**
 * 建卡 XP 规则（属性 / 技能 / 专长共享同一 XP 池）。
 * <ul>
 *   <li>总 XP 70；属性 9~30、技能 15~45、专长 ≥15（仅建卡时校验）。</li>
 *   <li>属性基础 1，上限 5；当前值 ×1 XP 升 1 点（1+2+3+4 = 10），可投入零散 XP，小数保留。</li>
 *   <li>技能上限 15；0→1 需 1 XP，其余每级需 当前等级 ×2 XP；建卡时技能上限 4（特殊身份 1 指定技能为 5，且 4→5 仅需 2 XP）。</li>
 * </ul>
 */
public final class BuildRules {
    private BuildRules() {}

    public static final int TOTAL_XP = 70;
    public static final int ATTR_MIN = 9, ATTR_MAX = 30;
    public static final int SKILL_MIN = 15, SKILL_MAX = 45;
    public static final int FEAT_MIN = 15;

    public static final int ATTR_BASE = 1, ATTR_CAP = 5;
    /** 单项属性从 1 到 5 所需 XP */
    public static final int ATTR_MAX_XP = 10;
    public static final int SKILL_CAP = 15;
    public static final int CREATION_SKILL_CAP = 4;

    /** 投入 xp 后的属性整数值 */
    public static int attrLevel(int xp) {
        int lvl = ATTR_BASE;
        while (lvl < ATTR_CAP && xp >= lvl) {
            xp -= lvl;
            lvl++;
        }
        return lvl;
    }

    /** 投入 xp 后的属性值（含小数：零散 XP / 当前升级所需） */
    public static float attrValue(int xp) {
        int lvl = ATTR_BASE;
        while (lvl < ATTR_CAP && xp >= lvl) {
            xp -= lvl;
            lvl++;
        }
        return lvl >= ATTR_CAP ? lvl : lvl + xp / (float) lvl;
    }

    public static String formatAttr(int xp) {
        float v = attrValue(xp);
        if (Math.abs(v - Math.round(v)) < 1e-4) return String.valueOf(Math.round(v));
        String s = String.format(java.util.Locale.ROOT, "%.2f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public static int sum(int[] a) {
        int s = 0;
        for (int v : a) s += v;
        return s;
    }

    /** 技能 level → level+1 的 XP；discount = 特殊身份 1 指定技能（4→5 仅 2 XP）；满级返回 -1 */
    public static int skillStep(int level, boolean discount) {
        if (level >= SKILL_CAP) return -1;
        if (level == 0) return 1;
        if (discount && level == 4) return 2;
        return level * 2;
    }

    public static int skillCostToReach(int level, boolean discount) {
        int c = 0;
        for (int l = 0; l < level; l++) c += skillStep(l, discount);
        return c;
    }

    /** 全部技能的原始 XP 花费（不含赠送折扣） */
    public static int skillCost(int[] levels, int discountSkill) {
        int c = 0;
        for (int i = 0; i < levels.length; i++) c += skillCostToReach(levels[i], i == discountSkill);
        return c;
    }

    /** 建卡时某技能的上限 */
    public static int creationCap(int skill, int discountSkill) {
        return skill == discountSkill ? CREATION_SKILL_CAP + 1 : CREATION_SKILL_CAP;
    }

    /** 技能实际占用 XP 池的部分 = 原始花费 − 赠送等级折算 − 特殊身份 2 的自由技能 XP */
    public static int skillPoolCost(int rawCost, int giftedXp, int freeSkillXp) {
        return Math.max(0, rawCost - giftedXp - freeSkillXp);
    }
}
