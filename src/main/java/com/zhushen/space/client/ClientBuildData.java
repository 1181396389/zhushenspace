package com.zhushen.space.client;

import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.FeatType;

/** 客户端缓存：建卡 XP 数据 */
public final class ClientBuildData {
    private ClientBuildData() {}

    public static int totalXp;
    public static boolean created;
    public static int[] attrXp = new int[AttributeType.COUNT];
    public static int[] featMask = new int[FeatType.COUNT];
    public static int si1 = -1, si3a = -1, si3b = -1;
    public static int giftedXp;
    public static int pendingItems;
    public static boolean pendingExchange;
    /** 每次同步自增，界面据此刷新 */
    public static int revision;

    public static void update(com.zhushen.space.network.SyncBuildPayload p) {
        totalXp = p.totalXp();
        created = p.created();
        attrXp = fit(p.attrXp(), AttributeType.COUNT);
        featMask = fit(p.featMask(), FeatType.COUNT);
        si1 = p.si1(); si3a = p.si3a(); si3b = p.si3b();
        giftedXp = p.giftedXp();
        pendingItems = p.pendingItems();
        pendingExchange = p.pendingExchange();
        revision++;
    }

    private static int[] fit(int[] a, int n) {
        int[] r = new int[n];
        System.arraycopy(a, 0, r, 0, Math.min(a.length, n));
        return r;
    }
}
