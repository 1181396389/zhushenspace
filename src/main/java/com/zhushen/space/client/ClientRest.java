package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.screen.ZsAnim;
import com.zhushen.space.screen.ZsShapes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 休息 HUD（短休 / 长休专用，不套用任何身体动作）：
 * 左侧是进度环 + 随呼吸（4 秒吸气 / 4 秒呼气）起伏的核心，核心里是火苗（短休）或新月（长休）；
 * 右侧是名称、剩余时间、呼吸提示与中断条件。结束时有完成光圈 / 中断收拢的收尾动画。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class ClientRest {

    private ClientRest() {
    }

    /** 当前休息：0 无，1 短休，2 长休 */
    private static int kind;
    private static long startMs, durMs;
    /** 收尾动画 */
    private static int endKind, result;
    private static long endMs;
    private static float endProg;

    private static final long OUTRO_MS = 900;
    private static final long BREATH_MS = 8000;

    public static void handle(byte k, int ticks, byte res) {
        long now = ZsAnim.nowMs();
        if (k != 0) {
            kind = k;
            startMs = now;
            durMs = Math.max(1, ticks) * 50L;
            endMs = 0;
            result = 0;
        } else {
            if (kind != 0) {
                endKind = kind;
                endProg = progress(now);
                result = res;
                endMs = now;
            }
            kind = 0;
        }
    }

    /** 正在休息（打坐 HUD 在此期间让位） */
    public static boolean active() {
        return kind != 0;
    }

    private static float progress(long now) {
        return ZsAnim.clamp01((now - startMs) / (float) durMs);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        kind = 0;
        endMs = 0;
    }

    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        long now = ZsAnim.nowMs();
        if (kind != 0 && now - startMs > durMs + 3000) { // 兜底：丢包时超时自动结束
            kind = 0;
            endMs = 0;
        }
        boolean outro = kind == 0 && endMs > 0 && now - endMs < OUTRO_MS;
        if (kind == 0 && !outro) return;

        int k = kind != 0 ? kind : endKind;
        float ot = outro ? (now - endMs) / (float) OUTRO_MS : 0f;
        float alpha = outro ? 1f - ZsAnim.clamp01((ot - 0.35f) / 0.65f) : ZsAnim.easeOutCubic((now - startMs) / 350f);
        if (alpha <= 0.01f) return;
        float prog = outro ? endProg : progress(now);
        boolean done = outro && result == 1, broken = outro && result != 1;
        if (done) prog = Math.min(1f, endProg + (1f - endProg) * ZsAnim.easeOutCubic(ot / 0.25f));
        if (broken) prog = endProg * (1f - ZsAnim.easeOutCubic(ot / 0.45f));

        // 配色：短休 = 暖橙（篝火），长休 = 月蓝；中断时褪成暗红
        int hi = k == 2 ? 0xFFD6E4FF : 0xFFFFD98A, lo = k == 2 ? 0xFF6F8CFF : 0xFFFF8238;
        int core = k == 2 ? 0xFF203050 : 0xFF3A2414, glowC = k == 2 ? 0xFF7FA0FF : 0xFFFF9A40;
        if (broken) {
            float r = ZsAnim.clamp01(ot / 0.2f);
            hi = ZsShapes.lerp(hi, 0xFFFF8A7A, r);
            lo = ZsShapes.lerp(lo, 0xFFB02A20, r);
            glowC = ZsShapes.lerp(glowC, 0xFFE04030, r);
        }

        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;
        int w = g.guiWidth(), h = g.guiHeight();

        // 文字内容
        long elapsed = outro ? (long) (endProg * durMs) : now - startMs;
        long remain = Math.max(0, durMs - elapsed);
        long sec = (remain + 999) / 1000;
        String title = Component.translatable(k == 2 ? "hud.zhushenspace.rest.long" : "hud.zhushenspace.rest.short").getString();
        String time = outro ? Component.translatable(done ? "hud.zhushenspace.rest.done" : "hud.zhushenspace.rest.broken").getString()
                : String.format("%d:%02d", sec / 60, sec % 60);
        float phase = ((now - startMs) % BREATH_MS) / (float) BREATH_MS;
        boolean inhale = phase < 0.5f;
        float breath = (float) (0.5 - 0.5 * Math.cos(phase * Math.PI * 2)); // 0 → 1（吸气）→ 0（呼气）
        if (outro) breath *= 1f - ot;
        String breathTxt = Component.translatable(inhale ? "hud.zhushenspace.rest.inhale" : "hud.zhushenspace.rest.exhale").getString();
        String hint = Component.translatable("hud.zhushenspace.rest.hint").getString();

        float R0 = 19f, R1 = 22f, box = (R1 + 5) * 2;
        float textW = Math.max(font.width(title + "  " + time), Math.max(font.width(breathTxt) * 0.75f, font.width(hint) * 0.62f));
        float groupW = box + 8 + textW;
        float gx = w / 2f - groupW / 2f;
        float cx = gx + box / 2f, cy = Math.max(h / 2f + 36, h - 100f);
        float tx = gx + box + 8;

        // ---------- 底板 ----------
        ZsShapes.roundRect(g, gx - 6, cy - R1 - 7, groupW + 14, (R1 + 7) * 2, R1 + 7,
                ZsShapes.fade(0x9A0C0A12, alpha), ZsShapes.fade(0x9A06050A, alpha));
        ZsShapes.roundRectOutline(g, gx - 6, cy - R1 - 7, groupW + 14, (R1 + 7) * 2, R1 + 7, 0.6f, ZsShapes.fade(ZsShapes.lerp(lo, 0x00000000, 0.55f), alpha * 0.8f));

        // ---------- 进度环 ----------
        ZsShapes.ring(g, cx, cy, R0, R1, ZsShapes.fade(0x26FFFFFF, alpha), ZsShapes.fade(0x14FFFFFF, alpha));
        // 刻度：24 格，已走过的点亮
        for (int i = 0; i < 24; i++) {
            double a = -Math.PI / 2 + i * Math.PI * 2 / 24;
            boolean lit = i / 24f <= prog;
            float r0 = R1 + 1.6f, r1 = R1 + (i % 6 == 0 ? 3.6f : 2.8f);
            float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
            int c = lit ? ZsShapes.fade(ZsShapes.lerp(lo, hi, i / 24f), alpha * 0.9f) : ZsShapes.fade(0x38FFFFFF, alpha);
            ZsShapes.line(g, cx + cs * r0, cy + sn * r0, cx + cs * r1, cy + sn * r1, 0.6f, c, c);
        }
        if (prog > 0.002f) {
            double a0 = -Math.PI / 2, a1 = a0 + Math.PI * 2 * prog;
            ZsShapes.arc(g, cx, cy, R1 + 0.4f, R1 + 4.5f, a0, a1, ZsShapes.fade(lo, alpha * 0.3f), ZsShapes.clear(lo),
                    ZsShapes.fade(hi, alpha * 0.55f), ZsShapes.clear(hi), false);
            ZsShapes.arc(g, cx, cy, R0 - 0.4f, R1 + 0.4f, a0, a1, ZsShapes.fade(lo, alpha), ZsShapes.fade(lo, alpha),
                    ZsShapes.fade(hi, alpha), ZsShapes.fade(hi, alpha), true);
            // 起点圆头 + 末端光点
            float mr = (R0 + R1) / 2f, hw = (R1 - R0) / 2f + 0.4f;
            ZsShapes.disc(g, cx, cy - mr, hw, ZsShapes.fade(lo, alpha));
            float ex = cx + (float) Math.cos(a1) * mr, ey = cy + (float) Math.sin(a1) * mr;
            ZsShapes.glow(g, ex, ey, hw, 5f, ZsShapes.fade(hi, alpha * 0.7f));
            ZsShapes.disc(g, ex, ey, hw + 0.3f, ZsShapes.fade(0xFFFFFFFF, alpha), ZsShapes.fade(hi, alpha));
        }

        // ---------- 呼吸核心 ----------
        float rc = 9.5f + 3.2f * breath;
        ZsShapes.glow(g, cx, cy, rc, 5f + 6f * breath, ZsShapes.fade(glowC, alpha * (0.22f + 0.38f * breath)));
        ZsShapes.disc(g, cx, cy, rc, ZsShapes.fade(ZsShapes.lerp(core, glowC, 0.25f + 0.2f * breath), alpha),
                ZsShapes.fade(core, alpha));
        ZsShapes.ring(g, cx, cy, rc - 1.1f, rc, ZsShapes.fade(ZsShapes.lerp(lo, 0x00000000, 0.3f), alpha),
                ZsShapes.fade(hi, alpha * (0.6f + 0.4f * breath)));
        // 漂浮的微尘（在核心与进度环之间缓慢绕行）
        for (int i = 0; i < 6; i++) {
            double a = now / 2600.0 + i * Math.PI / 3;
            float rad = rc + 2.5f + (R0 - rc - 4f) * (float) (0.5 + 0.5 * Math.sin(now / 1300.0 + i * 1.7));
            float mote = (float) (0.5 + 0.5 * Math.sin(now / 700.0 + i));
            ZsShapes.disc(g, cx + (float) Math.cos(a) * rad, cy + (float) Math.sin(a) * rad, 0.55f + 0.35f * mote,
                    ZsShapes.fade(hi, alpha * (0.25f + 0.45f * mote)), ZsShapes.fade(hi, 0));
        }
        // 图标：短休 = 摇曳的火苗；长休 = 新月
        if (k == 2) {
            ZsShapes.glow(g, cx, cy, 4f, 3f, ZsShapes.fade(hi, alpha * 0.35f));
            ZsShapes.crescent(g, cx, cy, 5.4f, ZsShapes.fade(0xFFFFFFFF, alpha), ZsShapes.fade(hi, alpha));
        } else {
            float fl = (float) (Math.sin(now / 90.0) * 0.5 + Math.sin(now / 57.0) * 0.5);
            float fr = 5.6f * (1f + 0.05f * fl + 0.06f * breath);
            ZsShapes.drop(g, cx + 0.3f * fl, cy - 0.4f, fr, ZsShapes.fade(hi, alpha), ZsShapes.fade(lo, alpha), false);
            ZsShapes.drop(g, cx + 0.15f * fl, cy + 1.2f, fr * 0.52f, ZsShapes.fade(0xFFFFFFFF, alpha), ZsShapes.fade(0xFFFFE6A0, alpha), false);
        }

        // ---------- 收尾：完成光圈 ----------
        if (done) {
            float t = ZsAnim.easeOutCubic(ot);
            ZsShapes.ring(g, cx, cy, R1 + 10 * t, R1 + 10 * t + 2.5f * (1 - t),
                    ZsShapes.fade(0xFFFFFFFF, (1 - t) * alpha), ZsShapes.fade(hi, 0));
            ZsShapes.glow(g, cx, cy, R1, 8 * (1 - t), ZsShapes.fade(hi, 0.6f * (1 - t)));
        }

        // ---------- 文字 ----------
        int a8 = (int) (255 * alpha);
        if (a8 < 8) return;
        float shake = broken ? (float) Math.sin(ot * 60) * 2f * (1 - ZsAnim.clamp01(ot / 0.4f)) : 0f;
        int titleY = (int) (cy - 16);
        g.drawString(font, title, (int) (tx + shake), titleY, (a8 << 24) | 0xF3EDE0, true);
        g.drawString(font, "  " + time, (int) (tx + shake) + font.width(title), titleY, (a8 << 24) | (hi & 0xFFFFFF), true);
        // 呼吸提示：每半个呼吸周期淡入淡出
        float half = (phase % 0.5f) / 0.5f;
        float ba = outro ? 0f : (float) Math.sin(half * Math.PI);
        if (ba > 0.03f) {
            g.pose().pushPose();
            g.pose().translate(tx, cy - 3, 0);
            g.pose().scale(0.75f, 0.75f, 1f);
            g.drawString(font, breathTxt, 0, 0, ((int) (a8 * (0.35f + 0.65f * ba)) << 24) | 0xC8C0D8, false);
            g.pose().popPose();
        }
        g.pose().pushPose();
        g.pose().translate(tx, cy + 8, 0);
        g.pose().scale(0.62f, 0.62f, 1f);
        g.drawString(font, hint, 0, 0, ((int) (a8 * 0.7f) << 24) | 0x9A93A6, false);
        g.pose().popPose();
    }
}
