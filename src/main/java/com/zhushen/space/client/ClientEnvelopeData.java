package com.zhushen.space.client;

/** 客户端缓存：是否已使用主神邀请函 */
public final class ClientEnvelopeData {
    private ClientEnvelopeData() {}

    private static boolean used;

    public static void update(boolean u) { used = u; }

    public static boolean used() { return used; }

    public static void clear() { used = false; }
}
