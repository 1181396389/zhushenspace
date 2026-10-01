package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.client.fx.AnimeFx;
import com.zhushen.space.screen.ZsAnim;
import com.zhushen.space.screen.ZsShapes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 动漫式屏幕演出：
 * 蓄力时屏幕四周向中心收拢的集中线（随蓄力进度加密、变亮，每两帧换一次排布产生“抖动”）；
 * 大招命中（波动拳 / 豪火球爆炸）时的一帧白闪 + 黑色冲击集中线。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class ClientArtScreenFx {
    private ClientArtScreenFx() {}

    private static long impactMs;
    private static float impactStrength;
    private static final long IMPACT_LEN = 340;

    /** 触发命中演出（取较强者） */
    public static void impact(float strength) {
        long now = ZsAnim.nowMs();
        float remaining = impactMs == 0 ? 0 : impactStrength * Math.max(0, 1f - (now - impactMs) / (float) IMPACT_LEN);
        if (strength > remaining) { impactMs = now; impactStrength = Math.min(1f, strength); }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) { impactMs = 0; }

    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Pre e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        GuiGraphics g = e.getGuiGraphics();
        long now = ZsAnim.nowMs();
        float w = g.guiWidth(), h = g.guiHeight();

        float charge = ClientCharge.level();
        if (charge >= 0f && mc.options.getCameraType().isFirstPerson()) {
            int seed = (int) (now / 70);
            int col = ClientCharge.palette()[1] & 0xFFFFFF;
            float alpha = 0.05f + 0.20f * charge + (charge >= 1f ? 0.06f * (0.5f + 0.5f * Mth.sin(now / 90f)) : 0f);
            focusLines(g, w, h, 18 + (int) (26 * charge), 0.80f - 0.22f * charge, col, alpha, seed);
        }

        if (impactMs > 0) {
            long dt = now - impactMs;
            if (dt >= IMPACT_LEN) { impactMs = 0; return; }
            float k = impactStrength;
            if (dt < 70) g.fill(0, 0, (int) Math.ceil(w), (int) Math.ceil(h), AnimeFx.argb(0xFFFFFF, 0.42f * k * (1f - dt / 70f)));
            float t = dt / (float) IMPACT_LEN;
            focusLines(g, w, h, 48, 0.38f + 0.25f * t, 0x000000, 0.55f * k * (1f - t), 9000 + (int) (dt / 50));
        }
    }

    /** 集中线：从屏幕外沿指向中心的细长楔形，内端透明 */
    private static void focusLines(GuiGraphics g, float w, float h, int n, float inner, int rgb, float alpha, int seed) {
        float cx = w / 2f, cy = h / 2f;
        float R = Mth.sqrt(cx * cx + cy * cy);
        for (int i = 0; i < n; i++) {
            float a = AnimeFx.TAU * (i + AnimeFx.hash(seed * 131 + i * 7)) / n;
            float ri = R * (inner + 0.18f * AnimeFx.hash(seed * 17 + i * 3));
            float ro = R * 1.05f;
            float half = 0.006f + 0.016f * AnimeFx.hash(seed * 29 + i * 11);
            float al = alpha * (0.55f + 0.45f * AnimeFx.hash(seed * 41 + i));
            int cOut = AnimeFx.argb(rgb, al), cIn = AnimeFx.argb(rgb, 0f);
            ZsShapes.tri(g,
                    cx + Mth.cos(a - half) * ro, cy + Mth.sin(a - half) * ro, cOut,
                    cx + Mth.cos(a) * ri, cy + Mth.sin(a) * ri, cIn,
                    cx + Mth.cos(a + half) * ro, cy + Mth.sin(a + half) * ro, cOut);
        }
    }
}
