package com.zhushen.space.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.common.util.INBTSerializable;

import java.util.Arrays;

/**
 * 玩家技能数据（作为 Attachment 持久化，死亡保留）。
 *
 * 战斗预设支持两套技能栏（A/B），两栏共享同一批已解锁技能，
 * 战斗模式下可按键切换当前生效的栏位。
 */
public class PlayerSkillData implements INBTSerializable<CompoundTag> {
    public static final int BAR_COUNT = 2;
    public static final int BAR_SLOTS = 9;

    private int[] points = new int[SkillType.COUNT];
    private int totalSkillPoints = SkillType.DEFAULT_TOTAL_SKILL_POINTS;
    /** 是否已使用过主神邀请函（技能点仅发放一次，防止重复刷点） */
    private boolean envelopeUsed = false;
    /** 两套战斗预设栏：每格 -1 为空，否则为 SkillAbility 序号 */
    private int[][] bars = new int[BAR_COUNT][BAR_SLOTS];

    public PlayerSkillData() {
        for (int[] bar : bars) {
            Arrays.fill(bar, -1);
        }
    }

    public int[] points() {
        return points;
    }

    public int get(int index) {
        return points[index];
    }

    public void setPoints(int[] newPoints) {
        if (SkillType.isValid(newPoints)) {
            this.points = newPoints.clone();
        }
    }

    public int totalSkillPoints() {
        return totalSkillPoints;
    }

    public void addTotalPoints(int amount) {
        this.totalSkillPoints += amount;
    }

    public boolean envelopeUsed() {
        return envelopeUsed;
    }

    public void markEnvelopeUsed() {
        this.envelopeUsed = true;
    }

    /** 战斗预设栏（只读访问，修改需经 setBar 校验） */
    public int[] bar(int index) {
        return bars[index];
    }

    /** 全部战斗预设栏（只读访问，用于同步） */
    public int[][] bars() {
        return bars;
    }

    public void setBar(int index, int[] newSlots) {
        if (SkillAbility.isValidSlots(newSlots)) {
            this.bars[index] = newSlots.clone();
        }
    }

    /** 剩余可用技能点数 */
    public int freePoints() {
        return totalSkillPoints - SkillType.totalCost(points);
    }

    /** 已消耗技能点数 */
    public int spentPoints() {
        return SkillType.totalCost(points);
    }

    /** 清理预设槽位中因技能点下降而不再解锁的能力 */
    public void pruneSlots() {
        for (int b = 0; b < BAR_COUNT; b++) {
            for (int i = 0; i < BAR_SLOTS; i++) {
                int s = bars[b][i];
                if (s == -1) continue;
                if (s < 0 || s >= SkillAbility.COUNT) {
                    bars[b][i] = -1;
                    continue;
                }
                SkillAbility ability = SkillAbility.values()[s];
                // 内力系/流派系能力不依赖技能点（使用时另行校验内力池与流派解锁）
                if (ability.isNeiliAbility() || ability.isSchoolAbility()) continue;
                if (points[ability.owner().ordinal()] < ability.requiredLevel()) {
                    bars[b][i] = -1;
                }
            }
        }
    }

    /** 清除槽位中的内力系/流派系能力（摘下饰品 / 内力池移除时调用，使其从预设与 HUD 消失） */
    public void clearGatedSlots() {
        for (int b = 0; b < BAR_COUNT; b++) {
            for (int i = 0; i < BAR_SLOTS; i++) {
                int s = bars[b][i];
                if (s >= 0 && s < SkillAbility.COUNT) {
                    SkillAbility ability = SkillAbility.values()[s];
                    if (ability.isNeiliAbility() || ability.isSchoolAbility()) {
                        bars[b][i] = -1;
                    }
                }
            }
        }
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("Points", points);
        tag.putInt("TotalPoints", totalSkillPoints);
        tag.putBoolean("EnvelopeUsed", envelopeUsed);
        tag.putIntArray("Bar0", bars[0]);
        tag.putIntArray("Bar1", bars[1]);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        int[] arr = tag.getIntArray("Points");
        this.points = new int[SkillType.COUNT];
        for (int i = 0; i < Math.min(arr.length, SkillType.COUNT); i++) {
            this.points[i] = arr[i];
        }
        this.totalSkillPoints = tag.contains("TotalPoints")
                ? tag.getInt("TotalPoints")
                : SkillType.DEFAULT_TOTAL_SKILL_POINTS;
        this.envelopeUsed = tag.getBoolean("EnvelopeUsed");
        // 新格式：Bar0/Bar1；旧格式兼容：Slots 迁移到第一栏
        int[][] loaded = new int[BAR_COUNT][];
        loaded[0] = tag.contains("Bar0") ? tag.getIntArray("Bar0") : tag.getIntArray("Slots");
        loaded[1] = tag.getIntArray("Bar1");
        this.bars = new int[BAR_COUNT][BAR_SLOTS];
        for (int[] bar : bars) {
            Arrays.fill(bar, -1);
        }
        for (int b = 0; b < BAR_COUNT; b++) {
            int[] src = loaded[b];
            for (int i = 0; i < Math.min(src.length, BAR_SLOTS); i++) {
                bars[b][i] = src[i];
            }
        }
        pruneSlots();
    }
}
