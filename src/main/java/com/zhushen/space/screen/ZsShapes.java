package com.zhushen.space.screen;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

/**
 * 矢量图形（GUI）：以浮点坐标直接提交三角形，圆 / 圆环 / 圆角矩形都按屏幕像素精度绘制，
 * 并在边缘加一圈约 1 个屏幕像素的透明渐变（软抗锯齿），在高 GUI 缩放下也不会出现锯齿台阶。
 * 所有图形走 {@link RenderType#gui()}，与 {@code GuiGraphics.fill} 同批次、保持绘制顺序。
 */
public final class ZsShapes {

    private ZsShapes() {
    }

    // ===== 颜色 =====

    public static int lerp(int c1, int c2, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a1 = c1 >>> 24, r1 = c1 >> 16 & 0xFF, g1 = c1 >> 8 & 0xFF, b1 = c1 & 0xFF;
        int a2 = c2 >>> 24, r2 = c2 >> 16 & 0xFF, g2 = c2 >> 8 & 0xFF, b2 = c2 & 0xFF;
        return (Math.round(a1 + (a2 - a1) * t) << 24) | (Math.round(r1 + (r2 - r1) * t) << 16)
                | (Math.round(g1 + (g2 - g1) * t) << 8) | Math.round(b1 + (b2 - b1) * t);
    }

    /** 透明度乘以 a */
    public static int fade(int argb, float a) {
        int al = Math.round((argb >>> 24) * Math.max(0f, Math.min(1f, a)));
        return (al << 24) | (argb & 0xFFFFFF);
    }

    public static int clear(int argb) {
        return argb & 0x00FFFFFF;
    }

    /** 软边宽度：约 1.1 个屏幕像素 */
    public static float aa() {
        double s = Minecraft.getInstance().getWindow().getGuiScale();
        return (float) (1.1 / Math.max(1.0, s));
    }

    private static int segs(float r) {
        return Math.max(20, Math.min(128, (int) (r * 2.2f)));
    }

    // ===== 基本三角形 =====

    private static VertexConsumer vc(GuiGraphics g) {
        return g.bufferSource().getBuffer(RenderType.gui());
    }

    /** 三角形（自动调整环绕方向以通过背面剔除） */
    public static void tri(GuiGraphics g, float x1, float y1, int c1, float x2, float y2, int c2, float x3, float y3, int c3) {
        float cross = (x2 - x1) * (y3 - y2) - (y2 - y1) * (x3 - x2);
        if (cross > 0) {
            float tx = x2, ty = y2;
            int tc = c2;
            x2 = x3; y2 = y3; c2 = c3;
            x3 = tx; y3 = ty; c3 = tc;
        }
        Matrix4f m = g.pose().last().pose();
        VertexConsumer v = vc(g);
        v.addVertex(m, x1, y1, 0).setColor(c1);
        v.addVertex(m, x2, y2, 0).setColor(c2);
        v.addVertex(m, x3, y3, 0).setColor(c3);
        v.addVertex(m, x3, y3, 0).setColor(c3);
    }

    /** 四边形（按顺序给出四个角） */
    public static void quad(GuiGraphics g, float x1, float y1, int c1, float x2, float y2, int c2,
                            float x3, float y3, int c3, float x4, float y4, int c4) {
        tri(g, x1, y1, c1, x2, y2, c2, x3, y3, c3);
        tri(g, x1, y1, c1, x3, y3, c3, x4, y4, c4);
    }

    // ===== 圆 / 圆环 =====

    /** 径向渐变圆（中心色 → 边缘色）+ 软边 */
    public static void disc(GuiGraphics g, float cx, float cy, float r, int cIn, int cOut) {
        if (r <= 0) return;
        int n = segs(r);
        float aa = aa();
        double step = Math.PI * 2 / n;
        for (int i = 0; i < n; i++) {
            double a0 = i * step, a1 = (i + 1) * step;
            float x0 = (float) Math.cos(a0), y0 = (float) Math.sin(a0), x1 = (float) Math.cos(a1), y1 = (float) Math.sin(a1);
            tri(g, cx, cy, cIn, cx + x0 * r, cy + y0 * r, cOut, cx + x1 * r, cy + y1 * r, cOut);
            quad(g, cx + x0 * r, cy + y0 * r, cOut, cx + x1 * r, cy + y1 * r, cOut,
                    cx + x1 * (r + aa), cy + y1 * (r + aa), clear(cOut), cx + x0 * (r + aa), cy + y0 * (r + aa), clear(cOut));
        }
    }

    public static void disc(GuiGraphics g, float cx, float cy, float r, int c) {
        disc(g, cx, cy, r, c, c);
    }

    /** 圆环：内缘色 → 外缘色（两侧软边） */
    public static void ring(GuiGraphics g, float cx, float cy, float r0, float r1, int cIn, int cOut) {
        arc(g, cx, cy, r0, r1, 0, Math.PI * 2, cIn, cOut, cIn, cOut, true);
    }

    /**
     * 受光圆环：颜色随角度在 dark / light 之间变化，模拟来自 lightAngle 方向的平行光（金属倒角）。
     * concave = 凹面（膛口内壁：背光侧反而亮）。
     */
    public static void litRing(GuiGraphics g, float cx, float cy, float r0, float r1, int dark, int light,
                               double lightAngle, boolean concave) {
        int n = segs(r1);
        float aa = aa();
        double step = Math.PI * 2 / n;
        for (int i = 0; i < n; i++) {
            double a0 = i * step, a1 = (i + 1) * step;
            int c0 = shade(a0, dark, light, lightAngle, concave), c1 = shade(a1, dark, light, lightAngle, concave);
            float cs0 = (float) Math.cos(a0), sn0 = (float) Math.sin(a0), cs1 = (float) Math.cos(a1), sn1 = (float) Math.sin(a1);
            quad(g, cx + cs0 * r0, cy + sn0 * r0, c0, cx + cs1 * r0, cy + sn1 * r0, c1,
                    cx + cs1 * r1, cy + sn1 * r1, c1, cx + cs0 * r1, cy + sn0 * r1, c0);
            quad(g, cx + cs0 * r1, cy + sn0 * r1, c0, cx + cs1 * r1, cy + sn1 * r1, c1,
                    cx + cs1 * (r1 + aa), cy + sn1 * (r1 + aa), clear(c1), cx + cs0 * (r1 + aa), cy + sn0 * (r1 + aa), clear(c0));
            if (r0 > aa) quad(g, cx + cs0 * (r0 - aa), cy + sn0 * (r0 - aa), clear(c0), cx + cs1 * (r0 - aa), cy + sn1 * (r0 - aa), clear(c1),
                    cx + cs1 * r0, cy + sn1 * r0, c1, cx + cs0 * r0, cy + sn0 * r0, c0);
        }
    }

    private static int shade(double a, int dark, int light, double lightAngle, boolean concave) {
        float t = (float) ((Math.cos(a - lightAngle) + 1) / 2);
        if (concave) t = 1 - t;
        return lerp(dark, light, t * t * (3 - 2 * t));
    }

    /**
     * 弧段（角度从 a0 到 a1，屏幕坐标 0 = 右，顺时针为正）。
     * 颜色：起点内 / 外、终点内 / 外；soft = 两侧软边。
     */
    public static void arc(GuiGraphics g, float cx, float cy, float r0, float r1, double a0, double a1,
                           int startIn, int startOut, int endIn, int endOut, boolean soft) {
        if (a1 <= a0 || r1 <= r0) return;
        int n = Math.max(2, (int) Math.ceil(segs(r1) * (a1 - a0) / (Math.PI * 2)));
        float aa = aa();
        for (int i = 0; i < n; i++) {
            float t0 = i / (float) n, t1 = (i + 1) / (float) n;
            double b0 = a0 + (a1 - a0) * t0, b1 = a0 + (a1 - a0) * t1;
            int in0 = lerp(startIn, endIn, t0), in1 = lerp(startIn, endIn, t1);
            int out0 = lerp(startOut, endOut, t0), out1 = lerp(startOut, endOut, t1);
            float cs0 = (float) Math.cos(b0), sn0 = (float) Math.sin(b0), cs1 = (float) Math.cos(b1), sn1 = (float) Math.sin(b1);
            quad(g, cx + cs0 * r0, cy + sn0 * r0, in0, cx + cs1 * r0, cy + sn1 * r0, in1,
                    cx + cs1 * r1, cy + sn1 * r1, out1, cx + cs0 * r1, cy + sn0 * r1, out0);
            if (soft) {
                quad(g, cx + cs0 * r1, cy + sn0 * r1, out0, cx + cs1 * r1, cy + sn1 * r1, out1,
                        cx + cs1 * (r1 + aa), cy + sn1 * (r1 + aa), clear(out1), cx + cs0 * (r1 + aa), cy + sn0 * (r1 + aa), clear(out0));
                if (r0 > aa) quad(g, cx + cs0 * (r0 - aa), cy + sn0 * (r0 - aa), clear(in0), cx + cs1 * (r0 - aa), cy + sn1 * (r0 - aa), clear(in1),
                        cx + cs1 * r0, cy + sn1 * r0, in1, cx + cs0 * r0, cy + sn0 * r0, in0);
            }
        }
    }

    /** 光晕：从半径 r 处的 color 向外 w 渐隐到透明 */
    public static void glow(GuiGraphics g, float cx, float cy, float r, float w, int color) {
        arc(g, cx, cy, r, r + w, 0, Math.PI * 2, color, clear(color), color, clear(color), false);
    }

    /** 星形（退壳星）：points 个尖角，径向渐变 */
    public static void star(GuiGraphics g, float cx, float cy, float rOut, float rIn, int points, double rot, int cIn, int cOut) {
        int n = points * 2;
        float aa = aa();
        for (int i = 0; i < n; i++) {
            double a0 = rot + i * Math.PI / points, a1 = rot + (i + 1) * Math.PI / points;
            float ra = i % 2 == 0 ? rOut : rIn, rb = i % 2 == 0 ? rIn : rOut;
            float x0 = cx + (float) Math.cos(a0) * ra, y0 = cy + (float) Math.sin(a0) * ra;
            float x1 = cx + (float) Math.cos(a1) * rb, y1 = cy + (float) Math.sin(a1) * rb;
            tri(g, cx, cy, cIn, x0, y0, cOut, x1, y1, cOut);
            // 边缘软化：沿边的外法线
            float ex = x1 - x0, ey = y1 - y0, len = (float) Math.sqrt(ex * ex + ey * ey);
            if (len < 1e-3f) continue;
            float nx = ey / len, ny = -ex / len;
            float mx = (x0 + x1) / 2 - cx, my = (y0 + y1) / 2 - cy;
            if (nx * mx + ny * my < 0) { nx = -nx; ny = -ny; }
            quad(g, x0, y0, cOut, x1, y1, cOut, x1 + nx * aa, y1 + ny * aa, clear(cOut), x0 + nx * aa, y0 + ny * aa, clear(cOut));
        }
    }

    // ===== 圆角矩形 =====

    /** 圆角矩形（竖向渐变 top → bottom，软边）；r ≥ h/2 时为胶囊。各部分互不重叠，半透明颜色也不会叠色 */
    public static void roundRect(GuiGraphics g, float x, float y, float w, float h, float r, int top, int bottom) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        float aa = aa();
        float yt = y + r, yb = y + h - r;
        int cT = top, cB = bottom, cYt = lerp(top, bottom, r / h), cYb = lerp(top, bottom, 1 - r / h);
        // 中间竖条（全高）
        quad(g, x + r, y, cT, x + r, y + h, cB, x + w - r, y + h, cB, x + w - r, y, cT);
        // 左右侧条
        if (yb > yt) {
            quad(g, x, yt, cYt, x, yb, cYb, x + r, yb, cYb, x + r, yt, cYt);
            quad(g, x + w - r, yt, cYt, x + w - r, yb, cYb, x + w, yb, cYb, x + w, yt, cYt);
            // 左右软边
            quad(g, x - aa, yt, clear(cYt), x - aa, yb, clear(cYb), x, yb, cYb, x, yt, cYt);
            quad(g, x + w, yt, cYt, x + w, yb, cYb, x + w + aa, yb, clear(cYb), x + w + aa, yt, clear(cYt));
        }
        // 上下软边
        quad(g, x + r, y - aa, clear(cT), x + r, y, cT, x + w - r, y, cT, x + w - r, y - aa, clear(cT));
        quad(g, x + r, y + h, cB, x + r, y + h + aa, clear(cB), x + w - r, y + h + aa, clear(cB), x + w - r, y + h, cB);
        // 四角扇形
        if (r <= 0) return;
        float[][] corners = {{x + w - r, yb}, {x + r, yb}, {x + r, yt}, {x + w - r, yt}};
        int per = Math.max(3, (int) (r * 2.5f));
        for (int c = 0; c < 4; c++) {
            float ccx = corners[c][0], ccy = corners[c][1];
            int cc = lerp(top, bottom, (ccy - y) / h);
            for (int i = 0; i < per; i++) {
                double a0 = c * Math.PI / 2 + i * (Math.PI / 2) / per, a1 = c * Math.PI / 2 + (i + 1) * (Math.PI / 2) / per;
                float cs0 = (float) Math.cos(a0), sn0 = (float) Math.sin(a0), cs1 = (float) Math.cos(a1), sn1 = (float) Math.sin(a1);
                float x0 = ccx + cs0 * r, y0 = ccy + sn0 * r, x1 = ccx + cs1 * r, y1 = ccy + sn1 * r;
                int c0 = lerp(top, bottom, (y0 - y) / h), c1 = lerp(top, bottom, (y1 - y) / h);
                tri(g, ccx, ccy, cc, x0, y0, c0, x1, y1, c1);
                quad(g, x0, y0, c0, x1, y1, c1, x1 + cs1 * aa, y1 + sn1 * aa, clear(c1), x0 + cs0 * aa, y0 + sn0 * aa, clear(c0));
            }
        }
    }

    /** 圆角矩形描边（线宽 t） */
    public static void roundRectOutline(GuiGraphics g, float x, float y, float w, float h, float r, float t, int color) {
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        float[][] corners = {{x + w - r, y + h - r}, {x + r, y + h - r}, {x + r, y + r}, {x + w - r, y + r}};
        for (int c = 0; c < 4; c++)
            arc(g, corners[c][0], corners[c][1], Math.max(0, r - t), r, c * Math.PI / 2, (c + 1) * Math.PI / 2, color, color, color, color, true);
        quad(g, x + r, y, color, x + r, y + t, color, x + w - r, y + t, color, x + w - r, y, color);
        quad(g, x + r, y + h - t, color, x + r, y + h, color, x + w - r, y + h, color, x + w - r, y + h - t, color);
        quad(g, x, y + r, color, x, y + h - r, color, x + t, y + h - r, color, x + t, y + r, color);
        quad(g, x + w - t, y + r, color, x + w - t, y + h - r, color, x + w, y + h - r, color, x + w, y + r, color);
    }

    /** 细线（线宽 t） */
    public static void line(GuiGraphics g, float x0, float y0, float x1, float y1, float t, int c0, int c1) {
        float dx = x1 - x0, dy = y1 - y0, len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-4f) return;
        float nx = -dy / len * t / 2, ny = dx / len * t / 2;
        quad(g, x0 + nx, y0 + ny, c0, x1 + nx, y1 + ny, c1, x1 - nx, y1 - ny, c1, x0 - nx, y0 - ny, c0);
    }

    // ===== 图标形状 =====

    /** 水滴 / 火苗：尖端朝上，(cx, cy) 为中心，高度约 2r；shine = 左上高光 */
    public static void drop(GuiGraphics g, float cx, float cy, float r, int c, int c2, boolean shine) {
        float a = 0.62f * r, oc = cy + 0.36f * r;
        tri(g, cx, cy - r, c, cx - a * 0.87f, oc - a * 0.5f, lerp(c, c2, 0.4f), cx + a * 0.87f, oc - a * 0.5f, lerp(c, c2, 0.4f));
        disc(g, cx, oc, a, lerp(c, c2, 0.4f), c2);
        if (shine) disc(g, cx - a * 0.35f, oc - a * 0.2f, a * 0.28f, fade(0xB0FFFFFF, (c >>> 24) / 255f), 0x00FFFFFF);
    }

    /** 新月：圆 1 的左缘到（右上偏移的）圆 2 左缘之间的区域，颜色自上而下 c → c2 */
    public static void crescent(GuiGraphics g, float cx, float cy, float r, int c, int c2) {
        float d = r * 0.55f, r2 = r * 0.86f, yo = -r * 0.18f;
        int n = Math.max(12, (int) (r * 3));
        float px0 = 0, px1 = 0, py = 0;
        int pc = c;
        for (int k = 0; k <= n; k++) {
            float y = -r + 2 * r * k / n;
            float h1 = (float) Math.sqrt(Math.max(0, r * r - y * y));
            float left = cx - h1, right = cx + h1;
            float dy = y - yo;
            if (Math.abs(dy) < r2) right = Math.min(right, cx + d - (float) Math.sqrt(r2 * r2 - dy * dy));
            right = Math.max(left, right);
            int cc = lerp(c, c2, (float) k / n);
            if (k > 0 && (px1 > px0 || right > left))
                quad(g, px0, cy + py, pc, left, cy + y, cc, right, cy + y, cc, px1, cy + py, pc);
            px0 = left; px1 = right; py = y; pc = cc;
        }
    }
}
