package com.zhushen.space.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.neoforged.neoforge.common.util.INBTSerializable;

/** 玩家专长：总专长点 + 已习得专长（按 key 存档，枚举调整顺序不会错位） */
public class PlayerFeatData implements INBTSerializable<CompoundTag> {
    private int totalPoints = 0;
    private int owned = 0;
    private boolean envelopeGranted = false;

    public int totalPoints() { return totalPoints; }

    public void addTotalPoints(int n) { totalPoints = Math.max(0, totalPoints + n); }

    public int owned() { return owned; }

    public void setOwned(int mask) { owned = mask; }

    public int freePoints() { return totalPoints - FeatType.cost(owned); }

    public boolean has(FeatType f) { return (owned & f.bit()) != 0; }

    public boolean envelopeGranted() { return envelopeGranted; }

    public void markEnvelopeGranted() { envelopeGranted = true; }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Total", totalPoints);
        tag.putBoolean("Envelope", envelopeGranted);
        ListTag list = new ListTag();
        for (FeatType f : FeatType.values()) if (has(f)) list.add(StringTag.valueOf(f.key));
        tag.put("Owned", list);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        totalPoints = tag.getInt("Total");
        envelopeGranted = tag.getBoolean("Envelope");
        owned = 0;
        ListTag list = tag.getList("Owned", 8);
        for (int i = 0; i < list.size(); i++) {
            FeatType f = FeatType.byKey(list.getString(i));
            if (f != null) owned |= f.bit();
        }
    }
}
