package com.zhushen.space.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.zhushen.space.screen.ZsShapes;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/**
 * 假面骑士 Ex-Aid（艾克赛德）风格 HUD 工具：
 * 银色手柄型胸甲板（VR Life Guard）、四色 X 控制器按键、液晶屏 Rider Gauge 斜切阶梯血格、
 * 3×5 像素字体（8-bit 游戏感），以及「HIT!」「GREAT!」式描边漫画字 + 爆炸星形底。
 * <p>
 * 像素格 / 矩形全部直接写入 {@link RenderType#gui()} 缓冲（不逐个 flush），与 {@code GuiGraphics.fill} 同批次、保持绘制顺序。
 */
public final class ExAidStyle {

    private ExAidStyle() {}

    // ===== 配色（Ex-Aid 洋红 + 荧光绿；Rider Gauge 浅蓝；X 控制器 蓝 / 红 / 绿 / 黄） =====
    public static final int PINK = 0xFFFF2E93, PINK_L = 0xFFFF7CC2, PINK_D = 0xFFB3005F;
    public static final int LIME = 0xFFB8FF2E, LIME_D = 0xFF62AD00;
    public static final int CYAN = 0xFF56F2FF, CYAN_D = 0xFF0F5D7A;
    public static final int YELLOW = 0xFFFFE135, ORANGE = 0xFFFF9A1F, RED = 0xFFFF3B4E;
    public static final int BTN_BLUE = 0xFF2F86FF, BTN_RED = 0xFFFF3A3A, BTN_GREEN = 0xFF2ED158, BTN_YELLOW = 0xFFFFC81F;
    public static final int SILVER_L = 0xFFF2F4F8, SILVER = 0xFFC5CBD4, SILVER_D = 0xFF7F8794;
    public static final int INK = 0xFF140C1A, WHITE = 0xFFFFFFFF;
    public static final int LCD_T = 0xFF143572, LCD_B = 0xFF061230, LCD_OFF = 0xFF1C3A6E;
    public static final int GOLD_L = 0xFFFFF3A6, GOLD = 0xFFFFC928, GOLD_D = 0xFFB97800;

    public static int darken(int c, float f) {
        return (c & 0xFF000000) | (ZsShapes.lerp(c | 0xFF000000, 0xFF000000, f) & 0xFFFFFF);
    }

    public static int lighten(int c, float f) {
        return (c & 0xFF000000) | (ZsShapes.lerp(c | 0xFF000000, 0xFFFFFFFF, f) & 0xFFFFFF);
    }

    // ===== 批量矩形 =====

    /** 矩形（不 flush；每次重新取缓冲，避免跨文字绘制后缓冲失效） */
    public static void rect(GuiGraphics g, float x0, float y0, float x1, float y1, int c) {
        if (x1 <= x0 || y1 <= y0 || (c >>> 24) == 0) return;
        Matrix4f m = g.pose().last().pose();
        VertexConsumer v = g.bufferSource().getBuffer(RenderType.gui());
        v.addVertex(m, x0, y0, 0).setColor(c);
        v.addVertex(m, x0, y1, 0).setColor(c);
        v.addVertex(m, x1, y1, 0).setColor(c);
        v.addVertex(m, x1, y0, 0).setColor(c);
    }

    /** 竖直渐变矩形 */
    public static void rectV(GuiGraphics g, float x0, float y0, float x1, float y1, int top, int bottom) {
        if (x1 <= x0 || y1 <= y0) return;
        Matrix4f m = g.pose().last().pose();
        VertexConsumer v = g.bufferSource().getBuffer(RenderType.gui());
        v.addVertex(m, x0, y0, 0).setColor(top);
        v.addVertex(m, x0, y1, 0).setColor(bottom);
        v.addVertex(m, x1, y1, 0).setColor(bottom);
        v.addVertex(m, x1, y0, 0).setColor(top);
    }

    /** 平行四边形（顶边右移 slant）：Rider Gauge 血格 / 徽章 */
    public static void para(GuiGraphics g, float x, float y, float w, float h, float slant, int top, int bottom) {
        ZsShapes.quad(g, x + slant, y, top, x + slant + w, y, top, x + w, y + h, bottom, x, y + h, bottom);
    }

    // ===== 3×5 像素字体 =====

    private static final Map<Character, int[]> GLYPH = new HashMap<>();

    private static void def(char c, String... rows) {
        int[] r = new int[5];
        for (int i = 0; i < 5; i++) {
            int bits = 0;
            for (int k = 0; k < 3; k++) if (rows[i].charAt(k) == '#') bits |= 1 << (2 - k);
            r[i] = bits;
        }
        GLYPH.put(c, r);
    }

    static {
        def('0', "###", "#.#", "#.#", "#.#", "###");
        def('1', ".#.", "##.", ".#.", ".#.", "###");
        def('2', "###", "..#", "###", "#..", "###");
        def('3', "###", "..#", "###", "..#", "###");
        def('4', "#.#", "#.#", "###", "..#", "..#");
        def('5', "###", "#..", "###", "..#", "###");
        def('6', "###", "#..", "###", "#.#", "###");
        def('7', "###", "..#", "..#", ".#.", ".#.");
        def('8', "###", "#.#", "###", "#.#", "###");
        def('9', "###", "#.#", "###", "..#", "###");
        def('A', ".#.", "#.#", "###", "#.#", "#.#");
        def('B', "##.", "#.#", "##.", "#.#", "##.");
        def('C', ".##", "#..", "#..", "#..", ".##");
        def('D', "##.", "#.#", "#.#", "#.#", "##.");
        def('E', "###", "#..", "##.", "#..", "###");
        def('F', "###", "#..", "##.", "#..", "#..");
        def('G', ".##", "#..", "#.#", "#.#", ".##");
        def('H', "#.#", "#.#", "###", "#.#", "#.#");
        def('I', "###", ".#.", ".#.", ".#.", "###");
        def('K', "#.#", "#.#", "##.", "#.#", "#.#");
        def('L', "#..", "#..", "#..", "#..", "###");
        def('M', "#.#", "###", "###", "#.#", "#.#");
        def('N', "##.", "#.#", "#.#", "#.#", "#.#");
        def('O', ".#.", "#.#", "#.#", "#.#", ".#.");
        def('P', "##.", "#.#", "##.", "#..", "#..");
        def('R', "##.", "#.#", "##.", "#.#", "#.#");
        def('S', ".##", "#..", ".#.", "..#", "##.");
        def('T', "###", ".#.", ".#.", ".#.", ".#.");
        def('U', "#.#", "#.#", "#.#", "#.#", "###");
        def('V', "#.#", "#.#", "#.#", "#.#", ".#.");
        def('W', "#.#", "#.#", "###", "###", "#.#");
        def('X', "#.#", "#.#", ".#.", "#.#", "#.#");
        def('Y', "#.#", "#.#", ".#.", ".#.", ".#.");
        def('Z', "###", "..#", ".#.", "#..", "###");
        def('!', ".#.", ".#.", ".#.", "...", ".#.");
        def('?', "##.", "..#", ".#.", "...", ".#.");
        def('.', "...", "...", "...", "...", ".#.");
        def('-', "...", "...", "###", "...", "...");
        def('+', "...", ".#.", "###", ".#.", "...");
        def('/', "..#", "..#", ".#.", "#..", "#..");
        def(':', "...", ".#.", "...", ".#.", "...");
        def(' ', "...", "...", "...", "...", "...");
    }

    public static int pixWidth(String s) {
        return s.isEmpty() ? 0 : s.length() * 4 - 1;
    }

    /** 像素字（每格 1×1 GUI 像素，字高 5） */
    public static void pix(GuiGraphics g, String s, float x, float y, int color) {
        float cx = x;
        for (int i = 0; i < s.length(); i++) {
            int[] rows = GLYPH.get(Character.toUpperCase(s.charAt(i)));
            if (rows != null) {
                for (int r = 0; r < 5; r++) {
                    int bits = rows[r];
                    for (int k = 0; k < 3; k++) {
                        if ((bits & (1 << (2 - k))) != 0) rect(g, cx + k, y + r, cx + k + 1, y + r + 1, color);
                    }
                }
            }
            cx += 4;
        }
    }

    /** 像素字 + 右下 1 像素硬阴影 */
    public static void pixShadow(GuiGraphics g, String s, float x, float y, int color, int shadow) {
        pix(g, s, x + 1, y + 1, shadow);
        pix(g, s, x, y, color);
    }

    /** 像素字 + 四周 1 像素描边（缩放 scale） */
    public static void pixOutlined(GuiGraphics g, String s, float x, float y, float scale, int color, int outline) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                if (dx != 0 || dy != 0) pix(g, s, dx, dy, outline);
        pix(g, s, 0, 0, color);
        g.pose().popPose();
    }

    // ===== 描边文字 / 漫画字 =====

    /** 原版字体 + 8 方向描边（漫画字） */
    public static void outlined(GuiGraphics g, Font font, String s, float x, float y, int fill, int outline) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                if (dx != 0 || dy != 0) g.drawString(font, s, dx, dy, outline, false);
        g.drawString(font, s, 0, 0, fill, false);
        g.pose().popPose();
    }

    /** 以 (cx, cy) 为中心、缩放 scale 的描边文字 */
    public static void outlinedCentered(GuiGraphics g, Font font, String s, float cx, float cy, float scale, int fill, int outline) {
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        g.pose().scale(scale, scale, 1);
        outlined(g, font, s, -font.width(s) / 2f + 0.5f, -3.5f, fill, outline);
        g.pose().popPose();
    }

    /**
     * 「HIT!」式弹出字：椭圆爆炸星形底（黑描边 + 底色）+ 斜放描边文字。
     *
     * @param spin 爆炸星形的旋转（弧度）
     */
    public static void comic(GuiGraphics g, Font font, String s, float cx, float cy, float scale, float rotDeg,
                             int fill, int outline, int burst, float alpha, double spin) {
        if (alpha <= 0.02f) return;
        int w = font.width(s);
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        g.pose().mulPose(Axis.ZP.rotationDegrees(rotDeg));
        g.pose().scale(scale, scale, 1);
        g.pose().pushPose();
        g.pose().scale(1f, 0.5f, 1f);
        float ro = w / 2f + 9, ri = w / 2f + 3;
        ZsShapes.star(g, 0, 0, ro + 1.6f, ri + 1.6f, 14, spin, ZsShapes.fade(INK, alpha), ZsShapes.fade(INK, alpha));
        ZsShapes.star(g, 0, 0, ro, ri, 14, spin, ZsShapes.fade(lighten(burst, 0.35f), alpha), ZsShapes.fade(burst, alpha));
        g.pose().popPose();
        outlined(g, font, s, -w / 2f + 0.5f, -3.5f, ZsShapes.fade(fill, alpha), ZsShapes.fade(outline, alpha));
        g.pose().popPose();
    }

    // ===== 部件 =====

    /** X 控制器按键：黑边 + 径向高光 + 镜面点；pressed = 按下（缩小变暗）；broken = 熄灭并打叉 */
    public static void button(GuiGraphics g, float cx, float cy, float r, int color, float lit, boolean pressed, boolean broken) {
        ZsShapes.disc(g, cx + 0.4f, cy + 0.9f, r + 1.3f, 0x70000000, 0x00000000);
        ZsShapes.disc(g, cx, cy, r + 1f, INK);
        float rr = pressed ? r * 0.84f : r;
        int c = broken ? 0xFF2A2730 : ZsShapes.lerp(darken(color, 0.72f), color, Math.max(0f, Math.min(1f, lit)));
        if (pressed) c = darken(c, 0.25f);
        ZsShapes.disc(g, cx, cy, rr, lighten(c, 0.38f), c);
        if (broken) {
            float k = rr * 0.6f;
            ZsShapes.line(g, cx - k, cy - k, cx + k, cy + k, 1.3f, RED, RED);
            ZsShapes.line(g, cx - k, cy + k, cx + k, cy - k, 1.3f, RED, RED);
        } else if (!pressed) {
            ZsShapes.disc(g, cx - rr * 0.34f, cy - rr * 0.38f, rr * 0.34f, 0xC8FFFFFF, 0x00FFFFFF);
        }
    }

    /** 液晶屏：黑色边框 + 深蓝渐变 + 点阵网格 + 顶部反光 */
    public static void lcd(GuiGraphics g, float x, float y, float w, float h, long now) {
        ZsShapes.roundRect(g, x - 1.5f, y - 1.5f, w + 3, h + 3, 4, 0xFF2B303A, 0xFF12151C);
        ZsShapes.roundRect(g, x, y, w, h, 3, LCD_T, LCD_B);
        for (float gx = x + 2; gx < x + w - 1; gx += 3) rect(g, gx, y + 1, gx + 0.5f, y + h - 1, 0x0F56F2FF);
        for (float gy = y + 2; gy < y + h - 1; gy += 3) rect(g, x + 1, gy, x + w - 1, gy + 0.5f, 0x0F56F2FF);
        rectV(g, x + 2, y + 1, x + w - 2, y + h * 0.42f, 0x22FFFFFF, 0x00FFFFFF);
        float sweep = (now / 22f) % (h + 14) - 7;
        if (sweep > 0 && sweep < h - 1) rect(g, x + 1, y + sweep, x + w - 1, y + sweep + 1, 0x1856F2FF);
    }

    /** 银色金属板（圆角，竖直渐变 + 顶部高光 + 黑描边 + 外圈颜色描边 rim） */
    public static void plate(GuiGraphics g, float x, float y, float w, float h, float r, int rim) {
        if ((rim >>> 24) != 0) ZsShapes.roundRect(g, x - 2.5f, y - 2.5f, w + 5, h + 5, r + 2.5f, rim, darken(rim, 0.25f));
        ZsShapes.roundRect(g, x - 1, y - 1, w + 2, h + 2, r + 1, INK, INK);
        ZsShapes.roundRect(g, x, y, w, h, r, SILVER_L, SILVER_D);
        rectV(g, x + r, y + 0.6f, x + w - r, y + 1.6f, 0xCCFFFFFF, 0x00FFFFFF);
    }

    /** 根据比例的血格颜色：浅蓝 → 黄 → 红 */
    public static int gaugeColor(float ratio) {
        return ratio > 0.6f ? CYAN : ratio > 0.3f ? YELLOW : RED;
    }
}
