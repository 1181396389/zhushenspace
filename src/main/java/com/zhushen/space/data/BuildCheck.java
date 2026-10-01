package com.zhushen.space.data;

import java.util.ArrayList;
import java.util.List;

/** 建卡方案校验（客户端显示与服务端提交共用） */
public final class BuildCheck {
    public int attr, skillRaw, skillPool, feat, free;
    /** 错误提示（语言键 + 参数） */
    public final List<Object[]> errors = new ArrayList<>();

    private void err(String key, Object... args) {
        Object[] e = new Object[args.length + 1];
        e[0] = key;
        System.arraycopy(args, 0, e, 1, args.length);
        errors.add(e);
    }

    public boolean ok() {
        return errors.isEmpty();
    }

    public static BuildCheck of(int totalXp, boolean created, int giftedXp,
                                int[] savedAttr, int[] savedSkills, int[] savedFeat, int savedSi1, int savedSi3a, int savedSi3b,
                                int[] attrXp, int[] skills, int[] featMask, int si1, int si3a, int si3b) {
        BuildCheck r = new BuildCheck();
        if (attrXp.length != AttributeType.COUNT || skills.length != SkillType.COUNT || featMask.length != FeatType.COUNT) {
            r.err("build.zhushenspace.err.invalid");
            return r;
        }
        boolean si1Owned = FeatType.has(featMask, FeatType.SPECIAL_IDENTITY, 1);
        boolean si3Owned = FeatType.has(featMask, FeatType.SPECIAL_IDENTITY, 3);
        int disc = si1Owned ? si1 : -1;
        for (int i = 0; i < AttributeType.COUNT; i++) {
            if (attrXp[i] < 0 || attrXp[i] > BuildRules.ATTR_MAX_XP || attrXp[i] < savedAttr[i]) r.err("build.zhushenspace.err.invalid");
        }
        for (int i = 0; i < SkillType.COUNT; i++) {
            if (skills[i] < 0 || skills[i] > BuildRules.SKILL_CAP || skills[i] < savedSkills[i]) r.err("build.zhushenspace.err.invalid");
            if (!created && skills[i] > BuildRules.creationCap(i, disc)) r.err("build.zhushenspace.err.cap");
        }
        for (FeatType f : FeatType.VALUES) {
            int m = featMask[f.ordinal()], sm = savedFeat[f.ordinal()];
            if (!f.validMask(m) || (m & sm) != sm) r.err("build.zhushenspace.err.invalid");
            if (created && f.creationOnly() && m != sm) r.err("build.zhushenspace.err.creation_feat");
            if ((m & FeatType.LEVEL_BITS) != (sm & FeatType.LEVEL_BITS)) {
                int[] al = new int[AttributeType.COUNT];
                for (int i = 0; i < al.length; i++) al[i] = BuildRules.attrLevel(attrXp[i]);
                if (!f.prereqMet(al, skills)) r.err("build.zhushenspace.err.prereq");
            }
            if (f == FeatType.BARBARIAN && (m & FeatType.LEVEL_BITS) != 0) {
                int c = FeatType.choice(m);
                if (c < 0 || c > 2) r.err("build.zhushenspace.err.barbarian");
            }
            if (f == FeatType.WOLF_CHILD && (m & FeatType.LEVEL_BITS) != 0) {
                int c = FeatType.choice(m);
                if (c < 0 || c > 31 || FeatType.wildAttr(m) != ((c >> 1) & 3)) r.err("build.zhushenspace.err.invalid");
                if ((sm & FeatType.LEVEL_BITS) != 0) {
                    // 已保存：变体与属性不可更改，已消除的缺陷不可恢复
                    if (FeatType.wildBase(m) != FeatType.wildBase(sm)
                            || (FeatType.wildLiterate(sm) && !FeatType.wildLiterate(m))
                            || (FeatType.wildFearless(sm) && !FeatType.wildFearless(m))) r.err("build.zhushenspace.err.invalid");
                }
                // 智力在建卡时变为 1 点：不能在智力上投入 XP（建卡后也不能再提升）
                int intI = AttributeType.INTELLIGENCE.ordinal();
                if (!created ? attrXp[intI] != 0 : attrXp[intI] > savedAttr[intI]) r.err("build.zhushenspace.err.wolf_int");
            }
        }
        if (created) {
            if (si1 != savedSi1 || si3a != savedSi3a || si3b != savedSi3b) r.err("build.zhushenspace.err.creation_feat");
        } else {
            if (si1Owned && (si1 < 0 || si1 >= SkillType.COUNT)) r.err("build.zhushenspace.err.si1");
            if (!si1Owned && si1 != -1) r.err("build.zhushenspace.err.invalid");
            if (!si3Owned && (si3a != -1 || si3b != -1)) r.err("build.zhushenspace.err.invalid");
            if (si3a >= SkillType.COUNT || si3b >= SkillType.COUNT || (si3a >= 0 && si3a == si3b)) r.err("build.zhushenspace.err.invalid");
        }
        r.attr = BuildRules.sum(attrXp);
        r.skillRaw = BuildRules.skillCost(skills, disc);
        int freeSkill = FeatType.has(featMask, FeatType.SPECIAL_IDENTITY, 2) ? 2 : 0;
        r.skillPool = BuildRules.skillPoolCost(r.skillRaw, giftedXp, freeSkill);
        r.feat = FeatType.totalCost(featMask);
        r.free = totalXp - r.attr - r.skillPool - r.feat;
        if (r.free < 0) r.err("build.zhushenspace.err.xp");
        if (!created) {
            if (r.attr < BuildRules.ATTR_MIN || r.attr > BuildRules.ATTR_MAX)
                r.err("build.zhushenspace.err.attr", r.attr, BuildRules.ATTR_MIN, BuildRules.ATTR_MAX);
            if (r.skillPool < BuildRules.SKILL_MIN || r.skillPool > BuildRules.SKILL_MAX)
                r.err("build.zhushenspace.err.skill", r.skillPool, BuildRules.SKILL_MIN, BuildRules.SKILL_MAX);
            if (r.feat < BuildRules.FEAT_MIN) r.err("build.zhushenspace.err.feat", r.feat, BuildRules.FEAT_MIN);
        }
        return r;
    }
}
