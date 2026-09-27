package com.zhushen.space.compat;

import net.neoforged.fml.ModList;

/**
 * TACZ（Timeless & Classics Guns: Zero，NeoForge 1.21.1 移植版 MUKSC/TACZ-1.21.1）可选联动入口。
 * <p>
 * 本类不引用任何 TACZ 类，可以安全加载；只有检测到 TACZ 已安装时，
 * 才会加载 {@link TaczGunEvents} 并注册枪械事件监听。
 */
public final class TaczCompat {
    public static final String MODID = "tacz";

    private static boolean loaded;

    private TaczCompat() {
    }

    /** 模组构造阶段调用 */
    public static void init() {
        loaded = ModList.get().isLoaded(MODID);
        if (loaded) {
            TaczGunEvents.register();
        }
    }

    /** TACZ 是否已安装 */
    public static boolean isLoaded() {
        return loaded;
    }
}
