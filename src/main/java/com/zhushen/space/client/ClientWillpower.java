package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.network.WillpowerActionPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 意志力客户端：G / B 按键（昏迷时仍可按 G 强撑）+ 状态提示文字。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class ClientWillpower {

    private static long sustainEndMs;
    private static long graceEndMs;
    private static boolean armedCheck;
    private static boolean armedGuard;

    private ClientWillpower() {
    }

    public static void update(int sustainTicks, int graceTicks, boolean check, boolean guard) {
        long now = System.currentTimeMillis();
        sustainEndMs = sustainTicks > 0 ? now + sustainTicks * 50L : 0;
        graceEndMs = graceTicks > 0 ? now + graceTicks * 50L : 0;
        armedCheck = check;
        armedGuard = guard;
    }

    /** 强撑中（含抉择窗口）：客户端不施加昏迷硬控 */
    public static boolean armedCheck() { return armedCheck; }
    public static boolean armedGuard() { return armedGuard; }

    public static boolean holdingOn() {
        long now = System.currentTimeMillis();
        return now < sustainEndMs || now < graceEndMs;
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || event.getAction() != GLFW.GLFW_PRESS) return;
        if (event.getKey() < 0) return;
        if (event.getKey() == ClientSetup.WILLPOWER.getKey().getValue()) {
            PacketDistributor.sendToServer(new WillpowerActionPayload(0));
        } else if (event.getKey() == ClientSetup.WILLPOWER_GUARD.getKey().getValue()) {
            PacketDistributor.sendToServer(new WillpowerActionPayload(1));
        }
    }

    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        GuiGraphics g = event.getGuiGraphics();
        long now = System.currentTimeMillis();
        int y = g.guiHeight() / 2 + 18;
        int cx = g.guiWidth() / 2;
        if (now < graceEndMs) {
            Component c = Component.translatable("hud.zhushenspace.willpower.decide",
                    (graceEndMs - now + 999) / 1000);
            g.drawCenteredString(mc.font, c, cx, y, 0xFFFF5555);
            y += 11;
        } else if (now < sustainEndMs) {
            Component c = Component.translatable("hud.zhushenspace.willpower.sustain",
                    (sustainEndMs - now + 999) / 1000);
            g.drawCenteredString(mc.font, c, cx, y, 0xFFFFC04D);
            y += 11;
        }
        if (armedCheck) {
            g.drawCenteredString(mc.font, Component.translatable("hud.zhushenspace.willpower.check"), cx, y, 0xFFFFE08A);
            y += 11;
        }
        if (armedGuard) {
            g.drawCenteredString(mc.font, Component.translatable("hud.zhushenspace.willpower.guard"), cx, y, 0xFF8AD0FF);
        }
    }
}
