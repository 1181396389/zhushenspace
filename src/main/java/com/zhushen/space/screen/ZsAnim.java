package com.zhushen.space.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * 主神空间动态 GUI 基础设施：
 * - 帧动画贴图（竖向精灵图逐帧播放，等效 GIF）
 * - 缓动函数
 * - 按键值平滑过渡的补间（悬停发光、选项卡下划线滑动、数值滚动等）
 */
public final class ZsAnim {

    private ZsAnim() {
    }

    // ===== 时间 =====

    public static long nowMs() {
        return Util.getMillis();
    }

    /** 以 periodMs 为周期的 0..1 相位 */
    public static float phase(long periodMs) {
        return (nowMs() % periodMs) / (float) periodMs;
    }

    /** 0..1..0 的正弦脉冲 */
    public static float pulse(long periodMs) {
        return 0.5f + 0.5f * (float) Math.sin(phase(periodMs) * Math.PI * 2);
    }

    // ===== 缓动 =====

    public static float clamp01(float t) {
        return t < 0 ? 0 : t > 1 ? 1 : t;
    }

    public static float easeOutCubic(float t) {
        t = clamp01(t);
        float u = 1 - t;
        return 1 - u * u * u;
    }

    public static float easeInOutSine(float t) {
        return (float) (-(Math.cos(Math.PI * clamp01(t)) - 1) / 2);
    }

    /** 带轻微回弹的弹出（面板打开） */
    public static float easeOutBack(float t) {
        t = clamp01(t);
        float c1 = 1.70158f, c3 = c1 + 1;
        return 1 + c3 * (float) Math.pow(t - 1, 3) + c1 * (float) Math.pow(t - 1, 2);
    }

    public static int lerpColor(int a, int b, float t) {
        t = clamp01(t);
        int aa = a >>> 24, ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
        int ba = b >>> 24, br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return (int) (aa + (ba - aa) * t) << 24 | (int) (ar + (br - ar) * t) << 16
                | (int) (ag + (bg - ag) * t) << 8 | (int) (ab + (bb - ab) * t);
    }

    public static int withAlpha(int color, float alpha) {
        int a = (int) ((color >>> 24) * clamp01(alpha));
        return a << 24 | (color & 0xFFFFFF);
    }

    // ===== 补间（按键值记忆状态，逐帧向目标逼近，与帧率无关） =====

    private static final Map<Long, float[]> TWEENS = new HashMap<>();
    private static long lastPrune;

    /**
     * 返回 key 对应的当前值，并以 speed（每秒趋近比例，越大越快）向 target 平滑逼近。
     */
    public static float tween(long key, float target, float speed) {
        long now = nowMs();
        float[] s = TWEENS.get(key);
        if (s == null) {
            s = new float[]{target, now, now};
            TWEENS.put(key, s);
            return target;
        }
        float dt = Math.min(0.1f, (now - (long) s[1]) / 1000f);
        s[1] = now;
        s[2] = now;
        float k = 1 - (float) Math.exp(-speed * dt);
        s[0] += (target - s[0]) * k;
        if (Math.abs(target - s[0]) < 0.001f) s[0] = target;
        if (now - lastPrune > 5000) prune(now);
        return s[0];
    }

    /** 由界面坐标生成补间键（同一位置的控件共享一个平滑状态） */
    public static long key(int kind, int x, int y) {
        return ((long) kind << 48) ^ ((long) (x & 0xFFFFFF) << 24) ^ (y & 0xFFFFFF);
    }

    private static void prune(long now) {
        lastPrune = now;
        TWEENS.values().removeIf(s -> now - (long) s[2] > 3000);
    }

    // ===== 帧动画贴图 =====

    /**
     * 竖向精灵图帧动画（等效 GIF）：frameW×frameH 的帧自上而下堆叠 frames 帧，每帧 frameMs 毫秒。
     */
    public record Sprite(ResourceLocation tex, int frameW, int frameH, int frames, int frameMs) {

        public static Sprite of(String name, int w, int h, int frames, int frameMs) {
            return new Sprite(ResourceLocation.fromNamespaceAndPath("zhushenspace", "textures/gui/anim/" + name + ".png"),
                    w, h, frames, frameMs);
        }

        public int frame() {
            return (int) (nowMs() / frameMs % frames);
        }

        /** 按当前帧绘制到 (x,y,w,h)，可缩放 */
        public void draw(GuiGraphics g, int x, int y, int w, int h) {
            draw(g, x, y, w, h, 0xFFFFFFFF);
        }

        /** 带染色 / 透明度（ARGB）绘制 */
        public void draw(GuiGraphics g, int x, int y, int w, int h, int argb) {
            draw(g, x, y, w, h, 0, 0, frameW, frameH, argb);
        }

        /** 绘制当前帧的子区域 (u,v,uw,vh)，用于裁切式填充（如能量条） */
        public void draw(GuiGraphics g, int x, int y, int w, int h, int u, int v, int uw, int vh, int argb) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            g.setColor((argb >> 16 & 255) / 255f, (argb >> 8 & 255) / 255f, (argb & 255) / 255f,
                    (argb >>> 24) / 255f);
            g.blit(tex, x, y, w, h, u, frame() * frameH + v, uw, vh, frameW, frameH * frames);
            g.setColor(1, 1, 1, 1);
            RenderSystem.disableBlend();
        }
    }

    public static final Sprite NEBULA = Sprite.of("nebula", 160, 120, 24, 90);
    public static final Sprite TAIJI = Sprite.of("taiji", 32, 32, 24, 70);
    public static final Sprite SIGIL = Sprite.of("sigil", 64, 64, 32, 60);
    public static final Sprite ENERGY_FLOW = Sprite.of("energy_flow", 16, 32, 16, 60);
    public static final Sprite COSMOS = Sprite.of("cosmos", 256, 192, 32, 100);
    public static final Sprite UBW = Sprite.of("ubw", 256, 192, 24, 90);
    public static final Sprite CORNER = Sprite.of("corner", 9, 9, 16, 80);
}
