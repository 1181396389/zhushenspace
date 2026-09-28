package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.common.DamageVariance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * 客户端伤害面板镜像（由 DamagePanelPayload 同步）：当前手持物的面板伤害（100%），
 * 区间下限按 {@link DamageVariance#MIN_FACTOR} 计算并向下取整。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class ClientDamagePanel {

    public static final int KIND_MELEE = 0;
    public static final int KIND_GUN = 1;

    private static volatile int kind = KIND_MELEE;
    /** 面板伤害；&lt; 0 表示尚未收到服务端数据 */
    private static volatile float panel = -1f;
    private static volatile int pellets = 1;

    private ClientDamagePanel() {
    }

    public static void update(int newKind, float newPanel, int newPellets) {
        kind = newKind;
        panel = newPanel;
        pellets = Math.max(1, newPellets);
    }

    public static boolean hasData() {
        return panel >= 0f;
    }

    public static int kind() {
        return kind;
    }

    public static int pellets() {
        return pellets;
    }

    /** 区间上限（面板 × 100%，向下取整） */
    public static int max() {
        return DamageVariance.displayMax(Math.max(0f, panel));
    }

    /** 区间下限（面板 × 20%，向下取整） */
    public static int min() {
        return DamageVariance.displayMin(Math.max(0f, panel));
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        update(KIND_MELEE, -1f, 1);
    }
}
