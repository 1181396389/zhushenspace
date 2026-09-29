package com.zhushen.space.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 「新月同行」风格（技能页）—— 为纪念而作。
 *
 * 依据主美公开的设计原则：银灰（冷灰）为底、橙色只作点缀；复古工业感而非未来科幻——
 * 仪器圆盘、刻度、打孔卡、条形码、源数学数字（π）。布局与交互简洁克制、注重统一：
 * 无描边阴影的墨色文字、细线分隔、方正无圆角、状态靠「墨 / 橙 / 灰」三色表达。
 */
public final class XytStyle {

    private XytStyle() {
    }

    // ===== 配色（冷灰 + 墨 + 橙） =====
    public static final int ORANGE = 0xFFF0701E;
    public static final int ORANGE_DEEP = 0xFFC4520E;
    public static final int INK = 0xFF1C1F23;
    public static final int INK_SUB = 0xFF5A6068;
    public static final int INK_FAINT = 0xFF8E949A;
    public static final int PAPER_ROW = 0x40FFFFFF;
    public static final int PAPER_ROW_HOVER = 0x8CFFFFFF;
    public static final int HAIRLINE = 0x331C1F23;
    public static final int WARN = 0xFFB0302A;
    public static final int WHITE = 0xFFF4F5F6;

    /** π 的前若干位（源数学：无限不循环，包含世间万物） */
    public static final String PI =
            "3.14159265358979323846264338327950288419716939937510582097494459230781640628620899862803482534211706798214";

    private static final ZsAnim.Sprite PAPER = new ZsAnim.Sprite(
            ResourceLocation.fromNamespaceAndPath("zhushenspace", "textures/gui/anim/xyt_paper.png"), 256, 192, 1, 1000);
    private static final ZsAnim.Sprite DIAL = ZsAnim.Sprite.of("xyt_dial", 96, 96, 30, 100);

    // ===== 底纹 =====

    /**
     * 档案纸底：按宽度等比铺放（不拉伸变形），右下角仪器圆盘缓转。
     */
    public static void paper(GuiGraphics g, int x, int y, int w, int h) {
        float s = w / 256f;
        int srcH = Math.min(192, Math.round(h / s));
        PAPER.draw(g, x, y, w, h, 0, 0, 256, srcH, 0xFFFFFFFF);
        // 仪器圆盘：右下角半露（裁切在纸面内）
        g.enableScissor(x, y, x + w, y + h);
        int ds = 84;
        DIAL.draw(g, x + w - ds + 22, y + h - ds + 26, ds, ds, 0x66FFFFFF);
        g.disableScissor();
        // 纸面上下沿细墨线
        g.fill(x, y, x + w, y + 1, 0x551C1F23);
        g.fill(x, y + h - 1, x + w, y + h, 0x551C1F23);
    }

    /** 标题牌：橙色竖块 + 墨色底白字（参考官方设定图人物名牌） */
    public static int titlePlate(GuiGraphics g, Font font, int x, int y, Component text) {
        Component bold = text.copy().withStyle(ChatFormatting.BOLD);
        int tw = font.width(bold);
        g.fill(x, y, x + 3, y + 13, ORANGE);
        g.fill(x + 4, y, x + 4 + tw + 8, y + 13, INK);
        g.drawString(font, bold, x + 8, y + 3, WHITE, false);
        return x + 4 + tw + 8;
    }

    /** 半尺寸小字（英文注释 / 编号 / 数字纸带） */
    public static void tiny(GuiGraphics g, Font font, String s, float x, float y, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.5f, 0.5f, 1);
        g.drawString(font, s, 0, 0, color, false);
        g.pose().popPose();
    }

    /** π 数字纸带：在 w 宽的窗口内缓慢左移循环（半尺寸字） */
    public static void piTape(GuiGraphics g, Font font, int x, int y, int w) {
        String loop = PI + "  ";
        int full = font.width(loop) / 2;
        float off = (ZsAnim.nowMs() / 60f) % full;
        g.enableScissor(x, y, x + w, y + 5);
        tiny(g, font, loop + loop, x - off, y, INK_SUB);
        g.disableScissor();
    }

    /** 刻度分隔线：墨色细线 + 左端橙色短段 + 下方每 8px 一格短刻度 */
    public static void rule(GuiGraphics g, int x1, int x2, int y) {
        g.fill(x1, y, x2, y + 1, 0x991C1F23);
        g.fill(x1, y - 1, x1 + 22, y + 1, ORANGE);
        for (int x = x1 + 26; x < x2; x += 8) {
            boolean major = ((x - x1 - 26) / 8) % 4 == 0;
            g.fill(x, y + 1, x + 1, y + (major ? 4 : 2), 0x661C1F23);
        }
    }

    /** 点数计：墨色标签 + 橙色两位数字 */
    public static int counter(GuiGraphics g, Font font, int x, int y, String label, int value) {
        int lw = font.width(label);
        g.fill(x, y, x + lw + 6, y + 11, INK);
        g.drawString(font, label, x + 3, y + 2, WHITE, false);
        String v = String.format("%02d", Math.max(0, value));
        Component vb = Component.literal(v).withStyle(ChatFormatting.BOLD);
        int vx = x + lw + 6;
        g.fill(vx, y, vx + font.width(vb) + 6, y + 11, value > 0 ? ORANGE : 0xFFB8BDC2);
        g.drawString(font, vb, vx + 3, y + 2, value > 0 ? WHITE : INK_SUB, false);
        return vx + font.width(vb) + 6;
    }

    // ===== 控件 =====

    /**
     * 按钮：primary=主操作（橙底白字），否则为墨线描边。悬停时 primary 加深、描边按钮填墨反白。
     */
    public static void button(GuiGraphics g, Font font, int mx, int my, int x, int y, int w, int h,
                              Component label, boolean enabled, boolean primary) {
        boolean hover = enabled && ZsTheme.over(mx, my, x, y, w, h);
        float t = ZsAnim.tween(ZsAnim.key(31, x, y), hover ? 1 : 0, 18);
        int ty = y + (h - 8) / 2 + 1;
        if (!enabled) {
            g.fill(x, y, x + w, y + h, 0x33FFFFFF);
            g.renderOutline(x, y, w, h, 0xFFB0B5BA);
            g.drawCenteredString(font, label, x + w / 2, ty, 0xFF9AA0A6);
            return;
        }
        if (primary) {
            g.fill(x, y, x + w, y + h, ZsAnim.lerpColor(ORANGE, ORANGE_DEEP, t));
            g.fill(x, y + h - 1, x + w, y + h, ORANGE_DEEP);
            drawCenteredNoShadow(g, font, label, x + w / 2, ty, WHITE);
        } else {
            g.fill(x, y, x + w, y + h, ZsAnim.lerpColor(0x55FFFFFF, INK, t));
            g.renderOutline(x, y, w, h, INK);
            drawCenteredNoShadow(g, font, label, x + w / 2, ty, ZsAnim.lerpColor(INK, WHITE, t));
        }
    }

    /** 方形 ± 按钮：墨线方框，悬停填橙反白，不可用时淡灰 */
    public static void stepButton(GuiGraphics g, Font font, int mx, int my, int x, int y, int s,
                                  boolean plus, boolean enabled) {
        boolean hover = enabled && ZsTheme.over(mx, my, x, y, s, s);
        float t = ZsAnim.tween(ZsAnim.key(32, x, y), hover ? 1 : 0, 20);
        int line = enabled ? ZsAnim.lerpColor(INK, WHITE, t) : 0xFFB0B5BA;
        if (enabled) g.fill(x, y, x + s, y + s, ZsAnim.lerpColor(0x40FFFFFF, ORANGE, t));
        g.renderOutline(x, y, s, s, enabled ? ZsAnim.lerpColor(INK, ORANGE, t) : 0xFFB0B5BA);
        // 手绘 ± 符号（像素对齐，避免字体基线偏移）
        int c = s / 2;
        g.fill(x + 3, y + c, x + s - 3, y + c + 1, line);
        if (plus) g.fill(x + c, y + 3, x + c + 1, y + s - 3, line);
    }

    /**
     * 等级刻度计：saved 格墨色实心，saved~cur 为橙色（待确认，闪烁），其余淡灰空框。
     * 满级时额外显示橙色「MAX」。返回右端 x。
     */
    public static int gauge(GuiGraphics g, Font font, int x, int y, int max, int saved, int cur, long key) {
        int segW = max > 8 ? 4 : 9, segH = 5, gap = max > 8 ? 1 : 2;
        float blink = 0.55f + 0.45f * ZsAnim.pulse(900);
        // 实心部分宽度平滑增长（加点时有「填充」动画）
        float fill = ZsAnim.tween(key, saved, 14);
        for (int i = 0; i < max; i++) {
            int sx = x + i * (segW + gap);
            if (i < cur && i >= saved) {
                g.fill(sx, y, sx + segW, y + segH, ZsAnim.withAlpha(ORANGE, blink));
            } else if (i < saved) {
                float part = ZsAnim.clamp01(fill - i);
                g.fill(sx, y, sx + segW, y + segH, 0x331C1F23);
                g.fill(sx, y, sx + Math.round(segW * part), y + segH, INK);
            } else {
                g.renderOutline(sx, y, segW, segH, INK_FAINT);
            }
        }
        int end = x + max * (segW + gap);
        return end;
    }

    /**
     * 列表行：半透白底 + 底部发丝线；悬停时底色提亮、左侧橙色标记由中心展开、右上角橙色小角标。
     */
    public static void row(GuiGraphics g, int x, int y, int w, int h, boolean hover) {
        float t = ZsAnim.tween(ZsAnim.key(33, x, y), hover ? 1 : 0, 18);
        g.fill(x, y, x + w, y + h, ZsAnim.lerpColor(PAPER_ROW, PAPER_ROW_HOVER, t));
        g.fill(x, y + h - 1, x + w, y + h, HAIRLINE);
        if (t > 0.01f) {
            int bh = Math.max(1, Math.round((h - 2) * t));
            int by = y + (h - bh) / 2;
            g.fill(x, by, x + 2, by + bh, ORANGE);
            int k = Math.round(4 * t);
            for (int i = 0; i < k; i++) g.fill(x + w - k + i, y + i, x + w, y + i + 1, ORANGE);
        }
    }

    /** 切换到本页时的一次性橙色扫描线（自上而下，带渐隐拖尾） */
    public static void scan(GuiGraphics g, int x, int y, int w, int h, long since) {
        float t = (ZsAnim.nowMs() - since) / 520f;
        if (t >= 1) return;
        int ly = y + Math.round(h * ZsAnim.easeInOutSine(t));
        for (int i = 0; i < 10; i++) {
            if (ly - i < y) break;
            g.fill(x, ly - i, x + w, ly - i + 1, ZsAnim.withAlpha(ORANGE, (1 - i / 10f) * 0.5f * (1 - t)));
        }
        g.fill(x, ly, x + w, ly + 1, ZsAnim.withAlpha(ORANGE, 1 - t));
    }

    /** 细竖滚动条：墨色细轨 + 橙色滑块 */
    public static void scrollbar(GuiGraphics g, int x, int top, int bottom, int content, int scroll, int maxScroll) {
        if (maxScroll <= 0 || content <= 0) return;
        int trackH = bottom - top;
        g.fill(x + 1, top, x + 2, bottom, 0x551C1F23);
        int thumbH = Math.max(10, trackH * trackH / Math.max(trackH, content));
        float s = ZsAnim.tween(ZsAnim.key(34, x, top), scroll, 20);
        int thumbY = top + (int) ((trackH - thumbH) * s / maxScroll);
        g.fill(x, thumbY, x + 3, thumbY + thumbH, ORANGE);
    }

    public static void drawCenteredNoShadow(GuiGraphics g, Font font, Component text, int cx, int y, int color) {
        g.drawString(font, text, cx - font.width(text) / 2, y, color, false);
    }

    // ===== 面板外框（技能页时整块面板统一为冷灰 + 橙） =====

    public static final int CHROME_BG = 0xF2202428;
    public static final int CHROME_EDGE = 0xFF6A7078;
    public static final int CHROME_BTN = 0xFF2E3339;
    public static final int CHROME_BTN_HOVER = 0xFF3A4047;
    public static final int CHROME_TEXT = 0xFFB8BDC2;

    /** 冷灰面板：深墨灰底 + 冷灰细边 + 左上橙色短标 + 右下橙色细线（无星云、无流光，克制） */
    public static void chrome(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x + 2, y + 2, x + w + 2, y + h + 2, 0x55000000); // 投影
        g.fill(x, y, x + w, y + h, CHROME_BG);
        g.renderOutline(x, y, w, h, CHROME_EDGE);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, 0x18FFFFFF);
        g.fill(x - 1, y - 1, x + 14, y + 1, ORANGE);
        g.fill(x - 1, y - 1, x + 1, y + 8, ORANGE);
        g.fill(x + w - 30, y + h - 1, x + w + 1, y + h + 1, ORANGE);
    }

    /**
     * 选项卡：未选中深灰浅字，悬停提亮，选中为冷灰纸色 + 墨字；橙色下划线在选项卡间平滑滑动。
     */
    public static void tabs(GuiGraphics g, Font font, int mx, int my, int[] xs, int[] ws, int y, int h,
                            Component[] labels, int selected) {
        for (int i = 0; i < xs.length; i++) {
            boolean sel = i == selected;
            boolean hover = ZsTheme.over(mx, my, xs[i], y, ws[i], h);
            float t = ZsAnim.tween(ZsAnim.key(36, xs[i], y), sel ? 1 : 0, 16);
            float hv = ZsAnim.tween(ZsAnim.key(37, xs[i], y), hover && !sel ? 1 : 0, 18);
            int bg = ZsAnim.lerpColor(ZsAnim.lerpColor(CHROME_BTN, CHROME_BTN_HOVER, hv), 0xFFD0D4D8, t);
            g.fill(xs[i], y, xs[i] + ws[i], y + h, bg);
            int tc = ZsAnim.lerpColor(ZsAnim.lerpColor(CHROME_TEXT, WHITE, hv), INK, t);
            drawCenteredNoShadow(g, font, labels[i], xs[i] + ws[i] / 2, y + (h - 8) / 2 + 1, tc);
        }
        float ux = ZsAnim.tween(ZsAnim.key(38, 0, y), xs[selected], 18);
        float uw = ZsAnim.tween(ZsAnim.key(38, 1, y), ws[selected], 18);
        g.fill((int) ux, y + h, (int) (ux + uw), y + h + 2, ORANGE);
    }

    /** 深色小按钮（面板标签行：大厅 / 设置）：悬停时边框转橙、文字提亮 */
    public static void darkButton(GuiGraphics g, Font font, int mx, int my, int x, int y, int w, int h,
                                  Component label) {
        boolean hover = ZsTheme.over(mx, my, x, y, w, h);
        float t = ZsAnim.tween(ZsAnim.key(39, x, y), hover ? 1 : 0, 18);
        g.fill(x, y, x + w, y + h, ZsAnim.lerpColor(CHROME_BTN, CHROME_BTN_HOVER, t));
        g.renderOutline(x, y, w, h, ZsAnim.lerpColor(CHROME_EDGE, ORANGE, t));
        drawCenteredNoShadow(g, font, label, x + w / 2, y + (h - 8) / 2 + 1,
                ZsAnim.lerpColor(CHROME_TEXT, WHITE, t));
    }
}
