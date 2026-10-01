package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.network.SyncGearPayload;
import com.zhushen.space.screen.ZsAnim;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 客户端：装备位穿脱状态（锁定判定 + 穿戴进度 HUD） */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class ClientGearData {
    private ClientGearData() {}

    public record View(String key, String item, byte state, long endMs, long totalMs, boolean locked) {
        public float progress(long now) {
            if (totalMs <= 0) return 1f;
            return ZsAnim.clamp01(1f - (endMs - now) / (float) totalMs);
        }

        public int secondsLeft(long now) { return (int) Math.max(0, Math.ceil((endMs - now) / 1000.0)); }
    }

    private static final Map<String, View> VIEWS = new LinkedHashMap<>();
    private static int bodyFlags;

    public static void update(List<SyncGearPayload.Entry> entries, int flags) {
        long now = ZsAnim.nowMs();
        VIEWS.clear();
        for (SyncGearPayload.Entry e : entries) {
            VIEWS.put(e.key(), new View(e.key(), e.item(), e.state(), now + e.remain() * 50L, e.total() * 50L, e.locked()));
        }
        bodyFlags = flags;
    }

    public static boolean locked(String key) {
        View v = VIEWS.get(key);
        return v != null && v.locked;
    }

    public static View get(String key) { return VIEWS.get(key); }

    public static Iterable<View> all() { return VIEWS.values(); }

    public static int bodyFlags() { return bodyFlags; }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        VIEWS.clear();
        bodyFlags = 0;
    }
}
