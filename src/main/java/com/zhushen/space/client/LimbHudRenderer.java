package com.zhushen.space.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.LimbPart;
import com.zhushen.space.screen.ZsAnim;
import com.zhushen.space.screen.ZsTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 战斗模式肢体 HUD（仅战斗模式显示，可在界面设置中拖动 / 缩放 / 关闭 / 切换风格）。
 * <ul>
 *   <li>风格 0「LV.999 终端」：银狼式像素科幻终端，小面积。每个部位一行分段像素血条 + 百分比，
 *       扫描线、闪烁光标、受击时 RGB 分离 + 横向切片故障；断肢显示 ERR / DISCONNECTED 乱码。</li>
 *   <li>风格 1「大黑塔桌宠」：Q 版大黑塔趴在小台子上，不显示任何血条，
 *       只在事件发生时（受击、部位濒危、断肢、恢复、头部失能、双腿尽失…）用气泡提醒；
 *       危险时会蹦一下并亮起「!」，平时偶尔闲聊。</li>
 * </ul>
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class LimbHudRenderer {

    private LimbHudRenderer() {
    }

    public static final float MIN_SCALE = 0.5f, MAX_SCALE = 2.0f;
    public static final int STYLE_TERMINAL = 0, STYLE_HERTA = 1;

    private static final int TW = 90, TH = 60;   // 终端尺寸
    private static final int PW = 50, PH = 48;   // 桌宠尺寸

    private static int style() {
        return ClientUiConfig.get().limbHudStyle == STYLE_HERTA ? STYLE_HERTA : STYLE_TERMINAL;
    }

    private static int baseW() { return style() == STYLE_HERTA ? PW : TW; }

    private static int baseH() { return style() == STYLE_HERTA ? PH : TH; }

    /** 面板位置：配置优先，默认伤势面板下方右对齐 */
    public static float[] layout(int screenW, int screenH, float scale) {
        ClientUiConfig.Data cfg = ClientUiConfig.get();
        float w = baseW() * scale, h = baseH() * scale;
        float x, y;
        if (cfg.limbX < 0 || cfg.limbY < 0) {
            float[] wp = WoundHudRenderer.layout(screenW, screenH, cfg.woundScale);
            x = wp[0] + wp[2] - w;
            y = wp[1] + wp[3] + 6;
            if (y + h > screenH) y = Math.max(0, wp[1] - h - 6);
        } else {
            x = cfg.limbX;
            y = cfg.limbY;
        }
        x = Math.max(0, Math.min(x, Math.max(0, screenW - w)));
        y = Math.max(0, Math.min(y, Math.max(0, screenH - h)));
        return new float[]{x, y, w, h};
    }

    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        boolean active = CombatModeClient.combatMode() && ClientUiConfig.get().limbHudEnabled;
        if (!active) {
            Herta.reset();
            return;
        }
        if (mc.options.hideGui) return;
        GuiGraphics g = event.getGuiGraphics();
        float open = ZsAnim.easeOutCubic((ZsAnim.nowMs() - CombatModeClient.combatSince()) / 320f);
        demo = false;
        draw(g, mc.font, g.guiWidth(), g.guiHeight(), ClientLimbData.mask(mc.player.getId()), open);
    }

    /** 界面设置预览（无伤无断时显示示例数据：左臂断开、右腿 45%） */
    public static void renderPreview(GuiGraphics g, Font font, int screenW, int screenH) {
        Minecraft mc = Minecraft.getInstance();
        int mask = mc.player == null ? 0 : ClientLimbData.mask(mc.player.getId());
        demo = mask == 0 && !ClientLimbData.anyDamage();
        draw(g, font, screenW, screenH, demo ? LimbPart.LEFT_ARM.bit() : mask, 1f);
        demo = false;
    }

    // ===== 数据 =====

    private static boolean demo;

    private static float ratio(LimbPart p, int mask) {
        if ((mask & p.bit()) != 0) return 0f;
        if (demo) return p == LimbPart.RIGHT_LEG ? 0.45f : p == LimbPart.HEAD ? 0.8f : 1f;
        int max = ClientLimbData.max(p);
        return max <= 0 ? 1f : Math.max(0f, Math.min(1f, ClientLimbData.current(p) / (float) max));
    }

    private static boolean cut(LimbPart p, int mask) {
        return (mask & p.bit()) != 0;
    }

    private static long hitAt(LimbPart p) {
        return demo ? 0 : ClientLimbData.hitAt(p);
    }

    private static void draw(GuiGraphics g, Font font, int sw, int sh, int mask, float open) {
        float scale = ClientUiConfig.get().limbScale;
        float[] pos = layout(sw, sh, scale);
        g.pose().pushPose();
        g.pose().translate(pos[0], pos[1], 0);
        g.pose().scale(scale, scale, 1f);
        if (style() == STYLE_HERTA) Herta.render(g, font, mask, open, pos[0] + pos[2] / 2f < sw / 2f);
        else Terminal.render(g, font, mask, open);
        g.pose().popPose();
    }

    // =====================================================================
    // 风格 0：LV.999 像素终端
    // =====================================================================

    private static final class Terminal {
        static final int BG = 0xE00A0B1A, BG2 = 0xE0141633;
        static final int CYAN = 0xFF3DF5FF, MAGENTA = 0xFFFF4FD8, LIME = 0xFF9CFF57, YELLOW = 0xFFFFE14D;
        static final int RED = 0xFFFF3355, DIM = 0xFF2A2F55, TXT = 0xFFB8C4FF;
        static final String[] TAG = {"HD", "RA", "LA", "RL", "LL"};
        static final String GLYPH = "#$%&@!?*01<>/\\";
        static final Random RNG = new Random();

        static int color(float r) {
            return r > 0.6f ? CYAN : r > 0.3f ? YELLOW : MAGENTA;
        }

        static void render(GuiGraphics g, Font font, int mask, float open) {
            long now = System.currentTimeMillis();
            long lastHit = 0;
            for (LimbPart p : LimbPart.values()) lastHit = Math.max(lastHit, hitAt(p));
            long since = now - lastHit;
            boolean glitch = !demo && since < 260;

            // 开启：从上往下"扫描展开"
            int visH = Math.max(1, Math.round(TH * open));

            // 底板 + 像素切角
            g.fill(2, 0, TW - 2, visH, BG);
            g.fill(0, 2, TW, Math.max(2, visH - 2), BG);
            // 边框（亮点沿边框跑动）
            frame(g, now);
            if (open < 1f) {
                g.fill(0, visH - 1, TW, visH, CYAN);
                return;
            }

            // 标题栏
            g.fill(2, 2, TW - 2, 11, BG2);
            String title = "LIMB//LV.999";
            chroma(g, font, title, 4, 3, glitch ? MAGENTA : LIME, glitch);
            if ((now / 400) % 2 == 0) g.fill(4 + font.width(title) + 1, 3, 4 + font.width(title) + 5, 10, LIME); // 光标
            // 右上角：心跳像素点阵
            for (int i = 0; i < 8; i++) {
                int hgt = 1 + (int) (Math.abs(Math.sin(now / 120.0 + i * 0.8)) * (glitch ? 6 : 3));
                g.fill(TW - 22 + i * 2, 10 - hgt, TW - 21 + i * 2, 10, i % 2 == 0 ? CYAN : MAGENTA);
            }

            // 部位行
            for (int i = 0; i < LimbPart.COUNT; i++) {
                LimbPart p = LimbPart.values()[i];
                int y = 13 + i * 9;
                int dx = 0;
                long ps = now - hitAt(p);
                if (!demo && ps < 260) dx = (RNG.nextInt(5) - 2); // 横向切片故障
                row(g, font, p, TAG[i], 4 + dx, y, mask, now, ps);
            }

            // 扫描线
            for (int y = 2; y < TH - 2; y += 2) g.fill(1, y, TW - 1, y + 1, 0x14000000);
            int sweep = (int) ((now / 18) % (TH + 20)) - 10;
            if (sweep > 1 && sweep < TH - 2) g.fill(1, sweep, TW - 1, sweep + 1, 0x223DF5FF);
            // 受击：全屏 RGB 撕裂条
            if (glitch) {
                for (int k = 0; k < 3; k++) {
                    int gy = 2 + RNG.nextInt(TH - 6);
                    g.fill(RNG.nextInt(8), gy, TW - RNG.nextInt(8), gy + 1 + RNG.nextInt(2), RNG.nextBoolean() ? 0x88FF4FD8 : 0x883DF5FF);
                }
            }
        }

        static void row(GuiGraphics g, Font font, LimbPart p, String tag, int x, int y, int mask, long now, long ps) {
            boolean severed = cut(p, mask);
            float r = ratio(p, mask);
            boolean crit = !severed && r <= 0.3f;
            int tc = severed ? RED : crit && (now / 250) % 2 == 0 ? MAGENTA : TXT;
            g.drawString(font, tag, x, y, tc, false);
            int bx = x + 14, segs = 10;
            if (severed) {
                // ERR 乱码条
                boolean flip = (now / 300) % 2 == 0;
                String s = flip ? "ERR:0x" + Integer.toHexString(0xD0 + p.ordinal()).toUpperCase() : scramble(9, now / 90 + p.ordinal());
                g.fill(bx, y, bx + segs * 4, y + 7, 0x44FF3355);
                g.drawString(font, s, bx + 1, y, flip ? RED : 0xFFFF8899, false);
                g.drawString(font, "N/A", bx + segs * 4 + 4, y, (now / 500) % 2 == 0 ? RED : 0xFF801020, false);
                return;
            }
            int on = (int) Math.ceil(r * segs - 1e-4);
            int col = color(r);
            for (int i = 0; i < segs; i++) {
                int sx = bx + i * 4;
                boolean lit = i < on;
                int c = lit ? col : DIM;
                // 最末一格呼吸闪烁
                if (lit && i == on - 1 && (now / 200) % 3 == 0) c = 0xFFFFFFFF;
                g.fill(sx, y + 1, sx + 3, y + 7, c);
                if (lit) g.fill(sx, y + 1, sx + 3, y + 2, 0x66FFFFFF);
            }
            if (!demo && ps < 300) g.fill(bx, y, bx + segs * 4, y + 8, ((int) (200 * (1 - ps / 300f)) << 24) | 0xFFFFFF);
            String pct = String.format("%03d", Math.round(r * 100));
            chroma(g, font, pct, bx + segs * 4 + 4, y, col, !demo && ps < 260);
        }

        /** RGB 分离文字 */
        static void chroma(GuiGraphics g, Font font, String s, int x, int y, int col, boolean strong) {
            int o = strong ? 2 : 1;
            g.drawString(font, s, x - o, y, 0x66FF4FD8, false);
            g.drawString(font, s, x + o, y, 0x663DF5FF, false);
            g.drawString(font, s, x, y, col, false);
        }

        static String scramble(int n, long seed) {
            Random r = new Random(seed);
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < n; i++) b.append(GLYPH.charAt(r.nextInt(GLYPH.length())));
            return b.toString();
        }

        static void frame(GuiGraphics g, long now) {
            int c = 0xFF3A3F7A;
            g.fill(2, 0, TW - 2, 1, c);
            g.fill(2, TH - 1, TW - 2, TH, c);
            g.fill(0, 2, 1, TH - 2, c);
            g.fill(TW - 1, 2, TW, TH - 2, c);
            // 四角像素 L 形高亮
            int a = CYAN, b = MAGENTA;
            g.fill(0, 0, 5, 1, a); g.fill(0, 0, 1, 5, a);
            g.fill(TW - 5, TH - 1, TW, TH, b); g.fill(TW - 1, TH - 5, TW, TH, b);
            // 跑动光点
            int per = 2 * (TW + TH);
            int k = (int) ((now / 12) % per);
            int px, py;
            if (k < TW) { px = k; py = 0; }
            else if (k < TW + TH) { px = TW - 1; py = k - TW; }
            else if (k < 2 * TW + TH) { px = TW - 1 - (k - TW - TH); py = TH - 1; }
            else { px = 0; py = TH - 1 - (k - 2 * TW - TH); }
            g.fill(px - 1, py, px + 2, py + 1, LIME);
            g.fill(px, py - 1, px + 1, py + 2, LIME);
        }
    }

    // =====================================================================
    // 风格 1：Q 版大黑塔桌宠（只提醒，不显示血条）
    // =====================================================================

    private static final class Herta {
        static final ResourceLocation TEX =
                ResourceLocation.fromNamespaceAndPath("zhushenspace", "textures/gui/herta_chibi.png");
        static final int TEXW = 227, TEXH = 256, ARM_Y = 188;
        static final int DH = 40;                      // 立绘显示高度
        static final long SHOW_MS = 3800;

        record Msg(Component text, boolean alarm) {}

        static final List<Msg> queue = new ArrayList<>();
        static Msg current;
        static long shownAt, lastIdle, lastRemind;
        static boolean init;
        static int prevMask;
        static final float[] prevRatio = new float[LimbPart.COUNT];
        static final long[] prevHit = new long[LimbPart.COUNT];
        static final Random RNG = new Random();

        static void reset() {
            init = false;
            queue.clear();
            current = null;
        }

        static Component part(LimbPart p) {
            return Component.translatable("limb.zhushenspace." + p.name().toLowerCase());
        }

        static void push(String key, boolean alarm, Object... args) {
            Msg m = new Msg(Component.translatable("hud.zhushenspace.herta." + key, args), alarm);
            if (alarm) {
                // 警报插队（但不打断正在显示的另一条警报）
                queue.add(0, m);
                if (current != null && !current.alarm()) shownAt = 0;
            } else if (queue.size() < 3) {
                queue.add(m);
            }
        }

        static String pick(String base, int n) {
            return base + "." + RNG.nextInt(n);
        }

        /** 对比上一帧，生成提醒 */
        static void observe(int mask, long now) {
            if (demo) return;
            if (!init) {
                init = true;
                prevMask = mask;
                for (LimbPart p : LimbPart.values()) {
                    prevRatio[p.ordinal()] = ratio(p, mask);
                    prevHit[p.ordinal()] = ClientLimbData.hitAt(p);
                }
                lastIdle = lastRemind = now;
                push(mask != 0 ? "start_hurt" : pick("start", 3), false);
                return;
            }
            boolean legsGone = cut(LimbPart.RIGHT_LEG, mask) && cut(LimbPart.LEFT_LEG, mask);
            boolean legsWere = (prevMask & LimbPart.RIGHT_LEG.bit()) != 0 && (prevMask & LimbPart.LEFT_LEG.bit()) != 0;
            for (LimbPart p : LimbPart.values()) {
                int i = p.ordinal();
                float r = ratio(p, mask);
                boolean was = (prevMask & p.bit()) != 0, is = cut(p, mask);
                if (is && !was) {
                    if (!(legsGone && !legsWere && !p.isArm())) push(pick(p.isArm() ? "sever_arm" : "sever_leg", 3), true, part(p));
                } else if (!is && was) {
                    push(pick("restore", 2), false, part(p));
                } else if (!is) {
                    float pr = prevRatio[i];
                    if (p == LimbPart.HEAD && r <= 0f && pr > 0f) push(pick("head_out", 2), true);
                    else if (r <= 0.3f && pr > 0.3f) push(pick("critical", 3), true, part(p));
                    else if (r > 0.6f && pr <= 0.3f) push("recover", false, part(p));
                    else if (ClientLimbData.hitAt(p) != prevHit[i] && r < pr && current == null && queue.isEmpty()
                            && RNG.nextInt(3) == 0) push(pick("hit", 4), false, part(p));
                }
                prevRatio[i] = r;
                prevHit[i] = ClientLimbData.hitAt(p);
            }
            if (legsGone && !legsWere) push(pick("crawl", 2), true);
            prevMask = mask;

            // 周期提醒：有断肢 / 濒危部位时每 30 秒念叨一次
            if (now - lastRemind > 30000 && current == null && queue.isEmpty()) {
                lastRemind = now;
                List<String> bad = new ArrayList<>();
                for (LimbPart p : LimbPart.values()) {
                    if (cut(p, mask)) bad.add(part(p).getString());
                }
                if (!bad.isEmpty()) push("remind_missing", false, String.join("、", bad));
                else {
                    for (LimbPart p : LimbPart.values()) {
                        if (ratio(p, mask) <= 0.3f) { push("remind_critical", false, part(p)); break; }
                    }
                }
            }
            // 闲聊
            if (ClientUiConfig.get().limbHudQuips && now - lastIdle > 22000 && current == null && queue.isEmpty()) {
                lastIdle = now;
                boolean clean = mask == 0 && !ClientLimbData.anyDamage();
                push(pick(clean ? "idle" : "idle_hurt", 4), false);
            }
        }

        static void render(GuiGraphics g, Font font, int mask, float open, boolean bubbleRight) {
            long now = System.currentTimeMillis();
            observe(mask, now);
            if (current != null && now - shownAt > SHOW_MS) current = null;
            if (current == null && !queue.isEmpty()) {
                current = queue.remove(0);
                shownAt = now;
                if (current.alarm() && !demo) {
                    Minecraft.getInstance().getSoundManager().play(
                            SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, 0.9f, 0.9f));
                }
            }
            Msg m = demo ? new Msg(Component.translatable("hud.zhushenspace.herta.preview"), false) : current;
            long since = demo ? 1000 : now - shownAt;
            boolean alarm = m != null && m.alarm() && since < SHOW_MS;

            float s = DH / (float) TEXH;
            int dw = Math.round(TEXW * s);
            int surfaceY = PH - 10;
            int ax = (PW - dw) / 2;
            // 入场：从台子下面探出来
            float rise = (1 - open) * 30;
            // 警报：蹦跳；平时：呼吸 + 偶尔歪头（左右轻晃）
            float hop = alarm && since < 900 ? (float) (Math.abs(Math.sin(since / 900.0 * Math.PI * 3)) * 5 * (1 - since / 900f)) : 0;
            float breathe = 1 + 0.015f * (float) Math.sin(now / 650.0);
            float sway = (float) Math.sin(now / 1800.0) * 0.6f;

            // 小台子（她趴着的桌面）
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            g.pose().pushPose();
            g.pose().translate(ax + sway, surfaceY - hop + rise, 0);
            g.pose().scale(s, s * breathe, 1);
            g.blit(TEX, 0, -ARM_Y, 0, 0, TEXW, TEXH, TEXW, TEXH);
            g.pose().popPose();
            RenderSystem.disableBlend();
            // 台子盖住下半身（桌宠的"窗沿"）
            g.fill(0, surfaceY, PW, PH, 0xE6120E1C);
            g.fill(0, surfaceY, PW, surfaceY + 1, ZsTheme.ACCENT_LIGHT);
            // 台子上的小状态灯：平时薰衣草慢呼吸，警报时红色快闪（不是血条，只是心情灯）
            int lamp = alarm ? ((now / 150) % 2 == 0 ? 0xFFFF4466 : 0xFF661122)
                    : ZsAnim.withAlpha(ZsTheme.ACCENT_LIGHT, 0.4f + 0.4f * (float) Math.abs(Math.sin(now / 900.0)));
            g.fill(PW / 2 - 6, surfaceY + 4, PW / 2 + 6, surfaceY + 6, lamp);
            ZsTheme.flower(g, PW - 6, surfaceY + 5, 6, now / 40f % 360, 0.9f);
            ZsTheme.petals(g, 2, 0, PW - 4, surfaceY, 2);

            // 「!」警报标记
            if (alarm && (now / 200) % 2 == 0) {
                int ex = PW - 8, ey = 2 - (int) hop;
                g.fill(ex - 1, ey - 1, ex + 4, ey + 11, 0xFF120E1C);
                g.fill(ex, ey, ex + 3, ey + 6, 0xFFFF4466);
                g.fill(ex, ey + 7, ex + 3, ey + 10, 0xFFFF4466);
            }

            if (m != null && open >= 1f) {
                float alpha = Math.min(ZsAnim.clamp01(since / 150f), 1 - ZsAnim.clamp01((since - (SHOW_MS - 350)) / 350f));
                bubble(g, font, m.text(), bubbleRight, 12, alpha, alarm);
            }
        }

        /** 气泡画在桌宠侧面（屏幕右侧时向左伸出，左侧时向右），尖角指向她的头 */
        static void bubble(GuiGraphics g, Font font, Component text, boolean right, int headY, float alpha, boolean alarm) {
            if (alpha <= 0.02f) return;
            List<FormattedCharSequence> lines = font.split(text, 130);
            int w = 0;
            for (FormattedCharSequence l : lines) w = Math.max(w, font.width(l));
            int bw = w + 10, bh = lines.size() * 10 + 6;
            int x = right ? PW + 6 : -bw - 6;
            int y = Math.max(-bh / 2, headY - bh / 2);
            float pop = alpha < 1 ? 0.85f + 0.15f * alpha : 1f;
            int edge = ZsAnim.withAlpha(alarm ? 0xFFFF6680 : ZsTheme.ACCENT_LIGHT, alpha);
            g.pose().pushPose();
            g.pose().translate(0, 0, 200);
            float pivotX = right ? x : x + bw;
            g.pose().translate(pivotX, y + bh / 2f, 0);
            g.pose().scale(pop, pop, 1);
            g.pose().translate(-pivotX, -(y + bh / 2f), 0);
            g.fill(x, y, x + bw, y + bh, ZsAnim.withAlpha(0xFF120E1C, 0.92f * alpha));
            g.renderOutline(x, y, bw, bh, edge);
            int ty = headY;
            for (int i = 0; i < 3; i++) {
                if (right) g.fill(x - 1 - i, ty - 2 + i, x - i, ty + 3 - i, edge);
                else g.fill(x + bw + i, ty - 2 + i, x + bw + i + 1, ty + 3 - i, edge);
            }
            for (int i = 0; i < lines.size(); i++) {
                g.drawString(font, lines.get(i), x + 5, y + 4 + i * 10, ZsAnim.withAlpha(ZsTheme.TEXT_MAIN, alpha), false);
            }
            g.pose().popPose();
        }
    }
}
