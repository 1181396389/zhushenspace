package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.network.SyncAdaptPayload;
import com.zhushen.space.screen.ZsAnim;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 客户端：魔虚罗法阵的转动（所有可见玩家）与适应进度（本人） */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class ClientAdaptData {
    private ClientAdaptData() {}

    /** 法阵转动动画：from → to 格，开始时刻 */
    private record Wheel(int from, int to, long atMs) {}

    private static final Map<Integer, Wheel> WHEELS = new HashMap<>();
    private static List<SyncAdaptPayload.Entry> entries = List.of();
    /** 本人最近一次转动的时刻（HUD 闪光） */
    private static long selfTurnMs;

    public static final long TURN_MS = 900;

    public static void update(int entityId, int wheel, List<SyncAdaptPayload.Entry> list) {
        long now = ZsAnim.nowMs();
        Wheel w = WHEELS.get(entityId);
        if (w == null || w.to != wheel) {
            float cur = w == null ? wheel : angleTurns(w, now);
            WHEELS.put(entityId, new Wheel(Math.round(cur), wheel, w == null ? now - TURN_MS : now));
            Minecraft mc = Minecraft.getInstance();
            if (w != null && wheel > w.to && mc.player != null && mc.player.getId() == entityId) selfTurnMs = now;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getId() == entityId) entries = list;
    }

    private static float angleTurns(Wheel w, long now) {
        float t = ZsAnim.clamp01((now - w.atMs) / (float) TURN_MS);
        return w.from + (w.to - w.from) * ZsAnim.easeOutBack(t);
    }

    /** 法阵当前角度（以「格」计，1 格 = 45°；带回弹缓动） */
    public static float turns(int entityId) {
        Wheel w = WHEELS.get(entityId);
        return w == null ? 0 : angleTurns(w, ZsAnim.nowMs());
    }

    /** 转动后的闪光强度 0~1 */
    public static float flash(int entityId) {
        Wheel w = WHEELS.get(entityId);
        if (w == null || w.to <= w.from) return 0;
        return 1f - ZsAnim.clamp01((ZsAnim.nowMs() - w.atMs) / 1200f);
    }

    public static List<SyncAdaptPayload.Entry> entries() { return entries; }

    public static long selfTurnMs() { return selfTurnMs; }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        WHEELS.clear();
        entries = List.of();
    }
}
