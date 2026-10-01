package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.common.ArtCharge;
import com.zhushen.space.data.ArtSkill;
import com.zhushen.space.screen.ZsAnim;
import com.zhushen.space.screen.ZsShapes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 蓄力环（准星外圈）：只有服务端确认开始蓄力后才显示；
 * 弧长 = 蓄力进度（满蓄 2 倍威力），下方小字为当前倍率；豪火球未达最短结印时间时为暗色并标出刻度；
 * 满蓄后外圈呼吸发光；松开释放时外扩一圈闪光。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class ClientCharge {
    private ClientCharge() {}

    private static boolean active;
    private static int nonce = -1, skill = -1;
    private static long startMs, endMs;
    private static float endProg;

    public static void handle(int n, boolean on, int s) {
        long now = ZsAnim.nowMs();
        if (on) {
            active = true; nonce = n; skill = s; startMs = now; endMs = 0;
        } else if (active && n == nonce) {
            end(now);
        }
    }

    /** 客户端本地结束（松键 / 取消）：立即收起，不必等服务端回包 */
    public static void clientEnd() {
        if (active) end(ZsAnim.nowMs());
    }

    private static void end(long now) {
        endProg = progress(now);
        active = false;
        endMs = now;
    }

    private static float progress(long now) {
        return ZsAnim.clamp01((now - startMs) / (ArtCharge.MAX_TICKS * 50f));
    }

    /** 当前蓄力进度 0..1；未在蓄力时返回 -1（供屏幕集中线使用） */
    public static float level() {
        long now = ZsAnim.nowMs();
        if (!active || now - startMs > 10_500) return -1f;
        return progress(now);
    }

    /** 当前蓄力技艺的主色 / 亮色 */
    public static int[] palette() { return colors(); }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) { active = false; endMs = 0; }

    private static int[] colors() {
        if (skill == ArtSkill.GREAT_FIREBALL.ordinal()) return new int[]{0xFFFF6A22, 0xFFFFE08A};
        if (skill == ArtSkill.EIGHT_FORMATION.ordinal()) {
            int[] c = {0xFFFF6D18, 0xFF80D8FF, 0xFFFFE76B, 0xFF9DEB63, 0xFFD9CEFF};
            int i = Math.max(0, Math.min(4, ClientArtData.current(ArtSkill.EIGHT_FORMATION)));
            return new int[]{c[i], ZsShapes.lerp(c[i], 0xFFFFFFFF, 0.6f)};
        }
        return new int[]{0xFF4FC3F7, 0xFFE6FBFF};
    }

    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        long now = ZsAnim.nowMs();
        boolean flash = !active && endMs > 0 && now - endMs < 260;
        if (!active && !flash) return;
        if (active && now - startMs > 10_500) { active = false; return; } // 兜底：服务端 10 秒自动取消
        GuiGraphics g = e.getGuiGraphics();
        float cx = g.guiWidth() / 2f, cy = g.guiHeight() / 2f;
        int[] col = colors();
        float r0 = 9f, r1 = 10.6f;
        if (flash) {
            float t = (now - endMs) / 260f;
            if (endProg > 0.15f) {
                float rr = r1 + 7 * ZsAnim.easeOutCubic(t);
                ZsShapes.ring(g, cx, cy, rr, rr + 1.6f * (1 - t), ZsShapes.fade(col[1], (1 - t) * endProg), ZsShapes.fade(col[0], 0));
            }
            return;
        }
        float prog = progress(now);
        float in = ZsAnim.easeOutCubic((now - startMs) / 140f);
        boolean fire = skill == ArtSkill.GREAT_FIREBALL.ordinal();
        float minP = ArtCharge.MIN_FIRE_TICKS / (float) ArtCharge.MAX_TICKS;
        boolean ready = !fire || prog >= minP;
        // 底槽
        ZsShapes.ring(g, cx, cy, r0 - 0.6f, r1 + 0.6f, ZsShapes.fade(0x50000000, in), ZsShapes.fade(0x50000000, in));
        ZsShapes.ring(g, cx, cy, r0, r1, ZsShapes.fade(0x30FFFFFF, in), ZsShapes.fade(0x20FFFFFF, in));
        // 进度弧
        if (prog > 0.003f) {
            double a0 = -Math.PI / 2, a1 = a0 + Math.PI * 2 * prog;
            int c0 = ready ? col[0] : 0xFF8A4A3A, c1 = ready ? col[1] : 0xFFB06A50;
            ZsShapes.arc(g, cx, cy, r0, r1, a0, a1, ZsShapes.fade(c0, in), ZsShapes.fade(c0, in),
                    ZsShapes.fade(c1, in), ZsShapes.fade(c1, in), true);
            float mr = (r0 + r1) / 2f;
            float hx = cx + (float) Math.cos(a1) * mr, hy = cy + (float) Math.sin(a1) * mr;
            ZsShapes.glow(g, hx, hy, 0.8f, 3f, ZsShapes.fade(c1, in * 0.8f));
        }
        // 豪火球：最短结印刻度
        if (fire) {
            double a = -Math.PI / 2 + Math.PI * 2 * minP;
            float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
            int mc2 = ready ? ZsShapes.fade(col[1], in) : ZsShapes.fade(0xFFFFFFFF, in);
            ZsShapes.line(g, cx + cs * (r0 - 1.5f), cy + sn * (r0 - 1.5f), cx + cs * (r1 + 1.5f), cy + sn * (r1 + 1.5f), 0.8f, mc2, mc2);
        }
        // 满蓄：外圈呼吸光
        if (prog >= 1f) {
            float pulse = (float) (0.55 + 0.45 * Math.sin(now / 140.0));
            ZsShapes.glow(g, cx, cy, r1, 4f, ZsShapes.fade(col[1], 0.55f * pulse * in));
        }
        // 倍率
        String txt = String.format("×%.1f", 1f + prog);
        int a8 = (int) (255 * in * (ready ? 1f : 0.6f));
        if (a8 >= 8) {
            g.pose().pushPose();
            g.pose().translate(cx, cy + r1 + 3, 0);
            g.pose().scale(0.6f, 0.6f, 1f);
            g.drawString(mc.font, txt, -mc.font.width(txt) / 2, 0, (a8 << 24) | (col[1] & 0xFFFFFF), true);
            g.pose().popPose();
        }
    }
}
