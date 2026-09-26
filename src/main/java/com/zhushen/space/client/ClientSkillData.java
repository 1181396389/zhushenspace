package com.zhushen.space.client;

import com.zhushen.space.data.SkillAbility;
import com.zhushen.space.data.SkillType;

/**
 * 客户端缓存的技能数据（由服务端 SyncSkillsPayload 更新）。
 * 不引用任何 Minecraft 客户端类，可在两端安全加载。
 */
public class ClientSkillData {
    private static final int[] POINTS = new int[SkillType.COUNT];
    /** 两套战斗预设栏（-1 为空） */
    private static final int[][] BARS = new int[2][9];
    /** 各主动技能冷却结束时间戳（毫秒），由同步的剩余 tick 换算 */
    private static final long[] COOLDOWN_UNTIL_MS = new long[SkillAbility.COUNT];
    /** 跳跃 / 攀爬增益结束时间戳（毫秒），由同步的剩余 tick 换算 */
    private static long leapUntilMs;
    private static long climbUntilMs;
    private static int totalSkillPoints = SkillType.DEFAULT_TOTAL_SKILL_POINTS;
    private static boolean received = false;

    public static void update(int[] points, int total, int[][] bars, int[] cooldownTicks) {
        if (points == null || points.length != SkillType.COUNT) return;
        System.arraycopy(points, 0, POINTS, 0, SkillType.COUNT);
        totalSkillPoints = total;
        if (bars != null && bars.length == 2) {
            for (int b = 0; b < 2; b++) {
                if (bars[b] != null && bars[b].length == 9) {
                    System.arraycopy(bars[b], 0, BARS[b], 0, 9);
                }
            }
        }
        long now = System.currentTimeMillis();
        if (cooldownTicks != null && cooldownTicks.length == SkillAbility.COUNT) {
            for (int i = 0; i < SkillAbility.COUNT; i++) {
                int ticks = cooldownTicks[i];
                COOLDOWN_UNTIL_MS[i] = ticks > 0 ? now + ticks * 50L : 0;
            }
        }
        received = true;
    }

    /** 同步进行中的增益状态（跳跃 / 攀爬剩余 tick，0 表示不活跃） */
    public static void updateTransient(int leapRemainTicks, int climbRemainTicks) {
        long now = System.currentTimeMillis();
        leapUntilMs = leapRemainTicks > 0 ? now + leapRemainTicks * 50L : 0;
        climbUntilMs = climbRemainTicks > 0 ? now + climbRemainTicks * 50L : 0;
    }

    /** 跳跃增益剩余毫秒数（0 表示不活跃） */
    public static long leapRemainingMs() {
        return Math.max(0, leapUntilMs - System.currentTimeMillis());
    }

    /** 攀爬增益剩余毫秒数（0 表示不活跃） */
    public static long climbRemainingMs() {
        return Math.max(0, climbUntilMs - System.currentTimeMillis());
    }

    public static int[] points() {
        return POINTS;
    }

    public static int totalSkillPoints() {
        return totalSkillPoints;
    }

    public static boolean received() {
        return received;
    }

    public static int freePoints(int[] editable) {
        return totalSkillPoints - SkillType.totalCost(editable);
    }

    /** 战斗预设栏（-1 为空），bar 取 0/1 */
    public static int[] bar(int bar) {
        return BARS[bar];
    }

    /** 指定栏位槽位装备的主动技能（-1 为空） */
    public static int slotAbility(int bar, int slot) {
        if (bar < 0 || bar >= 2 || slot < 0 || slot >= 9) return -1;
        return BARS[bar][slot];
    }

    /** 指定主动技能剩余冷却毫秒数 */
    public static long cooldownRemainingMs(int abilityId) {
        if (abilityId < 0 || abilityId >= SkillAbility.COUNT) return 0;
        long remain = COOLDOWN_UNTIL_MS[abilityId] - System.currentTimeMillis();
        return Math.max(0, remain);
    }

    /** 神秘学 + 科学技能点转化为智力属性 */
    public static int intelligenceBonus() {
        return POINTS[SkillType.OCCULTISM.ordinal()] + POINTS[SkillType.SCIENCE.ordinal()];
    }
}
