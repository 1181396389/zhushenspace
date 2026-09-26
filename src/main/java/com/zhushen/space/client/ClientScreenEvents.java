package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.screen.EnergyUiConfigScreen;
import net.minecraft.client.Minecraft;
import com.zhushen.space.screen.ZsButton;
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
            event.addListener(new ZsButton(options.width - 155, 8, 150, 20,
                Component.translatable("screen.zhushenspace.energy_config.entry"),
                            b -> Minecraft.getInstance().setScreen(new EnergyUiConfigScreen(options))));
        }
    }

    /**
     * 主神空间界面内的悬停提示框：替换原版紫色边框为深空蓝玻璃底 + 青蓝→鎏金渐变边框（边框亮度随时间呼吸）。
     */
    @SubscribeEvent
    public static void onTooltipColor(net.neoforged.neoforge.client.event.RenderTooltipEvent.Color event) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen == null || !screen.getClass().getName().startsWith("com.zhushen.space.")) return;
        float p = com.zhushen.space.screen.ZsAnim.pulse(2400);
        if (screen instanceof com.zhushen.space.screen.GodPanelScreen xg && xg.xytStyle()) {
            // 技能页（新月同行）：深墨灰底 + 橙→冷灰边框
            event.setBackground(0xF21C1F23);
            event.setBorderStart(0xFFF0701E);
            event.setBorderEnd(0xFF5A6068);
            return;
        }
        if (screen instanceof com.zhushen.space.screen.GodPanelScreen sg && sg.sgStyle()) {
            // 属性页（命运石之门）：显像管黑底 + 辉光橙→红莉栖红边框
            event.setBackground(0xF20C0B0E);
            event.setBorderStart(com.zhushen.space.screen.ZsAnim.lerpColor(0xFFFF8A2A, 0xFFFFC27A, p));
            event.setBorderEnd(0xFFD2413A);
            return;
        }
        if (screen instanceof com.zhushen.space.screen.GodPanelScreen gp && gp.forgeStyle()) {
            // 战斗预设页：锻铁底 + 暗铜→炽焰边框
            event.setBackground(0xF2140A07);
            event.setBorderStart(com.zhushen.space.screen.ZsAnim.lerpColor(0xFFFF9A3C, 0xFFFFD27A, p));
            event.setBorderEnd(com.zhushen.space.screen.ZsAnim.lerpColor(0xFF5A2A14, 0xFFA0461C, p));
            return;
        }
        // 通用（大黑塔）：夜空墨紫底 + 帽花紫→薰衣草边框，底边带一点帽环金
        event.setBackground(0xF2120E1C);
        event.setBorderStart(com.zhushen.space.screen.ZsAnim.lerpColor(0xFF8C6FE0, 0xFFCDBEF5, p));
        event.setBorderEnd(com.zhushen.space.screen.ZsAnim.lerpColor(0xFF5E4C94, 0xFFE8C77E, p * 0.6f));
    }
}
