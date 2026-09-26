package com.zhushen.space.client;

/**
 * 客户端 B/L/A 伤势镜像数据（由 SyncHealthPayload 同步），供 HUD / 面板显示。
 */
public class ClientHealthData {

    private static volatile int b, l, a;

    public static void update(int newB, int newL, int newA) {
        b = newB;
        l = newL;
        a = newA;
    }

    public static int b() {
        return b;
    }

    public static int l() {
        return l;
    }

    public static int a() {
        return a;
    }

    public static int total() {
        return b + l + a;
    }

    /** 是否存在伤势（决定 HUD 是否绘制伤势行） */
    public static boolean hasWounds() {
        return b + l + a > 0;
    }
}
