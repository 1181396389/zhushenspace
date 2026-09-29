package com.zhushen.space.client;

import com.zhushen.space.data.FeatType;

/** 客户端专长缓存 + 主神邀请函使用状态 */
public final class ClientFeatData {
    private ClientFeatData() {}

    private static int total, owned;
    private static boolean envelopeUsed, received;

    public static void update(int t, int o, boolean env) {
        total = t;
        owned = o;
        envelopeUsed = env;
        received = true;
    }

    public static int total() { return total; }

    public static int owned() { return owned; }

    public static int free(int mask) { return total - FeatType.cost(mask); }

    /** 是否已使用过主神邀请函（未使用前不能打开主神面板） */
    public static boolean envelopeUsed() { return envelopeUsed; }

    public static boolean received() { return received; }

    public static void clear() {
        total = owned = 0;
        envelopeUsed = received = false;
    }
}
