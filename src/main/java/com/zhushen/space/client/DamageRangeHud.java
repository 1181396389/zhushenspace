package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.screen.ZsAnim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 战斗模式伤害区间 HUD：屏幕上方显示当前手持物的伤害浮动区间「下限 ~ 上限」（面板的 20% ~ 100%），
 * <ul>
 *   <li>RGB 流动彩虹字（逐字色相偏移并随时间流动）+ 流动彩虹下划线</li>
 *   <li>热更新：切换武器、加点、开启 / 结束能力、冲锋等导致面板变化时，数字平滑滚动并弹跳闪白</li>
 *   <li>位置与缩放可在「主神空间界面设置」中拖拽 / 滚轮调整</li>
 * </ul>
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class DamageRangeHud {

    public static final float MIN_SCALE = 0.5f;
    public static final float MAX_SCALE = 2.0f;

    /** 数字放大倍率 */
    private static final float NUM_SCALE = 1.6f;
    /** 标签 / 后缀缩放 */
    private static final float SUB_SCALE = 0.75f;
    private static final int GAP = 4;
    /** 未缩放高度：大号数字 + 下划线 */
    private static final int H = 18;

    /** 数值变化检测（驱动弹跳闪光） */
    private static int lastMin = Integer.MIN_VALUE, lastMax = Integer.MIN_VALUE;
    private static long changedAt;

    private DamageRangeHud() {
    }

    // ===== 内容 =====

    private record Content(String label, int min, int max, String suffix) {
    }

    private static Content content(boolean demo) {
        if (!ClientDamagePanel.hasData() || demo && ClientDamagePanel.max() <= 0) {
            // 示例：面板 20 → 4 ~ 20
            return new Content(Component.translatable("hud.zhushenspace.damage.melee").getString(), 4, 20, "");
        }
        boolean gun = ClientDamagePanel.kind() == ClientDamagePanel.KIND_GUN;
        String label = Component.translatable(gun ? "hud.zhushenspace.damage.gun" : "hud.zhushenspace.damage.melee").getString();
        String suffix = gun && ClientDamagePanel.pellets() > 1
                ? Component.translatable("hud.zhushenspace.damage.pellets", ClientDamagePanel.pellets()).getString() : "";
        return new Content(label, ClientDamagePanel.min(), ClientDamagePanel.max(), suffix);
    }

    /** 未缩放宽度 */
    private static float width(Font font, Content c) {
        float w = font.width(c.label) * SUB_SCALE + GAP
                + (font.width(String.valueOf(c.min)) + font.width(String.valueOf(c.max))) * NUM_SCALE
                + font.width(" ~ ");
        if (!c.suffix.isEmpty()) w += GAP + font.width(c.suffix) * SUB_SCALE;
        return w;
    }

    /** HUD 位置与尺寸 {x, y, w, h}（默认屏幕上方居中，并夹紧在屏幕内） */
    public static float[] layout(Font font, int screenW, int screenH, float scale, boolean demo) {
        ClientUiConfig.Data cfg = ClientUiConfig.get();
        Content c = content(demo);
        float w = width(font, c) * scale;
        float h = H * scale;
        float x = cfg.damageX < 0 ? (screenW - w) / 2f : Mth.clamp(cfg.damageX, 0, Math.max(0, screenW - w));
        float y = cfg.damageY < 0 ? 4 : Mth.clamp(cfg.damageY, 0, Math.max(0, screenH - h));
        return new float[]{x, y, w, h};
    }

    // ===== HUD 事件 =====

    @SubscribeEvent
    public static void onHudRender(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        if (!CombatModeClient.combatMode() || !ClientDamagePanel.hasData()) return;
        GuiGraphics g = event.getGuiGraphics();
        float open = ZsAnim.easeOutCubic((ZsAnim.nowMs() - CombatModeClient.combatSince()) / 350f);
        render(g, mc.font, g.guiWidth(), g.guiHeight(), open, false);
    }

    /** 界面设置预览（无数据时显示示例 4 ~ 20） */
    public static void renderPreview(GuiGraphics g, Font font, int screenW, int screenH) {
        render(g, font, screenW, screenH, 1f, true);
    }

    // ===== 绘制 =====

    private static void render(GuiGraphics g, Font font, int screenW, int screenH, float open, boolean demo) {
        if (open <= 0.03f) return; // 过低的 alpha 会被字体渲染当作不透明
        float scale = ClientUiConfig.get().damageScale;
        Content c = content(demo);
        float[] pos = layout(font, screenW, screenH, scale, demo);
        long now = ZsAnim.nowMs();

        // 热更新：数值变化 → 弹跳 + 闪白；数字平滑滚动到新值
        if (lastMax == Integer.MIN_VALUE) {
            lastMin = c.min;
            lastMax = c.max;
        } else if (c.min != lastMin || c.max != lastMax) {
            lastMin = c.min;
            lastMax = c.max;
            changedAt = now;
        }
        float since = (now - changedAt) / 1000f;
        float flash = 1 - ZsAnim.clamp01(since / 0.45f);
        float bounce = 1 + 0.18f * flash * (float) Math.abs(Math.sin(since * 14));
        int shownMin = Math.round(ZsAnim.tween(ZsAnim.key(91, 1, 0), c.min, 14f));
        int shownMax = Math.round(ZsAnim.tween(ZsAnim.key(91, 2, 0), c.max, 14f));
        if (demo) {
            shownMin = c.min;
            shownMax = c.max;
        }

        float alpha = ZsAnim.clamp01(open);
        g.pose().pushPose();
        g.pose().translate(pos[0], pos[1] - (1 - open) * 8, 0);
        g.pose().scale(scale, scale, 1f);

        float hueBase = ZsAnim.phase(2400); // 彩虹整体流动
        float x = 0;
        int numTop = 0;
        int subTop = Math.round(9 * NUM_SCALE - 9 * SUB_SCALE) - 1;

        // 标签（小号）
        x = drawRainbow(g, font, c.label, x, subTop, SUB_SCALE, hueBase, 0f, alpha, 0.55f);
        x += GAP;
        // 下限 ~ 上限（大号，变化时弹跳）
        float numStart = x;
        x = drawNumber(g, font, String.valueOf(shownMin), x, numTop, bounce, hueBase, 0.15f, alpha, flash);
        x = drawRainbow(g, font, " ~ ", x, Math.round(9 * NUM_SCALE - 9) - 1, 1f, hueBase, 0.3f, alpha, 0.35f);
        x = drawNumber(g, font, String.valueOf(shownMax), x, numTop, bounce, hueBase, 0.45f, alpha, flash);
        float numEnd = x;
        // 弹丸数（霰弹枪）
        if (!c.suffix.isEmpty()) {
            x += GAP;
            x = drawRainbow(g, font, c.suffix, x, subTop, SUB_SCALE, hueBase, 0.6f, alpha, 0.55f);
        }

        // 流动彩虹下划线（数字下方）
        int lineY = Math.round(9 * NUM_SCALE) + 1;
        int x0 = Math.round(numStart), x1 = Math.round(numEnd);
        for (int px = x0; px < x1; px += 2) {
            float hue = hueBase + (px - x0) / 90f;
            int col = rgb(hue, 0.8f, 1f);
            g.fill(px, lineY, Math.min(px + 2, x1), lineY + 1, ZsAnim.withAlpha(0xFF000000 | col, 0.85f * alpha));
            g.fill(px, lineY + 1, Math.min(px + 2, x1), lineY + 2, ZsAnim.withAlpha(0xFF000000 | col, 0.25f * alpha));
        }
        g.pose().popPose();
    }

    /** 逐字彩虹文字；返回绘制后的 x（未缩放坐标） */
    private static float drawRainbow(GuiGraphics g, Font font, String text, float x, int y, float s,
                                     float hueBase, float hueOffset, float alpha, float saturation) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(s, s, 1f);
        int cx = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            float hue = hueBase + hueOffset + cx / 60f;
            int col = 0xFF000000 | rgb(hue, saturation, 1f);
            g.drawString(font, ch, cx, 0, ZsAnim.withAlpha(col, alpha), true);
            cx += font.width(ch);
            i += Character.charCount(cp);
        }
        g.pose().popPose();
        return x + cx * s;
    }

    /** 大号数字：以自身中心弹跳缩放，变化瞬间向白色闪光 */
    private static float drawNumber(GuiGraphics g, Font font, String text, float x, int y, float bounce,
                                    float hueBase, float hueOffset, float alpha, float flash) {
        float w = font.width(text) * NUM_SCALE;
        g.pose().pushPose();
        g.pose().translate(x + w / 2f, y + 9 * NUM_SCALE / 2f, 0);
        g.pose().scale(NUM_SCALE * bounce, NUM_SCALE * bounce, 1f);
        g.pose().translate(-font.width(text) / 2f, -4.5f, 0);
        int cx = 0;
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            float hue = hueBase + hueOffset + cx / 40f;
            int col = 0xFF000000 | rgb(hue, 0.85f, 1f);
            if (flash > 0) col = ZsAnim.lerpColor(col, 0xFFFFFFFF, flash * 0.8f);
            g.drawString(font, ch, cx, 0, ZsAnim.withAlpha(col, alpha), true);
            cx += font.width(ch);
        }
        g.pose().popPose();
        return x + w;
    }

    private static int rgb(float hue, float saturation, float value) {
        hue = hue - (float) Math.floor(hue);
        return Mth.hsvToRgb(hue, saturation, value) & 0xFFFFFF;
    }
}
