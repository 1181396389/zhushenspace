package com.zhushen.space.client;

import net.minecraft.client.gui.screens.Screen;

/** 共用代码中需要的客户端查询（调用前须确认运行在客户端） */
public final class ClientHooks {
    private ClientHooks() {}

    public static boolean shiftDown() { return Screen.hasShiftDown(); }
}
