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

    /**
     * 伤害面板：手持 TACZ 枪械时返回 {每次射击面板伤害, 弹丸数}，否则（或未安装 TACZ）返回 null。
     * TACZ 类只在已安装时才会被加载。
     */
    public static float[] panelDamage(net.minecraft.server.level.ServerPlayer player,
                                      net.minecraft.world.item.ItemStack stack) {
        return loaded ? TaczGunEvents.panelDamage(player, stack) : null;
    }

    /** TACZ 是否已安装 */
    public static boolean isLoaded() {
        return loaded;
    }
}
