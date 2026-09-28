package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.screen.ZsAnim;
import com.zhushen.space.screen.ZsTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端打坐状态：记录正在盘坐调息的玩家（实体 id → 开始时刻/时长），
 * 驱动 KosmX Player Animator 盘坐动作（可选依赖，未安装时仅显示 HUD）与本地打坐进度 HUD。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class ClientMeditation {

    private ClientMeditation() {
    }

    private static final Map<Integer, long[]> ACTIVE = new HashMap<>();

    /** 由 {@link ClientSetup}（MOD 总线）在客户端初始化时调用 */
    static void init(FMLClientSetupEvent event) {
        ClientAnims.init(event);
    }

    /** 网络包入口：ticks &gt; 0 开始，0 结束 */
    public static void handle(int entityId, int ticks) {
        Minecraft mc = Minecraft.getInstance();
        Entity entity = mc.level != null ? mc.level.getEntity(entityId) : null;
        if (ticks > 0) {
            ACTIVE.put(entityId, new long[]{ZsAnim.nowMs(), ticks * 50L});
        } else if (ACTIVE.remove(entityId) == null) {
            return;
        }
        if (entity instanceof AbstractClientPlayer player) {
            // KosmX Player Animator：盘坐调息（循环）/ 收功起身
            ClientAnims.play(player, ticks > 0 ? "meditate" : "meditate_end", ticks > 0 ? 8 : 3);
        }
    }

    public static boolean isMeditating(int entityId) {
        return ACTIVE.containsKey(entityId);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ACTIVE.clear();
    }

    /** 本地玩家打坐进度：屏幕中下方旋转法阵 + 流光进度条 + "调息中" */
    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        long[] st = ACTIVE.get(mc.player.getId());
        if (st == null) return;
        long elapsed = ZsAnim.nowMs() - st[0];
        if (elapsed > st[1] + 1000) { // 兜底：丢包时超时自动结束
            ACTIVE.remove(mc.player.getId());
            return;
        }
        float prog = ZsAnim.clamp01(elapsed / (float) st[1]);
        float in = ZsAnim.easeOutCubic(elapsed / 300f);
        GuiGraphics g = event.getGuiGraphics();
        int cx = g.guiWidth() / 2;
        int y = g.guiHeight() - 110;
        int alpha = (int) (255 * in);
        int size = 56;
        ZsAnim.SIGIL.draw(g, cx - size / 2, y - size / 2, size, size, (int) (alpha * 0.85f) << 24 | 0xFFFFFF);
        ZsAnim.TAIJI.draw(g, cx - 10, y - 10, 20, 20, alpha << 24 | 0xFFFFFF);
        int bw = 90;
        ZsTheme.flowBar(g, 0x6D656469L, cx - bw / 2, y + size / 2 + 2, bw, 3, prog, 0xFF4FC3F7, false);
        Component text = Component.translatable("hud.zhushenspace.meditating", (int) (prog * 100));
        g.drawCenteredString(mc.font, text, cx, y + size / 2 + 8, ZsAnim.withAlpha(ZsTheme.TEXT_TITLE, in));
    }
}
