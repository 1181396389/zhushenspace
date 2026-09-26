package com.zhushen.space.screen;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;

/**
 * 属性页：命运石之门（Steins;Gate）风格。
 * <ul>
 *   <li>世界线变动率探测仪：8 支辉光管（1.048596 = 命运石之门世界线）；有未确认加点时读数漂移到 α 世界线（0.xxxxxx），
 *       确认后滚动收束回 1.048596。</li>
 *   <li>冈部伦太郎（Lab Mem 001）：白衣白 + 翻盖手机 + 未来道具研究所徽章；「重置」按钮为白衣色。</li>
 *   <li>牧濑红莉栖（Lab Mem 004）：酒红发色 + Amadeus 神经元脑；「确认」按钮为红莉栖红。</li>
 *   <li>外框「交错同调」：白（冈部）红（红莉栖）两条世界线沿面板边缘反相波动、不断交错，交点亮起辉光橙；
 *       每隔数秒（或确认加点时）振幅收束为零，两线合为一条金色世界线（同调），一道脉冲绕框一周。四角黄铜齿轮互相咬合转动。</li>
 * </ul>
 * 贴图见 tools/gen_sg_textures.py。
 */
public final class SgStyle {
    private SgStyle() {}

    // ===== 配色 =====
    public static final int BG = 0xF20C0B0E;
    public static final int NIXIE = 0xFFFF8A2A;
    public static final int NIXIE_HOT = 0xFFFFC27A;
    public static final int NIXIE_DIM = 0xFF5A3420;
    public static final int BRASS = 0xFFB08A4A;
    public static final int BRASS_DARK = 0xFF5E4726;
    /** 冈部：白衣 */
    public static final int OKABE = 0xFFE9ECEF;
    /** 红莉栖：酒红发 / 领带红 */
    public static final int KURISU = 0xFFD2413A;
    public static final int KURISU_DEEP = 0xFF8E2226;
    public static final int SYNC = 0xFFFFD98A;
    public static final int TEXT = 0xFFE6E0D4;
    public static final int TEXT_SUB = 0xFF8C8478;
    public static final int BTN = 0xFF16141A;
    public static final int BTN_HOVER = 0xFF221D1C;

    private static ResourceLocation tex(String n) {
        return ResourceLocation.fromNamespaceAndPath("zhushenspace", "textures/gui/anim/" + n + ".png");
    }

    private static final ResourceLocation NIXIE_TEX = tex("sg_nixie"), GEAR = tex("sg_gear"), BADGE = tex("sg_badge"),
            AMADEUS = tex("sg_amadeus"), PHONE = tex("sg_phone"), RADIAL = tex("flare_radial");

    // ===== 同调节奏 =====
    private static final long SYNC_PERIOD = 7000, SYNC_LEN = 1400;
    private static long forcedSync = -1;

    /** 同调强度 0..1（周期性 + 确认加点时强制触发） */
    public static float sync() {
        long now = ZsAnim.nowMs();
        float s = bump(now % SYNC_PERIOD, SYNC_LEN);
        if (forcedSync >= 0) s = Math.max(s, bump(now - forcedSync, SYNC_LEN));
        return s;
    }

    private static float bump(long t, long len) {
        if (t < 0 || t > len) return 0;
        return (float) Math.sin(Math.PI * t / len);
    }

    private static long syncStart() {
        long now = ZsAnim.nowMs();
        if (forcedSync >= 0 && now - forcedSync <= SYNC_LEN) return forcedSync;
        return now - now % SYNC_PERIOD;
    }

    // ===== 世界线变动率探测仪 =====

    public static final String STEINS_GATE = "1.048596";
    private static String meterShown = STEINS_GATE, meterTarget = STEINS_GATE;
    private static long rollStart = -1;

    /** 确认加点：世界线收束（仪表滚动 + 外框同调 + 提示音） */
    public static void converge() {
        forcedSync = ZsAnim.nowMs();
        Minecraft mc = Minecraft.getInstance();
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.6f, 0.6f));
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BEACON_POWER_SELECT, 1.8f, 0.4f));
    }

    /** 打开 / 切到本页：仪表从乱码滚动到当前读数 */
    public static void rollIn() {
        rollStart = ZsAnim.nowMs();
    }

    /** 待确认加点对应的 α 世界线读数（由分配方案决定，同一方案读数固定） */
    public static String alphaReading(int[] cur) {
        int h = 17;
        for (int v : cur) h = h * 31 + v;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        int frac = 300000 + Math.floorMod(h, 400000); // α 世界线 0.3~0.7
        return String.format("0.%06d", frac);
    }

    public static final int TUBE_W = 7, TUBE_H = 13;

    /** 绘制 8 管读数；返回右端 x */
    public static int meter(GuiGraphics g, int x, int y, String target) {
        long now = ZsAnim.nowMs();
        if (!target.equals(meterTarget)) {
            meterShown = meterTarget;
            meterTarget = target;
            rollStart = now;
        }
        float boost = sync();
        for (int i = 0; i < 8; i++) {
            char ch = meterTarget.charAt(i);
            long settle = rollStart + 180 + i * 70L;
            if (rollStart >= 0 && now < settle && ch != '.') {
                // 滚动中：阴极快速轮换
                ch = (char) ('0' + (int) ((now / 45 + i * 7) % 10));
            }
            tube(g, x + i * (TUBE_W + 1), y, ch, boost + (rollStart >= 0 && now - settle < 160 && now >= settle ? 1 : 0));
        }
        return x + 8 * (TUBE_W + 1) - 1;
    }

    /** 单支辉光管：空管 + 点亮阴极（加色辉光，微弱闪烁） */
    public static void tube(GuiGraphics g, int x, int y, char ch, float boost) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(NIXIE_TEX, x, y, TUBE_W, TUBE_H, 0, 0, 28, 52, 336, 52);
        int cell = ch == '.' ? 11 : ch >= '0' && ch <= '9' ? ch - '0' + 1 : -1;
        if (cell > 0) {
            float flicker = 0.88f + 0.12f * (float) Math.sin(ZsAnim.nowMs() / 37.0 + x * 1.7);
            additive();
            glow(g, RADIAL, x + TUBE_W / 2f, y + TUBE_H / 2f - 1, 14, 16, NIXIE, 0.22f * flicker + 0.35f * boost);
            g.setColor(1, 1, 1, Math.min(1, flicker + 0.2f * boost));
            g.blit(NIXIE_TEX, x, y, TUBE_W, TUBE_H, cell * 28, 0, 28, 52, 336, 52);
            g.setColor(1, 1, 1, 1);
            normal();
        }
        RenderSystem.disableBlend();
    }

    /** 两位数字辉光管（剩余点数等） */
    public static int nixieNumber(GuiGraphics g, int x, int y, int value, int digits) {
        String s = String.format("%0" + digits + "d", Math.max(0, Math.min(value, (int) Math.pow(10, digits) - 1)));
        for (int i = 0; i < s.length(); i++) tube(g, x + i * (TUBE_W + 1), y, s.charAt(i), 0);
        return x + s.length() * (TUBE_W + 1) - 1;
    }

    // ===== 外框：交错同调 =====

    public static void chrome(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x + 2, y + 2, x + w + 2, y + h + 2, 0x66000000);
        g.fill(x, y, x + w, y + h, BG);
        for (int sy = y + 2; sy < y + h - 1; sy += 3) g.fill(x + 1, sy, x + w - 1, sy + 1, 0x07FFFFFF); // 显像管扫描线
        g.renderOutline(x, y, w, h, BRASS_DARK);
        g.renderOutline(x + 5, y + 5, w - 10, h - 10, 0x14B08A4A);

        worldlines(g, x, y, w, h);
        gears(g, x, y, w, h);
        plates(g, x, y, w, h);
    }

    /** 沿周长参数 s 求边框中线上的点（内缩 3px），返回 {x, y, 法线x, 法线y} */
    private static void perim(float s, int x, int y, int w, int h, float[] out) {
        float in = 3, W = w - 2 * in, H = h - 2 * in;
        float per = 2 * (W + H);
        s = ((s % per) + per) % per;
        if (s < W) { out[0] = x + in + s; out[1] = y + in; out[2] = 0; out[3] = 1; }
        else if ((s -= W) < H) { out[0] = x + w - in; out[1] = y + in + s; out[2] = -1; out[3] = 0; }
        else if ((s -= H) < W) { out[0] = x + w - in - s; out[1] = y + h - in; out[2] = 0; out[3] = -1; }
        else { s -= W; out[0] = x + in; out[1] = y + h - in - s; out[2] = 1; out[3] = 0; }
    }

    private static final float[] P = new float[4];
    /** 世界线波长（px） */
    private static final int WAVE = 72;
    private static int lastA = Integer.MIN_VALUE, lastAy, lastB = Integer.MIN_VALUE, lastBy;

    private static void worldlines(GuiGraphics g, int x, int y, int w, int h) {
        float per = 2 * (w - 6 + h - 6);
        long now = ZsAnim.nowMs();
        float sy = sync();
        float amp = 2f * (1 - sy);
        float phase = now / 900f;
        // 同调脉冲：绕框一周
        float pulseS = sy > 0 ? (now - syncStart()) / (float) SYNC_LEN * per : -1000;
        for (int s = 0; s < per; s++) {
            perim(s, x, y, w, h, P);
            float k = (float) Math.sin(s * (2 * Math.PI / WAVE) - phase);
            float oa = amp * k, ob = -amp * k;
            boolean cross = Math.abs(k) < 0.18f && amp > 0.3f;
            float pd = Math.abs(s - pulseS);
            float pulse = pd < 26 ? (1 - pd / 26f) * sy : 0;
            if (sy > 0.85f) {
                int c = ZsAnim.lerpColor(SYNC, 0xFFFFFFFF, pulse);
                px(g, P[0], P[1], c);
            } else {
                int ca = cross ? NIXIE_HOT : ZsAnim.withAlpha(OKABE, 0.75f);
                int cb = cross ? NIXIE_HOT : ZsAnim.withAlpha(KURISU, 0.85f);
                if (sy > 0) {
                    ca = ZsAnim.lerpColor(ca, SYNC, sy);
                    cb = ZsAnim.lerpColor(cb, SYNC, sy);
                }
                seg(g, P[0] + P[2] * oa, P[1] + P[3] * oa, ca, true, s == 0);
                seg(g, P[0] + P[2] * ob, P[1] + P[3] * ob, cb, false, s == 0);
            }
        }
        // 交点 / 脉冲辉光
        additive();
        if (sy > 0.05f && pulseS >= 0) {
            perim(pulseS, x, y, w, h, P);
            glow(g, RADIAL, P[0], P[1], 26, 26, SYNC, sy);
        }
        if (amp > 0.3f) {
            // 交点随相位沿边框移动：sin(...)=0 处
            float first = (float) (phase / (2 * Math.PI / WAVE));
            float step = WAVE / 2f;
            for (float s = first % step; s < per; s += step) {
                perim(s, x, y, w, h, P);
                glow(g, RADIAL, P[0], P[1], 6, 6, NIXIE, 0.35f * (1 - sy));
            }
        }
        normal();
    }

    /** 连续线：与上一点之间补齐像素，避免斜段断成虚线 */
    private static void seg(GuiGraphics g, float fx, float fy, int c, boolean a, boolean first) {
        int ix = Math.round(fx), iy = Math.round(fy);
        int px = a ? lastA : lastB, py = a ? lastAy : lastBy;
        if (first || Math.abs(px - ix) > 2 || Math.abs(py - iy) > 2) { px = ix; py = iy; }
        g.fill(Math.min(px, ix), Math.min(py, iy), Math.max(px, ix) + 1, Math.max(py, iy) + 1, c);
        if (a) { lastA = ix; lastAy = iy; } else { lastB = ix; lastBy = iy; }
    }

    private static void px(GuiGraphics g, float fx, float fy, int c) {
        int ix = Math.round(fx), iy = Math.round(fy);
        g.fill(ix, iy, ix + 1, iy + 1, c);
    }

    /** 四角黄铜齿轮：大轮 + 咬合小轮，转速与半径成反比、方向相反 */
    private static void gears(GuiGraphics g, int x, int y, int w, int h) {
        float a = ZsAnim.nowMs() / 40f * (1 + 2 * sync());
        gear(g, x + 1, y + 1, 9, a);
        gear(g, x - 3, y + 13, 5, -a * 9 / 5f + 18);
        gear(g, x + w - 1, y + h - 1, 9, -a);
        gear(g, x + w + 3, y + h - 13, 5, a * 9 / 5f + 18);
    }

    private static void gear(GuiGraphics g, float cx, float cy, float r, float deg) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        g.pose().mulPose(Axis.ZP.rotationDegrees(deg));
        g.pose().scale(r * 2 / 64f, r * 2 / 64f, 1);
        g.blit(GEAR, -32, -32, 0, 0, 64, 64, 64, 64);
        g.pose().popPose();
        RenderSystem.disableBlend();
    }

    /** 下沿铭牌：左 冈部（手机 + LAB MEM 001），右 红莉栖（Amadeus + LAB MEM 004） */
    private static void plates(GuiGraphics g, int x, int y, int w, int h) {
        Font font = Minecraft.getInstance().font;
        int py = y + h - 3;
        // 冈部
        int ox = x + 16;
        String a = "LAB MEM 001  OKABE RINTARO";
        int aw = font.width(a) / 2 + 12;
        g.fill(ox, py, ox + aw, py + 7, 0xFF0C0B0E);
        g.renderOutline(ox, py, aw, 7, ZsAnim.withAlpha(OKABE, 0.8f));
        mono(g, PHONE, ox + 2, py + 1, 4, 5, OKABE, 1);
        tiny(g, font, a, ox + 8, py + 1.5f, OKABE);
        // 红莉栖
        String b = "LAB MEM 004  MAKISE KURISU";
        int bw = font.width(b) / 2 + 12;
        int bx = x + w - 20 - bw;
        g.fill(bx, py, bx + bw, py + 7, 0xFF0C0B0E);
        g.renderOutline(bx, py, bw, 7, KURISU);
        tiny(g, font, b, bx + 3, py + 1.5f, KURISU);
        mono(g, AMADEUS, bx + bw - 8, py, 7, 7, KURISU, 1);
    }

    /** 列表区水印：左 研究所徽章（冈部，缓慢旋转），右 Amadeus 神经元脑（红莉栖，突触明灭） */
    public static void watermarks(GuiGraphics g, int x, int y, int w, int h) {
        int s = Math.min(h - 10, 96);
        float rot = ZsAnim.nowMs() / 200f % 360;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.pose().pushPose();
        g.pose().translate(x + w * 0.27f, y + h / 2f, 0);
        g.pose().mulPose(Axis.ZP.rotationDegrees(rot));
        g.setColor(1, 1, 1, 0.07f);
        g.blit(BADGE, -s / 2, -s / 2, s, s, 0, 0, 128, 128, 128, 128);
        g.pose().popPose();
        float p = ZsAnim.pulse(2600);
        mono(g, AMADEUS, x + w * 0.73f - s / 2f, y + (h - s) / 2f, s, s, KURISU, 0.07f + 0.05f * p);
        g.setColor(1, 1, 1, 1);
        RenderSystem.disableBlend();
    }

    // ===== 标签 / 按钮 =====

    public static void tabs(GuiGraphics g, Font font, int mx, int my, int[] xs, int[] ws, int y, int h,
                            Component[] labels, int selected) {
        for (int i = 0; i < xs.length; i++) {
            boolean sel = i == selected;
            boolean hover = ZsTheme.over(mx, my, xs[i], y, ws[i], h);
            float t = ZsAnim.tween(ZsAnim.key(41, xs[i], y), sel ? 1 : 0, 16);
            float hv = ZsAnim.tween(ZsAnim.key(42, xs[i], y), hover && !sel ? 1 : 0, 18);
            g.fill(xs[i], y, xs[i] + ws[i], y + h, ZsAnim.lerpColor(ZsAnim.lerpColor(BTN, BTN_HOVER, hv), 0xFF241A12, t));
            g.renderOutline(xs[i], y, ws[i], h, ZsAnim.lerpColor(BRASS_DARK, BRASS, Math.max(t, hv)));
            int tc = ZsAnim.lerpColor(ZsAnim.lerpColor(TEXT_SUB, TEXT, hv), NIXIE_HOT, t);
            XytStyle.drawCenteredNoShadow(g, font, labels[i], xs[i] + ws[i] / 2, y + (h - 8) / 2 + 1, tc);
        }
        float ux = ZsAnim.tween(ZsAnim.key(43, 0, y), xs[selected], 18);
        float uw = ZsAnim.tween(ZsAnim.key(43, 1, y), ws[selected], 18);
        g.fill((int) ux + 1, y + h - 2, (int) (ux + uw) - 1, y + h - 1, NIXIE);
        additive();
        glow(g, RADIAL, ux + uw / 2, y + h - 1.5f, uw * 1.2f, 8, NIXIE, 0.5f);
        normal();
    }

    public static void darkButton(GuiGraphics g, Font font, int mx, int my, int x, int y, int w, int h, Component label) {
        boolean hover = ZsTheme.over(mx, my, x, y, w, h);
        float t = ZsAnim.tween(ZsAnim.key(44, x, y), hover ? 1 : 0, 18);
        g.fill(x, y, x + w, y + h, ZsAnim.lerpColor(BTN, BTN_HOVER, t));
        g.renderOutline(x, y, w, h, ZsAnim.lerpColor(BRASS_DARK, NIXIE, t));
        XytStyle.drawCenteredNoShadow(g, font, label, x + w / 2, y + (h - 8) / 2 + 1, ZsAnim.lerpColor(TEXT_SUB, NIXIE_HOT, t));
    }

    /** 重置 = 冈部白衣（okabe=true），确认 = 红莉栖红 */
    public static void button(GuiGraphics g, Font font, int mx, int my, int x, int y, int w, int h,
                              Component label, boolean enabled, boolean okabe) {
        int ty = y + (h - 8) / 2 + 1;
        if (!enabled) {
            g.fill(x, y, x + w, y + h, 0xFF141216);
            g.renderOutline(x, y, w, h, 0xFF3A3432);
            XytStyle.drawCenteredNoShadow(g, font, label, x + w / 2, ty, 0xFF5A5450);
            return;
        }
        boolean hover = ZsTheme.over(mx, my, x, y, w, h);
        float t = ZsAnim.tween(ZsAnim.key(45, x, y), hover ? 1 : 0, 18);
        int main = okabe ? OKABE : KURISU;
        if (okabe) {
            g.fill(x, y, x + w, y + h, ZsAnim.lerpColor(0xFF1A1A1E, OKABE, t));
            g.renderOutline(x, y, w, h, OKABE);
            XytStyle.drawCenteredNoShadow(g, font, label, x + w / 2, ty, ZsAnim.lerpColor(OKABE, 0xFF16141A, t));
        } else {
            g.fill(x, y, x + w, y + h, ZsAnim.lerpColor(KURISU_DEEP, KURISU, t));
            g.renderOutline(x, y, w, h, ZsAnim.lerpColor(KURISU, 0xFFFF8070, t));
            XytStyle.drawCenteredNoShadow(g, font, label, x + w / 2, ty, 0xFFFFF4EE);
        }
        // 呼吸辉光（有待确认改动时提示）
        additive();
        glow(g, RADIAL, x + w / 2f, y + h / 2f, w * 1.4f, h * 2.2f, main, 0.10f + 0.12f * ZsAnim.pulse(1400) + 0.15f * t);
        normal();
    }

    /** ± 按钮：切角黄铜框，悬停辉光橙 */
    public static void stepButton(GuiGraphics g, int mx, int my, int x, int y, int s, boolean plus, boolean enabled) {
        boolean hover = enabled && ZsTheme.over(mx, my, x, y, s, s);
        float t = ZsAnim.tween(ZsAnim.key(46, x, y), hover ? 1 : 0, 20);
        int edge = enabled ? ZsAnim.lerpColor(BRASS, NIXIE, t) : 0xFF3A3432;
        g.fill(x + 1, y + 1, x + s - 1, y + s - 1, enabled ? ZsAnim.lerpColor(0xFF141216, 0xFF3A1C0C, t) : 0xFF121014);
        g.fill(x + 1, y, x + s - 1, y + 1, edge);
        g.fill(x + 1, y + s - 1, x + s - 1, y + s, edge);
        g.fill(x, y + 1, x + 1, y + s - 1, edge);
        g.fill(x + s - 1, y + 1, x + s, y + s - 1, edge);
        int line = enabled ? ZsAnim.lerpColor(TEXT, NIXIE_HOT, t) : 0xFF4A4440;
        int c = s / 2;
        g.fill(x + 3, y + c, x + s - 3, y + c + 1, line);
        if (plus) g.fill(x + c, y + 3, x + c + 1, y + s - 3, line);
    }

    // ===== 列表 =====

    /** 行：暗玻璃底 + 黄铜发丝线；悬停时左侧辉光橙竖条展开 + 底线亮起 */
    public static void row(GuiGraphics g, int x, int y, int w, int h, boolean hover, boolean odd) {
        float t = ZsAnim.tween(ZsAnim.key(47, x, y), hover ? 1 : 0, 18);
        g.fill(x, y, x + w, y + h, ZsAnim.lerpColor(odd ? 0x10FFFFFF : 0x06FFFFFF, 0x1EFF8A2A, t));
        g.fill(x, y + h - 1, x + w, y + h, ZsAnim.lerpColor(0x22B08A4A, 0x88FF8A2A, t));
        if (t > 0.01f) {
            int bh = Math.max(1, Math.round((h - 2) * t));
            int by = y + (h - bh) / 2;
            g.fill(x, by, x + 2, by + bh, NIXIE);
        }
    }

    /**
     * 世界线刻度计：一条细线上 max 个节点。已保存 = 辉光橙节点（带辉光），待确认 = 红莉栖红闪烁，未达 = 暗空心。
     * 返回右端 x。
     */
    public static int worldlineGauge(GuiGraphics g, int x, int y, int max, int saved, int cur, long key) {
        int pitch = 11;
        int end = x + (max - 1) * pitch;
        float fill = ZsAnim.tween(key, saved, 12);
        float blink = 0.5f + 0.5f * ZsAnim.pulse(800);
        // 底线
        g.fill(x, y, end + 1, y + 1, 0x445A3420);
        // 已保存段（平滑延伸）
        if (fill > 0) {
            int fe = x + Math.round((Math.min(fill, max) - 1) * pitch);
            if (fe > x) g.fill(x, y, fe + 1, y + 1, NIXIE);
        }
        // 待确认段（红莉栖红虚线）
        if (cur > saved) {
            int a = x + Math.max(0, saved - 1) * pitch, b = x + (cur - 1) * pitch;
            for (int px = a; px <= b; px += 2) g.fill(px, y, px + 1, y + 1, ZsAnim.withAlpha(KURISU, blink));
        }
        additive();
        for (int i = 0; i < max; i++) {
            int nx = x + i * pitch;
            if (i < saved && fill > i + 0.5f) glow(g, RADIAL, nx + 0.5f, y + 0.5f, 9, 9, NIXIE, 0.55f);
            else if (i < cur && i >= saved) glow(g, RADIAL, nx + 0.5f, y + 0.5f, 9, 9, KURISU, 0.5f * blink);
        }
        normal();
        for (int i = 0; i < max; i++) {
            int nx = x + i * pitch;
            if (i < saved && fill > i + 0.5f) {
                g.fill(nx - 1, y - 1, nx + 2, y + 2, NIXIE);
                g.fill(nx, y, nx + 1, y + 1, 0xFFFFF0D8);
            } else if (i < cur && i >= saved) {
                g.fill(nx - 1, y - 1, nx + 2, y + 2, ZsAnim.withAlpha(KURISU, 0.4f + 0.6f * blink));
            } else {
                g.fill(nx - 1, y - 1, nx + 2, y + 2, 0xFF3A2A20);
                g.fill(nx, y, nx + 1, y + 1, 0xFF0C0B0E);
            }
        }
        return end + 3;
    }

    /** 分隔线：黄铜细线，左端小齿轮，右端分岔为 α / β 两条世界线 */
    public static void rule(GuiGraphics g, int x1, int x2, int y) {
        int split = x2 - 34;
        g.fill(x1 + 8, y, split, y + 1, 0x99B08A4A);
        for (int x = x1 + 12; x < split; x += 10) g.fill(x, y + 1, x + 1, y + 3, 0x44B08A4A);
        for (int i = 0; i < 34; i++) {
            int dy = Math.round(i * 0.12f);
            g.fill(split + i, y - dy, split + i + 1, y - dy + 1, ZsAnim.withAlpha(OKABE, 0.7f - i * 0.015f));
            g.fill(split + i, y + dy, split + i + 1, y + dy + 1, ZsAnim.withAlpha(KURISU, 0.85f - i * 0.015f));
        }
        gear(g, x1 + 3.5f, y + 0.5f, 3.5f, ZsAnim.nowMs() / 30f);
    }

    public static void scrollbar(GuiGraphics g, int x, int top, int bottom, int content, int scroll, int maxScroll) {
        if (maxScroll <= 0 || content <= 0) return;
        int trackH = bottom - top;
        g.fill(x + 1, top, x + 2, bottom, 0x665E4726);
        int thumbH = Math.max(10, trackH * trackH / Math.max(trackH, content));
        float s = ZsAnim.tween(ZsAnim.key(48, x, top), scroll, 20);
        int thumbY = top + (int) ((trackH - thumbH) * s / maxScroll);
        g.fill(x, thumbY, x + 3, thumbY + thumbH, NIXIE);
    }

    /**
     * 世界线变动（切到本页 0.45 秒）：红 / 青错位横带 + 白色扫描线，模拟 D-Mail / 时间跳跃的画面撕裂。
     * 返回当前行抖动偏移（供列表错位）。
     */
    public static void shift(GuiGraphics g, int x, int y, int w, int h, long since) {
        long t = ZsAnim.nowMs() - since;
        if (t >= 450) return;
        float k = 1 - t / 450f;
        long seed = t / 40;
        for (int i = 0; i < 6; i++) {
            float r1 = hash((int) (seed * 13 + i)), r2 = hash((int) (seed * 7 + i + 99));
            int by = y + (int) (r1 * h), bh = 1 + (int) (r2 * 5);
            int off = (int) ((r2 - 0.5f) * 16 * k);
            int c = i % 2 == 0 ? KURISU : 0xFF4FD8E8;
            g.fill(x + Math.max(0, off), by, x + w + Math.min(0, off), Math.min(y + h, by + bh), ZsAnim.withAlpha(c, 0.28f * k));
        }
        int ly = y + Math.round(h * ZsAnim.easeInOutSine(t / 450f));
        g.fill(x, ly, x + w, ly + 1, ZsAnim.withAlpha(0xFFFFFFFF, 0.7f * k));
    }

    /** 行错位抖动（切页前 300ms） */
    public static float jitter(int row, long since) {
        long t = ZsAnim.nowMs() - since;
        if (t >= 300) return 0;
        return (hash((int) (t / 40) * 31 + row) - 0.5f) * 8 * (1 - t / 300f);
    }

    // ===== 工具 =====

    public static void tiny(GuiGraphics g, Font font, String s, float x, float y, int color) {
        XytStyle.tiny(g, font, s, x, y, color);
    }

    private static void mono(GuiGraphics g, ResourceLocation tex, float x, float y, float w, float h, int rgb, float a) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.setColor((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f, a);
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(w / 64f, h / 64f, 1);
        g.blit(tex, 0, 0, 64, 64, 0, 0, 1, 1, 1, 1);
        g.pose().popPose();
        g.setColor(1, 1, 1, 1);
        RenderSystem.disableBlend();
    }

    private static void glow(GuiGraphics g, ResourceLocation tex, float cx, float cy, float w, float h, int rgb, float a) {
        if (a <= 0.01f) return;
        g.setColor((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f, Math.min(1, a));
        g.pose().pushPose();
        g.pose().translate(cx - w / 2f, cy - h / 2f, 0);
        g.pose().scale(w / 64f, h / 64f, 1);
        g.blit(tex, 0, 0, 64, 64, 0, 0, 1, 1, 1, 1);
        g.pose().popPose();
        g.setColor(1, 1, 1, 1);
    }

    private static void additive() {
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
    }

    private static void normal() {
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    private static float hash(int i) {
        int h = i * 374761393 + 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFFFF) / (float) 0x1000000;
    }
}
