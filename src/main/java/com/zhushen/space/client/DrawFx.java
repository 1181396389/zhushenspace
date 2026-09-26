package com.zhushen.space.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import com.zhushen.space.screen.BladeBar;
import com.zhushen.space.screen.ZsAnim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * 「宝具拔出」光效：进入战斗模式 / 切换 A·B 栏时播放。
 * <pre>
 *   0 –  320ms  聚光：屏幕压暗，光粒自四周汇向护手宝石
 * 300 –  380ms  点火：宝石爆闪
 * 320 –  900ms  出鞘：剑身由护手向剑尖展开，前沿白热光刃 + 火星拖尾
 * 900 – 1500ms  开辟：剑尖炸开 —— 横贯屏幕的光条、冲击环；
 *                誓约胜利之剑为冲天金光柱，乖离剑为旋转的赤色风暴
 * 700 – 2600ms  真名：宝具名浮现后淡出
 * </pre>
 * 贴图见 tools/gen_draw_fx.py（白色灰度，运行时着色 + 加色混合）。
 */
public final class DrawFx {
    private DrawFx() {}

    public static final long GATHER_END = 320, DRAW_START = 320, DRAW_END = 900, TOTAL = 2600;

    private static final ResourceLocation RADIAL = tex("flare_radial"), STREAK = tex("flare_streak"),
            RING = tex("flare_ring"), PILLAR = tex("flare_pillar");

    private static long start = -1;
    private static BladeBar.Sword sword = BladeBar.Sword.EXCALIBUR;
    private static int soundStage;

    private static ResourceLocation tex(String n) {
        return ResourceLocation.fromNamespaceAndPath("zhushenspace", "textures/gui/anim/" + n + ".png");
    }

    /** 开始一次拔剑演出 */
    public static void play(BladeBar.Sword s) {
        start = ZsAnim.nowMs();
        sword = s;
        soundStage = 0;
        sound(SoundEvents.ARMOR_EQUIP_NETHERITE.value(), 0.6f, 0.9f);
        sound(SoundEvents.BEACON_POWER_SELECT, 0.7f, 0.5f);
    }

    public static void stop() {
        start = -1;
    }

    /** 已播放毫秒；未播放时返回极大值（剑栏直接完整显示） */
    public static long elapsed() {
        return start < 0 ? Long.MAX_VALUE / 4 : ZsAnim.nowMs() - start;
    }

    /** 剑身展开进度 0..1 */
    public static float drawProgress() {
        float t = (elapsed() - DRAW_START) / (float) (DRAW_END - DRAW_START);
        return ZsAnim.easeInOutSine(ZsAnim.clamp01(t));
    }

    private static void sound(SoundEvent e, float pitch, float vol) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(e, pitch, vol));
    }

    // ===== 背景层（剑栏之下）：压暗 + 聚光粒子 =====

    public static void renderUnder(GuiGraphics g, int barX, int barY) {
        long t = elapsed();
        if (t > TOTAL) return;
        int sw = g.guiWidth(), sh = g.guiHeight();

        // 压暗：底部更重，聚焦剑栏
        float env = ZsAnim.clamp01(t / 200f) * (1 - ZsAnim.clamp01((t - 1000) / 600f));
        if (env > 0) {
            g.fill(0, 0, sw, sh, ZsAnim.withAlpha(0xFF000000, 0.28f * env));
            g.fillGradient(0, sh / 2, sw, sh, 0, ZsAnim.withAlpha(0xFF000000, 0.45f * env));
        }

        // 聚光：光粒螺旋汇向宝石
        int gx = barX + BladeBar.GEM_X, gy = barY + BladeBar.GEM_Y;
        if (t < GATHER_END + 60) {
            additive();
            for (int i = 0; i < 22; i++) {
                float seed = hash(i);
                float local = ZsAnim.clamp01((t - seed * 120) / (float) (GATHER_END - seed * 120));
                if (local <= 0 || local >= 1) continue;
                float e = local * local;
                float ang = seed * 6.283f * 3 + e * 2.2f;
                float rad = (70 + hash(i + 50) * 110) * (1 - e);
                float px = gx + (float) Math.cos(ang) * rad, py = gy + (float) Math.sin(ang) * rad * 0.55f;
                float a = Math.min(1, local * 3) * (0.6f + 0.4f * e);
                glow(g, RADIAL, px, py, 6 + 4 * e, 6 + 4 * e, sword.aura(), a);
                // 拖尾
                float tx = gx + (float) Math.cos(ang - 0.25f) * rad * 1.08f,
                        ty = gy + (float) Math.sin(ang - 0.25f) * rad * 0.59f;
                glow(g, RADIAL, tx, ty, 4, 4, sword.aura(), a * 0.4f);
            }
            normal();
        }
    }

    // ===== 前景层（剑栏之上）：点火、光刃、火星、开辟、真名 =====

    public static void renderOver(GuiGraphics g, Font font, int barX, int barY) {
        long t = elapsed();
        if (t > TOTAL) return;
        int sw = g.guiWidth();
        int gx = barX + BladeBar.GEM_X, gy = barY + BladeBar.GEM_Y;
        int tipX = barX + BladeBar.W - 4, midY = barY + BladeBar.H / 2;
        int core = sword.core(), aura = sword.aura();

        sounds(t);
        additive();

        // 点火：宝石爆闪
        float ignite = bell(t, 290, 70, 560);
        if (ignite > 0) {
            glow(g, RADIAL, gx, gy, 70 * ignite + 10, 70 * ignite + 10, aura, ignite);
            glow(g, STREAK, gx, gy, 160 * ignite, 10, core, ignite * 0.9f);
        }

        // 出鞘：前沿白热光刃
        float d = drawProgress();
        if (t >= DRAW_START && t < DRAW_END + 120) {
            float front = barX + 30 + (BladeBar.W - 30) * d;
            float k = t < DRAW_END ? 1 : 1 - (t - DRAW_END) / 120f;
            glow(g, RADIAL, front, midY, 40, 40, aura, 0.9f * k);
            glow(g, RADIAL, front, midY, 14, 14, 0xFFFFFFFF, k);
            // 竖向光刃（旋转的光条）
            g.pose().pushPose();
            g.pose().translate(front, midY, 0);
            g.pose().mulPose(Axis.ZP.rotationDegrees(90));
            glow(g, STREAK, 0, 0, 70, 8, core, 0.9f * k);
            g.pose().popPose();
            // 剑身扫光：已拔出部分的余辉
            glow(g, STREAK, (barX + 30 + front) / 2f, midY, (front - barX - 30) * 1.3f, 26, aura, 0.35f * k);
        }

        // 火星：沿出鞘前沿迸出，受重力下落
        for (int i = 0; i < 40; i++) {
            float birth = DRAW_START + hash(i + 7) * (DRAW_END - DRAW_START);
            float age = (t - birth) / 1000f;
            float life = 0.35f + hash(i + 90) * 0.4f;
            if (age <= 0 || age > life) continue;
            float bd = ZsAnim.easeInOutSine((birth - DRAW_START) / (DRAW_END - DRAW_START));
            float x0 = barX + 30 + (BladeBar.W - 30) * bd;
            float vx = -40 + hash(i + 3) * 80, vy = -70 - hash(i + 11) * 90;
            float px = x0 + vx * age, py = midY + vy * age + 260 * age * age;
            float a = 1 - age / life;
            glow(g, RADIAL, px, py, 4, 4, ZsAnim.lerpColor(0xFFFFFFFF, aura, age / life), a);
        }

        // 开辟：剑尖爆发
        long b = t - DRAW_END;
        if (b >= 0 && b < 700) {
            float f = 1 - b / 700f;
            float flash = (float) Math.pow(f, 2.5);
            glow(g, RADIAL, tipX, midY, 110 * (0.6f + 0.4f * f), 110 * (0.6f + 0.4f * f), aura, flash);
            glow(g, RADIAL, tipX, midY, 30, 30, 0xFFFFFFFF, flash);
            glow(g, STREAK, tipX, midY, sw * 1.6f, 14 * f + 4, core, flash);
            glow(g, STREAK, sw / 2f, midY, sw * 2.2f, 3, 0xFFFFFFFF, flash * 0.7f);
            float r = 30 + 170 * ZsAnim.easeOutCubic(b / 600f);
            glow(g, RING, tipX, midY, r, r * 0.6f, aura, f * 0.8f);
            float r2 = 20 + 110 * ZsAnim.easeOutCubic(ZsAnim.clamp01((b - 90) / 600f));
            glow(g, RING, tipX, midY, r2, r2 * 0.6f, core, f * 0.5f);

            if (sword == BladeBar.Sword.EXCALIBUR) {
                // 誓约胜利之剑：冲天金光柱，底端落在剑尖
                float pw = 18 + 26 * f, ph = barY + 20;
                float a = ZsAnim.clamp01(b / 80f) * f;
                glow(g, PILLAR, tipX, midY - ph / 2f, pw * 2.2f, ph, aura, a * 0.6f);
                glow(g, PILLAR, tipX, midY - ph / 2f, pw * 0.7f, ph, 0xFFFFFFFF, a);
            } else {
                // 乖离剑：三道旋转的赤色风暴光条（呼应三段剑身逆向旋转）
                for (int i = 0; i < 3; i++) {
                    g.pose().pushPose();
                    g.pose().translate(tipX, midY, 0);
                    g.pose().mulPose(Axis.ZP.rotationDegrees(i * 60 + b * (i % 2 == 0 ? 0.5f : -0.35f)));
                    glow(g, STREAK, 0, 0, 220 * (0.5f + 0.5f * f), 6, i == 1 ? core : aura, f * 0.8f);
                    g.pose().popPose();
                }
                glow(g, RING, tipX, midY, 60 * f + 20, 60 * f + 20, 0xFFFF2A1A, f * 0.7f);
            }
        }
        normal();

        // 真名：宝具名 + 罗马字，拉开字距淡入，缓慢上浮后淡出
        float name = ZsAnim.clamp01((t - 750) / 350f) * (1 - ZsAnim.clamp01((t - 2000) / 600f));
        if (name > 0.02f) {
            float rise = (1 - ZsAnim.easeOutCubic(ZsAnim.clamp01((t - 750) / 900f))) * 6;
            int cx = barX + BladeBar.W / 2;
            float ty = barY - 30 + rise;
            g.pose().pushPose();
            g.pose().translate(cx, ty, 0);
            g.pose().scale(1.5f, 1.5f, 1);
            Component zh = Component.literal(sword.trueName());
            g.drawCenteredString(font, zh, 0, 0, ZsAnim.withAlpha(sword.letterColor(), name));
            g.pose().popPose();
            String en = sword.romanName();
            float spacing = 2 + 3 * (1 - name);
            int total = 0;
            for (char ch : en.toCharArray()) total += font.width(String.valueOf(ch));
            float x = cx - (total + spacing * (en.length() - 1)) / 2f;
            for (char ch : en.toCharArray()) {
                String s = String.valueOf(ch);
                g.drawString(font, s, (int) x, (int) (ty + 15), ZsAnim.withAlpha(aura, name * 0.9f), true);
                x += font.width(s) + spacing;
            }
            // 名下细光线
            int half = (int) (60 * ZsAnim.easeOutCubic(name));
            g.fill(cx - half, (int) ty + 26, cx + half, (int) ty + 27, ZsAnim.withAlpha(aura, name * 0.7f));
        }
    }

    private static void sounds(long t) {
        if (soundStage == 0 && t >= DRAW_START) {
            soundStage = 1;
            sound(SoundEvents.TRIDENT_RIPTIDE_1.value(), 1.3f, 0.6f);
        }
        if (soundStage == 1 && t >= DRAW_END) {
            soundStage = 2;
            sound(SoundEvents.BEACON_ACTIVATE, sword == BladeBar.Sword.EXCALIBUR ? 1.5f : 0.8f, 0.8f);
            sound(SoundEvents.AMETHYST_BLOCK_RESONATE, sword == BladeBar.Sword.EXCALIBUR ? 1.2f : 0.6f, 0.9f);
            if (sword == BladeBar.Sword.EA) sound(SoundEvents.WITHER_SHOOT, 0.5f, 0.35f);
        }
    }

    // ===== 工具 =====

    /** 以 (cx,cy) 为中心绘制 w×h 的着色光斑（需先 additive()） */
    private static void glow(GuiGraphics g, ResourceLocation tex, float cx, float cy, float w, float h,
                             int rgb, float alpha) {
        if (alpha <= 0.01f || w < 1 || h < 1) return;
        g.setColor((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f, Math.min(1, alpha));
        g.pose().pushPose();
        g.pose().translate(cx - w / 2f, cy - h / 2f, 0);
        g.pose().scale(w / 64f, h / 64f, 1);
        g.blit(tex, 0, 0, 64, 64, 0, 0, 1, 1, 1, 1);
        g.pose().popPose();
        g.setColor(1, 1, 1, 1);
    }

    private static void additive() {
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.depthMask(false);
    }

    private static void normal() {
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    /** 三段包络：a 起 → peak 峰 → end 止 */
    private static float bell(long t, long a, long rise, long end) {
        if (t < a || t > end) return 0;
        long peak = a + rise;
        return t < peak ? (t - a) / (float) rise : 1 - (t - peak) / (float) (end - peak);
    }

    private static float hash(int i) {
        int h = i * 374761393 + 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFFFF) / (float) 0x1000000;
    }
}
