package com.zhushen.space.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.neoforged.neoforge.common.util.INBTSerializable;

/**
 * 主神空间流派数据（Attachment 持久化，死亡保留）。
 *
 * - unlocked：流派购买记录（购买后获得对应饰品物品，装备饰品后才生效）
 * - skillBits：每个流派已购买技能的位掩码（bit = SkillAbility 序号）。
 *   购买流派时赠送的技能（如听劲）会自动置位；八式等需在商城单独购买。
 */
public class PlayerSchoolData implements INBTSerializable<CompoundTag> {

    private final boolean[] unlocked = new boolean[SchoolType.COUNT];
    /** 每个流派已购买技能位掩码（bit = SkillAbility.ordinal()） */
    private final int[] skillBits = new int[SchoolType.COUNT];

    static {
        // 位掩码为 int：技能数超过 31 会静默溢出，届时需改为 long / BitSet
        if (SkillAbility.COUNT > 31) {
            throw new IllegalStateException("SkillAbility 数量超过 31，PlayerSchoolData.skillBits 需要扩容");
        }
    }

    public boolean isUnlocked(SchoolType school) {
        return unlocked[school.ordinal()];
    }

    public void unlock(SchoolType school) {
        unlocked[school.ordinal()] = true;
    }

    public boolean[] unlocked() {
        return unlocked;
    }

    /** 指定流派的指定能力是否已购买（bit = SkillAbility 序号） */
    public boolean isSkillPurchased(int schoolOrdinal, int abilityId) {
        if (schoolOrdinal < 0 || schoolOrdinal >= SchoolType.COUNT) return false;
        return (skillBits[schoolOrdinal] & (1 << abilityId)) != 0;
    }

    /** 置位已购买技能 */
    public void purchaseSkill(int schoolOrdinal, int abilityId) {
        if (schoolOrdinal < 0 || schoolOrdinal >= SchoolType.COUNT) return;
        if (abilityId < 0 || abilityId >= SkillAbility.COUNT) return;
        skillBits[schoolOrdinal] |= (1 << abilityId);
    }

    /** 已购买技能位掩码（用于同步） */
    public int[] skillBits() {
        return skillBits;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        byte[] arr = new byte[SchoolType.COUNT];
        for (int i = 0; i < SchoolType.COUNT; i++) arr[i] = (byte) (unlocked[i] ? 1 : 0);
        tag.put("Unlocked", new ByteArrayTag(arr));
        tag.put("SkillBits", new IntArrayTag(skillBits)); // 旧格式，保留以便降级
        // 新格式：按技能 key 存已购技能，SkillAbility 调整顺序也不会错位
        for (int i = 0; i < SchoolType.COUNT; i++) {
            net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
            for (SkillAbility a : SkillAbility.values()) {
                if ((skillBits[i] & (1 << a.ordinal())) != 0) {
                    list.add(net.minecraft.nbt.StringTag.valueOf(a.key()));
                }
            }
            tag.put("SkillKeys" + i, list);
        }
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        byte[] arr = tag.getByteArray("Unlocked");
        for (int i = 0; i < SchoolType.COUNT; i++) {
            unlocked[i] = i < arr.length && arr[i] != 0;
        }
        int[] bits = tag.getIntArray("SkillBits");
        for (int i = 0; i < SchoolType.COUNT; i++) {
            if (tag.contains("SkillKeys" + i)) {
                skillBits[i] = 0;
                net.minecraft.nbt.ListTag list = tag.getList("SkillKeys" + i, net.minecraft.nbt.Tag.TAG_STRING);
                for (int j = 0; j < list.size(); j++) {
                    String key = list.getString(j);
                    for (SkillAbility a : SkillAbility.values()) {
                        if (a.key().equals(key)) skillBits[i] |= (1 << a.ordinal());
                    }
                }
            } else {
                skillBits[i] = i < bits.length ? bits[i] : 0;
            }
        }
    }
}
