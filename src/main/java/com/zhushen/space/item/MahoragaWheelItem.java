package com.zhushen.space.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 魔虚罗之法阵（B 级物品 · 头盔位）：只赋予「适应」——不附带魔虚罗的其他任何能力。
 * 规则见 AdaptationManager 与 docs/equipment-slots-v1.md。
 */
public class MahoragaWheelItem extends ArmorItem {

    public MahoragaWheelItem(Holder<ArmorMaterial> material, Properties props) {
        super(material, Type.HELMET, props);
    }

    @Override
    public boolean isEnchantable(ItemStack stack) { return false; }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tips, TooltipFlag flag) {
        tips.add(Component.translatable("item.zhushenspace.mahoraga_wheel.tooltip.rank").withStyle(ChatFormatting.GOLD));
        tips.add(Component.translatable("item.zhushenspace.mahoraga_wheel.tooltip.slot").withStyle(ChatFormatting.GRAY));
        tips.add(Component.translatable("item.zhushenspace.mahoraga_wheel.tooltip.adapt").withStyle(ChatFormatting.WHITE));
        tips.add(Component.translatable("item.zhushenspace.mahoraga_wheel.tooltip.limit").withStyle(ChatFormatting.GRAY));
        tips.add(Component.translatable("item.zhushenspace.mahoraga_wheel.tooltip.only").withStyle(ChatFormatting.DARK_GRAY));
        super.appendHoverText(stack, context, tips, flag);
    }
}
