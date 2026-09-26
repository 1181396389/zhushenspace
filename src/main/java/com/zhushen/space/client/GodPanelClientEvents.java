package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * 在玩家背包界面（InventoryScreen）上方注入"主神面板"选项卡按钮。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public class GodPanelClientEvents {

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof InventoryScreen screen)) return;

        // 背包玩家界面尺寸固定为 176x166
        int left = (screen.width - 176) / 2;
        int top = (screen.height - 166) / 2;

        // 选项卡位于背包界面上方，右对齐
        event.addListener(new GodPanelTabButton(left + 176 - 96, top - 22, 96, 20));
    }
}
