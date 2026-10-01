package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.screen.ZsAnim;
import com.zhushen.space.screen.ZsShapes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * 防御 / 豁免 HUD（假面骑士 Ex-Aid 风格），取代原版护甲条：
 * <ul>
 *   <li>左：洋红斜切徽章「DEF」+ 大号描边数字 = 当前防御；全力防御时变为无敌金（MUTEKI 金）并闪烁「FULL」，
 *       措手不及 / 失去基础防御时褪色并闪烁「FLAT」；意志守御就绪时角标「+9」。</li>
 *   <li>右：银色手柄板上的四色按键 = 四项豁免（蓝 意志 / 绿 反射 / 红 强韧 / 黄 范围），按键旁为数值；
 *       无法反射时反射 / 范围按键熄灭打叉。</li>
 *   <li>数值变化时弹跳并闪色（升绿降红）；防御明显上升或开启全力防御时弹出「GUARD UP!」/「FULL GUARD!」。</li>
 * </ul>
 * 默认占据原版护甲条的位置（随血量行数自动上移，并把左侧状态条整体上推）；
 * 在「主神空间界面设置」中拖动后改为固定位置，可缩放 / 关闭（关闭后恢复原版护甲条）。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class DefenseHudRenderer {

    private DefenseHudRenderer() {}

    public static final int W = 98, H = 24;
    public static final float MIN_SCALE = 0.5f, MAX_SCALE = 2.0f;

    private static final int BADGE_W = 34, SLANT = 5;
    private static final float PX = 42;  // 豁免板左缘

    /** 面板位置：默认原版护甲条处（单行血量时），拖动后为配置位置 */
    public static float[] layout(int sw, int sh, float scale) {
        ClientUiConfig.Data cfg = ClientUiConfig.get();
        float w = W * scale, h = H * scale;
        float x, y;
        if (cfg.defX < 0 || cfg.defY < 0) {
            x = sw / 2f - 91;
            y = sh - 40 - h;
        } else {
            x = Math.max(0, Math.min(cfg.defX, Math.max(0, sw - w)));
            y = Math.max(0, Math.min(cfg.defY, Math.max(0, sh - h)));
        }
        return new float[]{x, y, w, h};
    }

    /** 取代原版护甲层：默认位置时就地绘制并上推左侧状态条 */
    @SubscribeEvent
    public static void onArmorLayer(RenderGuiLayerEvent.Pre event) {
        if (!VanillaGuiLayers.ARMOR_LEVEL.equals(event.getName())) return;
        ClientUiConfig.Data cfg = ClientUiConfig.get();
        if (!cfg.defHudEnabled) return;
        event.setCanceled(true);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || cfg.defX >= 0 && cfg.defY >= 0) return;
        GuiGraphics g = event.getGuiGraphics();
        Gui gui = mc.gui;
        float s = cfg.defScale;
        float bottom = g.guiHeight() - gui.leftHeight + 9;
        render(g, mc.font, g.guiWidth() / 2f - 91, bottom - H * s, s, false);
        gui.leftHeight += Math.round(H * s) + 1;
    }

    /** 自定义位置时在 HUD 末尾绘制（与原版状态条同条件：可受伤的游戏模式、未隐藏界面） */
    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post event) {
        ClientUiConfig.Data cfg = ClientUiConfig.get();
        if (!cfg.defHudEnabled || cfg.defX < 0 || cfg.defY < 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.gameMode == null || !mc.gameMode.canHurtPlayer()) return;
        GuiGraphics g = event.getGuiGraphics();
        float[] pos = layout(g.guiWidth(), g.guiHeight(), cfg.defScale);
        render(g, mc.font, pos[0], pos[1], cfg.defScale, false);
    }

    /** 打开聊天栏 / 物品栏时，鼠标悬停在防御 HUD 上：显示当前的减伤关键字 */
    @SubscribeEvent
    public static void onScreenRender(net.neoforged.neoforge.client.event.ScreenEvent.Render.Post event) {
        var screen = event.getScreen();
        if (!(screen instanceof net.minecraft.client.gui.screens.ChatScreen)
                && !(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)) return;
        if (System.currentTimeMillis() - lastAt > 300 || !ClientDefenseData.valid()) return;
        int mx = event.getMouseX(), my = event.getMouseY();
        if (mx < lastX || my < lastY || mx > lastX + lastW || my > lastY + lastH) return;
        Minecraft mc = Minecraft.getInstance();
        event.getGuiGraphics().renderTooltip(mc.font, DamageKeywordText.tooltip(mc.font, 220), mx, my);
    }

    /** 界面设置预览（无数据时显示示例） */
    public static void renderPreview(GuiGraphics g, Font font, int sw, int sh) {
        float s = ClientUiConfig.get().defScale;
        float[] pos = layout(sw, sh, s);
        render(g, font, pos[0], pos[1], s, !ClientDefenseData.valid());
    }

    // ===== 绘制 =====

    private static int value(int i, boolean demo) {
        if (!demo) return ClientDefenseData.value(i);
        return switch (i) {
            case ClientDefenseData.DEF -> 12;
            case ClientDefenseData.WILL -> 8;
            case ClientDefenseData.REFLEX -> 9;
            case ClientDefenseData.FORT -> 7;
            default -> 12;
        };
    }

    /** 最近一次实际绘制的范围（打开聊天栏 / 物品栏时悬停显示减伤关键字） */
    private static float lastX, lastY, lastW, lastH;
    private static long lastAt;

    private static void render(GuiGraphics g, Font font, float x, float y, float scale, boolean demo) {
        long now = System.currentTimeMillis();
        if (!demo) {
            lastX = x;
            lastY = y;
            lastW = W * scale;
            lastH = H * scale;
            lastAt = now;
        }
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);
        boolean full = !demo && ClientDefenseData.flag(ClientDefenseData.FULL);
        boolean flat = !demo && ClientDefenseData.flag(ClientDefenseData.FLAT);
        boolean noReflex = !demo && ClientDefenseData.flag(ClientDefenseData.NO_REFLEX);
        badge(g, font, now, demo, full, flat);
        saves(g, font, now, demo, noReflex);
        callout(g, font, now, demo, full);
        g.pose().popPose();
    }

    /** 数值弹跳：变化后 300ms 内放大回弹 */
    private static float bounce(int i, long now, boolean demo) {
        if (demo) return 1f;
        long t = now - ClientDefenseData.changedAt(i);
        if (t > 300) return 1f;
        return 1f + 0.4f * (1 - ZsAnim.easeOutCubic(t / 300f));
    }

    /** 变化闪色：升绿降红（450ms） */
    private static int flash(int i, long now, boolean demo, int base) {
        if (demo) return base;
        long t = now - ClientDefenseData.changedAt(i);
        if (t > 450) return base;
        int to = ClientDefenseData.value(i) > ClientDefenseData.previous(i) ? ExAidStyle.LIME : ExAidStyle.RED;
        return ZsShapes.lerp(to, base, t / 450f);
    }

    private static void badge(GuiGraphics g, Font font, long now, boolean demo, boolean full, boolean flat) {
        int top, bottom;
        if (full) {
            float sh = (float) (0.5 + 0.5 * Math.sin(now / 180.0));
            top = ZsShapes.lerp(ExAidStyle.GOLD_L, 0xFFFFFFFF, sh * 0.35f);
            bottom = ExAidStyle.GOLD_D;
        } else if (flat) {
            top = 0xFF9C8CA4;
            bottom = 0xFF4A3F55;
        } else {
            top = ExAidStyle.PINK_L;
            bottom = ExAidStyle.PINK_D;
        }
        // 投影 → 黑描边 → 本体 → 顶部高光 → 荧光绿底条
        ExAidStyle.para(g, 1.2f, 1.5f, BADGE_W + 2, H, SLANT, 0x60000000, 0x60000000);
        ExAidStyle.para(g, -1.3f, -1.2f, BADGE_W + 2.6f, H + 2.4f, SLANT + 0.4f, ExAidStyle.INK, ExAidStyle.INK);
        ExAidStyle.para(g, 0, 0, BADGE_W, H, SLANT, top, bottom);
        ExAidStyle.para(g, SLANT * 0.9f, 0.8f, BADGE_W - 3, 1.2f, 0.2f, 0x90FFFFFF, 0x30FFFFFF);
        ExAidStyle.para(g, 1.2f, H - 4.2f, BADGE_W - 4, 2f, 0.4f,
                full ? 0xFFFFFFFF : ExAidStyle.LIME, full ? ExAidStyle.GOLD : ExAidStyle.LIME_D);

        // 标签：DEF / FULL / FLAT（交替闪烁）
        String label = "DEF";
        int lc = 0xFFFFFFFF;
        if (full && (now / 600) % 2 == 0) { label = "FULL"; lc = 0xFFFFFFFF; }
        else if (flat && (now / 600) % 2 == 0) { label = "FLAT"; lc = ExAidStyle.RED; }
        ExAidStyle.pixShadow(g, label, SLANT + 3, 2.5f, lc, ExAidStyle.INK);
        // 意志守御就绪：+9 角标
        if (!demo && ClientWillpower.armedGuard()) {
            int gc = (now / 300) % 2 == 0 ? ExAidStyle.YELLOW : ExAidStyle.ORANGE;
            ExAidStyle.pixShadow(g, "+9", BADGE_W - 6, 2.5f, gc, ExAidStyle.INK);
        }

        int def = value(ClientDefenseData.DEF, demo);
        String s = String.valueOf(def);
        float sc = (s.length() >= 3 ? 1.1f : 1.5f) * bounce(ClientDefenseData.DEF, now, demo);
        int fc = flash(ClientDefenseData.DEF, now, demo, 0xFFFFFFFF);
        ExAidStyle.outlinedCentered(g, font, s, BADGE_W / 2f + 1.5f, 13.2f, sc, fc, ExAidStyle.INK);

        if (full) {
            for (int k = 0; k < 2; k++) {
                float tw = (float) Math.abs(Math.sin(now / 260.0 + k * 1.7));
                float sx = k == 0 ? 5.5f : BADGE_W + 1.5f, sy = k == 0 ? H - 6 : 5;
                ZsShapes.star(g, sx, sy, 1.2f + 2.6f * tw, 0.5f + 0.6f * tw, 4, now / 400.0,
                        0xFFFFFFFF, ZsShapes.fade(ExAidStyle.GOLD_L, 0.2f));
            }
        }
    }

    private static final int[] SAVE = {ClientDefenseData.WILL, ClientDefenseData.REFLEX, ClientDefenseData.FORT, ClientDefenseData.AREA};
    private static final int[] SAVE_COL = {ExAidStyle.BTN_BLUE, ExAidStyle.BTN_GREEN, ExAidStyle.BTN_RED, ExAidStyle.BTN_YELLOW};
    private static final String[] SAVE_KEY = {"will", "reflex", "fort", "area"};

    private static void saves(GuiGraphics g, Font font, long now, boolean demo, boolean noReflex) {
        ExAidStyle.plate(g, PX, 0.5f, W - PX - 0.5f, H - 1, 5, 0);
        // 中缝十字刻线
        ExAidStyle.rect(g, PX + 2, H / 2f - 0.25f, W - 3, H / 2f + 0.25f, 0x40000000);
        ExAidStyle.rect(g, PX + (W - PX) / 2f - 0.25f, 2.5f, PX + (W - PX) / 2f + 0.25f, H - 2.5f, 0x40000000);
        for (int k = 0; k < 4; k++) {
            float cx0 = PX + 1.5f + (k % 2) * 27.5f, cy0 = 1.2f + (k / 2) * 11f;
            int idx = SAVE[k];
            boolean off = noReflex && (idx == ClientDefenseData.REFLEX || idx == ClientDefenseData.AREA);
            float bx = cx0 + 6.2f, by = cy0 + 5.6f;
            long ct = demo ? 9999 : now - ClientDefenseData.changedAt(idx);
            ExAidStyle.button(g, bx, by, 4.4f, SAVE_COL[k], 1f, ct < 140, off);
            if (!off) {
                String ch = Component.translatable("hud.zhushenspace.def." + SAVE_KEY[k]).getString();
                g.pose().pushPose();
                g.pose().translate(bx, by, 0);
                g.pose().scale(0.62f, 0.62f, 1);
                ExAidStyle.outlined(g, font, ch, -font.width(ch) / 2f + 0.5f, -3.5f, 0xFFFFFFFF,
                        ExAidStyle.darken(SAVE_COL[k], 0.6f));
                g.pose().popPose();
            }
            String num = off ? "-" : String.valueOf(value(idx, demo));
            float sc = bounce(idx, now, demo);
            int fc = off ? 0xFF9AA0AA : flash(idx, now, demo, 0xFFFFFFFF);
            g.pose().pushPose();
            g.pose().translate(cx0 + 13f, cy0 + 5.6f, 0);
            g.pose().scale(sc, sc, 1);
            ExAidStyle.outlined(g, font, num, 0, -3.5f, fc, ExAidStyle.INK);
            g.pose().popPose();
        }
    }

    /** 「GUARD UP!」/「FULL GUARD!」弹出字（防御 +3 以上或开启全力防御） */
    private static void callout(GuiGraphics g, Font font, long now, boolean demo, boolean full) {
        if (demo) return;
        String text = null;
        long t = 0;
        int fill = ExAidStyle.LIME, burst = ExAidStyle.PINK;
        long ft = now - ClientDefenseData.flagsAt();
        long dt = now - ClientDefenseData.changedAt(ClientDefenseData.DEF);
        if (full && ft < 900) {
            text = "FULL GUARD!";
            t = ft;
            fill = ExAidStyle.GOLD_L;
            burst = ExAidStyle.PINK;
        } else if (dt < 900 && ClientDefenseData.value(ClientDefenseData.DEF) >= ClientDefenseData.previous(ClientDefenseData.DEF) + 3) {
            text = "GUARD UP!";
            t = dt;
            fill = ExAidStyle.LIME;
            burst = ExAidStyle.BTN_BLUE;
        }
        if (text == null) return;
        float sc = 0.35f + 0.35f * ZsAnim.easeOutBack(ZsAnim.clamp01(t / 160f));
        float a = 1 - ZsAnim.clamp01((t - 650) / 250f);
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        ExAidStyle.comic(g, font, text, BADGE_W / 2f + 4, -5 - t / 900f * 5f, sc, -7f, fill, ExAidStyle.INK, burst, a, t / 400.0);
        g.pose().popPose();
    }
}
