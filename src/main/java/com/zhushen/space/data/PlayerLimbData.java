package com.zhushen.space.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.common.util.INBTSerializable;

/** 玩家肢体伤害与断肢状态（Attachment，死亡不保留——重生即完好之躯） */
public class PlayerLimbData implements INBTSerializable<CompoundTag> {

    /** 各部位已承受的伤害（部位当前血量 = 上限 − 伤害） */
    private final int[] damage = new int[LimbPart.COUNT];
    private int severedMask;
    /** 失去的眼睛：1 右眼，2 左眼（小部位，不单独计血量） */
    private int eyeMask;

    public static final int RIGHT_EYE = 1, LEFT_EYE = 2;

    public int eyeMask() {
        return eyeMask;
    }

    public boolean eyeLost(int eye) {
        return (eyeMask & eye) != 0;
    }

    public void setEyeLost(int eye, boolean lost) {
        eyeMask = lost ? eyeMask | eye : eyeMask & ~eye;
    }

    public int eyesLost() {
        return Integer.bitCount(eyeMask & 3);
    }

    public int damage(LimbPart p) {
        return damage[p.ordinal()];
    }

    public void setDamage(LimbPart p, int v) {
        damage[p.ordinal()] = Math.max(0, v);
    }

    public int current(LimbPart p, float maxHealth) {
        return Math.max(0, p.maxHp(maxHealth) - damage[p.ordinal()]);
    }

    public boolean isSevered(LimbPart p) {
        return (severedMask & p.bit()) != 0;
    }

    public void setSevered(LimbPart p, boolean v) {
        severedMask = v ? severedMask | p.bit() : severedMask & ~p.bit();
    }

    public int severedMask() {
        return severedMask;
    }

    public void reset() {
        java.util.Arrays.fill(damage, 0);
        severedMask = 0;
        eyeMask = 0;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("Damage", damage);
        tag.putInt("Severed", severedMask);
        tag.putInt("Eyes", eyeMask);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        int[] d = tag.getIntArray("Damage");
        java.util.Arrays.fill(damage, 0);
        System.arraycopy(d, 0, damage, 0, Math.min(d.length, damage.length));
        severedMask = tag.getInt("Severed");
        eyeMask = tag.getInt("Eyes");
    }
}
