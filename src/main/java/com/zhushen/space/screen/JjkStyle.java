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
        long now = ZsAnim.nowMs();
        int s = 3;
        int bg = hover ? alpha(accent, 0.9f) : 0xE0080808;
        for (int r = 0; r < h; r++) {
            int off = s - r * s / Math.max(1, h - 1);
            g.fill(x + off, y + r, x + w - s + off, y + r + 1, bg);
        }
        int e = enabled ? accent : 0xFF404040;
        line(g, x + s, y, x + w, y, 1, e);
        line(g, x, y + h - 1, x + w - s, y + h - 1, 1, e);
        line(g, x + s, y, x, y + h - 1, 1, e);
        line(g, x + w, y, x + w - s, y + h - 1, 1, e);
        if (enabled && !hover) {
            float t = ((now + x * 7L) % 3000) / 3000f;
            int px = x + (int) (t * w);
            g.fill(px, y + h - 2, Math.min(x + w - s, px + 10), y + h - 1, alpha(accent, 0.8f));
        }
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2 + 1,
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
        float open = ZsAnim.easeOutCubic(ZsAnim.clamp01((ZsAnim.nowMs() - since0) / 700f));
        g.fill(x + 2, y + 2, x + w + 2, y + h + 2, 0x66000000);
        JjkDomain.backdrop(g, x, y, w, h, open);
        // 标签行下压暗，保证文字可读
        g.fillGradient(x + 1, y + 1, x + w - 1, y + headerH - 16, 0xAA000000, 0x00000000);
        JjkDomain.frame3d(g, x, y, w, h, open);
    }

    /** 外框内缩：小游戏区域 = 标签行以下、货币栏以上，距外框 FRAME_IN */
    public static final int FRAME_IN = 7;

    public static int[] frameInner(int x, int y, int w, int h, int headerH) {
        return new int[]{x + FRAME_IN, y + headerH, x + w - FRAME_IN, y + h - 17};
    }

    /** 半透明单像素点 */
    private static void px(GuiGraphics g, float x, float y, int col) {
        int ix = (int) x, iy = (int) y;
        g.fill(ix, iy, ix + 1, iy + 1, col);
    }

    /**
     * 整个面板外框（精细动态）：两层发丝线构成实体框身（外线 + 内线，间距 5px），
     * 左半「无量空处」：框身内是缓慢流动的星尘与无限细分刻度（越靠近角越密，象征无限趋近），
     *   沿框奔流的细光、左中嵌一枚极小的奇点环、四角是细线同心弧。
     * 右半「伏魔御厨子」：框身内是暗红漆底与一排细密的齿痕，沿框随机闪现发丝般的斩线（带细小错位），
     *   右中嵌细线獠牙环、四角是交叉细斩痕。上下中缝两种结界互相侵蚀，边界抖动并迸出细火花。
     * 打开时框从上下中缝向四角描出。
     */
    public static void frame(GuiGraphics g, int x, int y, int w, int h, long since0) {
        long now = ZsAnim.nowMs();
        float t = now / 1000f;
        int mid = x + w / 2, x1 = x + w - 1, y1 = y + h - 1;
        int IN = 5;
        float grow = ZsAnim.easeOutCubic(ZsAnim.clamp01((now - since0) / 800f));
        int reach = Math.round((w / 2f + 1) * Math.min(1f, grow * 1.4f));        // 横向描出
        float vgrow = ZsAnim.clamp01(grow * 1.4f - 0.4f);                         // 竖边随后描出

        // 边界抖动（两种结界互相侵蚀）
        int seam = mid + Math.round((float) Math.sin(t * 1.3f) * 2 + (float) Math.sin(t * 3.7f));

        // ---------- 框身底色 ----------
        g.enableScissor(mid - reach, y, seam, y + h);
        band(g, x, y, w, h, IN, 0xE0081028, 0xE0030612);
        g.disableScissor();
        g.enableScissor(seam, y, mid + reach + 1, y + h);
        band(g, x, y, w, h, IN, 0xE0300610, 0xE0120206);
        g.disableScissor();

        // ---------- 左：无量空处 ----------
        g.enableScissor(mid - reach, y, seam, y + h);
        int vy = Math.round(h / 2f * vgrow);
        int cyM = y + h / 2;
        // 发丝双线
        g.fill(x, y, seam, y + 1, 0xFFE8F6FF);
        g.fill(x, y1, seam, y1 + 1, 0xCCE8F6FF);
        g.fill(x + IN, y + IN, seam, y + IN + 1, alpha(GOJO, 0.8f));
        g.fill(x + IN, y1 - IN, seam, y1 - IN + 1, alpha(GOJO, 0.8f));
        if (vgrow > 0) {
            g.fill(x, y, x + 1, y + vy, 0xFFE8F6FF);
            g.fill(x, y1 - vy, x + 1, y1 + 1, 0xFFE8F6FF);
            g.fill(x + IN, y + IN, x + IN + 1, Math.min(cyM, y + IN + vy), alpha(GOJO, 0.8f));
            g.fill(x + IN, Math.max(cyM, y1 - IN - vy), x + IN + 1, y1 - IN + 1, alpha(GOJO, 0.8f));
        }
        // 无限细分刻度：越靠近角越密（1/n 分布），亮度随时间波动
        for (int n = 1; n < 60; n++) {
            float f = 1f - 1f / (1 + n * 0.12f);
            float wv = 0.5f + 0.5f * (float) Math.sin(n * 0.7f - t * 4);
            int c = alpha(GOJO_LIGHT, 0.10f + 0.35f * wv);
            int tx = Math.round(seam - (seam - x - IN) * f);
            g.fill(tx, y + 2, tx + 1, y + 4, c);
            g.fill(tx, y1 - 3, tx + 1, y1 - 1, c);
            if (vgrow > 0.99f) {
                int ty = Math.round(cyM - (cyM - y - IN) * f);
                int by2 = Math.round(cyM + (y1 - IN - cyM) * f);
                g.fill(x + 2, ty, x + 4, ty + 1, c);
                g.fill(x + 2, by2, x + 4, by2 + 1, c);
            }
        }
        // 星尘：框身里缓慢漂移的亮点
        for (int i = 0; i < 46; i++) {
            float u = (hash(i, 51) + t * 0.02f * (0.5f + hash(i, 52))) % 1f;
            float[] p = halfPath(x, y, w, h, IN, u, true, seam);
            float tw = (float) Math.abs(Math.sin(t * (1.5f + hash(i, 53) * 3) + i));
            px(g, p[0] + (hash(i, 54) - 0.5f) * 3, p[1] + (hash(i, 55) - 0.5f) * 3, alpha(0xFFFFFFFF, 0.15f + 0.6f * tw));
        }
        // 沿框奔流的细光（自中缝向外，带渐隐尾）
        for (int i = 0; i < 5; i++) {
            float u = (t * 0.09f + i / 5f) % 1f;
            for (int j = 0; j < 14; j++) {
                float[] p = halfPath(x, y, w, h, IN, u - j * 0.0025f, true, seam);
                px(g, p[0], p[1], alpha(j == 0 ? 0xFFFFFFFF : GOJO, 0.9f * (1 - j / 14f)));
            }
        }
        // 四角：细线同心弧（缓转）
        cornerArcs(g, x + 2.5f, y + 2.5f, 0, t);
        cornerArcs(g, x + 2.5f, y1 - 2.5f, 3, t);
        // 左中：极小的奇点环
        if (vgrow > 0.99f) {
            float cx = x + 2.5f;
            disk(g, cx, cyM, 5.5f, 1, 0xFF01030C);
            ring(g, cx, cyM, 6.5f, 6.5f, t * 0.8f, 40, alpha(GOJO_LIGHT, 0.9f));
            ring(g, cx, cyM, 4.5f, 4.5f, -t * 1.6f, 24, alpha(GOJO, 0.9f));
            ring(g, cx, cyM, 2.5f, 2.5f, t * 3f, 12, alpha(GOJO_LIGHT, 0.7f));
            float pl = 0.5f + 0.5f * (float) Math.sin(t * 4);
            px(g, cx, cyM, alpha(0xFFFFFFFF, 0.6f + 0.4f * pl));
            // 引力：周围细点被缓慢吸入
            for (int i = 0; i < 8; i++) {
                float ph = (t * 0.6f + hash(i, 61)) % 1f;
                double a = hash(i, 62) * Math.PI * 2;
                float r = 14 * (1 - ph) + 3;
                px(g, cx + (float) Math.cos(a) * r, cyM + (float) Math.sin(a) * r, alpha(GOJO_LIGHT, ph * 0.8f));
            }
        }
        g.disableScissor();

        // ---------- 右：伏魔御厨子 ----------
        g.enableScissor(seam, y, mid + reach + 1, y + h);
        int bone = 0xFFE6D8BC;
        g.fill(seam, y, x1 + 1, y + 1, bone);
        g.fill(seam, y1, x1 + 1, y1 + 1, alpha(bone, 0.8f));
        g.fill(seam, y + IN, x1 - IN + 1, y + IN + 1, alpha(SUKUNA, 0.9f));
        g.fill(seam, y1 - IN, x1 - IN + 1, y1 - IN + 1, alpha(SUKUNA, 0.9f));
        if (vgrow > 0) {
            g.fill(x1, y, x1 + 1, y + vy, bone);
            g.fill(x1, y1 - vy, x1 + 1, y1 + 1, bone);
            g.fill(x1 - IN, y + IN, x1 - IN + 1, Math.min(cyM, y + IN + vy), alpha(SUKUNA, 0.9f));
            g.fill(x1 - IN, Math.max(cyM, y1 - IN - vy), x1 - IN + 1, y1 - IN + 1, alpha(SUKUNA, 0.9f));
        }
        // 细密齿痕：内线上一排 2px 小齿，缓慢咬合
        float chomp = 0.5f + 0.5f * (float) Math.sin(t * 2.4f);
        for (int xx = seam + 3; xx < x1 - IN; xx += 4) {
            int len = 1 + (chomp > 0.5f && (xx / 4) % 3 == 0 ? 1 : 0);
            g.fill(xx, y + IN - len, xx + 1, y + IN, alpha(bone, 0.75f));
            g.fill(xx, y1 - IN + 1, xx + 1, y1 - IN + 1 + len, alpha(bone, 0.75f));
        }
        if (vgrow > 0.99f) {
            for (int yy = y + IN + 3; yy < y1 - IN; yy += 4) {
                int len = 1 + (chomp > 0.5f && (yy / 4) % 3 == 0 ? 1 : 0);
                g.fill(x1 - IN + 1, yy, x1 - IN + 1 + len, yy + 1, alpha(bone, 0.75f));
            }
        }
        // 血脉：框身中线上缓慢流动的暗红细线
        for (int i = 0; i < 4; i++) {
            float u = (t * 0.06f + i / 4f) % 1f;
            for (int j = 0; j < 22; j++) {
                float[] p = halfPath(x, y, w, h, IN, u - j * 0.003f, false, seam);
                px(g, p[0], p[1], alpha(SUKUNA_GLOW, 0.55f * (1 - j / 22f)));
            }
        }
        // 发丝斩线：沿框随机闪现，一瞬划开后留下细小错位再消散
        for (int i = 0; i < 9; i++) {
            long cyc = (now + i * 173) / 620;
            float ph = ((now + i * 173) % 620) / 620f;
            if (hash(i, cyc * 7) < 0.35f) continue;
            float[] p = halfPath(x, y, w, h, IN, hash(i, cyc), false, seam);
            float ang = (float) (hash(i + 20, cyc) * Math.PI);
            float len = 5 + hash(i + 30, cyc) * 9;
            float gr = Math.min(1f, ph * 8f), a = ph < 0.15f ? 1f : 1 - (ph - 0.15f) / 0.85f;
            float cx = (float) Math.cos(ang) * len / 2 * gr, cy = (float) Math.sin(ang) * len / 2 * gr;
            line(g, p[0] - cx, p[1] - cy, p[0] + cx, p[1] + cy, 1, alpha(0xFFFFFFFF, a));
            float nx = -(float) Math.sin(ang), ny = (float) Math.cos(ang);
            line(g, p[0] - cx + nx, p[1] - cy + ny, p[0] + cx + nx, p[1] + cy + ny, 1, alpha(SUKUNA, a * 0.6f));
        }
        // 四角：交叉细斩痕
        crossCut(g, x1 - 2.5f, y + 2.5f, now, 0);
        crossCut(g, x1 - 2.5f, y1 - 2.5f, now, 1);
        // 右中：细线獠牙环
        if (vgrow > 0.99f) {
            float cx = x1 - 2.5f;
            disk(g, cx, cyM, 5.5f, 1, 0xFF140205);
            ring(g, cx, cyM, 6.5f, 6.5f, 0, 40, alpha(bone, 0.85f));
            for (int i = 0; i < 10; i++) {
                double a = -t * 0.7 + i * Math.PI / 5;
                float c = (float) Math.cos(a), sn = (float) Math.sin(a);
                px(g, cx + c * 5, cyM + sn * 5, bone);
                px(g, cx + c * 4, cyM + sn * 4, alpha(bone, 0.5f));
            }
            float pl = 0.5f + 0.5f * (float) Math.sin(t * 3);
            disk(g, cx, cyM, 1.5f + pl, 1, alpha(SUKUNA_GLOW, 0.9f));
        }
        g.disableScissor();

        // ---------- 上下中缝：侵蚀边界 + 细火花 ----------
        if (grow > 0.3f) {
            for (int e = 0; e < 2; e++) {
                int sy = e == 0 ? y : y1 - IN;
                int jit = Math.round((hash(e, now / 60) - 0.5f) * 2);
                g.fill(seam + jit, sy, seam + jit + 1, sy + IN + 1, 0xFFFFFFFF);
                for (int i = 0; i < 8; i++) {
                    long cyc = (now + i * 97) / 420;
                    float ph = ((now + i * 97) % 420) / 420f;
                    int dir = i % 2 == 0 ? -1 : 1;
                    float pxx = seam + dir * ph * (3 + hash(i + e * 10, cyc) * 9);
                    float pyy = sy + IN / 2f + (hash(i + 5 + e, cyc) - 0.5f) * (IN + ph * 6);
                    px(g, pxx, pyy, alpha(dir < 0 ? GOJO_LIGHT : SUKUNA_GLOW, 1 - ph));
                }
            }
        }
        // 外阴影
        g.fill(x + 1, y1 + 1, x1 + 2, y1 + 2, 0x55000000);
        g.fill(x1 + 1, y + 1, x1 + 2, y1 + 1, 0x55000000);
    }

    /** 框身（两层线之间）底色 */
    private static void band(GuiGraphics g, int x, int y, int w, int h, int IN, int c0, int c1) {
        g.fillGradient(x + 1, y + 1, x + w - 1, y + IN, c0, c1);
        g.fillGradient(x + 1, y + h - IN, x + w - 1, y + h - 1, c1, c0);
        g.fill(x + 1, y + IN, x + IN, y + h - IN, c1);
        g.fill(x + w - IN, y + IN, x + w - 1, y + h - IN, c1);
    }

    /** 框身中线上的点：u∈[0,1]，自上中缝 → 角 → 竖边中点（u<0.5 上半程，u≥0.5 对称下半程） */
    private static float[] halfPath(int x, int y, int w, int h, int IN, float u, boolean left, int seam) {
        u = ((u % 1f) + 1f) % 1f;
        boolean top = u < 0.5f;
        float v = top ? u * 2 : (u - 0.5f) * 2;
        float c = IN / 2f + 0.5f;
        float edgeX = left ? x + c : x + w - 1 - c;
        float horiz = Math.abs(seam - edgeX), vert = h / 2f - c;
        float d = v * (horiz + vert);
        float sx = left ? -1 : 1;
        float ly = top ? y + c : y + h - 1 - c;
        if (d < horiz) return new float[]{seam + sx * d, ly};
        d -= horiz;
        return new float[]{edgeX, top ? ly + d : ly - d};
    }

    private static void cornerArcs(GuiGraphics g, float cx, float cy, int q, float t) {
        for (int k = 0; k < 3; k++) {
            float r = 5 + k * 3;
            int n = 10 + k * 5;
            for (int i = 0; i <= n; i++) {
                double a = q * Math.PI / 2 + (i / (float) n) * Math.PI / 2;
                if (q == 3) a = -i / (float) n * Math.PI / 2;
                if (q == 0) a = i / (float) n * Math.PI / 2;
                float tw = 0.5f + 0.5f * (float) Math.sin(t * 2 - k + i * 0.4f);
                px(g, cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, alpha(GOJO_LIGHT, 0.2f + 0.5f * tw));
            }
        }
    }

    private static void crossCut(GuiGraphics g, float cx, float cy, long now, int e) {
        float fl = ((now / 80) % 25) == e * 11 ? 1f : 0.55f;
        line(g, cx - 5, cy - 5, cx + 5, cy + 5, 1, alpha(0xFFFFFFFF, fl));
        line(g, cx + 5, cy - 5, cx - 5, cy + 5, 1, alpha(SUKUNA_GLOW, fl));
        line(g, cx - 6, cy - 4, cx + 4, cy + 6, 1, alpha(SUKUNA, fl * 0.4f));
    }

    // ================= 咒术回战 UI 组件（动态） =================

    /** 咒力面板：墨色渐变底 + 毛笔粗糙边 + 流动咒力描边 + 角落封印括号 + 上升咒力残屑 */
    public static void cursedPanel(GuiGraphics g, int x, int y, int w, int h, int accent, float fade, int seed) {
        long now = ZsAnim.nowMs();
        g.fillGradient(x, y, x + w, y + h, alpha(0xFF07080E, 0.9f * fade), alpha(0xFF0B0306, 0.9f * fade));
        // 底部咒力雾
        g.fillGradient(x + 1, y + h - 18, x + w - 1, y + h - 1, 0, alpha(accent, 0.10f * fade));
        // 毛笔边：顶/底随机粗细
        for (int i = 0; i < w; i += 2) {
            float r1 = hash(seed * 31L + i, 7), r2 = hash(seed * 17L + i, 11);
            int t1 = r1 > 0.85f ? 2 : 1, t2 = r2 > 0.8f ? 2 : 1;
            g.fill(x + i, y, x + Math.min(w, i + 2), y + t1, alpha(0xFF2A2F3C, fade));
            g.fill(x + i, y + h - t2, x + Math.min(w, i + 2), y + h, alpha(0xFF2A2F3C, fade));
        }
        g.fill(x, y, x + 1, y + h, alpha(0xFF2A2F3C, fade));
        g.fill(x + w - 1, y, x + w, y + h, alpha(0xFF2A2F3C, fade));
        // 流动咒力：沿周长游走的光带
        int per = 2 * (w + h);
        for (int k = 0; k < 2; k++) {
            float head = ((now / 6f) + k * per / 2f + seed * 53) % per;
            for (int j = 0; j < 36; j++) {
                float d = (head - j * 1.5f + per) % per;
                float a = (1 - j / 36f) * fade;
                int col = alpha(k == 0 ? accent : SUKUNA_GLOW, a * 0.9f);
                float px, py;
                if (d < w) { px = x + d; py = y; }
                else if (d < w + h) { px = x + w - 1; py = y + d - w; }
                else if (d < 2 * w + h) { px = x + w - 1 - (d - w - h); py = y + h - 1; }
                else { px = x; py = y + h - 1 - (d - 2 * w - h); }
                g.fill((int) px, (int) py, (int) px + 1, (int) py + 1, col);
            }
        }
        // 角落封印括号
        int c = alpha(accent, fade), L = 6;
        g.fill(x - 1, y - 1, x + L, y + 1, c); g.fill(x - 1, y - 1, x + 1, y + L, c);
        g.fill(x + w - L, y - 1, x + w + 1, y + 1, c); g.fill(x + w - 1, y - 1, x + w + 1, y + L, c);
        g.fill(x - 1, y + h - 1, x + L, y + h + 1, c); g.fill(x - 1, y + h - L, x + 1, y + h + 1, c);
        g.fill(x + w - L, y + h - 1, x + w + 1, y + h + 1, c); g.fill(x + w - 1, y + h - L, x + w + 1, y + h + 1, c);
        // 上升残屑
        for (int i = 0; i < Math.max(4, w / 14); i++) {
            float life = ((now + (long) (hash(seed, i) * 4000)) % 4000) / 4000f;
            int px = x + 3 + (int) (hash(seed + 1, i) * (w - 6)) + (int) (Math.sin(life * 6 + i) * 2);
            int py = y + h - 2 - (int) (life * Math.min(40, h * 0.5f));
            g.fill(px, py, px + 1, py + 1, alpha(i % 3 == 0 ? SUKUNA_GLOW : accent, (1 - life) * 0.6f * fade));
        }
    }

    /** 符咒标签：选中为纸白符纸 + 朱印；未选为墨底；悬停轻颤 */
    public static void talisman(GuiGraphics g, Font font, int mx, int my, int x, int y, int w, int h,
                                Component label, int accent, boolean sel, float fade) {
        boolean hov = ZsTheme.over(mx, my, x, y, w, h);
        long now = ZsAnim.nowMs();
        int jx = hov && !sel ? (int) Math.round(Math.sin(now / 40.0) * 0.6) : 0;
        x += jx;
        if (sel) {
            g.fill(x, y, x + w, y + h, alpha(PAPER, fade));
            g.fill(x, y, x + 2, y + h, alpha(SUKUNA, fade));
            g.fill(x + w - 2, y, x + w, y + h, alpha(SUKUNA, fade));
            // 纸面墨纹扫过
            int sx = (int) ((now / 8) % (w + 20)) - 10;
            for (int k = 0; k < 4; k++) g.fill(Math.max(x + 2, x + sx + k), y, Math.min(x + w - 2, x + sx + k + 1), y + h, 0x18000000);
            g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2 + 1, INK, false);
        } else {
            g.fill(x, y, x + w, y + h, alpha(0xE0080808, fade));
            g.fill(x, y, x + w, y + 1, alpha(hov ? 0xFFFFFFFF : accent, 0.7f * fade));
            g.fill(x, y + h - 1, x + w, y + h, alpha(0xFF303440, fade));
            g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2 + 1, hov ? 0xFFFFFFFF : 0xFFB8C0CC, false);
        }
    }

    /** 斜切等级印：斜角平行四边形；拥有=主题色 + 斩击光；待确认=脉冲 */
    public static void sealPip(GuiGraphics g, Font font, int x, int y, int w, int h, String label,
                               int accent, boolean own, boolean saved, boolean hov) {
        long now = ZsAnim.nowMs();
        float pulse = ZsAnim.pulse(700);
        int bg = own ? (saved ? accent : alpha(accent, 0.45f + 0.4f * pulse)) : 0xE00A0B10;
        int s = Math.min(4, h / 2);
        for (int r = 0; r < h; r++) {
            int off = s - r * s / Math.max(1, h - 1);
            g.fill(x + off, y + r, x + w - s + off, y + r + 1, bg);
        }
        int edge = hov ? 0xFFFFFFFF : alpha(accent, own ? 1f : 0.7f);
        line(g, x + s, y, x + w, y, 1, edge);
        line(g, x, y + h - 1, x + w - s, y + h - 1, 1, edge);
        line(g, x + s, y, x, y + h - 1, 1, edge);
        line(g, x + w, y, x + w - s, y + h - 1, 1, edge);
        if (own) {
            // 周期性斩击高光
            float t = ((now + x * 13L) % 2400) / 2400f;
            if (t < 0.25f) {
                float u = t / 0.25f;
                int cx = x + (int) (u * (w + 8)) - 4;
                line(g, cx + 3, y, cx - 3, y + h, 2, 0x88FFFFFF);
            }
        }
        if (hov) g.fill(x + s, y - 2, x + w - s, y - 1, alpha(accent, 0.6f + 0.4f * pulse));
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2 + 1, own ? INK : 0xFFD8DEE8, false);
    }

    /** 列表选中条：咒焰火舌 + 横扫墨迹 */
    public static void selectRow(GuiGraphics g, int x, int y, int w, int h, int accent) {
        long now = ZsAnim.nowMs();
        g.fillGradient(x, y, x + w, y + h, alpha(accent, 0.28f), alpha(accent, 0.08f));
        for (int i = 0; i < w; i += 2) {
            float f = (float) Math.sin(i * 0.35 + now / 90.0) * 0.5f + 0.5f;
            int fh = (int) (f * 3 * hash(i, now / 120));
            if (fh > 0) g.fill(x + i, y + h - fh, x + i + 1, y + h, alpha(accent, 0.5f));
        }
        int sx = (int) ((now / 5) % (w + 40)) - 20;
        g.fillGradient(Math.max(x, x + sx), y, Math.min(x + w, x + sx + 14), y + h, 0x00FFFFFF, 0x00FFFFFF);
        for (int k = 0; k < 14; k++) {
            int px = x + sx + k;
            if (px < x || px >= x + w) continue;
            g.fill(px, y, px + 1, y + h, alpha(0xFFFFFFFF, 0.10f * (1 - Math.abs(k - 7) / 7f)));
        }
    }
}
