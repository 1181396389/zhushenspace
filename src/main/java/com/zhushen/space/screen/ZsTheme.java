package com.zhushen.space.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.sounds.SoundEvents;

/**
 * 主神空间 UI 统一主题：配色常量 + 常用绘制组件（面板 / 按钮 / 点数圆点 / 滚动条）。
 * 所有界面共用这一套，避免每个 Screen 各自复制一份配色与绘制代码。
 */
public final class ZsTheme {

    private ZsTheme() {
    }

    // ===== 配色 =====
    public static final int PANEL_BG = 0xEE0A1622;
    public static final int PANEL_BORDER = 0xFF5B9BD5;
    public static final int PANEL_GLOW = 0x333BA9E0;
    public static final int HEADER_LINE = 0x665B9BD5;
    public static final int ROW_BG = 0x66132B42;
    public static final int ROW_BG_ALT = 0x440F2233;
    public static final int ROW_HOVER = 0x99256490;
    public static final int TEXT_MAIN = 0xFFD9EEFF;
    public static final int TEXT_SUB = 0xFF8FC6EE;
    public static final int TEXT_TITLE = 0xFF9FD8F8;
    public static final int TEXT_DISABLED = 0xFF4A6A80;
    public static final int ACCENT = 0xFF3BA9E0;
    public static final int GOLD = 0xFFFFD966;
    public static final int CURRENCY = 0xFFE0B84D;
    public static final int WARN = 0xFFFF7B7B;
    public static final int PENDING = 0xFF7CF0A0;
    public static final Style GOLD_STYLE = Style.EMPTY.withColor(TextColor.fromRgb(GOLD));
    public static final int DOT_FULL = 0xFF4FC3F7;
    public static final int DOT_FULL_5 = 0xFFFFD966;
    public static final int DOT_PENDING = 0xFF7CF0A0;
    public static final int DOT_EMPTY = 0x66203A52;
    public static final int DOT_BORDER = 0xFF3A6E96;
    public static final int BTN_BG = 0xCC16455F;
    public static final int BTN_HOVER = 0xE02A6E96;
    public static final int BTN_ACTIVE = 0xE02A6E96;
    public static final int BTN_DISABLED = 0x55143049;
    public static final int BTN_BORDER = 0xFF5B9BD5;
    public static final int BTN_BORDER_HOVER = 0xFF7FC4F0;
    public static final int BTN_BORDER_DISABLED = 0xFF2A4A62;
    public static final int SLOT_BG = 0x99132B42;
    public static final int SLOT_BORDER = 0xFF3A6E96;
    public static final int SLOT_HOVER_BORDER = 0xFF7FC4F0;
    public static final int CHIP_BG = 0xCC16455F;
    public static final int CHIP_HOVER = 0xE02A6E96;

    // ===== 判定 =====

    public static boolean over(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ===== 绘制 =====

    /** 带外发光的主面板 */
    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, PANEL_GLOW);
        g.fill(x, y, x + w, y + h, PANEL_BG);
        g.renderOutline(x, y, w, h, PANEL_BORDER);
        g.renderOutline(x + 1, y + 1, w - 2, h - 2, PANEL_GLOW);
    }

    /** 水平分隔线 */
    public static void separator(GuiGraphics g, int x1, int x2, int y) {
        g.fill(x1, y, x2, y + 1, HEADER_LINE);
    }

    /** 通用按钮（enabled=false 时灰显，active=true 时保持高亮，用于选项卡） */
    public static void button(GuiGraphics g, Font font, int mx, int my, int x, int y, int w, int h,
                              Component label, boolean enabled, boolean active) {
        boolean hover = enabled && over(mx, my, x, y, w, h);
        int bg = !enabled ? BTN_DISABLED : active ? BTN_ACTIVE : hover ? BTN_HOVER : BTN_BG;
        int border = !enabled ? BTN_BORDER_DISABLED : (active || hover) ? BTN_BORDER_HOVER : BTN_BORDER;
        g.fill(x, y, x + w, y + h, bg);
        g.renderOutline(x, y, w, h, border);
        int color = !enabled ? TEXT_DISABLED : (active || hover) ? TEXT_MAIN : TEXT_SUB;
        g.drawCenteredString(font, label, x + w / 2, y + (h - 8) / 2, color);
    }

    public static void button(GuiGraphics g, Font font, int mx, int my, int x, int y, int w, int h,
                              Component label) {
        button(g, font, mx, my, x, y, w, h, label, true, false);
    }

    /**
     * 点数圆点条：saved 个已保存点（蓝，满级那颗金色），saved~current 之间为待确认点（绿）。
     * 返回圆点条右端 x。
     */
    public static int dots(GuiGraphics g, int x, int y, int size, int gap, int max, int saved, int current) {
        for (int d = 0; d < max; d++) {
            int dx = x + d * (size + gap);
            if (d < current) {
                int color = d >= saved ? DOT_PENDING : (d == max - 1 ? DOT_FULL_5 : DOT_FULL);
                g.fill(dx, y, dx + size, y + size, color);
            } else {
                g.fill(dx, y, dx + size, y + size, DOT_EMPTY);
                g.renderOutline(dx, y, size, size, d < saved ? WARN : DOT_BORDER);
            }
        }
        return x + max * (size + gap);
    }

    /** 竖直滚动条 */
    public static void scrollbar(GuiGraphics g, int x, int top, int bottom, int content, int scroll, int maxScroll) {
        if (maxScroll <= 0 || content <= 0) return;
        int trackH = bottom - top;
        g.fill(x, top, x + 3, bottom, 0x33132B42);
        int thumbH = Math.max(10, trackH * trackH / Math.max(trackH, content));
        int thumbY = top + (int) ((float) (trackH - thumbH) * scroll / maxScroll);
        g.fill(x, thumbY, x + 3, thumbY + thumbH, PANEL_BORDER);
    }

    public static void click(float pitch) {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch));
    }
}
