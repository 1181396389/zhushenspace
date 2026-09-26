package com.zhushen.space.client;

import com.zhushen.space.screen.GodPanelScreen;
import com.zhushen.space.screen.ZsAnim;
import com.zhushen.space.screen.ZsTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * 背包界面上方的"主神面板"选项卡按钮，仿照创造模式选项卡样式。
 */
public class GodPanelTabButton extends AbstractButton {

    private static final int BG = 0xF0133049;
    private static final int BG_HOVER = 0xF01F4E6E;
    private static final int BORDER = 0xFF5B9BD5;
    private static final int BORDER_HOVER = 0xFF7FC4F0;
    private static final int TEXT_COLOR = 0xFFD9EEFF;

    public GodPanelTabButton(int x, int y, int width, int height) {
        super(x, y, width, height, Component.translatable("screen.zhushenspace.godpanel.tab"));
    }

    @Override
    public void onPress() {
        Minecraft.getInstance().setScreen(new GodPanelScreen());
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean hovered = isHoveredOrFocused();
        float t = ZsAnim.tween(ZsAnim.key(9, getX(), getY()), hovered ? 1 : 0, 16);
        // 选项卡主体（顶部不描边，模拟贴合背包面板上沿的选项卡），悬停平滑渐变 + 扫光
        g.fill(getX(), getY(), getX() + width, getY() + height, ZsAnim.lerpColor(BG, BG_HOVER, t));
        if (hovered) ZsTheme.shimmer(g, getX(), getY(), width, height, 1);
        // 左右下三边描边
        g.fill(getX(), getY(), getX() + 1, getY() + height, BORDER);
        g.fill(getX() + width - 1, getY(), getX() + width, getY() + height, BORDER);
        g.fill(getX(), getY() + height - 1, getX() + width, getY() + height, BORDER);
        // 悬停顶部高亮
        if (t > 0.01f) {
            g.fill(getX(), getY(), getX() + width, getY() + 1, ZsAnim.withAlpha(BORDER_HOVER, t));
        }

        Font font = Minecraft.getInstance().font;
        Component msg = getMessage();
        g.drawString(font, msg, getX() + (width - font.width(msg)) / 2,
                getY() + (height - 8) / 2, TEXT_COLOR, true);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
