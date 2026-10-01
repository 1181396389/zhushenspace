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
    /** 收到 entries 的时刻（推算遗忘倒计时） */
    private static long entriesAt;
    /** 上一份 entries 中各现象的转动格数（用于行闪光） */
    private static final Map<String, Long> ROW_FLASH = new HashMap<>();
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
        if (mc.player != null && mc.player.getId() == entityId) {
            Map<String, Integer> before = new HashMap<>();
            for (SyncAdaptPayload.Entry e : entries) before.put(String.join("|", e.label()), e.turns());
            for (SyncAdaptPayload.Entry e : list) {
                String k = String.join("|", e.label());
                Integer b = before.get(k);
                if (b != null && e.turns() > b) ROW_FLASH.put(k, now);
            }
            entries = list;
            entriesAt = now;
        }
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

    /** 头顶法阵模型的转动时长（与模型自带动画一致：45° / 0.21 秒，匀速） */
    public static final long WORLD_TURN_MS = 210;

    /** 头顶法阵当前角度（以「格」计，匀速转动） */
    public static float worldTurns(int entityId) {
        Wheel w = WHEELS.get(entityId);
        if (w == null) return 0;
        int steps = Math.max(1, Math.abs(w.to - w.from));
        float t = ZsAnim.clamp01((ZsAnim.nowMs() - w.atMs) / (float) (WORLD_TURN_MS * steps));
        return w.from + (w.to - w.from) * t;
    }

    /** 转动后的闪光强度 0~1 */
    public static float flash(int entityId) {
        Wheel w = WHEELS.get(entityId);
        if (w == null || w.to <= w.from) return 0;
        return 1f - ZsAnim.clamp01((ZsAnim.nowMs() - w.atMs) / 1200f);
    }

    public static List<SyncAdaptPayload.Entry> entries() { return entries; }

    public static long selfTurnMs() { return selfTurnMs; }

    /** 距离开始遗忘还剩的毫秒数（0 = 正在遗忘） */
    public static long graceLeftMs(SyncAdaptPayload.Entry e) {
        if (e.grace() <= 0) return 0;
        return Math.max(0, e.grace() * 50L - (ZsAnim.nowMs() - entriesAt));
    }

    /** 某现象最近一次转动的闪光强度 0~1 */
    public static float rowFlash(SyncAdaptPayload.Entry e) {
        Long at = ROW_FLASH.get(String.join("|", e.label()));
        return at == null ? 0 : 1f - ZsAnim.clamp01((ZsAnim.nowMs() - at) / 900f);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        WHEELS.clear();
        entries = List.of();
        ROW_FLASH.clear();
    }
}
