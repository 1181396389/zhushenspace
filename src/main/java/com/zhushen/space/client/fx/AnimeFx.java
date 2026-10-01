package com.zhushen.space.client.fx;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * 动漫风特效几何图元。全部在局部 XY 平面（或沿 -Z 轴）上用纯色三角 / 四边形拼出，
 * 色阶靠多层不同颜色叠画（外深内浅、白芯），不用贴图。
 * 渲染类型为 QUADS：三角形以“第 4 顶点重复第 3 顶点”的退化四边形提交。
 */
public final class AnimeFx {

    private AnimeFx() {}

    public static final float TAU = (float) (Math.PI * 2);

    // ───────────────────────── 基础 ─────────────────────────

    public static int argb(int rgb, float a) {
        int al = Mth.clamp((int) (a * 255f + 0.5f), 0, 255);
        return (al << 24) | (rgb & 0xFFFFFF);
    }

    /** 颜色线性插值（rgb） */
    public static int mix(int a, int b, float t) {
        t = Mth.clamp(t, 0f, 1f);
        int r = (int) Mth.lerp(t, (a >> 16) & 255, (b >> 16) & 255);
        int g = (int) Mth.lerp(t, (a >> 8) & 255, (b >> 8) & 255);
        int bl = (int) Mth.lerp(t, a & 255, b & 255);
        return (r << 16) | (g << 8) | bl;
    }

    public static float hash(int n) {
        n = (n << 13) ^ n;
        return ((n * (n * n * 15731 + 789221) + 1376312589) & 0x7fffffff) / (float) 0x7fffffff;
    }

    public static float easeOut(float t) {
        t = Mth.clamp(t, 0f, 1f);
        float u = 1f - t;
        return 1f - u * u * u;
    }

    public static float easeOutBack(float t) {
        t = Mth.clamp(t, 0f, 1f);
        float c1 = 1.70158f, c3 = c1 + 1f;
        float u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    public static float clamp01(float v) { return Mth.clamp(v, 0f, 1f); }

    private static void v(VertexConsumer vc, Matrix4f m, float x, float y, float z, int c) {
        vc.addVertex(m, x, y, z).setColor(c);
    }

    public static void tri(VertexConsumer vc, Matrix4f m,
                           float ax, float ay, float az, int ca,
                           float bx, float by, float bz, int cb,
                           float cx, float cy, float cz, int cc) {
        v(vc, m, ax, ay, az, ca);
        v(vc, m, bx, by, bz, cb);
        v(vc, m, cx, cy, cz, cc);
        v(vc, m, cx, cy, cz, cc);
    }

    public static void quad(VertexConsumer vc, Matrix4f m,
                            float ax, float ay, float az, int ca,
                            float bx, float by, float bz, int cb,
                            float cx, float cy, float cz, int cc,
                            float dx, float dy, float dz, int cd) {
        v(vc, m, ax, ay, az, ca);
        v(vc, m, bx, by, bz, cb);
        v(vc, m, cx, cy, cz, cc);
        v(vc, m, dx, dy, dz, cd);
    }

    // ───────────────────────── 平面图元（XY 平面） ─────────────────────────

    /**
     * 火焰 / 能量团：中心扇形 + 噪声轮廓（低频起伏 + 尖刺），并沿 (tx,ty) 反方向拖出尾焰。
     * tail 为尾长倍数（0 = 正圆形团）。
     */
    public static void blob(VertexConsumer vc, Matrix4f m, float cx, float cy, float r,
                            float wobble, float spike, float phase,
                            float tx, float ty, float tail, int cIn, int cOut, int seg) {
        float tl = Mth.sqrt(tx * tx + ty * ty);
        if (tl > 1e-4f) { tx /= tl; ty /= tl; } else { tail = 0f; }
        float px = 0, py = 0;
        for (int i = 0; i <= seg; i++) {
            float a = TAU * i / seg;
            float ca = Mth.cos(a), sa = Mth.sin(a);
            float rr = r * (1f + wobble * (0.55f * Mth.sin(3f * a + phase * 1.3f) + 0.45f * Mth.sin(5f * a - phase * 1.7f))
                    + spike * pow4(Math.max(0f, Mth.sin(7f * a + phase * 2.1f))));
            if (tail > 0f) {
                float d = ca * tx + sa * ty;
                if (d > 0f) rr *= 1f + tail * (float) Math.pow(d, 2.6);
            }
            float x = cx + ca * rr, y = cy + sa * rr;
            if (i > 0) tri(vc, m, cx, cy, 0, cIn, px, py, 0, cOut, x, y, 0, cOut);
            px = x;
            py = y;
        }
    }

    private static float pow4(float v) { v *= v; return v * v; }

    /** 柔光圆盘：中心 cIn → 边缘 cOut */
    public static void disc(VertexConsumer vc, Matrix4f m, float cx, float cy, float r, int cIn, int cOut, int seg) {
        blob(vc, m, cx, cy, r, 0, 0, 0, 0, 0, 0, cIn, cOut, seg);
    }

    /** 圆环（可只画一段弧 a0..a1） */
    public static void ring(VertexConsumer vc, Matrix4f m, float r0, float r1, int cIn, int cOut,
                            float a0, float a1, int seg) {
        float span = a1 - a0;
        int n = Math.max(2, (int) Math.ceil(seg * Math.abs(span) / TAU));
        for (int i = 0; i < n; i++) {
            float t0 = a0 + span * i / n, t1 = a0 + span * (i + 1) / n;
            float c0 = Mth.cos(t0), s0 = Mth.sin(t0), c1 = Mth.cos(t1), s1 = Mth.sin(t1);
            quad(vc, m, c0 * r0, s0 * r0, 0, cIn, c0 * r1, s0 * r1, 0, cOut,
                    c1 * r1, s1 * r1, 0, cOut, c1 * r0, s1 * r0, 0, cIn);
        }
    }

    public static void ring(VertexConsumer vc, Matrix4f m, float r0, float r1, int cIn, int cOut, int seg) {
        ring(vc, m, r0, r1, cIn, cOut, 0, TAU, seg);
    }

    /** 冲击放射：n 根随机长度的尖刺 + 中心圆 */
    public static void starburst(VertexConsumer vc, Matrix4f m, int n, float rIn, float rOut, float rot, int seed,
                                 int cCenter, int cTip) {
        float w = (float) Math.PI / n * 0.62f;
        for (int k = 0; k < n; k++) {
            float a = rot + TAU * k / n + (hash(seed + k * 31) - 0.5f) * 0.35f;
            float len = rOut * (0.5f + 0.5f * hash(seed * 7 + k * 13));
            tri(vc, m,
                    Mth.cos(a - w) * rIn, Mth.sin(a - w) * rIn, 0, cCenter,
                    Mth.cos(a) * len, Mth.sin(a) * len, 0, cTip,
                    Mth.cos(a + w) * rIn, Mth.sin(a + w) * rIn, 0, cCenter);
        }
        disc(vc, m, 0, 0, rIn * 1.05f, cCenter, cCenter, 20);
    }

    /** 菱形针：p0 → p1，中段最宽 */
    public static void needle(VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1, float w,
                              int cMid, int cEnd) {
        float dx = x1 - x0, dy = y1 - y0;
        float l = Mth.sqrt(dx * dx + dy * dy);
        if (l < 1e-5f) return;
        float nx = -dy / l * w, ny = dx / l * w;
        float mx = (x0 + x1) * 0.5f, my = (y0 + y1) * 0.5f;
        quad(vc, m, x0, y0, 0, cEnd, mx + nx, my + ny, 0, cMid, x1, y1, 0, cEnd, mx - nx, my - ny, 0, cMid);
    }

    /** 四芒闪光（十字长芒 + 斜向短芒） */
    public static void sparkle(VertexConsumer vc, Matrix4f m, float x, float y, float size, float rot, int cMid, int cEnd) {
        for (int k = 0; k < 4; k++) {
            float a = rot + k * (float) Math.PI / 2f;
            float l = (k % 2 == 0) ? size : size * 0.8f;
            needle(vc, m, x, y, x + Mth.cos(a) * l, y + Mth.sin(a) * l, size * 0.11f, cMid, cEnd);
            float b = a + (float) Math.PI / 4f;
            needle(vc, m, x, y, x + Mth.cos(b) * l * 0.42f, y + Mth.sin(b) * l * 0.42f, size * 0.07f, cMid, cEnd);
        }
    }

    /** 平面上的一根矩形条（中心、方向角、半长、半宽） */
    public static void bar(VertexConsumer vc, Matrix4f m, float cx, float cy, float ang, float hl, float hw, int c) {
        float ux = Mth.cos(ang), uy = Mth.sin(ang);
        float nx = -uy, ny = ux;
        quad(vc, m,
                cx - ux * hl - nx * hw, cy - uy * hl - ny * hw, 0, c,
                cx + ux * hl - nx * hw, cy + uy * hl - ny * hw, 0, c,
                cx + ux * hl + nx * hw, cy + uy * hl + ny * hw, 0, c,
                cx - ux * hl + nx * hw, cy - uy * hl + ny * hw, 0, c);
    }

    /** 半圆盘（a0 起逆时针 π） */
    public static void halfDisc(VertexConsumer vc, Matrix4f m, float cx, float cy, float r, float a0, int c, int seg) {
        for (int i = 0; i < seg; i++) {
            float t0 = a0 + (float) Math.PI * i / seg, t1 = a0 + (float) Math.PI * (i + 1) / seg;
            tri(vc, m, cx, cy, 0, c, cx + Mth.cos(t0) * r, cy + Mth.sin(t0) * r, 0, c,
                    cx + Mth.cos(t1) * r, cy + Mth.sin(t1) * r, 0, c);
        }
    }

    // ───────────────────────── 立体图元 ─────────────────────────

    /**
     * 新月刀光：横跨 x∈[-halfW, halfW]，向 +Y 拱起 sag、向 +Z（前方）弯出 depth；
     * 刀身厚度 thick 向两端收尖。f0..f1 为本层占刀身厚度的比例（0 = 上沿刃口，1 = 下沿）。
     * 只画参数 t ∈ [t0, t1] 的部分（用于“划出”动画）。
     */
    public static void crescent(VertexConsumer vc, Matrix4f m, float halfW, float sag, float thick, float depth,
                                float f0, float f1, int cTop, int cBot, float t0, float t1, int seg) {
        t0 = Mth.clamp(t0, -1f, 1f);
        t1 = Mth.clamp(t1, -1f, 1f);
        if (t1 <= t0) return;
        int n = Math.max(2, (int) (seg * (t1 - t0) / 2f));
        float pt = 0, pxTop = 0, pyTop = 0, pyBot = 0, pz = 0;
        int pcTop = 0, pcBot = 0;
        for (int i = 0; i <= n; i++) {
            float t = t0 + (t1 - t0) * i / n;
            float base = 1f - t * t;
            float x = halfW * t;
            float yTop = sag * base;
            float th = thick * (float) Math.pow(Math.max(base, 0f), 0.7);
            float z = depth * base;
            float e = clamp01((1f - Math.abs(t)) * 5f);
            int ct = fadeA(cTop, e), cb = fadeA(cBot, e);
            float yt = yTop - th * f0, yb = yTop - th * f1;
            if (i > 0) quad(vc, m, pxTop, pyTop, pz, pcTop, x, yt, z, ct, x, yb, z, cb, pxTop, pyBot, pz, pcBot);
            pt = t;
            pxTop = x;
            pyTop = yt;
            pyBot = yb;
            pz = z;
            pcTop = ct;
            pcBot = cb;
        }
    }

    public static int fadeA(int c, float k) {
        int a = (c >>> 24) & 255;
        return ((int) (a * clamp01(k)) << 24) | (c & 0xFFFFFF);
    }

    /**
     * 沿 -Z 拖出的流光 / 光束：从 (x,y,z0) 到 (x,y,z0-len)，宽度 w，面朝相机（sx,sy 为垂直于轴的屏幕侧向单位向量）。
     * 头部 cHead，尾部 cTail。
     */
    public static void streak(VertexConsumer vc, Matrix4f m, float x, float y, float z0, float len, float w,
                              float sx, float sy, int cHead, int cTail) {
        float z1 = z0 - len;
        float zm = z0 - len * 0.12f;
        float ox = sx * w, oy = sy * w;
        // 头部尖 → 最宽 → 尾部尖
        quad(vc, m, x, y, z0, cHead, x + ox, y + oy, zm, cHead, x, y, z1, cTail, x - ox, y - oy, zm, cHead);
    }

    /** 计算轴向（Z 轴）面片的屏幕侧向：垂直于 Z 轴和局部相机方向 */
    public static float[] axialSide(float cx, float cy, float cz) {
        float sx = -cy, sy = cx;
        float l = Mth.sqrt(sx * sx + sy * sy);
        if (l < 1e-4f) return new float[]{1f, 0f};
        return new float[]{sx / l, sy / l};
    }
}
