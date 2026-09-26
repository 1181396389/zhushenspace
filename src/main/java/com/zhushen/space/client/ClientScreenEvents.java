package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.screen.EnergyUiConfigScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * 在原版"设置"页添加"主神空间界面设置"入口（能量池 HUD 拖拽/缩放配置）。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public class ClientScreenEvents {

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        // 原版"设置"页右上角添加"主神空间界面设置"入口（能量池 HUD 拖拽/缩放配置）。
        // 不做宽度门槛：1920×1080 默认 GUI 缩放 4 时设置页宽度仅 480，旧门槛 >=520 导致按钮永不出现
        if (screen instanceof OptionsScreen options && options.width >= 360) {
            event.addListener(Button.builder(
                            Component.translatable("screen.zhushenspace.energy_config.entry"),
                            b -> Minecraft.getInstance().setScreen(new EnergyUiConfigScreen(options)))
                    .bounds(options.width - 155, 8, 150, 20)
                    .build());
        }
    }
}
