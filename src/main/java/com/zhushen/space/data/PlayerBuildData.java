package com.zhushen.space.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.common.util.INBTSerializable;

/**
 * 建卡数据：XP 池、属性投入 XP、专长等级、建卡专长的选择，以及留给后续系统对接的待处理项
 * （可携带物品次数、超凡身份兑换）。属性整数值同步写入 PlayerAttributeData，技能等级在 PlayerSkillData。
 */
public class PlayerBuildData implements INBTSerializable<CompoundTag> {
    public static final int VERSION = 1;

    public int version = 0;
    public int totalXp = 0;
    public boolean created = false;
    public int[] attrXp = new int[AttributeType.COUNT];
    public int[] featMask = new int[FeatType.COUNT];
    /** 特殊身份 1 指定的技能（-1 = 未指定） */
    public int si1Skill = -1;
    /** 特殊身份 3 指定的两项技能 */
    public int[] si3Skills = {-1, -1};
    /** 特殊身份 3 赠送等级折算的 XP（不占 XP 池） */
    public int giftedSkillXp = 0;
    /** 待发放：可携带入场物品（每项 ≤500 分），由管理员 / 后续物品系统处理 */
    public int pendingItems = 0;
    /** 待处理：超凡身份的 D 级兑换 */
    public boolean pendingExchange = false;

    public boolean has(FeatType f, int level) {
        return FeatType.has(featMask, f, level);
    }

    public int freeSkillXp() {
        return has(FeatType.SPECIAL_IDENTITY, 2) ? 2 : 0;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag t = new CompoundTag();
        t.putInt("Version", version);
        t.putInt("TotalXp", totalXp);
        t.putBoolean("Created", created);
        t.putIntArray("AttrXp", attrXp);
        CompoundTag feats = new CompoundTag();
        for (FeatType f : FeatType.VALUES) if (featMask[f.ordinal()] != 0) feats.putInt(f.key, featMask[f.ordinal()]);
        t.put("Feats", feats);
        t.putInt("Si1", si1Skill);
        t.putIntArray("Si3", si3Skills);
        t.putInt("GiftedXp", giftedSkillXp);
        t.putInt("PendingItems", pendingItems);
        t.putBoolean("PendingExchange", pendingExchange);
        return t;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag t) {
        version = t.getInt("Version");
        totalXp = t.getInt("TotalXp");
        created = t.getBoolean("Created");
        int[] a = t.getIntArray("AttrXp");
        attrXp = new int[AttributeType.COUNT];
        System.arraycopy(a, 0, attrXp, 0, Math.min(a.length, attrXp.length));
        featMask = new int[FeatType.COUNT];
        CompoundTag feats = t.getCompound("Feats");
        for (String k : feats.getAllKeys()) {
            FeatType f = FeatType.byKey(k);
            if (f != null) featMask[f.ordinal()] = feats.getInt(k);
        }
        si1Skill = t.contains("Si1") ? t.getInt("Si1") : -1;
        int[] s3 = t.getIntArray("Si3");
        si3Skills = new int[]{s3.length > 0 ? s3[0] : -1, s3.length > 1 ? s3[1] : -1};
        giftedSkillXp = t.getInt("GiftedXp");
        pendingItems = t.getInt("PendingItems");
        pendingExchange = t.getBoolean("PendingExchange");
    }
}
