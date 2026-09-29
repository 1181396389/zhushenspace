package com.zhushen.space.screen;

import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 咒术回战风格绘制工具：五条悟（无量空处：蓝白 / 深空）、两面宿傩（伏魔御厨子：猩红 / 墨黑）、
 * 虎杖悠仁（橙 + 黑闪）、伏黑惠（影：藏青 / 纯黑）。
 */
public final class JjkStyle {
    private JjkStyle() {}

    public static final int GOJO = 0xFF6FD3FF, GOJO_LIGHT = 0xFFD8F4FF, GOJO_DEEP = 0xFF05060F;
    public static final int SUKUNA = 0xFFE0253A, SUKUNA_DARK = 0xFF12030A, SUKUNA_GLOW = 0xFFFF5A5A;
    public static final int ITADORI = 0xFFFF7A2E, ITADORI_DARK = 0xFF140B08;
    public static final int MEGUMI = 0xFF4E6FA8, MEGUMI_DARK = 0xFF05070D;
    public static final int INK = 0xFF050102, PAPER = 0xFFF2ECE0;

    /** 确定性哈希 → [0,1) */
    public static float hash(long a, long b) {
        long h = a * 0x9E3779B97F4A7C15L ^ (b + 0x632BE59BD9B4E019L) * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h >>> 40) / (float) (1L << 24);
    }

    public static int alpha(int c, float a) {
        int al = (int) ((c >>> 24) * Math.max(0f, Math.min(1f, a)));
        return (al << 24) | (c & 0xFFFFFF);
    }

    /** 粗线段 */
    public static void line(GuiGraphics g, float x1, float y1, float x2, float y2, float w, int col) {
        float dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.5f) return;
        g.pose().pushPose();
        g.pose().translate(x1, y1, 0);
        g.pose().mulPose(Axis.ZP.rotation((float) Math.atan2(dy, dx)));
        int hw = Math.max(1, Math.round(w));
        g.fill(0, -hw / 2, Math.round(len), hw - hw / 2, col);
        g.pose().popPose();
    }

    /** 实心圆（逐行填充；sy 为纵向缩放，用于眨眼） */
    public static void disk(GuiGraphics g, float cx, float cy, float r, float sy, int col) {
        int ir = (int) Math.ceil(r);
        for (int dy = -ir; dy <= ir; dy++) {
            float fy = dy / Math.max(0.01f, sy);
            if (Math.abs(fy) > r) continue;
            int hw = (int) Math.sqrt(r * r - fy * fy);
            g.fill((int) cx - hw, (int) cy + dy, (int) cx + hw + 1, (int) cy + dy + 1, col);
        }
    }

    /** 点状椭圆环 */
    public static void ring(GuiGraphics g, float cx, float cy, float rx, float ry, float rot, int dots, int col) {
        for (int i = 0; i < dots; i++) {
            double a = rot + i * Math.PI * 2 / dots;
            int x = (int) (cx + Math.cos(a) * rx), y = (int) (cy + Math.sin(a) * ry);
            g.fill(x, y, x + 1, y + 1, col);
        }
    }

    /** 专长界面按钮：墨黑底 + 主题色描边，悬停时纸白反色 */
    public static void button(GuiGraphics g, Font font, int mx, int my, int x, int y, int w, int h,
                              Component label, int accent, boolean enabled) {
        boolean hover = enabled && ZsTheme.over(mx, my, x, y, w, h);
        g.fill(x, y, x + w, y + h, hover ? alpha(accent, 0.9f) : 0xE0080808);
        g.renderOutline(x, y, w, h, enabled ? accent : 0xFF404040);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, alpha(accent, 0.5f));
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2,
                !enabled ? 0xFF606060 : hover ? INK : 0xFFFFFFFF, false);
    }

    /** 左半边框路径：上中点 → 左上角 → 左下角 → 下中点；右半对称。u∈[0,1] */
    private static float[] path(int x, int y, int w, int h, float u, boolean left) {
        float half = w / 2f, L = half * 2 + h, d = u * L;
        float mid = x + half;
        float sx = left ? -1 : 1;
        if (d < half) return new float[]{mid + sx * d, y};
        d -= half;
        float edgeX = left ? x : x + w - 1;
        if (d < h) return new float[]{edgeX, y + d};
        d -= h;
        return new float[]{edgeX - sx * d, y + h - 1};
    }

    /**
     * 专长页外框：两种领域结界自中缝向两侧"展开"。
     * 左：深空蓝白（信息流光点沿边框奔流），右：猩红墨黑（边框上周期闪现的斩痕刻口）。
     * 打开后 0.7 秒内背景从中线向两侧铺开、边框从上下中点向四角生长；之后上下中缝持续对撞火花，
     * 每 4 秒一道展开波沿边框从中缝奔向两侧。背景只有抽象的领域质感，不出现具体角色元素。
     */
    public static void chrome(GuiGraphics g, int x, int y, int w, int h, long since0, int headerH) {
        long now = ZsAnim.nowMs();
        float k = ZsAnim.easeOutCubic((now - since0) / 700f);
        float t = now / 1000f;
        int mid = x + w / 2;
        int half = w / 2;
        int ex = Math.round(half * k);

        g.fill(x, y, x + w, y + h, 0xF0050508);
        // —— 背景：左深空 / 右猩红，自中线展开 ——
        if (ex > 0) {
            g.fillGradient(mid - ex, y, mid, y + h, 0xF0060A1C, 0xF0020308);
            g.fillGradient(mid, y, mid + ex, y + h, 0xF0240610, 0xF00A0204);
            g.enableScissor(mid - ex, y + 1, mid, y + h - 1);
            // 信息流：细长光痕向外奔流
            for (int i = 0; i < 34; i++) {
                float ph = (t * (0.15f + hash(i, 1) * 0.25f) + hash(i, 2)) % 1f;
                int ly = y + 4 + (int) (hash(i, 3) * (h - 8));
                int lx = mid - (int) (ph * half);
                int len = 6 + (int) (hash(i, 4) * 18 * ph);
                g.fill(lx - len, ly, lx, ly + 1, alpha(i % 4 == 0 ? GOJO : 0xFFFFFFFF, 0.08f + 0.22f * ph));
            }
            for (int i = 0; i < 40; i++) {
                float tw = (float) Math.abs(Math.sin(t * (1 + hash(i, 5) * 2) + i));
                int px = mid - 2 - (int) (hash(i, 6) * (half - 4)), py = y + 2 + (int) (hash(i, 7) * (h - 4));
                g.fill(px, py, px + 1, py + 1, alpha(0xFFD8F4FF, 0.15f + 0.45f * tw));
            }
            g.disableScissor();
            g.enableScissor(mid, y + 1, mid + ex, y + h - 1);
            // 斩痕：淡淡的斜向刻痕闪现消散
            for (int i = 0; i < 7; i++) {
                long cyc = (now + i * 211) / 900;
                float ph = ((now + i * 211) % 900) / 900f;
                float sx = mid + 6 + hash(i, cyc) * (half - 12), sy = y + 6 + hash(i + 40, cyc) * (h - 12);
                float ang = 0.5f + (hash(i + 80, cyc) - 0.5f) * 1.2f, len = 14 + hash(i + 9, cyc) * 26;
                float gr = Math.min(1f, ph * 5f), a = (1 - ph) * 0.45f;
                float x2 = sx + (float) Math.cos(ang) * len * gr, y2 = sy + (float) Math.sin(ang) * len * gr;
                line(g, sx, sy, x2, y2, 3, alpha(SUKUNA, a * 0.5f));
                line(g, sx, sy, x2, y2, 1, alpha(0xFFFFFFFF, a));
            }
            // 余烬
            for (int i = 0; i < 18; i++) {
                float ph = (t * (0.2f + hash(i, 31) * 0.3f) + hash(i, 32)) % 1f;
                int px = mid + 3 + (int) (hash(i, 33) * (half - 6)), py = y + h - 2 - (int) (ph * (h - 4));
                g.fill(px, py, px + 1, py + 2, alpha(SUKUNA_GLOW, 0.5f * (1 - ph)));
            }
            g.disableScissor();
            // 展开前沿：一道亮线推向两侧
            if (k < 1f) {
                g.fill(mid - ex - 1, y, mid - ex + 1, y + h, alpha(0xFFFFFFFF, 1 - k));
                g.fill(mid - ex + 1, y, mid - ex + 4, y + h, alpha(GOJO, (1 - k) * 0.6f));
                g.fill(mid + ex - 1, y, mid + ex + 1, y + h, alpha(0xFFFFFFFF, 1 - k));
                g.fill(mid + ex - 4, y, mid + ex - 1, y + h, alpha(SUKUNA, (1 - k) * 0.6f));
            }
        }
        // 标签行下压暗，保证文字可读
        g.fillGradient(x + 1, y + 1, x + w - 1, y + headerH, 0x88000000, 0x00000000);

        // —— 边框：从上下中点向四角生长 ——
        float grow = ZsAnim.clamp01((now - since0) / 750f);
        grow = ZsAnim.easeOutCubic(grow);
        for (int side = 0; side < 2; side++) {
            boolean left = side == 0;
            int core = left ? 0xFFE8F8FF : 0xFFFF4A5A, glow = left ? GOJO : SUKUNA;
            int steps = (int) ((half * 2 + h) * grow / 2);  // 上半程（自上中点）与下半程（自下中点）各生长一半
            for (int i = 0; i < steps; i++) {
                float u = i / (float) (half * 2 + h);
                float[] a = path(x, y, w, h, u, left);
                float[] b = path(x, y, w, h, 1 - u, left);
                g.fill((int) a[0], (int) a[1], (int) a[0] + 1, (int) a[1] + 1, core);
                g.fill((int) b[0], (int) b[1], (int) b[0] + 1, (int) b[1] + 1, core);
            }
            // 外圈辉光（完全展开后）
            if (grow >= 1f) {
                int gx0 = left ? x - 2 : mid, gx1 = left ? mid : x + w + 2;
                int ga = alpha(glow, 0.18f + 0.1f * ZsAnim.pulse(2400));
                g.fill(gx0, y - 2, gx1, y, ga);
                g.fill(gx0, y + h, gx1, y + h + 2, ga);
                if (left) g.fill(x - 2, y, x, y + h, ga); else g.fill(x + w, y, x + w + 2, y + h, ga);
                g.fill(left ? x + 1 : mid, y + 1, left ? mid : x + w - 1, y + 2, alpha(glow, 0.35f));
            }
        }
        if (grow < 1f) return;

        // 左：光点沿边框奔流（自中缝向外）
        for (int i = 0; i < 10; i++) {
            float u = (t * 0.12f + i / 10f) % 1f;
            float[] p = path(x, y, w, h, u, true);
            g.fill((int) p[0] - 1, (int) p[1] - 1, (int) p[0] + 2, (int) p[1] + 2, alpha(0xFFFFFFFF, 0.8f));
            g.fill((int) p[0] - 2, (int) p[1], (int) p[0] + 3, (int) p[1] + 1, alpha(GOJO, 0.6f));
        }
        // 右：边框上周期闪现的斩痕刻口
        int L = half * 2 + h;
        for (int i = 0; i < 12; i++) {
            long cyc = (now + i * 157) / 700;
            float ph = ((now + i * 157) % 700) / 700f;
            float[] p = path(x, y, w, h, hash(i, cyc), false);
            float a = 1 - ph;
            line(g, p[0] - 3, p[1] - 3, p[0] + 3, p[1] + 3, 1, alpha(0xFFFFFFFF, a));
            line(g, p[0] - 4, p[1] - 3, p[0] + 2, p[1] + 3, 2, alpha(SUKUNA, a * 0.7f));
        }
        // 展开波：每 4 秒一道亮波沿边框从中缝奔向两侧
        float wph = (now % 4000) / 4000f;
        if (wph < 0.5f) {
            float u = wph / 0.5f;
            for (int j = 0; j < 14; j++) {
                float uu = u - j / (float) L;
                if (uu < 0) break;
                float a = (1 - j / 14f) * (1 - u * 0.5f);
                for (int side = 0; side < 2; side++) {
                    boolean left = side == 0;
                    float[] p1 = path(x, y, w, h, uu * 0.5f, left), p2 = path(x, y, w, h, 1 - uu * 0.5f, left);
                    int c = alpha(left ? 0xFFFFFFFF : 0xFFFFB0B0, a);
                    g.fill((int) p1[0] - 1, (int) p1[1] - 1, (int) p1[0] + 2, (int) p1[1] + 2, c);
                    g.fill((int) p2[0] - 1, (int) p2[1] - 1, (int) p2[0] + 2, (int) p2[1] + 2, c);
                }
            }
        }
        // 上下中缝：对撞火花
        for (int e = 0; e < 2; e++) {
            int sy = e == 0 ? y : y + h - 1;
            int dirY = e == 0 ? 1 : -1;
            for (int j = 0; j < 4; j++) {
                int jx = (int) ((hash(j + e * 10, now / 50) - 0.5f) * 3);
                g.fill(mid + jx, sy + dirY * j * 2, mid + jx + 1, sy + dirY * j * 2 + 2 * dirY, 0xFFFFFFFF);
            }
            for (int i = 0; i < 10; i++) {
                long cyc = (now + i * 83) / 500;
                float ph = ((now + i * 83) % 500) / 500f;
                int dir = i % 2 == 0 ? -1 : 1;
                float px = mid + dir * ph * (6 + hash(i, cyc) * 14);
                float py = sy + dirY * (ph * 4 + hash(i + 3, cyc) * 3);
                g.fill((int) px, (int) py, (int) px + 1, (int) py + 1,
                        alpha(dir < 0 ? GOJO : SUKUNA_GLOW, 1 - ph));
            }
        }
    }

    /** 专长页实体边框宽度 */
    public static final int BAND = 10;

    /** 实体边框内圈（小游戏区域）：{x0, y0, x1, y1} */
    public static int[] frameInner(int x, int y, int w, int h, int headerH) {
        int fx = x + 2, fy = y + headerH, fw = w - 4, fh = h - headerH - 18;
        return new int[]{fx + BAND, fy + BAND, fx + fw - BAND, fy + fh - BAND};
    }

    /** 竖向三角（尖朝 dir：1 向下 / -1 向上） */
    private static void fangV(GuiGraphics g, int cx, int baseY, int dir, int len, int half, int col) {
        for (int j = 0; j < len; j++) {
            int hw = Math.max(0, Math.round(half * (1 - j / (float) len)));
            int yy = baseY + dir * j;
            g.fill(cx - hw, yy, cx + hw + 1, yy + 1, col);
        }
    }

    /** 横向三角（尖朝 dir：1 向右 / -1 向左） */
    private static void fangH(GuiGraphics g, int baseX, int cy, int dir, int len, int half, int col) {
        for (int j = 0; j < len; j++) {
            int hh = Math.max(0, Math.round(half * (1 - j / (float) len)));
            int xx = baseX + dir * j;
            g.fill(xx, cy - hh, xx + 1, cy + hh + 1, col);
        }
    }

    /**
     * 实体领域边框：左半「无量空处」（深空质地的金属框，信息流沿框奔流、刻度随无限波动闪烁、
     * 左中嵌奇点徽盘），右半「伏魔御厨子」（猩红漆框、骨节横肋、内缘獠牙开合、血脉流动、右中嵌斩痕徽盘）。
     * 打开时边框从上下中缝向两侧展开；两半在上下中缝以扣环咬合。
     */
    public static void frame(GuiGraphics g, int x, int y, int w, int h, long since0, int headerH) {
        long now = ZsAnim.nowMs();
        float t = now / 1000f;
        int fx = x + 2, fy = y + headerH, fw = w - 4, fh = h - headerH - 18;
        int fx1 = fx + fw, fy1 = fy + fh, mid = fx + fw / 2, B = BAND;
        float k = ZsAnim.easeOutCubic(ZsAnim.clamp01((now - since0 - 150) / 800f));
        int ex = Math.round((fw / 2f + 2) * k);
        if (ex <= 0) return;

        // ================= 左：无量空处 =================
        g.enableScissor(mid - ex, fy - 2, mid, fy1 + 2);
        int[][] lb = {{fx, fy, mid, fy + B}, {fx, fy1 - B, mid, fy1}, {fx, fy, fx + B, fy1}};
        for (int[] r : lb) g.fillGradient(r[0], r[1], r[2], r[3], 0xFF0C1838, 0xFF02040E);
        // 刻度：随时间向外奔涌的明暗波
        for (int xx = mid - 4; xx > fx + B; xx -= 5) {
            float wv = (float) Math.max(0, Math.sin((mid - xx) * 0.08 - t * 5));
            int c = alpha(GOJO_LIGHT, 0.12f + 0.6f * wv * wv);
            g.fill(xx, fy + 3, xx + 1, fy + B - 3, c);
            g.fill(xx, fy1 - B + 3, xx + 1, fy1 - 3, c);
        }
        for (int yy = fy + B + 2; yy < fy1 - B; yy += 5) {
            float wv = (float) Math.max(0, Math.sin(Math.abs(yy - (fy + fy1) / 2f) * 0.08 - t * 5 + 3));
            g.fill(fx + 3, yy, fx + B - 3, yy + 1, alpha(GOJO_LIGHT, 0.12f + 0.6f * wv * wv));
        }
        // 信息流：光点沿框中线自中缝向外奔流
        int Lh = (mid - fx) + (fh / 2);
        for (int i = 0; i < 16; i++) {
            float u = (t * (0.18f + hash(i, 11) * 0.12f) + hash(i, 12)) % 1f;
            float d = u * Lh;
            boolean top = i % 2 == 0;
            int px, py;
            if (d < mid - fx - B / 2f) { px = (int) (mid - d); py = top ? fy + B / 2 : fy1 - B / 2 - 1; }
            else { px = fx + B / 2; float dd = d - (mid - fx - B / 2f); py = (int) (top ? fy + B / 2 + dd : fy1 - B / 2 - dd); }
            int tail = 3 + (int) (hash(i, 13) * 5);
            g.fill(px, py, px + tail, py + 1, alpha(GOJO, 0.5f));
            g.fill(px, py, px + 1, py + 1, 0xFFFFFFFF);
        }
        // 边缘：外亮内蓝 + 倒角高光
        g.fill(fx, fy, mid, fy + 1, GOJO_LIGHT);
        g.fill(fx, fy1 - 1, mid, fy1, alpha(GOJO_LIGHT, 0.7f));
        g.fill(fx, fy, fx + 1, fy1, GOJO_LIGHT);
        g.fill(fx + B, fy + B - 1, mid, fy + B, GOJO);
        g.fill(fx + B, fy1 - B, mid, fy1 - B + 1, GOJO);
        g.fill(fx + B - 1, fy + B - 1, fx + B, fy1 - B + 1, GOJO);
        g.fill(fx + 1, fy + 1, mid, fy + 2, 0x55FFFFFF);
        g.fill(fx + 1, fy + 1, fx + 2, fy1 - 1, 0x55FFFFFF);
        // 角板：旋转的同心点环
        for (int e = 0; e < 2; e++) {
            int cy = e == 0 ? fy + B / 2 : fy1 - B / 2;
            int cx = fx + B / 2;
            g.fill(cx - 8, cy - 8, cx + 9, cy + 9, 0xFF050A1A);
            g.renderOutline(cx - 8, cy - 8, 17, 17, GOJO);
            ring(g, cx, cy, 6, 6, t * 1.6f, 14, GOJO_LIGHT);
            ring(g, cx, cy, 3.5f, 3.5f, -t * 2.4f, 8, GOJO);
            g.fill(cx, cy, cx + 1, cy + 1, 0xFFFFFFFF);
        }
        // 左中徽盘：奇点（虚空 + 星尘 + 三重点环 + 脉动白芯）
        {
            int cx = fx + B / 2, cy = (fy + fy1) / 2;
            disk(g, cx, cy, 15, 1, GOJO);
            disk(g, cx, cy, 14, 1, 0xFF01020A);
            for (int i = 0; i < 18; i++) {
                double a = hash(i, 21) * Math.PI * 2 + t * (0.3 + hash(i, 22));
                float r = 3 + hash(i, 23) * 10;
                float tw = (float) Math.abs(Math.sin(t * 3 + i));
                g.fill(cx + (int) (Math.cos(a) * r), cy + (int) (Math.sin(a) * r),
                        cx + (int) (Math.cos(a) * r) + 1, cy + (int) (Math.sin(a) * r) + 1, alpha(GOJO_LIGHT, 0.3f + 0.7f * tw));
            }
            ring(g, cx, cy, 12, 12, t, 36, alpha(GOJO_LIGHT, 0.8f));
            ring(g, cx, cy, 8.5f, 8.5f, -t * 1.7f, 24, alpha(GOJO, 0.9f));
            ring(g, cx, cy, 5, 5, t * 3, 12, GOJO_LIGHT);
            float p = 0.6f + 0.4f * (float) Math.sin(t * 4);
            disk(g, cx, cy, 2.5f + p, 1, alpha(0xFFFFFFFF, 0.9f));
        }
        g.disableScissor();

        // ================= 右：伏魔御厨子 =================
        g.enableScissor(mid, fy - 2, mid + ex, fy1 + 2);
        int[][] rb = {{mid, fy, fx1, fy + B}, {mid, fy1 - B, fx1, fy1}, {fx1 - B, fy, fx1, fy1}};
        for (int[] r : rb) g.fillGradient(r[0], r[1], r[2], r[3], 0xFF4A0812, 0xFF140206);
        // 血脉：沿框向外流动的暗红脉冲
        for (int xx = mid + 2; xx < fx1 - B; xx++) {
            float wv = (float) Math.max(0, Math.sin((xx - mid) * 0.05 - t * 3));
            if (wv < 0.3f) continue;
            int c = alpha(SUKUNA_GLOW, (wv - 0.3f) * 0.5f);
            g.fill(xx, fy + B / 2, xx + 1, fy + B / 2 + 1, c);
            g.fill(xx, fy1 - B / 2 - 1, xx + 1, fy1 - B / 2, c);
        }
        for (int yy = fy + B; yy < fy1 - B; yy++) {
            float wv = (float) Math.max(0, Math.sin(Math.abs(yy - (fy + fy1) / 2f) * 0.05 - t * 3 + 2));
            if (wv < 0.3f) continue;
            g.fill(fx1 - B / 2 - 1, yy, fx1 - B / 2, yy + 1, alpha(SUKUNA_GLOW, (wv - 0.3f) * 0.5f));
        }
        // 骨节横肋
        int bone = 0xFFD8C8A8;
        for (int xx = mid + 8; xx < fx1 - B; xx += 12) {
            g.fill(xx, fy + 2, xx + 2, fy + B - 2, alpha(bone, 0.55f));
            g.fill(xx - 1, fy + 2, xx + 3, fy + 3, alpha(bone, 0.7f));
            g.fill(xx, fy1 - B + 2, xx + 2, fy1 - 2, alpha(bone, 0.55f));
            g.fill(xx - 1, fy1 - 3, xx + 3, fy1 - 2, alpha(bone, 0.7f));
        }
        for (int yy = fy + B + 8; yy < fy1 - B; yy += 12) {
            g.fill(fx1 - B + 2, yy, fx1 - 2, yy + 2, alpha(bone, 0.55f));
            g.fill(fx1 - 3, yy - 1, fx1 - 2, yy + 3, alpha(bone, 0.7f));
        }
        // 边缘：骨白外缘 + 猩红内缘
        g.fill(mid, fy, fx1, fy + 1, bone);
        g.fill(mid, fy1 - 1, fx1, fy1, alpha(bone, 0.7f));
        g.fill(fx1 - 1, fy, fx1, fy1, bone);
        g.fill(mid, fy + B - 1, fx1 - B, fy + B, SUKUNA);
        g.fill(mid, fy1 - B, fx1 - B, fy1 - B + 1, SUKUNA);
        g.fill(fx1 - B, fy + B - 1, fx1 - B + 1, fy1 - B + 1, SUKUNA);
        // 内缘獠牙：缓慢开合（咬合）
        float chomp = 0.5f + 0.5f * (float) Math.sin(t * 2.2f);
        int fang = 0xFFEDE0C8;
        for (int xx = mid + 6, n = 0; xx < fx1 - B - 3; xx += 9, n++) {
            int len = 3 + Math.round(3 * chomp) + (n % 3 == 0 ? 2 : 0);
            fangV(g, xx, fy + B, 1, len, 2, fang);
            fangV(g, xx, fy1 - B - 1, -1, len, 2, fang);
        }
        for (int yy = fy + B + 6, n = 0; yy < fy1 - B - 3; yy += 9, n++) {
            int len = 3 + Math.round(3 * chomp) + (n % 3 == 0 ? 2 : 0);
            fangH(g, fx1 - B - 1, yy, -1, len, 2, fang);
        }
        // 角板：交叉斩痕
        for (int e = 0; e < 2; e++) {
            int cy = e == 0 ? fy + B / 2 : fy1 - B / 2, cx = fx1 - B / 2 - 1;
            g.fill(cx - 8, cy - 8, cx + 9, cy + 9, 0xFF1A0306);
            g.renderOutline(cx - 8, cy - 8, 17, 17, SUKUNA);
            float fl = (now / 90) % 20 == e * 7 ? 1f : 0.6f;
            line(g, cx - 6, cy - 6, cx + 6, cy + 6, 1, alpha(0xFFFFFFFF, fl));
            line(g, cx + 6, cy - 6, cx - 6, cy + 6, 1, alpha(SUKUNA_GLOW, fl));
        }
        // 右中徽盘：旋转的獠牙环 + 十字斩痕 + 血光内核
        {
            int cx = fx1 - B / 2 - 1, cy = (fy + fy1) / 2;
            disk(g, cx, cy, 15, 1, bone);
            disk(g, cx, cy, 14, 1, 0xFF1A0206);
            float p = 0.5f + 0.5f * (float) Math.sin(t * 3);
            disk(g, cx, cy, 9, 1, alpha(SUKUNA, 0.25f + 0.25f * p));
            for (int i = 0; i < 8; i++) {
                double a = t * 0.8 + i * Math.PI / 4;
                float c = (float) Math.cos(a), s2 = (float) Math.sin(a);
                line(g, cx + c * 13, cy + s2 * 13, cx + c * 9, cy + s2 * 9, 2, fang);
            }
            double sa = -t * 1.3;
            for (int j = 0; j < 2; j++) {
                double a = sa + j * Math.PI / 2;
                float c = (float) Math.cos(a) * 8, s2 = (float) Math.sin(a) * 8;
                line(g, cx - c, cy - s2, cx + c, cy + s2, 1, 0xFFFFFFFF);
            }
            disk(g, cx, cy, 2 + p, 1, SUKUNA_GLOW);
        }
        g.disableScissor();

        // ================= 上下中缝扣环 =================
        if (k >= 1f) {
            for (int e = 0; e < 2; e++) {
                int cy = e == 0 ? fy + B / 2 : fy1 - B / 2;
                g.fill(mid - 6, cy - 6, mid, cy + 7, 0xFF0A1430);
                g.fill(mid, cy - 6, mid + 6, cy + 7, 0xFF3A0610);
                g.renderOutline(mid - 6, cy - 6, 12, 13, 0xFFFFFFFF);
                float fl = 0.5f + 0.5f * (float) Math.sin(t * 7 + e);
                g.fill(mid - 3, cy - 2, mid, cy + 3, alpha(GOJO, fl));
                g.fill(mid, cy - 2, mid + 3, cy + 3, alpha(SUKUNA_GLOW, 1 - fl));
                g.fill(mid, cy - 6, mid + 1, cy + 7, 0xFFFFFFFF);
            }
        }
    }
}
