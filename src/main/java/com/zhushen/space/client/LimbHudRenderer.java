package com.zhushen.space.client;

import com.mojang.math.Axis;
import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.LimbPart;
import com.zhushen.space.screen.ZsAnim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 肢体状态 HUD —— 仅在战斗模式显示（非战斗时完全不渲染，不影响正常状态）。
 * <p>
 * 两种风格（界面设置里可切换，并可拖动位置 / 滚轮缩放 / 关闭）：
 * <ul>
 *   <li>恶搞风（默认）：呼吸晃动的 Q 版小人，表情随伤势变化；断肢处喷卡通血滴、
 *       断掉的肢体在旁边打转飘走并说 "拜拜~"；受击时整只抖动并弹出 "POW!"；
 *       双腿全断时小人一屁股坐在地上；头部清空时 X_X + 绕头转圈的星星；顶部吐槽气泡。</li>
 *   <li>经典风：原来的色块人形图。</li>
 * </ul>
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class LimbHudRenderer {

    private LimbHudRenderer() {
    }

    public static final float MIN_SCALE = 0.5f, MAX_SCALE = 2.0f;
    /** 面板基础尺寸（未缩放） */
    public static final int W = 84, H = 92;

    private static final int PANEL = 0x88101820;
    private static final int OUTLINE = 0xFF0B1216;
    private static final int BLOOD = 0xFFD8203A;
    private static final int BLOOD_DARK = 0xFF8A1020;

    /** 面板位置：配置优先，默认伤势面板下方右对齐 */
    public static float[] layout(int screenW, int screenH, float scale) {
        ClientUiConfig.Data cfg = ClientUiConfig.get();
        float w = W * scale, h = H * scale;
        float x, y;
        if (cfg.limbX < 0 || cfg.limbY < 0) {
            float[] wp = WoundHudRenderer.layout(screenW, screenH, cfg.woundScale);
            x = wp[0] + wp[2] - w;
            y = wp[1] + wp[3] + 6;
            if (y + h > screenH) y = Math.max(0, wp[1] - h - 6);
        } else {
            x = cfg.limbX;
            y = cfg.limbY;
        }
        x = Math.max(0, Math.min(x, Math.max(0, screenW - w)));
        y = Math.max(0, Math.min(y, Math.max(0, screenH - h)));
        return new float[]{x, y, w, h};
    }

    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        if (!CombatModeClient.combatMode() || !ClientUiConfig.get().limbHudEnabled) return;
        GuiGraphics g = event.getGuiGraphics();
        float open = ZsAnim.easeOutCubic((ZsAnim.nowMs() - CombatModeClient.combatSince()) / 380f);
        render(g, mc.font, g.guiWidth(), g.guiHeight(), ClientLimbData.mask(mc.player.getId()), open, false);
    }

    /** 界面设置预览：无伤无断时给示例（断左臂 + 右腿半血），方便看效果 */
    public static void renderPreview(GuiGraphics g, Font font, int screenW, int screenH) {
        Minecraft mc = Minecraft.getInstance();
        int mask = mc.player == null ? 0 : ClientLimbData.mask(mc.player.getId());
        boolean demo = mask == 0 && !ClientLimbData.anyDamage();
        render(g, font, screenW, screenH, demo ? LimbPart.LEFT_ARM.bit() : mask, 1f, demo);
    }

    // ===== 数据 =====

    private static boolean demo;

    private static float ratio(LimbPart p, int mask) {
        if ((mask & p.bit()) != 0) return 0f;
        if (demo) return p == LimbPart.RIGHT_LEG ? 0.45f : 1f;
        int max = ClientLimbData.max(p);
        return max <= 0 ? 1f : Math.max(0f, Math.min(1f, ClientLimbData.current(p) / (float) max));
    }

    private static boolean cut(LimbPart p, int mask) {
        return (mask & p.bit()) != 0;
    }

    private static int hpColor(float r) {
        return r > 0.6f ? 0xFF7FE0A0 : r > 0.3f ? 0xFFF5D76E : r > 0f ? 0xFFFF8A4C : 0xFFFF3050;
    }

    private static long lastHit(long now) {
        if (demo) return now - 100000;
        long t = 0;
        for (LimbPart p : LimbPart.values()) t = Math.max(t, ClientLimbData.hitAt(p));
        return t;
    }

    private static LimbPart lastHitPart() {
        LimbPart best = null;
        long t = 0;
        for (LimbPart p : LimbPart.values()) {
            if (ClientLimbData.hitAt(p) > t) { t = ClientLimbData.hitAt(p); best = p; }
        }
        return best;
    }

    // ===== 绘制入口 =====

    private static void render(GuiGraphics g, Font font, int sw, int sh, int mask, float open, boolean isDemo) {
        demo = isDemo;
        ClientUiConfig.Data cfg = ClientUiConfig.get();
        float scale = cfg.limbScale;
        float[] pos = layout(sw, sh, scale);
        long now = System.currentTimeMillis();
        long since = now - lastHit(now);

        float shakeX = 0, shakeY = 0;
        if (since < 350 && cfg.limbHudFun) {
            float k = 1 - since / 350f;
            shakeX = (float) Math.sin(now / 17.0) * 2.5f * k;
            shakeY = (float) Math.cos(now / 23.0) * 1.5f * k;
        }
        g.pose().pushPose();
        g.pose().translate(pos[0] + shakeX, pos[1] + shakeY + (1 - open) * -12f, 0);
        g.pose().scale(scale, scale, 1f);
        // 弹出动画：从右上角 "蹦" 出来
        float pop = open < 1 ? 0.6f + 0.4f * open + (float) Math.sin(open * Math.PI) * 0.15f : 1f;
        g.pose().translate(W / 2f, H / 2f, 0);
        g.pose().scale(pop, pop, 1f);
        g.pose().translate(-W / 2f, -H / 2f, 0);

        g.fill(0, 0, W, H, PANEL);
        if (cfg.limbHudFun) renderFun(g, font, mask, now, since, cfg.limbHudQuips);
        else renderClassic(g, font, mask, now);
        g.pose().popPose();
    }

    // ===== 恶搞风 =====

    private static void renderFun(GuiGraphics g, Font font, int mask, long now, long since, boolean quips) {
        boolean noLegs = cut(LimbPart.RIGHT_LEG, mask) && cut(LimbPart.LEFT_LEG, mask);
        boolean headOut = ratio(LimbPart.HEAD, mask) <= 0f;
        boolean anyCut = mask != 0;
        boolean anyHurt = false;
        for (LimbPart p : LimbPart.values()) if (!cut(p, mask) && ratio(p, mask) < 0.999f) anyHurt = true;

        int groundY = H - 8;
        // 地面（虚线，缓慢滚动）
        int off = (int) (now / 80 % 6);
        for (int x = 4 - off; x < W - 4; x += 6) g.fill(Math.max(4, x), groundY, Math.min(W - 4, x + 3), groundY + 1, 0x668FC7D6);

        // 呼吸 / 摇摆
        float t = now / 1000f;
        float bob = (float) Math.sin(t * (headOut ? 1.2 : anyHurt ? 5.0 : 2.6)) * (anyHurt ? 1.2f : 1.6f);
        float sway = headOut ? (float) Math.sin(t * 1.3) * 8f : (float) Math.sin(t * 1.7) * 3f;
        int cx = W / 2;
        // 身体基准：头顶 y
        int legLen = 16;
        int bodyTop = groundY - legLen - 18;
        if (noLegs) bodyTop = groundY - 18; // 一屁股坐地上
        int headTop = bodyTop - 17;

        g.pose().pushPose();
        g.pose().translate(cx, groundY, 0);
        g.pose().mulPose(Axis.ZP.rotationDegrees(sway));
        g.pose().translate(-cx, -groundY + bob * 0.5f, 0);

        // 腿（双腿断时画一滩血 + 屁股）
        legs(g, cx, bodyTop + 18, legLen, mask, now, noLegs, groundY);
        // 躯干
        box(g, cx - 7, bodyTop, 14, 18, 0xFF4FA3C7);
        g.fill(cx - 7, bodyTop + 13, cx + 7, bodyTop + 15, 0xFF2C5E75); // 腰带
        // 手臂
        arm(g, cx - 7, bodyTop + 1, -1, LimbPart.RIGHT_ARM, mask, now, anyHurt);
        arm(g, cx + 7, bodyTop + 1, 1, LimbPart.LEFT_ARM, mask, now, anyHurt);
        // 头（带表情）
        g.pose().pushPose();
        float nod = headOut ? (float) Math.sin(t * 2) * 10f : (float) Math.sin(t * 2.6 + 1) * 3f;
        g.pose().translate(cx, bodyTop, 0);
        g.pose().mulPose(Axis.ZP.rotationDegrees(nod));
        g.pose().translate(-cx, -bodyTop + bob * 0.5f, 0);
        head(g, cx - 9, headTop, mask, now, headOut, anyCut, anyHurt, noLegs);
        g.pose().popPose();
        g.pose().popPose();

        // 飞走的断肢（不随身体摆动，自顾自打转）
        int seat = noLegs ? 0 : 1;
        floating(g, font, LimbPart.RIGHT_ARM, mask, now, 10, bodyTop + 4, -1);
        floating(g, font, LimbPart.LEFT_ARM, mask, now, W - 10, bodyTop + 4, 1);
        floating(g, font, LimbPart.RIGHT_LEG, mask, now, 9, groundY - 6 - seat * 4, -1);
        floating(g, font, LimbPart.LEFT_LEG, mask, now, W - 9, groundY - 6 - seat * 4, 1);

        // 受击爆字
        if (since < 650 && !demo) {
            LimbPart hp = lastHitPart();
            float k = since / 650f;
            float s = 0.6f + (float) Math.sin(Math.min(1f, k * 3f) * Math.PI / 2) * 0.6f;
            int a = (int) (255 * (1 - k * k));
            if (a > 8) {
                String word = Component.translatable("hud.zhushenspace.limb.pow." + (int) (lastHit(now) / 7 % 4)).getString();
                int px = hp == null ? cx : hp == LimbPart.RIGHT_ARM || hp == LimbPart.RIGHT_LEG ? 16 : hp == LimbPart.HEAD ? cx : W - 16;
                int py = (hp == LimbPart.HEAD ? headTop : hp != null && !hp.isArm() ? groundY - 14 : bodyTop + 6) - (int) (k * 10);
                g.pose().pushPose();
                g.pose().translate(px, py, 200);
                g.pose().mulPose(Axis.ZP.rotationDegrees(-12 + (lastHit(now) % 24)));
                g.pose().scale(s, s, 1);
                burst(g, 0, 0, 13, (a << 24) | 0xFFE14D);
                int tw = font.width(word);
                g.drawString(font, word, -tw / 2, -4, (a << 24) | 0xD8203A, false);
                g.pose().popPose();
            }
        }

        // 吐槽气泡
        if (quips) {
            String key;
            int n;
            if (headOut) { key = "head"; n = 3; }
            else if (noLegs) { key = "noleg"; n = 3; }
            else if (cut(LimbPart.RIGHT_ARM, mask) && cut(LimbPart.LEFT_ARM, mask)) { key = "noarm"; n = 3; }
            else if (cut(LimbPart.RIGHT_ARM, mask) || cut(LimbPart.LEFT_ARM, mask)) { key = "arm"; n = 3; }
            else if (cut(LimbPart.RIGHT_LEG, mask) || cut(LimbPart.LEFT_LEG, mask)) { key = "leg"; n = 3; }
            else if (anyHurt) { key = "hurt"; n = 3; }
            else { key = "ok"; n = 3; }
            int idx = (int) (now / 4500 % n);
            String line = Component.translatable("hud.zhushenspace.limb.quip." + key + "." + idx).getString();
            float appear = ZsAnim.clamp01((now % 4500) / 220f);
            bubble(g, font, line, cx, 3, appear);
        }
    }

    private static void box(GuiGraphics g, int x, int y, int w, int h, int col) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, OUTLINE);
        g.fill(x, y, x + w, y + h, col);
        g.fill(x, y, x + w, y + 1, lighten(col));
    }

    private static int lighten(int c) {
        int r = Math.min(255, ((c >> 16) & 0xFF) + 50), gg = Math.min(255, ((c >> 8) & 0xFF) + 50), b = Math.min(255, (c & 0xFF) + 50);
        return (c & 0xFF000000) | (r << 16) | (gg << 8) | b;
    }

    /** 带血量填充的肢体（空的部分为暗色，底部往上填） */
    private static void limb(GuiGraphics g, int x, int y, int w, int h, float r, long now, LimbPart p) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, OUTLINE);
        g.fill(x, y, x + w, y + h, 0xFF22303A);
        int fh = Math.max(r > 0 ? 1 : 0, Math.round(h * r));
        g.fill(x, y + h - fh, x + w, y + h, hpColor(r));
        long since = now - ClientLimbData.hitAt(p);
        if (!demo && since < 300) {
            int a = (int) (220 * (1 - since / 300f));
            g.fill(x, y, x + w, y + h, (a << 24) | 0xFFFFFF);
        }
        if (r > 0 && r <= 0.3f && (now / 300) % 2 == 0) g.fill(x, y, x + w, y + h, 0x40FF0000);
    }

    private static void arm(GuiGraphics g, int shoulderX, int y, int dir, LimbPart p, int mask, long now, boolean hurt) {
        int w = 5, h = 15;
        if (cut(p, mask)) {
            stump(g, shoulderX + (dir < 0 ? -w : 0), y, w, now, dir, true);
            return;
        }
        float t = now / 1000f;
        // 双臂乱挥（受伤时挥得更慌）
        float ang = dir * (18 + (float) Math.sin(t * (hurt ? 9 : 3) + (dir > 0 ? 1.5 : 0)) * (hurt ? 28 : 10));
        g.pose().pushPose();
        g.pose().translate(shoulderX, y + 1, 0);
        g.pose().mulPose(Axis.ZP.rotationDegrees(-ang));
        limb(g, dir < 0 ? -w : 0, -1, w, h, ratio(p, mask), now, p);
        g.pose().popPose();
    }

    private static void legs(GuiGraphics g, int cx, int hipY, int len, int mask, long now, boolean noLegs, int groundY) {
        if (noLegs) {
            // 血泊（缓慢扩大/收缩）
            int pw = 18 + (int) (Math.sin(now / 700.0) * 3);
            g.fill(cx - pw, groundY - 1, cx + pw, groundY + 2, BLOOD_DARK);
            g.fill(cx - pw + 3, groundY - 2, cx + pw - 3, groundY, BLOOD);
            stump(g, cx - 7, hipY, 6, now, -1, false);
            stump(g, cx + 1, hipY, 6, now, 1, false);
            return;
        }
        float t = now / 1000f;
        for (int i = 0; i < 2; i++) {
            LimbPart p = i == 0 ? LimbPart.RIGHT_LEG : LimbPart.LEFT_LEG;
            int x = i == 0 ? cx - 7 : cx + 1;
            if (cut(p, mask)) {
                stump(g, x, hipY, 6, now, i == 0 ? -1 : 1, false);
                continue;
            }
            // 单腿时 "金鸡独立" 蹦跶；正常时小碎步
            boolean hop = cut(i == 0 ? LimbPart.LEFT_LEG : LimbPart.RIGHT_LEG, mask);
            float ang = hop ? (float) Math.sin(t * 8) * 6 : (float) Math.sin(t * 3 + i * Math.PI) * 7;
            g.pose().pushPose();
            g.pose().translate(x + 3, hipY, 0);
            if (hop) g.pose().translate(0, -Math.abs((float) Math.sin(t * 8)) * 3, 0);
            g.pose().mulPose(Axis.ZP.rotationDegrees(ang));
            limb(g, -3, 0, 6, len, ratio(p, mask), now, p);
            g.pose().popPose();
        }
    }

    /** 断口：锯齿红肉 + 骨头 + 喷射的卡通血滴 */
    private static void stump(GuiGraphics g, int x, int y, int w, long now, int dir, boolean arm) {
        int sy = arm ? y + 1 : y;
        g.fill(x - 1, sy - 1, x + w + 1, sy + 4, OUTLINE);
        g.fill(x, sy, x + w, sy + 3, BLOOD_DARK);
        for (int i = 0; i < w; i += 2) g.fill(x + i, sy + 3, x + i + 1, sy + 4, BLOOD); // 锯齿
        g.fill(x + w / 2 - 1, sy + 1, x + w / 2 + 1, sy + 5, 0xFFF3EEDC); // 骨头
        // 喷血：每个断口 4 颗血滴沿抛物线循环
        float base = arm ? 0 : 0.37f;
        for (int i = 0; i < 4; i++) {
            float ph = ((now / 700f) + i * 0.25f + base + (dir > 0 ? 0.13f : 0)) % 1f;
            float px = x + w / 2f + dir * ph * (arm ? 14 : 8);
            float py = sy + 3 - (arm ? 9 : 6) * ph + 22 * ph * ph;
            int a = (int) (255 * (1 - ph));
            int s = ph < 0.5f ? 2 : 1;
            g.fill((int) px, (int) py, (int) px + s, (int) py + s + (ph > 0.3f ? 1 : 0), (a << 24) | (BLOOD & 0xFFFFFF));
        }
    }

    /** 断掉的肢体在旁边转圈飘，头顶飘 "拜拜~" */
    private static void floating(GuiGraphics g, Font font, LimbPart p, int mask, long now, int x, int y, int dir) {
        if (!cut(p, mask)) return;
        float t = now / 1000f + p.ordinal();
        float fy = y + (float) Math.sin(t * 2.2) * 3;
        float fx = x + (float) Math.cos(t * 1.4) * 2;
        g.pose().pushPose();
        g.pose().translate(fx, fy, 50);
        g.pose().mulPose(Axis.ZP.rotationDegrees((now / 6f * dir) % 360));
        int w = 4, h = p.isArm() ? 11 : 12;
        g.fill(-w / 2 - 1, -h / 2 - 1, w / 2 + 1, h / 2 + 1, OUTLINE);
        g.fill(-w / 2, -h / 2, w / 2, h / 2, 0xFF7A8B92);
        g.fill(-w / 2, -h / 2, w / 2, -h / 2 + 2, BLOOD);
        g.fill(-1, -h / 2 - 2, 1, -h / 2, 0xFFF3EEDC);
        g.pose().popPose();
        // "拜拜~" 小字（半透明闪烁）
        if ((now / 900 + p.ordinal()) % 3 != 0) {
            g.pose().pushPose();
            g.pose().translate(fx, fy - 10, 60);
            g.pose().scale(0.5f, 0.5f, 1);
            String s = Component.translatable("hud.zhushenspace.limb.bye").getString();
            g.drawString(font, s, -font.width(s) / 2, 0, 0xCCFFFFFF, false);
            g.pose().popPose();
        }
    }

    private static void head(GuiGraphics g, int x, int y, int mask, long now, boolean out,
                             boolean anyCut, boolean hurt, boolean noLegs) {
        int w = 18, h = 16;
        float r = ratio(LimbPart.HEAD, mask);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, OUTLINE);
        g.fill(x, y, x + w, y + h, 0xFFF1C9A0); // 皮肤
        g.fill(x, y, x + w, y + 4, 0xFF5A3A22); // 头发
        g.fill(x, y + 4, x + 2, y + 7, 0xFF5A3A22);
        g.fill(x + w - 2, y + 4, x + w, y + 7, 0xFF5A3A22);
        // 头部血量：头顶小血条
        g.fill(x, y - 4, x + w, y - 2, 0xFF22303A);
        g.fill(x, y - 4, x + Math.round(w * r), y - 2, hpColor(r));
        long since = now - ClientLimbData.hitAt(LimbPart.HEAD);
        if (!demo && since < 300) g.fill(x, y, x + w, y + h, ((int) (200 * (1 - since / 300f)) << 24) | 0xFFFFFF);

        int ex1 = x + 4, ex2 = x + w - 7, ey = y + 7;
        int ink = 0xFF1A1A1A;
        if (out) {
            // X_X + 舌头 + 转圈星星
            xEye(g, ex1, ey, ink);
            xEye(g, ex2, ey, ink);
            g.fill(x + 6, y + 12, x + 12, y + 13, ink);
            g.fill(x + 9, y + 13, x + 11, y + 15, 0xFFE0607A);
            for (int i = 0; i < 3; i++) {
                double a = now / 260.0 + i * Math.PI * 2 / 3;
                int sx = x + w / 2 + (int) (Math.cos(a) * 12), sy = y - 1 + (int) (Math.sin(a) * 3);
                star(g, sx, sy, 0xFFFFE14D);
            }
        } else if (anyCut) {
            // >_<  + 大哭（泪水瀑布）+ 张大的嘴
            g.fill(ex1, ey - 1, ex1 + 1, ey, ink); g.fill(ex1 + 1, ey, ex1 + 2, ey + 1, ink); g.fill(ex1, ey + 1, ex1 + 1, ey + 2, ink);
            g.fill(ex2 + 2, ey - 1, ex2 + 3, ey, ink); g.fill(ex2 + 1, ey, ex2 + 2, ey + 1, ink); g.fill(ex2 + 2, ey + 1, ex2 + 3, ey + 2, ink);
            int mo = 2 + (int) (Math.abs(Math.sin(now / 90.0)) * 2);
            g.fill(x + 7, y + 11, x + 11, y + 11 + mo, 0xFF7A1020);
            for (int i = 0; i < 3; i++) {
                int ty = (int) ((now / 60 + i * 4) % 12);
                g.fill(ex1, ey + 2 + ty, ex1 + 1, ey + 4 + ty, 0xCC6EC6FF);
                g.fill(ex2 + 2, ey + 2 + ty, ex2 + 3, ey + 4 + ty, 0xCC6EC6FF);
            }
            if (noLegs) g.fill(x - 3, y + 2, x - 1, y + 5, 0xFF6EC6FF); // 汗
        } else if (hurt) {
            // o_o + 滑落的汗滴 + 波浪嘴
            g.fill(ex1, ey - 1, ex1 + 3, ey + 2, ink);
            g.fill(ex2, ey - 1, ex2 + 3, ey + 2, ink);
            g.fill(ex1 + 1, ey, ex1 + 2, ey + 1, 0xFFFFFFFF);
            g.fill(ex2 + 1, ey, ex2 + 2, ey + 1, 0xFFFFFFFF);
            for (int i = 0; i < 6; i++) g.fill(x + 6 + i, y + 12 + (i % 2), x + 7 + i, y + 13 + (i % 2), ink);
            int dy = (int) (now / 90 % 10);
            g.fill(x + w, y + 3 + dy, x + w + 2, y + 6 + dy, 0xDD6EC6FF);
        } else {
            // ^_^ 眨眼 + 笑
            boolean blink = now % 3200 < 140;
            if (blink) {
                g.fill(ex1, ey + 1, ex1 + 3, ey + 2, ink);
                g.fill(ex2, ey + 1, ex2 + 3, ey + 2, ink);
            } else {
                g.fill(ex1, ey, ex1 + 3, ey + 2, ink);
                g.fill(ex2, ey, ex2 + 3, ey + 2, ink);
            }
            g.fill(x + 6, y + 11, x + 7, y + 12, ink);
            g.fill(x + 11, y + 11, x + 12, y + 12, ink);
            g.fill(x + 7, y + 12, x + 11, y + 13, ink);
            g.fill(x + 3, y + 10, x + 5, y + 11, 0x66FF6080); // 腮红
            g.fill(x + w - 5, y + 10, x + w - 3, y + 11, 0x66FF6080);
        }
    }

    private static void xEye(GuiGraphics g, int x, int y, int c) {
        for (int i = 0; i < 3; i++) {
            g.fill(x + i, y - 1 + i, x + i + 1, y + i, c);
            g.fill(x + 2 - i, y - 1 + i, x + 3 - i, y + i, c);
        }
    }

    private static void star(GuiGraphics g, int x, int y, int c) {
        g.fill(x - 1, y, x + 2, y + 1, c);
        g.fill(x, y - 1, x + 1, y + 2, c);
    }

    /** 漫画爆炸框（多角星近似） */
    private static void burst(GuiGraphics g, int x, int y, int r, int col) {
        int a = col >>> 24;
        for (int i = 0; i < 8; i++) {
            g.pose().pushPose();
            g.pose().translate(x, y, 0);
            g.pose().mulPose(Axis.ZP.rotationDegrees(i * 22.5f));
            g.fill(-r, -3, r, 3, (a << 24) | 0x1A1A1A);
            g.fill(-r + 1, -2, r - 1, 2, col);
            g.pose().popPose();
        }
        g.fill(x - r + 4, y - 6, x + r - 4, y + 6, col);
    }

    /** 吐槽气泡：顶部居中，带小尾巴，出现时有个弹跳 */
    private static void bubble(GuiGraphics g, Font font, String text, int cx, int y, float appear) {
        float s = 0.5f;
        int tw = (int) (font.width(text) * s);
        int bw = Math.min(W - 6, tw + 8), bh = 9;
        g.pose().pushPose();
        g.pose().translate(cx, y + bh / 2f, 100);
        float k = appear < 1 ? 0.7f + 0.3f * appear + (float) Math.sin(appear * Math.PI) * 0.2f : 1f;
        g.pose().scale(k, k, 1);
        g.pose().translate(-cx, -(y + bh / 2f), 0);
        int x0 = cx - bw / 2;
        g.fill(x0 - 1, y - 1, x0 + bw + 1, y + bh + 1, OUTLINE);
        g.fill(x0, y, x0 + bw, y + bh, 0xFFFDF8E8);
        g.fill(cx + 2, y + bh, cx + 5, y + bh + 2, 0xFFFDF8E8);
        g.fill(cx + 3, y + bh + 2, cx + 4, y + bh + 3, 0xFFFDF8E8);
        g.pose().translate(cx, y + 2.5f, 0);
        g.pose().scale(s, s, 1);
        g.drawString(font, text, -font.width(text) / 2, 0, 0xFF2A2A2A, false);
        g.pose().popPose();
    }

    // ===== 经典风 =====

    private static void renderClassic(GuiGraphics g, Font font, int mask, long now) {
        int U = 2;
        int x = (W - 16 * U) / 2, y = 8;
        cpart(g, x + 4 * U, y, 8, 8, LimbPart.HEAD, mask, now);
        g.fill(x + 4 * U, y + 8 * U, x + 12 * U, y + 20 * U, 0xFF3E8FA3);
        cpart(g, x, y + 8 * U, 4, 12, LimbPart.RIGHT_ARM, mask, now);
        cpart(g, x + 12 * U, y + 8 * U, 4, 12, LimbPart.LEFT_ARM, mask, now);
        cpart(g, x + 4 * U, y + 20 * U, 4, 12, LimbPart.RIGHT_LEG, mask, now);
        cpart(g, x + 8 * U, y + 20 * U, 4, 12, LimbPart.LEFT_LEG, mask, now);
        g.drawCenteredString(font, Component.translatable("hud.zhushenspace.limb.title"), W / 2, H - 12, 0xFF8FC7D6);
    }

    private static void cpart(GuiGraphics g, int x, int y, int pw, int ph, LimbPart p, int mask, long now) {
        int w = pw * 2, h = ph * 2;
        if (cut(p, mask)) {
            g.fill(x, y, x + w, y + h, 0x55303030);
            int m = Math.min(w, h);
            for (int i = 0; i < m; i++) {
                int yy = y + i * h / m;
                g.fill(x + i * w / m, yy, x + i * w / m + 1, yy + 1, 0xFFB02030);
                g.fill(x + w - 1 - i * w / m, yy, x + w - i * w / m, yy + 1, 0xFFB02030);
            }
            return;
        }
        float r = ratio(p, mask);
        int col = r > 0.6f ? 0xFF4FC3D7 : r > 0.3f ? 0xFFF5D76E : r > 0f ? 0xFFE8603A : 0xFFFF2030;
        if (r <= 0f && (now / 250) % 2 == 0) col = 0xFF601018;
        g.fill(x, y, x + w, y + h, 0xFF1A2A30);
        int fillH = Math.max(r > 0 ? 1 : 0, Math.round(h * r));
        g.fill(x, y + h - fillH, x + w, y + h, col);
        long since = now - ClientLimbData.hitAt(p);
        if (!demo && since < 300) g.fill(x, y, x + w, y + h, ((int) (200 * (1 - since / 300f)) << 24) | 0xFFFFFF);
    }
}
