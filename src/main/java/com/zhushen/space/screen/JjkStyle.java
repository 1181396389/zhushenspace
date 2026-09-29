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

    /** 主神面板上的专长入口：左蓝右红两半对撞，中缝闪电；有可用专长点时呼吸发光 */
    public static void entryButton(GuiGraphics g, Font font, int mx, int my, int x, int y, int w, int h,
                                   Component label, boolean glow) {
        long now = ZsAnim.nowMs();
        boolean hover = ZsTheme.over(mx, my, x, y, w, h);
        int mid = x + w / 2 + (int) (Math.sin(now / 300.0) * 2);
        if (glow) {
            float p = ZsAnim.pulse(1200);
            g.fill(x - 2, y - 2, x + w + 2, y + h + 2, alpha(0xFFB060FF, 0.15f + 0.3f * p));
        }
        g.fill(x, y, mid, y + h, 0xF00A1A38);
        g.fill(mid, y, x + w, y + h, 0xF0300610);
        // 中缝锯齿闪电
        for (int yy = y; yy < y + h; yy += 2) {
            int j = (int) ((hash(yy, now / 60) - 0.5f) * 3);
            g.fill(mid + j - 1, yy, mid + j, yy + 2, GOJO);
            g.fill(mid + j, yy, mid + j + 1, yy + 2, 0xFFFFFFFF);
            g.fill(mid + j + 1, yy, mid + j + 2, yy + 2, SUKUNA);
        }
        int edge = hover ? 0xFFFFFFFF : 0xFF8A6FB0;
        g.renderOutline(x, y, w, h, edge);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2 + 1, 0xFFFFFFFF, true);
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
}
