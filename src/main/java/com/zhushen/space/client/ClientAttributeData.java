package com.zhushen.space.client;

import com.zhushen.space.data.AttributeType;

/**
 * 客户端缓存的属性数据（由服务端 SyncAttributesPayload 更新）。
 * 不引用任何 Minecraft 客户端类，可在两端安全加载。
 */
public class ClientAttributeData {
    private static final int[] POINTS = new int[AttributeType.COUNT];
    private static int totalPoints = AttributeType.DEFAULT_TOTAL_POINTS;
    private static boolean received = false;

    public static void update(int[] points, int total) {
        if (points == null || points.length != AttributeType.COUNT) return;
        System.arraycopy(points, 0, POINTS, 0, AttributeType.COUNT);
        totalPoints = total;
        received = true;
    }

    public static int[] points() {
        return POINTS;
    }

    public static int totalPoints() {
        return totalPoints;
    }

    public static boolean received() {
        return received;
    }

    public static int freePoints(int[] editable) {
        return totalPoints - AttributeType.totalCost(editable);
    }

    public static int legendaryPoints(int[] editable) {
        return AttributeType.legendaryCount(editable);
    }
}
