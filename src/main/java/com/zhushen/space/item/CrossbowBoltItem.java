package com.zhushen.space.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/** 弩矢：轻弩 / 重弩的弹药（弓不能使用）。射出后与箭一样可以捡回。 */
public class CrossbowBoltItem extends ArrowItem {

    public CrossbowBoltItem(Properties props) {
        super(props);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tips, TooltipFlag flag) {
        tips.add(Component.translatable("item.zhushenspace.crossbow_bolt.desc").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }
}
