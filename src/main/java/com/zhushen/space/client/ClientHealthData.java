package com.zhushen.space.client;

/**
 * 客户端 B/L/A 伤势镜像数据（由 SyncHealthPayload 同步），供 HUD / 面板显示。
 */
public class ClientHealthData {

    private static volatile int b, l, a, maxHp;

    public static void update(int newB, int newL, int newA, int newMaxHp) {
        b = newB;
        l = newL;
        a = newA;
        maxHp = newMaxHp;
    }

    /**
     * 伤势系统使用的生命上限：优先取服务端同步值（与 HealthManager 结算口径一致），
     * 若本地属性上限更高（例如属性刚提交、伤势包尚未到达）则取较高者，确保随最大生命值上升。
     */
    public static int maxHp(float localMaxHealth) {
        int local = Math.round(localMaxHealth);
        return Math.max(maxHp, local);
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
