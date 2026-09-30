package com.zhushen.space.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.common.util.INBTSerializable;

import java.util.UUID;

/**
 * 玩家身体状况（Attachment，死亡不保留——重生即完好之躯）：
 * 生存需求（水分 / 体力 / 精力）、不良状态点数、毁灭性后果、倒地。
 */
public class PlayerConditionData implements INBTSerializable<CompoundTag> {

    // ===== 生存需求 =====
    /** 水分 0~100 */
    public float thirst = 100f;
    /** 体力（上限随耐力变化；-1 = 尚未初始化，首次 tick 设为上限） */
    public float stamina = -1f;
    /** 精力（睡眠）0~100 */
    public float sleep = 100f;
    /** 力竭：体力耗尽后，恢复到一定比例之前不能冲刺 / 攻击 */
    public boolean exhausted;
    /** 困到昏睡（精力归零） */
    public boolean collapsed;

    // ===== 不良状态 =====
    public final int[] points = new int[StatusType.COUNT];
    /** 燃烧的性质：0 = 自然火焰，1 = 恶意燃烧（不会自行熄灭），2 = 魔法火焰（持续时间内无法以物理方式熄灭） */
    public int burnKind;
    /** 魔法火焰结束的时刻（gameTime） */
    public long magicBurnUntil;
    /** 造成燃烧点数的单位（燃烧伤害的来源） */
    public UUID burnSource;
    /** 已触发的毁灭性后果（按 StatusType 位） */
    public int permanent;

    // ===== 倒地 =====
    public boolean prone;

    public int points(StatusType t) { return points[t.ordinal()]; }

    public void setPoints(StatusType t, int v) { points[t.ordinal()] = Math.max(0, v); }

    public boolean isPermanent(StatusType t) { return (permanent & t.bit()) != 0; }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("Thirst", thirst);
        tag.putFloat("Stamina", stamina);
        tag.putFloat("Sleep", sleep);
        tag.putBoolean("Exhausted", exhausted);
        tag.putBoolean("Collapsed", collapsed);
        tag.putIntArray("Points", points);
        tag.putInt("BurnKind", burnKind);
        tag.putLong("MagicBurnUntil", magicBurnUntil);
        if (burnSource != null) tag.putUUID("BurnSource", burnSource);
        tag.putInt("Permanent", permanent);
        tag.putBoolean("Prone", prone);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        thirst = tag.contains("Thirst") ? tag.getFloat("Thirst") : 100f;
        stamina = tag.contains("Stamina") ? tag.getFloat("Stamina") : -1f;
        sleep = tag.contains("Sleep") ? tag.getFloat("Sleep") : 100f;
        exhausted = tag.getBoolean("Exhausted");
        collapsed = tag.getBoolean("Collapsed");
        int[] p = tag.getIntArray("Points");
        java.util.Arrays.fill(points, 0);
        System.arraycopy(p, 0, points, 0, Math.min(p.length, points.length));
        burnKind = tag.getInt("BurnKind");
        magicBurnUntil = tag.getLong("MagicBurnUntil");
        burnSource = tag.hasUUID("BurnSource") ? tag.getUUID("BurnSource") : null;
        permanent = tag.getInt("Permanent");
        prone = tag.getBoolean("Prone");
    }
}
