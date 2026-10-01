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

    /**
     * 两柄剑：A 栏 = 誓约胜利之剑（金蓝护手、银白剑身、金色镶纹、风王结界风痕），
     * B 栏 = 乖离剑（三段黑色圆柱反向旋转、赤色刻纹、段间金环、剑尖赤色漩涡）。
     * 贴图由 tools/gen_holy_swords.py 生成，槽位布局与本类常量一致。
     */
    public enum Sword {
        // 字母位置：Excalibur = 护手下蓝色饰板中央；Ea = 护手蓝圆（与 tools/gen_holy_swords.py 一致）
        EXCALIBUR("excalibur", 0xFF1C3E96, 0xFF6FA8FF, 0xFFFFFFFF, 0xFFFFD27A, 27, 15),
        EA("ea", 0xFF6A0A0E, 0xFFFF3A2E, 0xFFFFE0D0, 0xFFFF5A3C, 22, 9);

        final ResourceLocation bar;
        final ZsAnim.Sprite glow;
        /** 护手宝石：暗色 / 亮色（活跃呼吸在两者间），字母颜色，外焰色 */
        final int gemDark, gemLight, letter, aura;

        /** 栏位字母中心（逻辑坐标） */
        public final int gemX, gemY;

        Sword(String id, int gemDark, int gemLight, int letter, int aura, int gemX, int gemY) {
            this.gemX = gemX;
            this.gemY = gemY;
            this.bar = ResourceLocation.fromNamespaceAndPath("zhushenspace", "textures/gui/anim/" + id + "_bar.png");
            this.glow = new ZsAnim.Sprite(
                    ResourceLocation.fromNamespaceAndPath("zhushenspace", "textures/gui/anim/" + id + "_glow.png"),
                    960, 120, 20, 70);
            this.gemDark = gemDark;
            this.gemLight = gemLight;
            this.letter = letter;
            this.aura = aura;
        }

        public int aura() { return aura; }
        public int letterColor() { return letter; }
        /** 光效内焰：誓约胜利之剑为蓝白，乖离剑为赤白 */
        public int core() { return this == EXCALIBUR ? 0xFFCFE6FF : 0xFFFFB49A; }
        /** 宝具真名（按语言显示） */
        public net.minecraft.network.chat.Component trueName() {
            return net.minecraft.network.chat.Component.translatable("blade.zhushenspace." + (this == EXCALIBUR ? "excalibur" : "ea"));
        }
        /** 真名下方拉开字距的罗马字 */
        public String romanName() {
            return net.minecraft.network.chat.Component.translatable("blade.zhushenspace." + (this == EXCALIBUR ? "excalibur" : "ea") + ".roman").getString();
        }

        /** 栏位 → 剑：A（0）誓约胜利之剑，B（1）乖离剑 */
        public static Sword ofBar(int bar) {
            return bar == 0 ? EXCALIBUR : EA;
        }
    }

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
     * 绘制剑栏本体 + 流光。active=当前使用的栏（宝石亮起、剑光全开、外焰），letter=护手宝石上的栏位字母。
     */
    public static void draw(GuiGraphics g, Font font, int x, int y, float scale, Sword sword, String letter,
                            boolean active) {
        int w = Math.round(W * scale), h = Math.round(H * scale);
        float p = ZsAnim.pulse(1600);
        // 底部投影 + 活跃栏外焰（誓约胜利之剑金光 / 乖离剑赤光）
        g.fill(x + 2, y + h - 2, x + w - 6, y + h + 1, 0x66000000);
        if (active) {
            int glow = ZsAnim.withAlpha(sword.aura, 0.12f + 0.18f * p);
            g.fill(x + Math.round(30 * scale), y - 2, x + w - Math.round(10 * scale), y + h + 2, glow);
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(sword.bar, x, y, w, h, 0, 0, 960, 120, 960, 120);
        RenderSystem.disableBlend();
        sword.glow.draw(g, x, y, w, h, active ? 0xFFFFFFFF : 0x55FFFFFF);

        // 栏位字母：刻在饰板 / 蓝圆上（贴图自带底座），活跃时在字母色与宝石亮色间呼吸
        float gx = x + (sword.gemX + 0.5f) * scale, gy = y + sword.gemY * scale;
        float ls = 0.75f * scale;
        int lc = active ? ZsAnim.lerpColor(sword.letter, sword.gemLight, p * 0.5f) : IRON_SUB;
        g.pose().pushPose();
        g.pose().translate(gx, gy, 0);
        g.pose().scale(ls, ls, 1);
        // 暗色描边：金 / 蓝底上都清晰可读
        int lx = -font.width(letter) / 2;
        int outline = active ? 0xFF0A0E24 : 0xFF000000;
        for (int[] o : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
            g.drawString(font, letter, lx + o[0], -4 + o[1], outline, false);
        }
        g.drawString(font, letter, lx, -4, lc, false);
        g.pose().popPose();
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

    // ===== 面板外框（战斗预设页：锻铁 + 余烬，与剑冢背景统一） =====

    public static final int CHROME_BG = 0xF2140A07;
    public static final int BRONZE = 0xFF7A5634;
    public static final int IRON_BTN = 0xFF2A1A12;
    public static final int IRON_BTN_HOVER = 0xFF4A2414;

    /**
     * 锻铁面板：深铁底 + 铜边（边缘余烬呼吸）+ 四角铆钉 + 顶沿炽热细线 + 沿边游走的火星。
     */
    public static void chrome(GuiGraphics g, int x, int y, int w, int h) {
        float p = ZsAnim.pulse(2600);
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, ZsAnim.withAlpha(EMBER, 0.08f + 0.12f * p));
        g.fill(x, y, x + w, y + h, CHROME_BG);
        g.renderOutline(x, y, w, h, BRONZE);
        g.renderOutline(x + 1, y + 1, w - 2, h - 2, 0x663A2418);
        g.fillGradient(x + 1, y + 1, x + w - 1, y + 3, ZsAnim.withAlpha(EMBER, 0.55f + 0.35f * p), 0x00FF9A3C);
        // 四角铆钉
        for (int[] c : new int[][]{{x + 3, y + 3}, {x + w - 5, y + 3}, {x + 3, y + h - 5}, {x + w - 5, y + h - 5}}) {
            g.fill(c[0], c[1], c[0] + 2, c[1] + 2, 0xFFB08050);
            g.fill(c[0] + 1, c[1] + 1, c[0] + 2, c[1] + 2, 0xFF3A2418);
        }
        // 沿底边游走的火星（带拖尾）
        float ph = ZsAnim.phase(4200);
        int head = x + (int) (w * ph);
        for (int i = 0; i < 18; i++) {
            int px = head - i;
            if (px < x || px >= x + w) continue;
            g.fill(px, y + h - 1, px + 1, y + h, ZsAnim.withAlpha(EMBER_HOT, 1 - i / 18f));
        }
    }

    /**
     * 选项卡：未选中深铁 + 铜字，悬停转暖，选中为余烬渐变 + 亮字；炽焰下划线在选项卡间平滑滑动。
     */
    public static void tabs(GuiGraphics g, Font font, int mx, int my, int[] xs, int[] ws, int y, int h,
                            net.minecraft.network.chat.Component[] labels, int selected) {
        for (int i = 0; i < xs.length; i++) {
            boolean sel = i == selected;
            boolean hover = ZsTheme.over(mx, my, xs[i], y, ws[i], h);
            float t = ZsAnim.tween(ZsAnim.key(41, xs[i], y), sel ? 1 : 0, 16);
            float hv = ZsAnim.tween(ZsAnim.key(42, xs[i], y), hover && !sel ? 1 : 0, 18);
            int top = ZsAnim.lerpColor(ZsAnim.lerpColor(IRON_BTN, IRON_BTN_HOVER, hv), 0xFF7A3414, t);
            int bot = ZsAnim.lerpColor(ZsAnim.lerpColor(0xFF180E0A, 0xFF2A1008, hv), 0xFF3A140A, t);
            g.fillGradient(xs[i], y, xs[i] + ws[i], y + h, top, bot);
            g.renderOutline(xs[i], y, ws[i], h, ZsAnim.lerpColor(0xFF4A3020, EMBER, Math.max(t, hv * 0.6f)));
            int tc = ZsAnim.lerpColor(ZsAnim.lerpColor(IRON_SUB, IRON_TEXT, hv), 0xFFFFF4D8, t);
            g.drawCenteredString(font, labels[i], xs[i] + ws[i] / 2, y + (h - 8) / 2, tc);
        }
        float ux = ZsAnim.tween(ZsAnim.key(43, 0, y), xs[selected], 18);
        float uw = ZsAnim.tween(ZsAnim.key(43, 1, y), ws[selected], 18);
        float p = ZsAnim.pulse(1200);
        g.fill((int) ux, y + h, (int) (ux + uw), y + h + 1, ZsAnim.lerpColor(EMBER, EMBER_HOT, p));
        g.fill((int) ux + 2, y + h + 1, (int) (ux + uw) - 2, y + h + 2, ZsAnim.withAlpha(EMBER, 0.45f));
    }

    /** 锻铁小按钮：深铁底铜边，悬停时烧红 + 火光扫过 */
    public static void button(GuiGraphics g, Font font, int mx, int my, int x, int y, int w, int h,
                              net.minecraft.network.chat.Component label) {
        boolean hover = ZsTheme.over(mx, my, x, y, w, h);
        float t = ZsAnim.tween(ZsAnim.key(44, x, y), hover ? 1 : 0, 18);
        g.fillGradient(x, y, x + w, y + h, ZsAnim.lerpColor(IRON_BTN, IRON_BTN_HOVER, t),
                ZsAnim.lerpColor(0xFF180E0A, 0xFF2A1008, t));
        g.renderOutline(x, y, w, h, ZsAnim.lerpColor(BRONZE, EMBER, t));
        if (t > 0.05f) emberSweep(g, x, y, w, h, t);
        g.drawCenteredString(font, label, x + w / 2, y + (h - 8) / 2, ZsAnim.lerpColor(IRON_SUB, 0xFFFFF4D8, t));
    }
}
