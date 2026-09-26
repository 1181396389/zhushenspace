package com.zhushen.space.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * 设置界面上趴着的 Q 版魔女大黑塔：双臂搭在底板上沿、长发垂下，轻微呼吸；帽上偶尔飘落花瓣。
 * 悬停提示「戳一下」，点击后弹跳一下并随机说一句话（气泡 3 秒）。
 * 贴图：textures/gui/herta_chibi.png（227x256，手臂支撑线 y≈188），由 tools/gen_herta_textures.py 抠图生成。
 */
public final class HertaChibi {
    private HertaChibi() {}

    private static final ResourceLocation TEX =
            ResourceLocation.fromNamespaceAndPath("zhushenspace", "textures/gui/herta_chibi.png");
    private static final int TW = 227, TH = 256, ARM_Y = 188;
    /** 显示高度（GUI 像素） */
    private static final int DH = 72;
    private static final int QUOTES = 6;

    private static int boxX, boxY, boxW, boxH;
    private static long pokedAt = -1;
    private static int quote;

    /** anchorX = 左缘，surfaceY = 她趴着的平面（底板上沿） */
    public static void render(GuiGraphics g, Font font, int anchorX, int surfaceY, int mx, int my) {
        float s = DH / (float) TH;
        int dw = Math.round(TW * s);
        long now = ZsAnim.nowMs();
        float since = pokedAt < 0 ? 1e9f : now - pokedAt;
        // 被戳：弹一下（衰减）；平时：缓慢呼吸
        float hop = since < 600 ? (float) (Math.abs(Math.sin(since / 600.0 * Math.PI * 2)) * 5 * (1 - since / 600f)) : 0;
        float breathe = 1 + 0.012f * (float) Math.sin(now / 700.0);
        float top = surfaceY - ARM_Y * s - hop;
        boxX = anchorX;
        boxY = (int) top;
        boxW = dw;
        boxH = DH;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.pose().pushPose();
        // 以手臂支撑线为基准缩放：身体起伏，手臂始终搭在底板上
        g.pose().translate(anchorX, surfaceY - hop, 0);
        g.pose().scale(s, s * breathe, 1);
        g.blit(TEX, 0, -ARM_Y, 0, 0, TW, TH, TW, TH);
        g.pose().popPose();
        RenderSystem.disableBlend();

        // 帽檐飘落的花瓣
        ZsTheme.petals(g, anchorX - 4, (int) top + 8, dw + 8, DH - 8, 3);

        boolean hover = ZsTheme.over(mx, my, boxX, boxY, boxW, boxH);
        if (since < 3000) {
            bubble(g, font, Component.translatable("screen.zhushenspace.herta.quote." + quote),
                    anchorX + dw / 2, boxY - 2, 1 - Math.max(0, (since - 2600) / 400f));
        } else if (hover) {
            bubble(g, font, Component.translatable("screen.zhushenspace.herta.poke"), anchorX + dw / 2, boxY - 2, 1);
        }
    }

    /** 点击她：返回是否命中 */
    public static boolean click(double mx, double my) {
        if (!ZsTheme.over(mx, my, boxX, boxY, boxW, boxH)) return false;
        int next = (int) (Math.random() * (QUOTES - 1));
        quote = next >= quote ? next + 1 : next;   // 不连续重复同一句
        pokedAt = ZsAnim.nowMs();
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, 1.4f + (float) Math.random() * 0.3f, 0.8f));
        return true;
    }

    /** 对话气泡：墨紫底 + 薰衣草边 + 下方小尖角，尖角指向 (cx, bottom) */
    private static void bubble(GuiGraphics g, Font font, Component text, int cx, int bottom, float alpha) {
        if (alpha <= 0.02f) return;
        List<FormattedCharSequence> lines = font.split(text, 150);
        int w = 0;
        for (FormattedCharSequence l : lines) w = Math.max(w, font.width(l));
        int bw = w + 10, bh = lines.size() * 10 + 6;
        int x = Math.max(4, Math.min(cx - bw / 2, g.guiWidth() - bw - 4)), y = bottom - bh - 4;
        int edge = ZsAnim.withAlpha(ZsTheme.ACCENT_LIGHT, alpha);
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        g.fill(x, y, x + bw, y + bh, ZsAnim.withAlpha(0xFF120E1C, 0.92f * alpha));
        g.renderOutline(x, y, bw, bh, edge);
        for (int i = 0; i < 3; i++) g.fill(cx - 2 + i, y + bh + i, cx + 3 - i, y + bh + i + 1, edge);
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(font, lines.get(i), x + 5, y + 4 + i * 10, ZsAnim.withAlpha(ZsTheme.TEXT_MAIN, alpha), false);
        }
        ZsTheme.flower(g, x + bw - 1, y, 7, ZsAnim.nowMs() / 30f % 360, alpha);
        g.pose().popPose();
    }
}
