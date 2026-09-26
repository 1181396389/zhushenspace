package com.zhushen.space.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.neoforged.neoforge.common.util.INBTSerializable;

/**
 * 主神空间货币数据（Attachment 持久化，死亡保留）。
 *
 * 支线：5 个等级 S/A/B/C/D（索引 0~4），3 个低级支线可拼合为 1 个高级支线，
 * 高级支线也可拆解为 3 个低级支线（{@link #combine} / {@link #split}）。
 * 奖励点数：又称分数，购买资源的主要货币。
 * 经验：XP，用于"钻研/训练"提升已有能力（不可转让、不可凭空生成）。
 */
public class PlayerCurrencyData implements INBTSerializable<CompoundTag> {

    public static final int TIER_COUNT = 5; // 0=S 1=A 2=B 3=C 4=D

    private final int[] branches = new int[TIER_COUNT];
    private int score;
    private int xp;

    public int[] branches() {
        return branches;
    }

    public int branch(int tier) {
        return branches[tier];
    }

    public int score() {
        return score;
    }

    public int xp() {
        return xp;
    }

    public void addBranch(int tier, int amount) {
        branches[tier] = Math.max(0, branches[tier] + amount);
    }

    public void addScore(int amount) {
        score = Math.max(0, score + amount);
    }

    public void addXp(int amount) {
        xp = Math.max(0, xp + amount);
    }

    /** 拼合：3 个 tier 级支线 → 1 个 tier-1 级支线（tier 1=A ~ 4=D）。成功返回 true */
    public boolean combine(int tier) {
        if (tier < 1 || tier >= TIER_COUNT || branches[tier] < 3) return false;
        branches[tier] -= 3;
        branches[tier - 1] += 1;
        return true;
    }

    /** 拆解：1 个 tier 级支线 → 3 个 tier+1 级支线（tier 0=S ~ 3=C）。成功返回 true */
    public boolean split(int tier) {
        if (tier < 0 || tier >= TIER_COUNT - 1 || branches[tier] < 1) return false;
        branches[tier] -= 1;
        branches[tier + 1] += 3;
        return true;
    }

    /** 支线等级字符（S/A/B/C/D）转索引 */
    public static int parseTier(String letter) {
        return switch (letter.toUpperCase()) {
            case "S" -> 0;
            case "A" -> 1;
            case "B" -> 2;
            case "C" -> 3;
            case "D" -> 4;
            default -> -1;
        };
    }

    /** 索引转等级字符 */
    public static String tierLetter(int tier) {
        return switch (tier) {
            case 0 -> "S";
            case 1 -> "A";
            case 2 -> "B";
            case 3 -> "C";
            default -> "D";
        };
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.put("Branches", new IntArrayTag(branches));
        tag.putInt("Score", score);
        tag.putInt("Xp", xp);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        int[] arr = tag.getIntArray("Branches");
        for (int i = 0; i < TIER_COUNT; i++) {
            branches[i] = i < arr.length ? Math.max(0, arr[i]) : 0;
        }
        score = Math.max(0, tag.getInt("Score"));
        xp = Math.max(0, tag.getInt("Xp"));
    }
}
