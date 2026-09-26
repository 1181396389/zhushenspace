package com.zhushen.space.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** 主神空间风格按钮：替代原版灰色按钮，渲染走 {@link ZsTheme#button}（悬停渐变 + 扫光） */
public class ZsButton extends Button {

    public ZsButton(int x, int y, int w, int h, Component message, OnPress onPress) {
        super(x, y, w, h, message, onPress, DEFAULT_NARRATION);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        ZsTheme.button(g, Minecraft.getInstance().font, mouseX, mouseY, getX(), getY(), width, height,
                getMessage(), active, isFocused());
    }
}
