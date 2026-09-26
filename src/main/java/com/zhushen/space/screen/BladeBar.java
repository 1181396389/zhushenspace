package com.zhushen.space.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 无限剑制风格「巨剑技能栏」：战斗 HUD 与战斗预设界面共用。
 * 一柄横置巨剑：柄头 → 缠绳握柄 → 护手（宝石镶 A/B 栏位）→ 剑身（九个锻铸凹槽即技能槽）→ 剑尖。
 * 刃口有自柄向剑尖游走的炽光，槽间刻纹依次脉动；冷却为赤热铁水遮罩，冷却完成迸发火花。
 *
 * 逻辑尺寸 240×30（贴图 2 倍分辨率），可按 scale 缩放。
 */
public final class BladeBar {

    private BladeBar() {
    }

    public static final int W = 240, H = 30;
    public static final int SLOT0 = 36, PITCH = 21, SLOT = 20, SLOT_Y = 5;
    public static final int GEM_X = 23, GEM_Y = 15;

    private static final ResourceLocation BAR =
            ResourceLocation.fromNamespaceAndPath("zhushenspace", "textures/gui/anim/blade_bar.png");
    private static final ZsAnim.Sprite GLOW = new ZsAnim.Sprite(
            ResourceLocation.fromNamespaceAndPath("zhushenspace", "textures/gui/anim/blade_glow.png"),
            480, 60, 20, 70);

    // 锻铁配色
    public static final int EMBER = 0xFFFF9A3C;
    public static final int EMBER_HOT = 0xFFFFD27A;
    public static final int IRON_TEXT = 0xFFF2DCC0;
    public static final int IRON_SUB = 0xFFC49A74;

    /** 槽位左上角（相对剑栏原点，已缩放，四舍五入到像素） */
    public static int slotX(int barX, int slot, float scale) {
        return barX + Math.round((SLOT0 + slot * PITCH) * scale);
    }

    public static int slotY(int barY, float scale) {
        return barY + Math.round(SLOT_Y * scale);
    }

    public static int slotSize(float scale) {
        return Math.round(SLOT * scale);
    }

    /**
     * 绘制剑栏本体 + 流光。active=当前使用的栏（宝石燃起、刃光明亮），letter=护手宝石上的栏位字母。
     */
    public static void draw(GuiGraphics g, Font font, int x, int y, float scale, String letter, boolean active) {
        int w = Math.round(W * scale), h = Math.round(H * scale);
        float p = ZsAnim.pulse(1600);
        // 底部投影 + 活跃栏外焰
        g.fill(x + 2, y + h - 2, x + w - 6, y + h + 1, 0x66000000);
        if (active) {
            int glow = ZsAnim.withAlpha(EMBER, 0.12f + 0.18f * p);
            g.fill(x + Math.round(30 * scale), y - 2, x + w - Math.round(10 * scale), y + h + 2, glow);
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(BAR, x, y, w, h, 0, 0, 480, 60, 480, 60);
        RenderSystem.disableBlend();
        GLOW.draw(g, x, y, w, h, active ? 0xFFFFFFFF : 0x55FFFFFF);

        // 护手宝石：活跃栏熔金色呼吸，非活跃为暗红
        int gx = x + Math.round(GEM_X * scale), gy = y + Math.round(GEM_Y * scale);
        int r = Math.round(6 * scale);
        int gem = active ? ZsAnim.lerpColor(0xFFB84A10, 0xFFFFB040, p) : 0xFF4A1410;
        for (int dy = -r; dy <= r; dy++) {
            int half = (int) Math.sqrt(r * r - dy * dy);
            g.fill(gx - half, gy + dy, gx + half + 1, gy + dy + 1, gem);
        }
        g.drawCenteredString(font, letter, gx + 1, gy - 3, active ? 0xFFFFF4D8 : IRON_SUB);
    }

    /**
     * 技能槽内容：图标 + 冷却（赤热铁水自上而下退去，前沿白热线）+ 冷却完成火花闪。
     */
    public static void socket(GuiGraphics g, ResourceLocation icon, int sx, int sy, int size,
                              float remainFrac, float readyFlash, boolean hover) {
        if (hover) {
            g.fill(sx - 1, sy - 1, sx + size + 1, sy + size + 1, ZsAnim.withAlpha(EMBER, 0.55f));
            g.fill(sx, sy, sx + size, sy + size, 0xFF1A0C08);
        }
        if (icon != null) {
            int is = size - 4;
            g.blit(icon, sx + 2, sy + 2, is, is, 0f, 0f, 32, 32, 32, 32);
        }
        if (remainFrac > 0) {
            int h = (int) Math.ceil(size * ZsAnim.clamp01(remainFrac));
            int top = sy + size - h;
            g.fillGradient(sx, top, sx + size, sy + size, 0xC0501408, 0xD0200806);
            float p = ZsAnim.pulse(500);
            g.fill(sx, top, sx + size, top + 1, ZsAnim.lerpColor(EMBER, 0xFFFFFFFF, p * 0.6f));
        }
        if (readyFlash > 0) {
            g.fill(sx, sy, sx + size, sy + size, ZsAnim.withAlpha(EMBER_HOT, 0.5f * readyFlash));
            int ext = (int) (4 * (1 - readyFlash));
            g.renderOutline(sx - 1 - ext, sy - 1 - ext, size + 2 + ext * 2, size + 2 + ext * 2,
                    ZsAnim.withAlpha(EMBER_HOT, readyFlash));
        }
    }

    /**
     * 锻铁铭牌（技能芯片 / 文件夹）：深铁渐变 + 铜边，悬停时边缘烧红并有火光扫过。
     */
    public static void plate(GuiGraphics g, int x, int y, int w, int h, boolean hover, boolean locked) {
        float t = ZsAnim.tween(ZsAnim.key(21, x, y), hover && !locked ? 1 : 0, 16);
        if (t > 0.01f) g.fill(x - 1, y - 1, x + w + 1, y + h + 1, ZsAnim.withAlpha(EMBER, 0.45f * t));
        int top = locked ? 0xDD1A1412 : ZsAnim.lerpColor(0xEE3A2418, 0xEE5A2A14, t);
        int bot = locked ? 0xDD100C0A : ZsAnim.lerpColor(0xEE180E0A, 0xEE2A1008, t);
        g.fillGradient(x, y, x + w, y + h, top, bot);
        int edge = locked ? 0xFF3A2A22 : ZsAnim.lerpColor(0xFF7A5634, EMBER, t);
        g.renderOutline(x, y, w, h, edge);
        if (!locked) {
            g.fill(x + 1, y + 1, x + w - 1, y + 2, ZsAnim.withAlpha(0xFFE0B080, 0.35f + 0.3f * t));
            // 左侧剑形刻痕
            g.fill(x + 1, y + 2, x + 2, y + h - 2, ZsAnim.withAlpha(EMBER, 0.3f + 0.6f * t));
        }
        if (t > 0.05f) emberSweep(g, x, y, w, h, t);
    }

    /** 火光扫过（橙色斜向高光） */
    private static void emberSweep(GuiGraphics g, int x, int y, int w, int h, float strength) {
        float ph = ZsAnim.phase(1400);
        int band = Math.max(6, w / 6);
        int cx = x - band + (int) ((w + band * 2) * ph);
        g.enableScissor(x, y, x + w, y + h);
        for (int i = 0; i < band; i++) {
            float a = (1 - Math.abs(i - band / 2f) / (band / 2f)) * 0.3f * strength;
            int col = ZsAnim.withAlpha(EMBER_HOT, a);
            for (int yy = 0; yy < h; yy += 2) {
                int off = (h - yy) / 2;
                g.fill(cx + i + off, y + yy, cx + i + off + 1, y + Math.min(h, yy + 2), col);
            }
        }
        g.disableScissor();
    }

    /** 余烬分隔线：暗铜底线 + 游走的火星 */
    public static void separator(GuiGraphics g, int x1, int x2, int y) {
        g.fill(x1, y, x2, y + 1, 0x667A5634);
        int w = x2 - x1;
        int cx = x1 + (int) (w * ZsAnim.phase(3000));
        for (int i = -24; i < 24; i++) {
            int px = cx + i;
            if (px < x1 || px >= x2) continue;
            g.fill(px, y, px + 1, y + 1, ZsAnim.withAlpha(EMBER, 1 - Math.abs(i) / 24f));
        }
    }
}
