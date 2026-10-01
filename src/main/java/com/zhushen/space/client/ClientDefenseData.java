package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.network.DefenseHudPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** 客户端：本地玩家的防御 / 豁免显示值（服务端每 5 刻按变化同步），并记录每项数值的变化时刻（HUD 弹跳动画） */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class ClientDefenseData {

    private ClientDefenseData() {}

    public static final int DEF = 0, WILL = 1, REFLEX = 2, FORT = 3, AREA = 4;
    public static final int FULL = 1, FLAT = 2, NO_REFLEX = 4, NO_PARRY = 8;

    private static DefenseHudPayload cur;
    private static final int[] values = new int[5];
    private static final int[] prev = new int[5];
    private static final long[] changedAt = new long[5];
    private static int flags;
    private static long flagsAt;
    private static java.util.List<com.zhushen.space.network.DamageKeywordsPayload.Entry> keywords = java.util.List.of();

    /** 减伤关键字（悬停显示） */
    public static void updateKeywords(com.zhushen.space.network.DamageKeywordsPayload p) { keywords = p.entries(); }

    public static java.util.List<com.zhushen.space.network.DamageKeywordsPayload.Entry> keywords() { return keywords; }

    public static void update(DefenseHudPayload p) {
        long now = System.currentTimeMillis();
        int[] v = {p.def(), p.will(), p.reflex(), p.fort(), p.area()};
        for (int i = 0; i < 5; i++) {
            if (cur != null && v[i] != values[i]) {
                prev[i] = values[i];
                changedAt[i] = now;
            }
            values[i] = v[i];
        }
        if (cur != null && p.flags() != flags) flagsAt = now;
        flags = p.flags();
        cur = p;
    }

    public static boolean valid() { return cur != null; }

    public static int value(int i) { return values[i]; }

    /** 上一次变化前的值（用于判断升 / 降） */
    public static int previous(int i) { return prev[i]; }

    public static long changedAt(int i) { return changedAt[i]; }

    public static boolean flag(int f) { return (flags & f) != 0; }

    public static long flagsAt() { return flagsAt; }

    public static int base() { return cur == null ? 0 : cur.base(); }

    public static int armor() { return cur == null ? 0 : cur.armor(); }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        cur = null;
        flags = 0;
        keywords = java.util.List.of();
        java.util.Arrays.fill(values, 0);
        java.util.Arrays.fill(changedAt, 0);
    }
}
