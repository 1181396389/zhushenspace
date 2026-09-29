package com.zhushen.space.screen;

import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;

import static com.zhushen.space.screen.JjkStyle.*;

/**
 * 专长页：领域对峙。
 * 背景左「无量空处」（无垠星空、旋转星系、朝观者奔涌的信息流），右「伏魔御厨子」（猩红天幕、血海、骨山、
 * 带獠牙巨口的神社），两个领域在中缝互相侵蚀（边界来回推挤、雷光与火花）。
 * 外框为立体浮雕框：左半冷钢蓝、右半黑漆朱红，带倒角高光 / 阴影与角钉；
 * 底部中央两枚立体徽记对峙 —— 左：蓝色无限球 + 三轴旋转光环；右：赤黑球 + 倾斜旋转的獠牙环。
 */
public final class JjkDomain {
    private JjkDomain() {}

    /** 领域边界 x（随时间来回推挤） */
    public static float seam(int x, int w, float t) {
        return x + w / 2f + (float) (Math.sin(t * 0.7) * w * 0.05 + Math.sin(t * 1.9) * w * 0.015);
    }

    // ===================== 背景 =====================

    public static void backdrop(GuiGraphics g, int x, int y, int w, int h, float open) {
        float t = ZsAnim.nowMs() / 1000f;
        int sx = Math.round(seam(x, w, t));
        int mid = x + w / 2;
        int ex = Math.round(w / 2f * open) + 2;
        g.fill(x, y, x + w, y + h, 0xFF000000);

        // —— 左：无量空处 ——
        int lx0 = Math.max(x, mid - ex), lx1 = Math.min(sx, mid + ex);
        if (lx1 > lx0) {
            g.enableScissor(lx0, y, lx1, y + h);
            voidDomain(g, x, y, sx, h, w, t, open);
            g.disableScissor();
        }

        // —— 右：伏魔御厨子 ——
        int rx0 = Math.max(sx, mid - ex), rx1 = Math.min(x + w, mid + ex);
        if (rx1 > rx0) {
            g.enableScissor(rx0, y, rx1, y + h);
            malevolentShrine(g, sx, y, x + w, h, t);
            // 斩击闪现（领域内无差别斩）
            long now = ZsAnim.nowMs();
            for (int i = 0; i < 5; i++) {
                long cyc = (now + i * 331) / 1100;
                float ph = ((now + i * 331) % 1100) / 1100f;
                if (ph > 0.3f) continue;
                float cx = sx + hash(i, cyc) * (x + w - sx), cy = y + hash(i + 9, cyc) * h;
                float ang = (float) (hash(i + 5, cyc) * Math.PI), len = 20 + hash(i + 3, cyc) * 40;
                float a = 1 - ph / 0.3f;
                float c = (float) Math.cos(ang) * len / 2, s = (float) Math.sin(ang) * len / 2;
                line(g, cx - c, cy - s, cx + c, cy + s, 1, alpha(0xFFFFFFFF, a * 0.8f));
                line(g, cx - c, cy - s + 1, cx + c, cy + s + 1, 1, alpha(SUKUNA, a * 0.5f));
            }
            g.disableScissor();
        }

        // —— 领域对撞：边界雷光 ——
        if (open >= 1f) {
            float prevX = sx, prevY = y;
            long seed = ZsAnim.nowMs() / 70;
            for (int yy = y + 6; yy <= y + h; yy += 6) {
                float nx = sx + (hash(yy, seed) - 0.5f) * 8;
                line(g, prevX, prevY, nx, yy, 3, 0x559A5CFF);
                line(g, prevX, prevY, nx, yy, 1, 0xFFF4ECFF);
                prevX = nx; prevY = yy;
            }
            g.fillGradient(sx - 6, y, sx, y + h, 0x006FD3FF, 0x336FD3FF);
            g.fillGradient(sx, y, sx + 6, y + h, 0x33E0253A, 0x00E0253A);
            for (int i = 0; i < 14; i++) {
                long cyc = (ZsAnim.nowMs() + i * 71) / 380;
                float ph = ((ZsAnim.nowMs() + i * 71) % 380) / 380f;
                float py = y + hash(i, cyc) * h;
                int dir = i % 2 == 0 ? -1 : 1;
                float px = sx + dir * ph * (6 + hash(i + 4, cyc) * 18);
                g.fill((int) px, (int) (py + ph * 6), (int) px + 1, (int) (py + ph * 6) + 1,
                        alpha(dir < 0 ? GOJO_LIGHT : SUKUNA_GLOW, 1 - ph));
            }
        }
    }

    /** 伏魔御厨子神社剪影：台基、立柱、三重翘檐屋顶、正面獠牙巨口、檐角牛骨 */
    private static int hsv(float hue, float sat, float val) {
        hue = ((hue % 1f) + 1f) % 1f;
        float h6 = hue * 6, f = h6 - (int) h6;
        float p = val * (1 - sat), q = val * (1 - sat * f), u = val * (1 - sat * (1 - f));
        float r, gg, b;
        switch ((int) h6) {
            case 0 -> { r = val; gg = u; b = p; }
            case 1 -> { r = q; gg = val; b = p; }
            case 2 -> { r = p; gg = val; b = u; }
            case 3 -> { r = p; gg = q; b = val; }
            case 4 -> { r = u; gg = p; b = val; }
            default -> { r = val; gg = p; b = q; }
        }
        return 0xFF000000 | ((int) (r * 255) << 16) | ((int) (gg * 255) << 8) | (int) (b * 255);
    }

    /**
     * 无量空处（动画版）：墨色云翳中撕开一道白光裂隙，巨大的「黑洞之眼」带虹彩光环缓慢旋转，
     * 墨点碎屑被吸入，周期性闪白（信息洪流灌入的瞬间）。
     */
    private static void voidDomain(GuiGraphics g, int x, int y, int sx, int h, int w, float t, float open) {
        int x1 = sx;
        // 底：深墨蓝 → 冷灰（云翳）
        g.fillGradient(x, y, x1, y + h, 0xFF0B0F1A, 0xFF1A2130);
        // 墨云团块（缓慢漂移）
        for (int i = 0; i < 22; i++) {
            float cx = x + ((hash(i, 301) * (w * 0.6f) + t * (3 + hash(i, 302) * 5)) % (w * 0.6f));
            float cy = y + hash(i, 303) * h;
            float r = 8 + hash(i, 304) * 22;
            disk(g, cx, cy, r, 0.7f, alpha(0xFF02030A, 0.35f));
        }
        float gx = x + w * 0.23f, gy = y + h * 0.46f;
        float appear = ZsAnim.easeOutBack(ZsAnim.clamp01(open * 1.2f));
        float R = Math.min(w * 0.2f, h * 0.36f) * appear;
        // —— 白光裂隙：斜向贯穿，边缘为撕裂墨块 ——
        float slope = -0.32f;
        float rift = 0.5f + 0.5f * (float) Math.sin(t * 0.9);
        for (int px = x; px < x1; px += 2) {
            float cy = gy + (px - gx) * slope;
            float n1 = hash(px / 2, 311), n2 = hash(px / 2, 312);
            float hw = (R * 0.18f + 3) * (0.7f + 0.3f * rift) + (n1 - 0.5f) * 6;
            float hw2 = hw + 2 + n2 * 5;
            g.fill(px, (int) (cy - hw2), px + 2, (int) (cy + hw2), alpha(0xFFBFD6EA, 0.35f));
            g.fill(px, (int) (cy - hw), px + 2, (int) (cy + hw), 0xFFF4F8FF);
            // 墨块啃噬边缘
            if (n2 > 0.72f) {
                int bs = 1 + (int) (hash(px, 313) * 4);
                g.fill(px, (int) (cy - hw) - 1, px + bs, (int) (cy - hw) + bs, 0xFF05060A);
            }
            if (n1 > 0.75f) {
                int bs = 1 + (int) (hash(px, 314) * 4);
                g.fill(px, (int) (cy + hw) - bs, px + bs, (int) (cy + hw) + 1, 0xFF05060A);
            }
        }
        if (R < 2) return;
        // —— 外围吸积旋涡：灰白流线顺时针旋转 ——
        float rot = t * 0.35f;
        for (int a = 0; a < 240; a++) {
            double ang = a * Math.PI * 2 / 240;
            for (int k = 0; k < 14; k++) {
                float d = k / 14f;
                float rr = R * (1.02f + d * 0.75f);
                double aa = ang + rot + d * 1.4;
                float streak = (float) (0.5 + 0.5 * Math.sin(ang * 9 + d * 7 - t * 1.3));
                float av = (1 - d) * (0.15f + 0.55f * streak);
                int px = (int) (gx + Math.cos(aa) * rr), py = (int) (gy + Math.sin(aa) * rr * 0.92f);
                g.fill(px, py, px + 1, py + 1, alpha(k < 3 ? 0xFFE6EEF8 : 0xFF8FA0B8, av));
            }
        }
        // —— 虹彩光环（金 → 青 → 紫，随角度与时间流转）——
        for (int a = 0; a < 360; a++) {
            double ang = a * Math.PI / 180;
            float lum = (float) (0.55 + 0.45 * Math.sin(ang * 2 - t * 2.1));
            int col = hsv(0.12f + (float) Math.sin(ang + t * 0.6) * 0.35f, 0.55f, 1f);
            for (int k = 0; k < 3; k++) {
                float rr = R * (0.94f + k * 0.035f);
                int px = (int) (gx + Math.cos(ang) * rr), py = (int) (gy + Math.sin(ang) * rr * 0.92f);
                g.fill(px, py, px + 1, py + 1, alpha(k == 1 ? 0xFFFFFFFF : col, lum * (k == 1 ? 0.9f : 0.7f)));
            }
        }
        // —— 视界：纯黑 + 内侧暗蓝旋纹 ——
        disk(g, gx, gy, R * 0.92f, 0.92f, 0xFF000000);
        for (int j = 0; j < 120; j++) {
            float d = j / 120f;
            double aa = -t * 0.8 + d * 9;
            float rr = R * (0.88f - d * 0.5f);
            int px = (int) (gx + Math.cos(aa) * rr), py = (int) (gy + Math.sin(aa) * rr * 0.92f);
            g.fill(px, py, px + 1, py + 1, alpha(0xFF3A5A8A, 0.5f * (1 - d)));
        }
        // 深处一点闪烁的光（无限）
        float blink = (float) Math.pow(0.5 + 0.5 * Math.sin(t * 1.7), 6);
        disk(g, gx, gy, 1 + 1.5f * blink, 1f, alpha(0xFFFFFFFF, 0.4f + 0.6f * blink));
        // —— 被吸入的墨点碎屑 ——
        for (int i = 0; i < 60; i++) {
            float ph = (t * (0.12f + hash(i, 321) * 0.2f) + hash(i, 322)) % 1f;
            double ang = hash(i, 323) * Math.PI * 2 + ph * 2.2;
            float rr = R * (2.4f - ph * 1.45f);
            int px = (int) (gx + Math.cos(ang) * rr), py = (int) (gy + Math.sin(ang) * rr * 0.92f);
            int sz = hash(i, 324) > 0.7f ? 2 : 1;
            g.fill(px, py, px + sz, py + sz, alpha(0xFF05060A, 0.9f * (1 - ph * 0.6f)));
        }
        // —— 信息洪流闪白：约每 7 秒一次 ——
        float fl = (t % 7f);
        if (fl < 0.35f) g.fill(x, y, x1, y + h, alpha(0xFFFFFFFF, (0.35f - fl) / 0.35f * 0.55f));
        // 暗角
        g.fillGradient(x, y, x1, y + h / 5, 0x88000000, 0x00000000);
        g.fillGradient(x, y + h - h / 5, x1, y + h, 0x00000000, 0x88000000);
    }

    // ============ 伏魔御厨子（动画版）：血色涡云天幕 + 神龛 + 黑水镜面倒影 + 青色水光 ============
    private static float MY = Float.NaN; // 镜像轴（NaN = 不镜像）

    private static float my(float yy) { return Float.isNaN(MY) ? yy : 2 * MY - yy; }

    private static void mrect(GuiGraphics g, float x0, float y0, float x1, float y1, int col) {
        float a = my(y0), b = my(y1);
        rect(g, x0, Math.min(a, b), x1, Math.max(a, b), col);
    }

    private static void mline(GuiGraphics g, float x0, float y0, float x1, float y1, float w, int col) {
        line(g, x0, my(y0), x1, my(y1), w, col);
    }

    private static void mtri(GuiGraphics g, float ax, float ay, float bx, float by, float cx, float cy, int col) {
        tri(g, ax, my(ay), bx, my(by), cx, my(cy), col);
    }

    private static void malevolentShrine(GuiGraphics g, int x0, int y, int x1, int h, float t) {
        int w = x1 - x0;
        int waterY = y + (int) (h * 0.52f);
        // 天幕：纯黑 → 暗红
        g.fillGradient(x0, y, x1, waterY, 0xFF030001, 0xFF1A0205);
        // 血色涡云：多条正弦流带，缓慢翻涌
        for (int band = 0; band < 7; band++) {
            float by = y + h * (0.05f + band * 0.065f);
            float amp = 4 + band * 1.5f, fq = 0.03f + hash(band, 401) * 0.03f, sp = 0.4f + hash(band, 402) * 0.6f;
            for (int px = x0; px < x1; px += 1) {
                float yy = by + (float) Math.sin(px * fq + t * sp + band) * amp
                        + (float) Math.sin(px * fq * 2.7f - t * sp * 1.3f) * amp * 0.4f;
                float den = (float) (0.5 + 0.5 * Math.sin(px * 0.05 + t * 0.7 + band * 2));
                int th = 1 + (int) (den * 3);
                g.fill(px, (int) yy, px + 1, (int) yy + th, alpha(band % 2 == 0 ? 0xFFB0121E : 0xFF6A0810, 0.18f + 0.3f * den));
            }
        }
        // 涡旋：两团旋转的血云
        for (int v = 0; v < 2; v++) {
            float vx = x0 + w * (v == 0 ? 0.2f : 0.82f), vy = y + h * 0.14f;
            for (int j = 0; j < 140; j++) {
                float d = j / 140f;
                double a = d * 10 + t * (v == 0 ? 0.6 : -0.6);
                float r = d * w * 0.16f;
                int px = (int) (vx + Math.cos(a) * r), py = (int) (vy + Math.sin(a) * r * 0.4f);
                g.fill(px, py, px + 2, py + 1, alpha(0xFFD0202E, 0.35f * (1 - d)));
            }
        }
        float cx = x0 + w / 2f + 4;
        float size = Math.min(h * 0.46f, w * 0.62f);
        // 神龛（正像）
        MY = Float.NaN;
        shrine2(g, cx, waterY - 2, size, t, 1f);
        // —— 黑水 ——
        g.fillGradient(x0, waterY, x1, y + h, 0xFF02080C, 0xFF03141A);
        // 青色水光斑块（水底反光，缓慢流动）
        for (int i = 0; i < 90; i++) {
            float fx = (hash(i, 411) * w + t * (4 + hash(i, 412) * 6)) % w;
            float fy = waterY + 4 + hash(i, 413) * (y + h - waterY - 4);
            float pw = 6 + hash(i, 414) * 18;
            float lum = (float) (0.5 + 0.5 * Math.sin(t * (1 + hash(i, 415) * 2) + i));
            g.fill((int) (x0 + fx), (int) fy, (int) (x0 + fx + pw), (int) fy + 1 + (int) (hash(i, 416) * 2),
                    alpha(i % 4 == 0 ? 0xFF7FF0FF : 0xFF1FA6B8, 0.12f + 0.3f * lum));
        }
        // 倒影（镜像神龛，压暗）
        MY = waterY;
        shrine2(g, cx, waterY - 2, size, t, 0.55f);
        MY = Float.NaN;
        // 波纹：横向错位暗条扫过倒影
        for (int r = waterY + 2; r < y + h; r += 3) {
            float ph = (float) Math.sin(r * 0.4 + t * 2.2);
            int off = (int) (ph * 6);
            g.fill(x0 + Math.max(0, off), r, x1 + Math.min(0, off), r + 1, alpha(0xFF02080C, 0.35f + 0.2f * ph));
        }
        // 青蓝光柱（水面两侧，闪烁）
        for (int k = 0; k < 4; k++) {
            float px = x0 + w * (k < 2 ? 0.1f + k * 0.12f : 0.78f + (k - 2) * 0.12f);
            float fl = 0.55f + 0.45f * (float) Math.sin(t * (1.5 + k * 0.7) + k * 2);
            int pw = 4 + k % 2 * 3;
            g.fillGradient((int) px, waterY - (int) (h * 0.06f), (int) px + pw, y + h, alpha(0xFF3FC8FF, 0.0f), alpha(0xFF3FC8FF, 0.55f * fl));
            g.fill((int) px + pw / 2, waterY, (int) px + pw / 2 + 1, y + h, alpha(0xFFBFF4FF, 0.6f * fl));
        }
        // 水平线：红雾交界
        g.fillGradient(x0, waterY - 6, x1, waterY, 0x00000000, 0x55A0101C);
        g.fill(x0, waterY, x1, waterY + 1, 0x88FF4A3A);
    }

    /** 神龛：赤红立柱、橙焰檐口、正面青光巨口、顶上两对巨大弯角 */
    private static void shrine2(GuiGraphics g, float cx, float base, float size, float t, float lit) {
        float s = size / 100f;
        float glow = 0.75f + 0.25f * (float) Math.sin(t * 2.3);
        int pillar = ZsAnim.lerpColor(0xFF200404, 0xFFFF4A1E, lit * glow);
        int dark = 0xFF0A0203, roofC = ZsAnim.lerpColor(0xFF120404, 0xFF3A0C0C, lit);
        int eave = ZsAnim.lerpColor(0xFF301008, 0xFFFF8A3A, lit * glow);
        int bone = ZsAnim.lerpColor(0xFF203038, 0xFFBFE6F0, lit);
        int teal = ZsAnim.lerpColor(0xFF06303A, 0xFF4FE0F0, lit * glow);
        // 台基
        mrect(g, cx - 30 * s, base - 5 * s, cx + 30 * s, base, roofC);
        mrect(g, cx - 32 * s, base - 1.5f * s, cx + 32 * s, base, eave);
        // 殿身
        mrect(g, cx - 22 * s, base - 42 * s, cx + 22 * s, base - 5 * s, dark);
        // 巨口：上下獠牙 + 青光喉
        float open = 7 + 3 * (float) Math.sin(t * 1.1f);
        float mcy = base - 24 * s;
        mrect(g, cx - 15 * s, mcy - open * s, cx + 15 * s, mcy + open * s, ZsAnim.lerpColor(dark, teal, 0.35f));
        mrect(g, cx - 3 * s, mcy - open * s * 0.7f, cx + 3 * s, mcy + open * s * 0.7f, ZsAnim.lerpColor(dark, 0xFFFFFFFF, 0.5f * lit));
        for (int i = 0; i < 7; i++) {
            float tx = cx - 14 * s + i * 4 * s;
            mtri(g, tx, mcy - open * s, tx + 3.6f * s, mcy - open * s, tx + 1.8f * s, mcy - (open - 4.5f) * s, bone);
            mtri(g, tx, mcy + open * s, tx + 3.6f * s, mcy + open * s, tx + 1.8f * s, mcy + (open - 4.5f) * s, bone);
        }
        // 赤柱（两对）
        for (int i : new int[]{-1, 1}) {
            float px = cx + i * 19 * s;
            mrect(g, px - 2.2f * s, base - 42 * s, px + 2.2f * s, base - 5 * s, pillar);
            mrect(g, px - 0.6f * s, base - 42 * s, px + 0.6f * s, base - 5 * s, alpha(0xFFFFE0A0, 0.6f * lit * glow));
            // 柱侧弯角（肋骨状）
            for (int r = 0; r < 3; r++) {
                float ry = base - (34 - r * 9) * s;
                float ppx = px + i * 2 * s, ppy = ry;
                for (int q = 1; q <= 5; q++) {
                    float nx = px + i * (2 + q * 1.8f) * s, ny = ry - (float) Math.sin(q / 5f * Math.PI) * 4 * s + q * 0.6f * s;
                    mline(g, ppx, ppy, nx, ny, Math.max(1, 1.6f * s), bone);
                    ppx = nx; ppy = ny;
                }
            }
        }
        // 屋顶（两层，橙色檐口发光）
        float ry = base - 42 * s;
        for (int k = 0; k < 2; k++) {
            float hw = (40 - k * 12) * s, th = 6 * s;
            for (int r = 0; r < Math.max(2, Math.round(th)); r++) {
                float ww = hw * (1 - 0.3f * r / th);
                mrect(g, cx - ww, ry - r - 1, cx + ww, ry - r, roofC);
            }
            mrect(g, cx - hw - 3 * s, ry - 1.5f * s, cx + hw + 3 * s, ry, eave);
            mline(g, cx - hw - 3 * s, ry - 1, cx - hw - 7 * s, ry - 5 * s, Math.max(1, s * 1.2f), eave);
            mline(g, cx + hw + 3 * s, ry - 1, cx + hw + 7 * s, ry - 5 * s, Math.max(1, s * 1.2f), eave);
            // 檐下红花饰
            for (int i : new int[]{-1, 1}) {
                float fx = cx + i * hw * 0.7f, fy = ry + 2 * s;
                disk(g, fx, my(fy), 2.2f * s, 1, alpha(0xFFFF3A2A, 0.8f * lit + 0.2f));
            }
            ry -= th;
            if (k == 0) { mrect(g, cx - 18 * s, ry - 6 * s, cx + 18 * s, ry, dark); ry -= 6 * s; }
        }
        // 顶饰：青色宝珠
        disk(g, cx, my(ry - 3 * s), 2.5f * s, 1, teal);
        // 顶上巨角：两对向外上方弯曲
        for (int pair = 0; pair < 2; pair++) {
            float spread = 12 + pair * 10, rise = 20 + pair * 6;
            for (int i : new int[]{-1, 1}) {
                float px = cx + i * 4 * s, py = ry - 2 * s;
                for (int q = 1; q <= 10; q++) {
                    float u = q / 10f;
                    float nx = cx + i * (4 + spread * (float) Math.sin(u * Math.PI * 0.6)) * s;
                    float ny = ry - 2 * s - rise * u * s + (float) Math.pow(u, 3) * 8 * s;
                    float wdt = Math.max(1, (3.2f - u * 2.6f) * s * 0.9f);
                    mline(g, px, py, nx, ny, wdt, bone);
                    px = nx; py = ny;
                }
            }
        }
        // 神龛辉光
        if (lit > 0.9f) disk(g, cx, my(mcy), 9 * s, 1, alpha(0xFF4FE0F0, 0.08f + 0.08f * glow));
    }

    private static void shrine(GuiGraphics g, float cx, float base, float size, float t) {
        int wood = 0xFF1C0508, woodL = 0xFF3A0A10, edge = 0xFF8A1A24, bone = 0xFFE6D8BC;
        float s = size / 100f;
        // 台基
        rect(g, cx - 42 * s, base - 8 * s, cx + 42 * s, base, woodL);
        rect(g, cx - 46 * s, base - 3 * s, cx + 46 * s, base, wood);
        // 立柱
        for (int i = -2; i <= 2; i++) {
            float px = cx + i * 17 * s;
            rect(g, px - 2 * s, base - 40 * s, px + 2 * s, base - 8 * s, i == 0 ? woodL : wood);
        }
        // 正面巨口（上下獠牙，缓慢开合）
        float open = 5 + 3 * (float) Math.sin(t * 1.2f);
        float my = base - 24 * s;
        rect(g, cx - 22 * s, my - open * s, cx + 22 * s, my + open * s, 0xFF050001);
        for (int i = 0; i < 9; i++) {
            float tx = cx - 20 * s + i * 5 * s;
            tri(g, tx, my - open * s, tx + 4 * s, my - open * s, tx + 2 * s, my - (open - 4) * s, bone);
            tri(g, tx, my + open * s, tx + 4 * s, my + open * s, tx + 2 * s, my + (open - 4) * s, bone);
        }
        rect(g, cx - 23 * s, my - open * s - 2 * s, cx + 23 * s, my - open * s, edge);
        rect(g, cx - 23 * s, my + open * s, cx + 23 * s, my + open * s + 2 * s, edge);
        // 三重屋顶（自下而上，逐层收窄，檐角上翘）
        float ry = base - 40 * s;
        for (int k = 0; k < 3; k++) {
            float hw = (58 - k * 14) * s, th = 7 * s;
            roof(g, cx, ry, hw, th, wood, edge);
            // 檐角牛骨（角）
            horn(g, cx - hw, ry - th, -1, s, bone);
            horn(g, cx + hw, ry - th, 1, s, bone);
            ry -= th;
            if (k < 2) {
                rect(g, cx - (hw - 12 * s), ry - 8 * s, cx + (hw - 12 * s), ry, woodL);
                // 层间的小口
                for (int i = -1; i <= 1; i++) {
                    float ex = cx + i * 10 * s;
                    rect(g, ex - 2 * s, ry - 5 * s, ex + 2 * s, ry - 3 * s, 0xFF050001);
                }
                ry -= 8 * s;
            }
        }
        // 宝顶
        rect(g, cx - 1.5f * s, ry - 10 * s, cx + 1.5f * s, ry, edge);
        disk(g, cx, ry - 11 * s, 2.5f * s, 1, bone);
        // 神社周身暗红辉光
        float p = 0.5f + 0.5f * (float) Math.sin(t * 2);
        disk(g, cx, my, 6 * s, 1, alpha(SUKUNA_GLOW, 0.15f + 0.15f * p));
    }

    private static void roof(GuiGraphics g, float cx, float y, float hw, float th, int col, int edge) {
        int rows = Math.max(2, Math.round(th));
        for (int r = 0; r < rows; r++) {
            float k = r / (float) rows;
            float w = hw * (1 - 0.35f * k);
            float lift = (1 - k) * 0;
            rect(g, cx - w, y - r - 1 - lift, cx + w, y - r, col);
        }
        rect(g, cx - hw - 2, y - 1, cx + hw + 2, y, edge);
        // 翘檐
        rect(g, cx - hw - 3, y - 3, cx - hw - 1, y - 1, edge);
        rect(g, cx + hw + 1, y - 3, cx + hw + 3, y - 1, edge);
    }

    private static void horn(GuiGraphics g, float x, float y, int dir, float s, int bone) {
        float px = x, py = y;
        for (int i = 1; i <= 6; i++) {
            float nx = x + dir * i * 1.4f * s, ny = y - i * i * 0.35f * s;
            line(g, px, py, nx, ny, Math.max(1, 2 - i / 3), bone);
            px = nx; py = ny;
        }
    }

    /** 头骨堆：由下往上逐层减少 */
    private static void skulls(GuiGraphics g, float cx, float base, int r, float t, int seed) {
        int rows = 3;
        for (int row = 0; row < rows; row++) {
            int n = rows - row + 1;
            float y = base - 3 - row * r * 1.1f;
            for (int i = 0; i < n; i++) {
                float x = cx + (i - (n - 1) / 2f) * r * 1.5f + (hash(seed + row, i) - 0.5f) * 3;
                skull(g, x, y, r * 0.7f);
            }
        }
    }

    private static void skull(GuiGraphics g, float x, float y, float r) {
        disk(g, x, y - r * 0.2f, r, 1, 0xFFB8A888);
        disk(g, x, y - r * 0.3f, r * 0.85f, 1, 0xFFD8CCB0);
        rect(g, x - r * 0.5f, y + r * 0.4f, x + r * 0.5f, y + r * 0.8f, 0xFFB8A888);
        disk(g, x - r * 0.38f, y - r * 0.15f, r * 0.24f, 1, 0xFF100204);
        disk(g, x + r * 0.38f, y - r * 0.15f, r * 0.24f, 1, 0xFF100204);
        rect(g, x - 0.5f, y + r * 0.2f, x + 0.5f, y + r * 0.35f, 0xFF100204);
    }

    private static void rect(GuiGraphics g, float x0, float y0, float x1, float y1, int col) {
        g.fill(Math.round(x0), Math.round(y0), Math.max(Math.round(x0) + 1, Math.round(x1)),
                Math.max(Math.round(y0) + 1, Math.round(y1)), col);
    }

    /** 三角形（逐行扫描） */
    private static void tri(GuiGraphics g, float ax, float ay, float bx, float by, float cx, float cy, int col) {
        int y0 = Math.round(Math.min(ay, Math.min(by, cy))), y1 = Math.round(Math.max(ay, Math.max(by, cy)));
        for (int yy = y0; yy <= y1; yy++) {
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            float[][] e = {{ax, ay, bx, by}, {bx, by, cx, cy}, {cx, cy, ax, ay}};
            for (float[] s : e) {
                if ((yy >= Math.min(s[1], s[3])) && (yy <= Math.max(s[1], s[3])) && s[1] != s[3]) {
                    float xx = s[0] + (yy - s[1]) * (s[2] - s[0]) / (s[3] - s[1]);
                    lo = Math.min(lo, xx); hi = Math.max(hi, xx);
                }
            }
            if (hi >= lo) g.fill(Math.round(lo), yy, Math.round(hi) + 1, yy + 1, col);
        }
    }

    // ===================== 立体外框 =====================

    public static void frame3d(GuiGraphics g, int x, int y, int w, int h, float open) {
        float t = ZsAnim.nowMs() / 1000f;
        int mid = x + w / 2, x1 = x + w, y1 = y + h;
        int B = 6;
        int ex = Math.round(w / 2f * open) + 1;

        // 左半：冷钢蓝浮雕
        g.enableScissor(mid - ex, y - 12, mid, y1 + 12);
        bevelHalf(g, x, y, mid, y1, B, true, 0xFF9FC8F0, 0xFF3A5C88, 0xFF0A1428, 0xFF1C2E4E, t);
        g.disableScissor();
        // 右半：黑漆朱红浮雕
        g.enableScissor(mid, y - 12, mid + ex, y1 + 12);
        bevelHalf(g, mid, y, x1, y1, B, false, 0xFFE0707A, 0xFF7A1420, 0xFF14030A, 0xFF3A0812, t);
        g.disableScissor();

        if (open < 1f) return;
        // 角钉（立体半球）
        stud(g, x + 3, y + 3, GOJO_LIGHT, GOJO);
        stud(g, x + 3, y1 - 4, GOJO_LIGHT, GOJO);
        stud(g, x1 - 4, y + 3, 0xFFFFB0B0, SUKUNA);
        stud(g, x1 - 4, y1 - 4, 0xFFFFB0B0, SUKUNA);

        // 底部中央：徽记对峙
        float ey = y1 - 1;
        float push = (float) Math.sin(t * 0.7) * 3;
        float gxc = mid - 15 + push, sxc = mid + 15 + push;
        // 对撞电弧
        long seed = ZsAnim.nowMs() / 60;
        float px = gxc + 8, py = ey;
        for (int i = 1; i <= 4; i++) {
            float nx = gxc + 8 + (sxc - gxc - 16) * i / 4f, ny = ey + (i < 4 ? (hash(i, seed) - 0.5f) * 6 : 0);
            line(g, px, py, nx, ny, 1, 0xFFF0E0FF);
            px = nx; py = ny;
        }
        gojoEmblem(g, gxc, ey, t);
        sukunaEmblem(g, sxc, ey, t);
    }

    /** 半边浮雕框：外亮倒角 → 框面渐变 → 内暗倒角；并沿框面流动一道高光 */
    private static void bevelHalf(GuiGraphics g, int x0, int y0, int x1, int y1, int B, boolean left,
                                  int hi, int face, int dark, int face2, float t) {
        int ox = left ? x0 : x1 - B;             // 竖边
        // 框面
        g.fillGradient(x0, y0, x1, y0 + B, face, face2);
        g.fillGradient(x0, y1 - B, x1, y1, face2, face);
        g.fillGradient(ox, y0 + B, ox + B, y1 - B, face2, face2);
        // 外倒角：上与外侧高光，下暗
        g.fill(x0, y0, x1, y0 + 1, hi);
        g.fill(left ? x0 : x1 - 1, y0, left ? x0 + 1 : x1, y1, left ? hi : alpha(hi, 0.6f));
        g.fill(x0, y1 - 1, x1, y1, dark);
        // 内倒角：内上暗（阴影），内下亮（反光）
        int ix0 = left ? x0 + B : x0, ix1 = left ? x1 : x1 - B;
        g.fill(ix0, y0 + B - 1, ix1, y0 + B, dark);
        g.fill(ix0, y0 + B, ix1, y0 + B + 1, 0x66000000);
        g.fill(ix0, y1 - B, ix1, y1 - B + 1, alpha(hi, 0.5f));
        int ivx = left ? x0 + B - 1 : x1 - B;
        g.fill(ivx, y0 + B - 1, ivx + 1, y1 - B + 1, left ? dark : alpha(hi, 0.5f));
        // 框面中线刻槽
        int gy0 = y0 + B / 2, gy1 = y1 - B / 2 - 1, gx = left ? x0 + B / 2 : x1 - B / 2 - 1;
        g.fill(x0 + (left ? B / 2 : 0), gy0, x1 - (left ? 0 : B / 2), gy0 + 1, alpha(dark, 0.8f));
        g.fill(x0 + (left ? B / 2 : 0), gy1, x1 - (left ? 0 : B / 2), gy1 + 1, alpha(dark, 0.8f));
        g.fill(gx, gy0, gx + 1, gy1 + 1, alpha(dark, 0.8f));
        // 流光：一道斜高光沿上下框面划过
        float u = (t * 0.25f + (left ? 0 : 0.5f)) % 1f;
        int sx = Math.round(x0 + (x1 - x0) * (left ? 1 - u : u));
        for (int k = 0; k < 10; k++) {
            int a = (int) (0x70 * (1 - Math.abs(k - 5) / 5f));
            g.fill(sx + k, y0 + 1, sx + k + 1, y0 + B - 1, (a << 24) | 0xFFFFFF);
            g.fill(sx + k, y1 - B + 1, sx + k + 1, y1 - 1, (a << 24) | 0xFFFFFF);
        }
    }

    private static void stud(GuiGraphics g, int cx, int cy, int hi, int col) {
        disk(g, cx, cy, 3, 1, 0xFF000000);
        disk(g, cx, cy, 2.5f, 1, col);
        g.fill(cx - 1, cy - 2, cx + 1, cy - 1, hi);
        g.fill(cx - 2, cy - 1, cx - 1, cy, hi);
    }

    /** 左徽记：蓝色无限球 + 三轴旋转光环（3D） */
    private static void gojoEmblem(GuiGraphics g, float cx, float cy, float t) {
        disk(g, cx, cy, 11, 1, 0xFF02040C);
        disk(g, cx, cy, 10, 1, 0x556FD3FF);
        disk(g, cx, cy, 6.5f, 1, GOJO);
        disk(g, cx, cy, 4f, 1, GOJO_LIGHT);
        disk(g, cx - 1.5f, cy - 1.5f, 1.5f, 1, 0xFFFFFFFF);
        for (int k = 0; k < 3; k++) {
            g.pose().pushPose();
            g.pose().translate(cx, cy, 50);
            g.pose().mulPose(Axis.ZP.rotation(k * 1.047f));
            g.pose().mulPose(Axis.XP.rotation(t * (1.1f + k * 0.4f) + k));
            for (int i = 0; i < 36; i++) {
                double a = i * Math.PI * 2 / 36;
                float r = 9;
                int x = (int) Math.round(Math.cos(a) * r), y = (int) Math.round(Math.sin(a) * r);
                g.fill(x, y, x + 1, y + 1, alpha(i % 6 == 0 ? 0xFFFFFFFF : GOJO_LIGHT, 0.9f));
            }
            g.pose().popPose();
        }
    }

    /** 右徽记：赤黑球 + 倾斜旋转的獠牙环（3D）+ 十字斩痕 */
    private static void sukunaEmblem(GuiGraphics g, float cx, float cy, float t) {
        disk(g, cx, cy, 11, 1, 0xFF0C0103);
        disk(g, cx, cy, 10, 1, 0x55E0253A);
        disk(g, cx, cy, 6.5f, 1, 0xFF5A0A12);
        disk(g, cx, cy, 4f, 1, SUKUNA);
        disk(g, cx - 1.5f, cy - 1.5f, 1.2f, 1, 0xFFFFB0B0);
        g.pose().pushPose();
        g.pose().translate(cx, cy, 50);
        g.pose().mulPose(Axis.XP.rotation(1.05f));
        g.pose().mulPose(Axis.ZP.rotation(-t * 1.4f));
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI * 2 / 12;
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            line(g, c * 8, s * 8, c * 11.5f, s * 11.5f, 2, 0xFFE6D8BC);
        }
        g.pose().popPose();
        float fl = ((ZsAnim.nowMs() / 90) % 16) == 0 ? 1f : 0.7f;
        line(g, cx - 5, cy - 5, cx + 5, cy + 5, 1, alpha(0xFFFFFFFF, fl));
        line(g, cx + 5, cy - 5, cx - 5, cy + 5, 1, alpha(0xFFFFFFFF, fl));
    }
}
