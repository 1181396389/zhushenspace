package com.zhushen.space.client;

import java.util.Arrays;

/**
 * 客户端主神空间进度镜像（由 SyncProgressPayload 同步）。
 */
public class ClientProgressData {

    private static volatile int[] branches = new int[5];
    private static volatile int score;
    private static volatile boolean[] schools = new boolean[1];
    /** 每个流派已购买技能位掩码（bit = SkillAbility 序号） */
    private static volatile int[] schoolSkillBits = new int[1];

    public static void update(int[] branchArr, int scoreValue, boolean[] schoolArr, int[] skillBits) {
        branches = Arrays.copyOf(branchArr, branchArr.length);
        score = scoreValue;
        schools = Arrays.copyOf(schoolArr, schoolArr.length);
        schoolSkillBits = Arrays.copyOf(skillBits, skillBits.length);
    }

    public static int[] branches() {
        return branches;
    }

    public static int branch(int tier) {
        int[] arr = branches;
        return tier >= 0 && tier < arr.length ? arr[tier] : 0;
    }

    public static int score() {
        return score;
    }

    /** 指定流派是否已解锁（购买记录） */
    public static boolean school(int index) {
        boolean[] arr = schools;
        return index >= 0 && index < arr.length && arr[index];
    }

    public static boolean taiChiUnlocked() {
        return school(0);
    }

    /** 指定流派的指定能力是否已购买 */
    public static boolean skillPurchased(int schoolIndex, int abilityId) {
        int[] arr = schoolSkillBits;
        if (schoolIndex < 0 || schoolIndex >= arr.length) return false;
        return (arr[schoolIndex] & (1 << abilityId)) != 0;
    }
}
