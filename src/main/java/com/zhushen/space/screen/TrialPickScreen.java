package com.zhushen.space.screen;

import com.zhushen.space.client.ClientTrial;
import com.zhushen.space.data.ArtSkill;
import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.SkillType;
import com.zhushen.space.data.TrialTemplate;
import com.zhushen.space.network.TrialActionPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * 新手试炼：试用角色卡选择界面。四张卡片横排，悬停浮起发光，点击即载入（不消耗 XP，离开试炼自动还原）。
 */
public class TrialPickScreen extends Screen {

    private static final int BG_CARD = 0xF00C1522, TEXT = 0xFFEAF6FF, SUB = 0xFF8FA7B8, ACCENT = 0xFF35D0FF;
    private final long openAt = System.currentTimeMillis();
    private final float[] lift = new float[4];

    public TrialPickScreen() {
        super(Component.translatable("trial.zhushenspace.pick.title"));
    }

    private int cardW() { return Math.min(118, (width - 40) / 4 - 8); }

    private int cardH() { return Math.min(186, height - 92); }

    private int cardX(int i) {
        int w = cardW(), gap = 8, total = 4 * w + 3 * gap;
        return (width - total) / 2 + i * (w + gap);
    }

    private int cardY() { return Math.max(52, (height - cardH()) / 2 + 12); }

    private int hovered(double mx, double my) {
        for (int i = 0; i < 4; i++) {
            int x = cardX(i), y = cardY();
            if (mx >= x && mx < x + cardW() && my >= y - 6 && my < y + cardH()) return i;
        }
        return -1;
    }

    private static int alpha(int c, float a) {
        return ((int) (((c >>> 24) & 0xFF) * Mth.clamp(a, 0, 1)) << 24) | (c & 0xFFFFFF);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fillGradient(0, 0, width, height, 0xE0050A12, 0xF0081220);
        // 细网格
        for (int x = 0; x < width; x += 24) g.fill(x, 0, x + 1, height, 0x0C35D0FF);
        for (int y = 0; y < height; y += 24) g.fill(0, y, width, y + 1, 0x0C35D0FF);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        Font font = Minecraft.getInstance().font;
        long t = System.currentTimeMillis() - openAt;
        float intro = Mth.clamp(t / 350f, 0, 1);

        // 标题
        g.pose().pushPose();
        g.pose().translate(width / 2f, 14, 0);
        g.pose().scale(1.5f, 1.5f, 1);
        g.drawCenteredString(font, title, 0, 0, alpha(TEXT, intro));
        g.pose().popPose();
        g.drawCenteredString(font, Component.translatable("trial.zhushenspace.pick.sub"), width / 2, 32, alpha(SUB, intro));

        int hov = hovered(mouseX, mouseY);
        int cur = ClientTrial.template();
        for (int i = 0; i < 4; i++) {
            lift[i] = Mth.lerp(0.25f, lift[i], i == hov ? 1f : 0f);
            float ci = Mth.clamp((t - i * 70) / 320f, 0, 1);
            ci = 1 - (1 - ci) * (1 - ci) * (1 - ci);
            card(g, font, i, (int) (cardY() - lift[i] * 5 + (1 - ci) * 30), ci, lift[i], i == cur);
        }
        g.drawCenteredString(font, Component.translatable("trial.zhushenspace.pick.hint"), width / 2,
                Math.min(height - 12, cardY() + cardH() + 8), alpha(SUB, intro));
    }

    private void card(GuiGraphics g, Font font, int i, int y, float a, float hov, boolean current) {
        TrialTemplate tp = TrialTemplate.VALUES[i];
        int x = cardX(i), w = cardW(), h = cardH(), col = tp.color;
        // 光晕
        if (hov > 0.02f) for (int k = 1; k <= 4; k++)
            g.renderOutline(x - k, y - k, w + 2 * k, h + 2 * k, alpha(col, 0.22f * hov / k));
        g.fill(x, y, x + w, y + h, alpha(BG_CARD, a));
        g.fillGradient(x, y, x + w, y + 34, alpha(col & 0x00FFFFFF | 0x90000000, a), alpha(col & 0x00FFFFFF | 0x10000000, a));
        g.renderOutline(x, y, w, h, alpha(current ? 0xFFFFFFFF : col, (0.5f + 0.5f * hov) * a));
        g.fill(x, y, x + w, y + 2, alpha(col, a));

        int px = x + 6, cy = y + 6;
        g.drawString(font, Component.translatable(tp.nameKey()), px, cy, alpha(0xFFFFFFFF, a), true);
        cy += 11;
        g.drawString(font, Component.translatable(tp.roleKey()), px, cy, alpha(0xFFDDE8F0, a), false);
        cy += 15;

        // 属性条：九项 1~5
        String[] ab = Component.translatable("trial.zhushenspace.attr_short").getString().split(" ");
        int pip = Math.max(3, (w - 12 - 30) / 5 - 1);
        for (int k = 0; k < AttributeType.COUNT && cy < y + h - 60; k++) {
            String n = k < ab.length ? ab[k] : "?";
            g.drawString(font, n, px, cy, alpha(SUB, a), false);
            int v = tp.attrs[k];
            for (int s = 0; s < 5; s++) {
                int sx = px + 28 + s * (pip + 1);
                g.fill(sx, cy + 2, sx + pip, cy + 6, s < v ? alpha(col, a) : alpha(0x30FFFFFF, a));
            }
            cy += 8;
        }
        cy += 3;
        g.fill(px, cy, x + w - 6, cy + 1, alpha(0x30FFFFFF, a));
        cy += 4;
        // 能量池
        g.drawString(font, Component.translatable("trial.zhushenspace.pick.pool",
                Component.translatable("energy.zhushenspace." + tp.poolId())), px, cy, alpha(ACCENT, a), false);
        cy += 10;
        // 技能
        StringBuilder sk = new StringBuilder();
        SkillType[] st = tp.skillTypes();
        int[] sl = tp.skillLevels();
        for (int k = 0; k < st.length; k++) {
            if (k > 0) sk.append(" · ");
            sk.append(Component.translatable(st[k].nameKey()).getString()).append(sl[k]);
        }
        StringBuilder ar = new StringBuilder();
        for (int k = 0; k < tp.arts.length; k++) {
            if (k > 0) ar.append(" · ");
            ArtSkill s = tp.arts[k];
            ar.append(Component.translatable(s.ability.nameKey()).getString());
        }
        cy = lines(g, font, Component.translatable("trial.zhushenspace.pick.skills", sk.toString()), px, cy, w - 12, y + h - 4, a);
        cy = lines(g, font, Component.translatable("trial.zhushenspace.pick.arts", ar.toString()), px, cy + 2, w - 12, y + h - 4, a);
        lines(g, font, Component.translatable(tp.descKey()), px, cy + 3, w - 12, y + h - 4, a * 0.9f);
    }

    private int lines(GuiGraphics g, Font font, Component c, int x, int y, int w, int maxY, float a) {
        List<FormattedCharSequence> ls = font.split(c, w);
        for (FormattedCharSequence l : ls) {
            if (y + 8 > maxY) break;
            g.drawString(font, l, x, y, alpha(SUB, a), false);
            y += 9;
        }
        return y;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            int i = hovered(mx, my);
            if (i >= 0) {
                ClientTrial.send(TrialActionPayload.PICK, i);
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BEACON_POWER_SELECT, 1.6f, 0.6f));
                onClose();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
