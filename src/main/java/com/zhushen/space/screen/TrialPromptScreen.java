package com.zhushen.space.screen;

import com.zhushen.space.client.ClientTrial;
import com.zhushen.space.network.TrialActionPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 首次使用邀请函后（碎屏之后）的询问：是否进入新手试炼。
 * 沿用邀请函的复古荧光屏风格：打字机输出 → [进入试炼] / [跳过]。跳过后直接打开主神面板。
 */
public class TrialPromptScreen extends Screen {

    private static final int PH = 0xFF3CFF78, PH_DIM = 0xFF12602C, PH_BG0 = 0xFF04140A, PH_BG1 = 0xFF010603;
    private static final long CHAR_MS = 28;

    private final long openAt = System.currentTimeMillis();
    private String text = "";
    private int chars;
    private long lastChar;

    public TrialPromptScreen() {
        super(Component.translatable("trial.zhushenspace.prompt.title"));
    }

    @Override
    protected void init() {
        super.init();
        text = Component.translatable("trial.zhushenspace.prompt.text").getString();
    }

    private boolean ready() { return chars >= text.length(); }

    private int btnW() { return 120; }

    private int btnH() { return 24; }

    private int btnY() { return height / 2 + 46; }

    private int enterX() { return width / 2 - btnW() - 12; }

    private int skipX() { return width / 2 + 12; }

    private static boolean over(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int alpha(int c, float a) {
        return ((int) (((c >>> 24) & 0xFF) * Math.max(0, Math.min(1, a))) << 24) | (c & 0xFFFFFF);
    }

    @Override
    public void tick() {
        super.tick();
        long n = System.currentTimeMillis();
        if (n - openAt < 250) return;
        while (chars < text.length() && n - lastChar >= CHAR_MS) {
            chars = Math.min(text.length(), chars + 2);
            lastChar = n;
            if (chars % 4 == 0) Minecraft.getInstance().getSoundManager()
                    .play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), 1.8f, 0.1f));
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fillGradient(0, 0, width, height, PH_BG0, PH_BG1);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        Font font = Minecraft.getInstance().font;
        long n = System.currentTimeMillis();
        float flick = 0.94f + 0.06f * (float) Math.sin(n / 37.0);

        g.drawString(font, "ZHUSHEN-OS  TRIAL.EXE   " + (n / 500 % 2 == 0 ? "■" : " "), 18, 16, alpha(PH, 0.7f * flick), false);
        g.fill(18, 28, width - 18, 29, alpha(PH_DIM, 0.9f));

        // 正文：按 \n 分段，逐行换行，居中
        String shown = text.substring(0, Math.min(chars, text.length()));
        List<String> lines = new ArrayList<>();
        int maxW = Math.min(360, width - 60);
        for (String para : shown.split("\n", -1)) {
            StringBuilder cur = new StringBuilder();
            for (char c : para.toCharArray()) {
                if (font.width(cur.toString() + c) > maxW && cur.length() > 0) { lines.add(cur.toString()); cur = new StringBuilder(); }
                cur.append(c);
            }
            lines.add(cur.toString());
        }
        int y0 = height / 2 - 34 - lines.size() * 6;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            boolean first = i == 0;
            int x = width / 2 - maxW / 2;
            if (first) {
                g.pose().pushPose();
                g.pose().translate(x, y0, 0);
                g.pose().scale(1.5f, 1.5f, 1);
                g.drawString(font, l, 1, 0, alpha(PH, 0.25f), false);
                g.drawString(font, l, 0, 0, alpha(PH, flick), false);
                g.pose().popPose();
                y0 += 18;
            } else {
                g.drawString(font, l, x, y0, alpha(PH, 0.85f * flick), false);
                y0 += 11;
            }
            if (i == lines.size() - 1 && !ready() && (n / 300) % 2 == 0)
                g.drawString(font, "█", x + font.width(l) * (first ? 2 : 1), y0 - (first ? 18 : 11), alpha(PH, flick), false);
        }

        if (ready()) {
            button(g, font, mouseX, mouseY, enterX(), Component.translatable("trial.zhushenspace.prompt.enter").getString());
            button(g, font, mouseX, mouseY, skipX(), Component.translatable("trial.zhushenspace.prompt.skip").getString());
            g.drawCenteredString(font, Component.translatable("trial.zhushenspace.prompt.foot"), width / 2,
                    btnY() + btnH() + 12, alpha(PH_DIM | 0xFF000000, 1f));
        }
        // 扫描线 + 暗角
        for (int y = 0; y < height; y += 2) g.fill(0, y, width, y + 1, 0x30000000);
        int v = Math.min(width, height) / 5;
        g.fillGradient(0, 0, width, v, 0x99000000, 0x00000000);
        g.fillGradient(0, height - v, width, height, 0x00000000, 0x99000000);
    }

    private void button(GuiGraphics g, Font font, int mx, int my, int x, String label) {
        int y = btnY(), w = btnW(), h = btnH();
        boolean hov = over(mx, my, x, y, w, h);
        if (hov) g.fill(x, y, x + w, y + h, PH);
        g.renderOutline(x, y, w, h, PH);
        g.renderOutline(x + 2, y + 2, w - 4, h - 4, alpha(PH, 0.35f));
        String s = "[ " + label + " ]";
        g.drawString(font, s, x + (w - font.width(s)) / 2, y + (h - 8) / 2, hov ? PH_BG1 : PH, false);
    }

    private void choose(boolean enter) {
        Minecraft mc = Minecraft.getInstance();
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BEACON_POWER_SELECT, enter ? 1.6f : 1.0f, 0.5f));
        if (enter) {
            ClientTrial.send(TrialActionPayload.ENTER, 0);
            mc.setScreen(null);
        } else {
            ClientTrial.send(TrialActionPayload.SKIP, 0);
            mc.setScreen(new GodPanelScreen());
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return true;
        if (!ready()) { chars = text.length(); return true; }
        if (over(mx, my, enterX(), btnY(), btnW(), btnH())) { choose(true); return true; }
        if (over(mx, my, skipX(), btnY(), btnW(), btnH())) { choose(false); return true; }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER && ready()) { choose(true); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    public boolean isPauseScreen() { return false; }
}
