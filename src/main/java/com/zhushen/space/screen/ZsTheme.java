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
 * 主神空间 UI 统一主题 —— 大黑塔（崩坏：星穹铁道）魔女风：夜空星底 + 裙摆蓝紫魔法阵 + 帽上紫花角饰 +
 * 挂在面板角上的尖顶魔女帽 + 飘落花瓣 + 小人偶。商店 / 货币兑换 / 设置 / 背包入口 / 通用提示框等共用这一套
 * （属性、技能、战斗预设页各有专属风格）。改这里即全局生效。
 */
public final class ZsTheme {

    private ZsTheme() {
    }

    // ===== 配色（大黑塔：夜空墨紫 + 帽花紫 + 银发薰衣草 + 帽环金） =====
    public static final int PANEL_BG = 0xEE120E1C;
    public static final int PANEL_BORDER = 0xFF8C6FE0;
    public static final int PANEL_GLOW = 0x339B7BEA;
    public static final int HEADER_LINE = 0x668C6FE0;
    public static final int ROW_BG = 0x552A2046;
    public static final int ROW_BG_ALT = 0x381C1630;
    public static final int ROW_HOVER = 0x995A44A0;
    public static final int TEXT_MAIN = 0xFFEDE8F6;
    public static final int TEXT_SUB = 0xFFBBAEDD;
    public static final int TEXT_TITLE = 0xFFD6C8FA;
    public static final int TEXT_DISABLED = 0xFF5C5270;
    public static final int ACCENT = 0xFF9B7BEA;
    public static final int ACCENT_LIGHT = 0xFFCDBEF5;
    public static final int GOLD = 0xFFE8C77E;
    public static final int CURRENCY = 0xFFE0BE7A;
    public static final int WARN = 0xFFFF8FA8;
    public static final int PENDING = 0xFFF2A9DA;
    public static final int JADE = 0xFF9FD8F0;
    public static final Style GOLD_STYLE = Style.EMPTY.withColor(TextColor.fromRgb(GOLD));
    public static final int DOT_FULL = 0xFFA98BF0;
    public static final int DOT_FULL_5 = 0xFFE8C77E;
    public static final int DOT_PENDING = 0xFFF2A9DA;
    public static final int DOT_EMPTY = 0x662A2046;
    public static final int DOT_BORDER = 0xFF5E4C94;
    public static final int BTN_BG = 0xCC241C3C;
    public static final int BTN_HOVER = 0xE04A3688;
    public static final int BTN_ACTIVE = 0xE04A3688;
    public static final int BTN_DISABLED = 0x551A1428;
    public static final int BTN_BORDER = 0xFF7A62C4;
    public static final int BTN_BORDER_HOVER = 0xFFCDBEF5;
    public static final int BTN_BORDER_DISABLED = 0xFF3A3050;
    public static final int SLOT_BG = 0x992A2046;
    public static final int SLOT_BORDER = 0xFF5E4C94;
    public static final int SLOT_HOVER_BORDER = 0xFFCDBEF5;
    public static final int CHIP_BG = 0xCC241C3C;
    public static final int CHIP_HOVER = 0xE04A3688;
    /** 飘落花瓣（参考图中的淡粉紫花瓣） */
    public static final int PETAL = 0xFFF0B8DC;

    private static final net.minecraft.resources.ResourceLocation FLOWER = tex("herta_flower"), HAT = tex("herta_hat"),
            SIGIL = tex("herta_sigil"), DOLL = tex("herta_doll");

    private static net.minecraft.resources.ResourceLocation tex(String n) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("zhushenspace", "textures/gui/anim/" + n + ".png");
    }

    private static final int K_BTN = 1, K_ROW = 2, K_SLOT = 3, K_TAB = 4, K_BAR = 5;

    // ===== 判定 =====

    public static boolean over(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ===== 打开动画 =====

    /**
     * 界面打开动画：从 92% 缩放 + 透明弹出到 100%（带轻微回弹）。
     * 在 render 开头调用并 push，结束时调用 {@link #endOpen}。返回 0..1 的进度（可用于淡入内容）。
     */
    public static float beginOpen(GuiGraphics g, long openedAt, int cx, int cy) {
        float t = ZsAnim.clamp01((ZsAnim.nowMs() - openedAt) / 260f);
        float s = 0.92f + 0.08f * ZsAnim.easeOutBack(t);
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        g.pose().scale(s, s, 1);
        g.pose().translate(-cx, -cy, 0);
        return t;
    }

    public static void endOpen(GuiGraphics g) {
        g.pose().popPose();
    }

    /** 全屏暗角遮罩（替代原版模糊背景，随打开进度淡入） */
    public static void backdrop(GuiGraphics g, int w, int h, float t) {
        int a = (int) (0xAA * ZsAnim.easeOutCubic(t));
        g.fillGradient(0, 0, w, h, a << 24 | 0x0A0714, a << 24 | 0x1C1232);
    }

    // ===== 面板 =====

    /**
     * 动态主面板：星云帧动画底纹 + 半透明蒙层 + 沿边框循环流动的光点 + 四角呼吸角饰。
     */
    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        float p = ZsAnim.pulse(3200);
        int glow = ZsAnim.withAlpha(ACCENT, 0.10f + 0.12f * p);
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, glow);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, glow);

        // 夜空星底 + 墨紫蒙层 + 顶部薰衣草渐亮
        ZsAnim.HERTA_SKY.draw(g, x, y, w, h);
        g.fill(x, y, x + w, y + h, 0x9E120E1C);
        g.fillGradient(x, y, x + w, y + 28, 0x2ACDBEF5, 0x00CDBEF5);

        g.renderOutline(x, y, w, h, PANEL_BORDER);
        g.renderOutline(x + 2, y + 2, w - 4, h - 4, 0x22CDBEF5);

        petals(g, x, y, w, h, 7);
        borderComet(g, x, y, w, h, 6400, PETAL);
        borderComet(g, x, y, w, h, 6400, ACCENT_LIGHT, 0.5f);

        // 角饰：四角紫花（缓慢自转、呼吸），右上角挂一顶魔女帽
        float rot = ZsAnim.nowMs() / 60f % 360;
        flower(g, x, y, 11, rot, 0.85f + 0.15f * p);
        flower(g, x, y + h - 1, 9, -rot, 0.8f);
        flower(g, x + w - 1, y + h - 1, 11, rot + 36, 0.85f + 0.15f * p);
        hat(g, x + w - 16, y - 13, 22);
    }

    /** 大黑塔帽上的紫花：以 (cx,cy) 为中心、size 像素、旋转 deg 度 */
    public static void flower(GuiGraphics g, float cx, float cy, float size, float deg, float alpha) {
        icon(g, FLOWER, cx, cy, size, size, deg, alpha, 32, 32);
    }

    /** 尖顶魔女帽（左上角位置 x,y，边长 size），随时间轻轻摇晃 */
    public static void hat(GuiGraphics g, float x, float y, float size) {
        float sway = (float) Math.sin(ZsAnim.nowMs() / 900.0) * 4;
        icon(g, HAT, x + size / 2, y + size / 2, size, size, sway, 1, 48, 48);
    }

    /** 蓝紫魔法阵水印（裙摆徽记），中心 (cx,cy)，缓慢旋转 */
    public static void sigil(GuiGraphics g, float cx, float cy, float size, int argb) {
        float rot = ZsAnim.nowMs() / 180f % 360;
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        g.setColor((argb >> 16 & 255) / 255f, (argb >> 8 & 255) / 255f, (argb & 255) / 255f, (argb >>> 24) / 255f);
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(rot));
        g.pose().scale(size / 128f, size / 128f, 1);
        g.blit(SIGIL, -64, -64, 0, 0, 128, 128, 128, 128);
        g.pose().popPose();
        g.setColor(1, 1, 1, 1);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }

    /** 大黑塔的紫色小人偶（左上角 x,y，边长 size），上下漂浮 */
    public static void doll(GuiGraphics g, float x, float y, float size) {
        float bob = (float) Math.sin(ZsAnim.nowMs() / 500.0) * 1.5f;
        icon(g, DOLL, x + size / 2, y + size / 2 + bob, size, size, 0, 1, 32, 32);
    }

    private static void icon(GuiGraphics g, net.minecraft.resources.ResourceLocation tex, float cx, float cy, float w, float h,
                             float deg, float alpha, int tw, int th) {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        g.setColor(1, 1, 1, alpha);
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        if (deg != 0) g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(deg));
        g.pose().scale(w / tw, h / th, 1);
        g.blit(tex, -tw / 2, -th / 2, 0, 0, tw, th, tw, th);
        g.pose().popPose();
        g.setColor(1, 1, 1, 1);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }

    /** 飘落花瓣：n 片淡粉紫花瓣在区域内斜向飘落、左右摆动（确定性伪随机，循环无缝） */
    public static void petals(GuiGraphics g, int x, int y, int w, int h, int n) {
        long now = ZsAnim.nowMs();
        g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
        for (int i = 0; i < n; i++) {
            float seed = hash(i * 7 + 3), speed = 9000 + hash(i + 40) * 7000;
            float t = ((now / speed) + seed) % 1f;
            float px = x + (hash(i + 11) * 1.2f - 0.1f) * w + t * 30 + (float) Math.sin(t * 12 + i) * 5;
            float py = y - 4 + t * (h + 8);
            float a = Math.min(1, Math.min(t * 6, (1 - t) * 6)) * 0.75f;
            int c = ZsAnim.withAlpha(i % 3 == 0 ? ACCENT_LIGHT : PETAL, a);
            int ix = (int) px, iy = (int) py;
            boolean flip = Math.sin(t * 20 + i) > 0;   // 翻转：横 / 竖两态交替
            if (flip) { g.fill(ix, iy, ix + 2, iy + 1, c); g.fill(ix + 1, iy + 1, ix + 3, iy + 2, c); }
            else { g.fill(ix, iy, ix + 1, iy + 2, c); g.fill(ix + 1, iy + 1, ix + 2, iy + 3, c); }
        }
        g.disableScissor();
    }

    private static float hash(int i) {
        int h = i * 374761393 + 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFFFF) / (float) 0x1000000;
    }

    /** 子面板（卡片 / 说明栏）：无星云，仅玻璃底 + 细边 */
    public static void card(GuiGraphics g, int x, int y, int w, int h, boolean hover) {
        float t = ZsAnim.tween(ZsAnim.key(K_ROW, x, y), hover ? 1 : 0, 14);
        g.fill(x, y, x + w, y + h, ZsAnim.lerpColor(0x881A1430, 0xAA3A2C6E, t));
        g.renderOutline(x, y, w, h, ZsAnim.lerpColor(0x667A62C4, BTN_BORDER_HOVER, t));
        if (t > 0.01f) shimmer(g, x, y, w, h, t);
    }

    private static void borderComet(GuiGraphics g, int x, int y, int w, int h, long period, int color) {
        borderComet(g, x, y, w, h, period, color, 0);
    }

    /** 沿矩形周长移动的彗星光点（带 14 像素拖尾） */
    private static void borderComet(GuiGraphics g, int x, int y, int w, int h, long period, int color, float offset) {
        int per = 2 * (w + h);
        float head = ((ZsAnim.phase(period) + offset) % 1f) * per;
        for (int i = 0; i < 14; i++) {
            float d = head - i;
            if (d < 0) d += per;
            int[] pt = perimeterPoint(x, y, w, h, (int) d);
            int col = ZsAnim.withAlpha(color, 1f - i / 14f);
            g.fill(pt[0], pt[1], pt[0] + 1, pt[1] + 1, col);
        }
    }

    private static int[] perimeterPoint(int x, int y, int w, int h, int d) {
        if (d < w) return new int[]{x + d, y};
        d -= w;
        if (d < h) return new int[]{x + w - 1, y + d};
        d -= h;
        if (d < w) return new int[]{x + w - 1 - d, y + h - 1};
        d -= w;
        return new int[]{x, y + h - 1 - Math.min(d, h - 1)};
    }

    /** 斜向扫光（悬停时一道高光自左向右扫过） */
    public static void shimmer(GuiGraphics g, int x, int y, int w, int h, float strength) {
        float ph = ZsAnim.phase(1600);
        int band = Math.max(6, w / 5);
        int cx = x - band + (int) ((w + band * 2) * ph);
        g.enableScissor(x, y, x + w, y + h);
        for (int i = 0; i < band; i++) {
            float a = (1 - Math.abs(i - band / 2f) / (band / 2f)) * 0.22f * strength;
            int col = ZsAnim.withAlpha(0xFFE6DCFF, a);
            int sx = cx + i;
            // 斜切：上沿比下沿偏右 h/2
            for (int yy = 0; yy < h; yy += 2) {
                int off = (h - yy) / 2;
                g.fill(sx + off, y + yy, sx + off + 1, y + Math.min(h, yy + 2), col);
            }
        }
        g.disableScissor();
    }

    /** 水平分隔线（中间亮两端渐隐，亮点缓慢游走） */
    public static void separator(GuiGraphics g, int x1, int x2, int y) {
        int w = x2 - x1;
        g.fill(x1, y, x2, y + 1, 0x338C6FE0);
        int cx = x1 + (int) (w * (0.5f + 0.35f * (float) Math.sin(ZsAnim.phase(6000) * Math.PI * 2)));
        int half = w / 4;
        for (int i = -half; i < half; i++) {
            float a = 1 - Math.abs(i) / (float) half;
            int px = cx + i;
            if (px < x1 || px >= x2) continue;
            g.fill(px, y, px + 1, y + 1, ZsAnim.withAlpha(ACCENT_LIGHT, a * 0.9f));
        }
        // 游走的四芒星
        if (cx >= x1 + 2 && cx < x2 - 2) {
            g.fill(cx - 2, y, cx + 3, y + 1, 0xFFFFFFFF);
            g.fill(cx, y - 2, cx + 1, y + 3, 0xFFFFFFFF);
            g.fill(cx - 1, y - 1, cx + 2, y + 2, ZsAnim.withAlpha(ACCENT_LIGHT, 0.8f));
        }
    }

    // ===== 按钮 =====

    /** 通用按钮：悬停时颜色平滑渐变 + 扫光，active=选项卡选中态 */
    public static void button(GuiGraphics g, Font font, int mx, int my, int x, int y, int w, int h,
                              Component label, boolean enabled, boolean active) {
        boolean hover = enabled && over(mx, my, x, y, w, h);
        float t = ZsAnim.tween(ZsAnim.key(K_BTN, x, y), hover || active ? 1 : 0, 16);
        if (!enabled) {
            g.fill(x, y, x + w, y + h, BTN_DISABLED);
            g.renderOutline(x, y, w, h, BTN_BORDER_DISABLED);
            g.drawCenteredString(font, label, x + w / 2, y + (h - 8) / 2, TEXT_DISABLED);
            return;
        }
        // 悬停外发光
        if (t > 0.01f) g.fill(x - 1, y - 1, x + w + 1, y + h + 1, ZsAnim.withAlpha(ACCENT, 0.35f * t));
        g.fill(x, y, x + w, y + h, ZsAnim.lerpColor(BTN_BG, BTN_HOVER, t));
        // 上半高光
        g.fillGradient(x + 1, y + 1, x + w - 1, y + h / 2, 0x22FFFFFF, 0x00FFFFFF);
        g.renderOutline(x, y, w, h, ZsAnim.lerpColor(BTN_BORDER, BTN_BORDER_HOVER, t));
        if (hover) shimmer(g, x, y, w, h, 1);
        int color = ZsAnim.lerpColor(TEXT_SUB, 0xFFFFFFFF, t);
        g.drawCenteredString(font, label, x + w / 2, y + (h - 8) / 2, color);
        // 悬停：左上角绽开一朵小紫花（按钮够高时）
        if (t > 0.05f && h >= 12) flower(g, x + 1, y + 1, 7 * t, ZsAnim.nowMs() / 8f % 360, t);
    }

    public static void button(GuiGraphics g, Font font, int mx, int my, int x, int y, int w, int h,
                              Component label) {
        button(g, font, mx, my, x, y, w, h, label, true, false);
    }

    /**
     * 选项卡条：选中下划线在选项卡之间平滑滑动（groupKey 区分不同选项卡组）。
     */
    public static void tabs(GuiGraphics g, Font font, int mx, int my, int[] xs, int[] ws, int y, int h,
                            Component[] labels, int selected, int groupKey) {
        for (int i = 0; i < xs.length; i++) {
            boolean sel = i == selected;
            boolean hover = over(mx, my, xs[i], y, ws[i], h);
            float t = ZsAnim.tween(ZsAnim.key(K_TAB, xs[i], y), sel ? 1 : hover ? 0.55f : 0, 14);
            g.fill(xs[i], y, xs[i] + ws[i], y + h, ZsAnim.lerpColor(0x66241C3C, 0xE04A3688, t));
            g.renderOutline(xs[i], y, ws[i], h, ZsAnim.lerpColor(0x887A62C4, BTN_BORDER_HOVER, t));
            g.drawCenteredString(font, labels[i], xs[i] + ws[i] / 2, y + (h - 8) / 2,
                    ZsAnim.lerpColor(TEXT_SUB, 0xFFFFFFFF, t));
        }
        // 滑动下划线
        float ux = ZsAnim.tween(ZsAnim.key(K_TAB, groupKey, -1), xs[selected], 18);
        float uw = ZsAnim.tween(ZsAnim.key(K_TAB, groupKey, -2), ws[selected], 18);
        int ly = y + h;
        g.fill((int) ux, ly, (int) (ux + uw), ly + 1, ACCENT_LIGHT);
        g.fill((int) ux + 2, ly + 1, (int) (ux + uw) - 2, ly + 2, ZsAnim.withAlpha(ACCENT, 0.5f));
        // 下划线中央的小紫花
        flower(g, ux + uw / 2, ly + 0.5f, 7, ZsAnim.nowMs() / 40f % 360, 1);
    }

    // ===== 行 / 格子 =====

    /** 列表行：悬停背景平滑渐变 + 左侧指示条伸出 */
    public static void row(GuiGraphics g, int x, int y, int w, int h, boolean hover, boolean alt) {
        float t = ZsAnim.tween(ZsAnim.key(K_ROW, x, y), hover ? 1 : 0, 16);
        g.fill(x, y, x + w, y + h, ZsAnim.lerpColor(alt ? ROW_BG_ALT : ROW_BG, ROW_HOVER, t));
        if (t > 0.01f) {
            int barH = (int) (h * t);
            int by = y + (h - barH) / 2;
            g.fill(x, by, x + 2, by + barH, ACCENT_LIGHT);
            g.fill(x + 2, by, x + 2 + (int) (24 * t), by + barH, ZsAnim.withAlpha(ACCENT, 0.18f * t));
        }
    }

    /** 技能格：悬停放大高亮边 + 呼吸内光（occupied=有技能） */
    public static void slot(GuiGraphics g, int mx, int my, int x, int y, int size, boolean occupied) {
        boolean hover = over(mx, my, x, y, size, size);
        float t = ZsAnim.tween(ZsAnim.key(K_SLOT, x, y), hover ? 1 : 0, 18);
        g.fill(x, y, x + size, y + size, occupied ? SLOT_BG : 0x661A1430);
        if (occupied) {
            float p = ZsAnim.pulse(2400);
            g.fillGradient(x + 1, y + 1, x + size - 1, y + size - 1,
                    ZsAnim.withAlpha(ACCENT, 0.10f + 0.08f * p), 0x00000000);
        }
        g.renderOutline(x, y, size, size, ZsAnim.lerpColor(SLOT_BORDER, SLOT_HOVER_BORDER, t));
        if (t > 0.01f) g.renderOutline(x - 1, y - 1, size + 2, size + 2, ZsAnim.withAlpha(ACCENT_LIGHT, 0.5f * t));
    }

    /** 冷却扫描遮罩：自顶向下逐渐退去的暗层 + 分界亮线 */
    public static void cooldown(GuiGraphics g, int x, int y, int size, float remainFrac) {
        if (remainFrac <= 0) return;
        int h = (int) Math.ceil(size * ZsAnim.clamp01(remainFrac));
        g.fill(x, y + size - h, x + size, y + size, 0xA0000000);
        g.fill(x, y + size - h, x + size, y + size - h + 1, ZsAnim.withAlpha(ACCENT_LIGHT, 0.8f));
    }

    // ===== 点数 / 进度 =====

    /**
     * 点数圆点条：saved 个已保存点（蓝，满级金色，呼吸微光），saved~current 为待确认点（绿，闪烁）。
     */
    public static int dots(GuiGraphics g, int x, int y, int size, int gap, int max, int saved, int current) {
        float blink = 0.55f + 0.45f * ZsAnim.pulse(900);
        float breathe = ZsAnim.pulse(2600);
        for (int d = 0; d < max; d++) {
            int dx = x + d * (size + gap);
            if (d < current) {
                int color;
                if (d >= saved) color = ZsAnim.withAlpha(DOT_PENDING, blink);
                else color = d == max - 1 ? DOT_FULL_5 : DOT_FULL;
                g.fill(dx, y, dx + size, y + size, color);
                if (d < saved) {
                    g.fill(dx - 1, y - 1, dx + size + 1, y + size + 1,
                            ZsAnim.withAlpha(color, 0.15f + 0.15f * breathe));
                }
            } else {
                g.fill(dx, y, dx + size, y + size, DOT_EMPTY);
                g.renderOutline(dx, y, size, size, DOT_BORDER);
            }
        }
        return x + max * (size + gap);
    }

    /**
     * 动态进度条：数值平滑滚动 + 能量流光帧动画叠层 + 前沿亮线（vertical=竖向自下而上）。
     * key 用于平滑记忆（同一条传同一 key）。返回平滑后的比例。
     */
    public static float flowBar(GuiGraphics g, long key, int x, int y, int w, int h, float frac, int color,
                                boolean vertical) {
        float f = ZsAnim.tween(ZsAnim.key(K_BAR, (int) key, (int) (key >> 32)), ZsAnim.clamp01(frac), 8);
        g.fill(x, y, x + w, y + h, 0xCC0E0A18);
        int len = (int) Math.round((vertical ? h : w) * f);
        if (len > 0) {
            int fx = x, fy = y, fw = w, fh = h;
            if (vertical) {
                fy = y + h - len;
                fh = len;
            } else {
                fw = len;
            }
            int base = 0xFF000000 | color & 0xFFFFFF;
            int dark = ZsAnim.lerpColor(base, 0xFF000000, 0.45f);
            if (vertical) g.fillGradient(fx, fy, fx + fw, fy + fh, base, dark);
            else g.fillGradient(fx, fy, fx + fw, fy + fh, base, dark);
            // 流光叠层（按条尺寸平铺裁切）
            ZsAnim.Sprite fl = ZsAnim.ENERGY_FLOW;
            int tw = fl.frameW(), th = fl.frameH();
            for (int ty = fy; ty < fy + fh; ty += th) {
                for (int tx = fx; tx < fx + fw; tx += tw) {
                    int cw = Math.min(tw, fx + fw - tx), ch = Math.min(th, fy + fh - ty);
                    fl.draw(g, tx, ty, cw, ch, 0, 0, cw, ch, 0xAAFFFFFF);
                }
            }
            // 前沿亮线
            float p = ZsAnim.pulse(1200);
            int edge = ZsAnim.withAlpha(0xFFFFFFFF, 0.5f + 0.5f * p);
            if (vertical) g.fill(fx, fy, fx + fw, fy + 1, edge);
            else g.fill(fx + fw - 1, fy, fx + fw, fy + fh, edge);
        }
        return f;
    }

    public static void click(float pitch) {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch));
    }

    /** 带竖直偏移与透明度的文字（内容淡入 / 上浮动画用） */
    public static void fadeText(GuiGraphics g, Font font, Component text, int x, int y, int color, float alpha) {
        if (alpha <= 0.02f) return;
        g.drawString(font, text, x, y, ZsAnim.withAlpha(color, alpha), true);
    }

    /** 竖直滚动条（滑块位置平滑） */
    public static void scrollbar(GuiGraphics g, int x, int top, int bottom, int content, int scroll, int maxScroll) {
        if (maxScroll <= 0 || content <= 0) return;
        int trackH = bottom - top;
        g.fill(x, top, x + 3, bottom, 0x332A2046);
        int thumbH = Math.max(10, trackH * trackH / Math.max(trackH, content));
        float s = ZsAnim.tween(ZsAnim.key(K_BAR, x, top), scroll, 20);
        int thumbY = top + (int) ((trackH - thumbH) * s / maxScroll);
        g.fill(x, thumbY, x + 3, thumbY + thumbH, PANEL_BORDER);
        g.fill(x, thumbY, x + 1, thumbY + thumbH, ACCENT_LIGHT);
    }
}
