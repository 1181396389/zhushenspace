package com.zhushen.space.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.common.util.INBTSerializable;

/** 技艺购买记录 / 选项 / 研发 / 轮盘设置（死亡保留） */
public class PlayerArtData implements INBTSerializable<CompoundTag> {
    public long owned;
    /** 每个技艺：已选选项位掩码（PICK 可研发多个） */
    public int[] optionBits = new int[ArtSkill.COUNT];
    /** 每个技艺：当前选项（CYCLE 切换 / PICK 首选） */
    public int[] current = new int[ArtSkill.COUNT];
    public int[] researchBits = new int[ArtSkill.COUNT];
    /** 留手：-1 = 关闭；否则 1..100 */
    public int holdback = -1;
    /** 增幅：默认关闭；潜行施放 = 用满 */
    public boolean amplify;
    /** 能量加值：检定时自动花能量 +1DP */
    public boolean boost;
    /** 念动力场：受到攻击时自动花 1 点念动力获得力场防御（轮盘开关） */
    public boolean tkField;

    public boolean owns(ArtSkill s) { return (owned & (1L << s.ordinal())) != 0; }

    public boolean hasOption(ArtSkill s, int i) { return (optionBits[s.ordinal()] & (1 << i)) != 0; }

    public boolean hasResearch(ArtSkill s, int i) { return (researchBits[s.ordinal()] & (1 << i)) != 0; }

    public boolean hasResearch(ArtSkill s, String key) {
        for (int i = 0; i < s.researches.length; i++) if (s.researches[i].key().equals(key)) return hasResearch(s, i);
        return false;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag t = new CompoundTag();
        t.putLong("owned", owned);
        t.putIntArray("opt", optionBits);
        t.putIntArray("cur", current);
        t.putIntArray("res", researchBits);
        t.putInt("holdback", holdback);
        t.putBoolean("amp", amplify);
        t.putBoolean("boost", boost);
        t.putBoolean("tkField", tkField);
        return t;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag t) {
        owned = t.getLong("owned");
        copy(t.getIntArray("opt"), optionBits);
        copy(t.getIntArray("cur"), current);
        copy(t.getIntArray("res"), researchBits);
        holdback = t.contains("holdback") ? t.getInt("holdback") : -1;
        amplify = t.getBoolean("amp");
        boost = t.getBoolean("boost");
        tkField = t.getBoolean("tkField");
    }

    private static void copy(int[] src, int[] dst) {
        System.arraycopy(src, 0, dst, 0, Math.min(src.length, dst.length));
    }
}
