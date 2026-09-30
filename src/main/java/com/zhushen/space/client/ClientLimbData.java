package com.zhushen.space.client;

import com.zhushen.space.data.LimbPart;
import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.Map;

/** 客户端肢体数据：本地玩家的部位血量 + 所有可见玩家的断肢掩码（渲染用） */
public final class ClientLimbData {

    private ClientLimbData() {
    }

    private static final Map<Integer, Integer> MASKS = new HashMap<>();
    private static int[] cur = new int[LimbPart.COUNT];
    private static int[] max = new int[LimbPart.COUNT];
    private static final long[] hitAt = new long[LimbPart.COUNT];
    /** 本地玩家失去的眼睛：1 右眼，2 左眼 */
    private static int eyes;

    public static int eyes() {
        return eyes;
    }

    public static void update(int entityId, int[] c, int[] m, int mask, int eyeMask) {
        if (mask == 0) MASKS.remove(entityId);
        else MASKS.put(entityId, mask);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getId() == entityId && c.length == LimbPart.COUNT && m.length == LimbPart.COUNT) {
            long now = System.currentTimeMillis();
            for (int i = 0; i < c.length; i++) if (c[i] < cur[i]) hitAt[i] = now;
            cur = c;
            max = m;
            eyes = eyeMask;
        }
    }

    public static int mask(int entityId) {
        return MASKS.getOrDefault(entityId, 0);
    }

    public static boolean severed(int entityId, LimbPart p) {
        return (mask(entityId) & p.bit()) != 0;
    }

    public static int current(LimbPart p) {
        return cur[p.ordinal()];
    }

    public static int max(LimbPart p) {
        return Math.max(1, max[p.ordinal()]);
    }

    public static long hitAt(LimbPart p) {
        return hitAt[p.ordinal()];
    }

    /** 本地玩家头部血量清空（有有效数据时） */
    public static boolean selfHeadOut() {
        return max[LimbPart.HEAD.ordinal()] > 0 && cur[LimbPart.HEAD.ordinal()] <= 0;
    }

    public static boolean anyDamage() {
        for (int i = 0; i < LimbPart.COUNT; i++) if (cur[i] < max[i]) return true;
        return false;
    }

    public static void clear() {
        MASKS.clear();
        cur = new int[LimbPart.COUNT];
        max = new int[LimbPart.COUNT];
        eyes = 0;
    }
}
