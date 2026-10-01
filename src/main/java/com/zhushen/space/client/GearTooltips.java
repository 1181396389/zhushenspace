package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.common.GearManager;
import com.zhushen.space.common.GearMountManager;
import com.zhushen.space.data.GearMounts;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/** 物品提示：搭载的插件 / 装置；插件、装置、概念武装的用法 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class GearTooltips {
    private GearTooltips() {}

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent e) {
        ItemStack st = e.getItemStack();
        GearMounts m = GearMountManager.mounts(st);
        if (!m.plugin.isEmpty())
            e.getToolTip().add(Component.translatable("tooltip.zhushenspace.gear.plugin", m.plugin.getHoverName()).withStyle(ChatFormatting.AQUA));
        if (!m.device.isEmpty())
            e.getToolTip().add(Component.translatable("tooltip.zhushenspace.gear.device", m.device.getHoverName()).withStyle(ChatFormatting.AQUA));
        if (st.is(GearMountManager.PLUGIN) || st.is(GearMountManager.MOUNTABLE_DEVICE))
            e.getToolTip().add(Component.translatable("tooltip.zhushenspace.gear.mount_hint").withStyle(ChatFormatting.DARK_GRAY));
        if (st.is(GearManager.CONCEPT_ARMAMENT))
            e.getToolTip().add(Component.translatable(st.is(GearManager.CONCEPT_FREE_SWAP)
                    ? "tooltip.zhushenspace.gear.concept_free" : "tooltip.zhushenspace.gear.concept").withStyle(ChatFormatting.LIGHT_PURPLE));
    }
}
