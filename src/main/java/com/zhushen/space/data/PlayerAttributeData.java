package com.zhushen.space.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.common.util.INBTSerializable;

/**
 * 玩家属性数据（作为 Attachment 持久化，死亡保留）。
 */
public class PlayerAttributeData implements INBTSerializable<CompoundTag> {
    private int[] points = new int[AttributeType.COUNT];
    private int totalPoints = AttributeType.DEFAULT_TOTAL_POINTS;
    /** 是否已使用过主神邀请函（点数仅发放一次，防止重复刷点） */
    private boolean envelopeUsed = false;

    public int[] points() {
        return points;
    }

    public int get(int index) {
        return points[index];
    }

    public void setPoints(int[] newPoints) {
        if (AttributeType.isValid(newPoints)) {
            this.points = newPoints.clone();
        }
    }

    public int totalPoints() {
        return totalPoints;
    }

    public void addTotalPoints(int amount) {
        this.totalPoints += amount;
    }

    public boolean envelopeUsed() {
        return envelopeUsed;
    }

    public void markEnvelopeUsed() {
        this.envelopeUsed = true;
    }

    /** 剩余可用点数 */
    public int freePoints() {
        return totalPoints - AttributeType.totalCost(points);
    }

    /** 传奇点数（满级属性数量） */
    public int legendaryPoints() {
        return AttributeType.legendaryCount(points);
    }

    /** 已消耗点数 */
    public int spentPoints() {
        return AttributeType.totalCost(points);
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("Points", points);
        tag.putInt("TotalPoints", totalPoints);
        tag.putBoolean("EnvelopeUsed", envelopeUsed);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        int[] arr = tag.getIntArray("Points");
        this.points = new int[AttributeType.COUNT];
        for (int i = 0; i < Math.min(arr.length, AttributeType.COUNT); i++) {
            this.points[i] = arr[i];
        }
        this.totalPoints = tag.contains("TotalPoints")
                ? tag.getInt("TotalPoints")
                : AttributeType.DEFAULT_TOTAL_POINTS;
        this.envelopeUsed = tag.getBoolean("EnvelopeUsed");
    }
}
