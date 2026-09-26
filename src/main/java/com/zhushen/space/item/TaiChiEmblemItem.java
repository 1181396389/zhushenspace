package com.zhushen.space.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

import java.util.List;

/**
 * 太极拳流派饰品（穿戴在「流派」饰品栏）。
 *
 * 只有装备在饰品栏上时，太极拳才会生效：
 * - 获得内力能量池（容量 = 耐力 + 感知），并随附内力吐息与打坐
 * - 太极被动：徒手（双手无武器）时天生武器攻击 +6、护甲 +6
 * - 已购买的太极拳技能（八式 + 听劲）方可使用
 */
public class TaiChiEmblemItem extends Item implements ICurioItem {

    public TaiChiEmblemItem(Properties properties) {
        super(properties);
    }

    /** 仅允许装备在「流派」饰品栏 */
    @Override
    public boolean canEquip(SlotContext slotContext, ItemStack stack) {
        return "school".equals(slotContext.identifier());
    }

    @Override
    public boolean canEquipFromUse(SlotContext slotContext, ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable("item.zhushenspace.tai_chi_emblem.tooltip.intro"));
        tooltipComponents.add(Component.translatable("item.zhushenspace.tai_chi_emblem.tooltip.passive"));
        tooltipComponents.add(Component.translatable("item.zhushenspace.tai_chi_emblem.tooltip.pool"));
        tooltipComponents.add(Component.translatable("item.zhushenspace.tai_chi_emblem.tooltip.skills"));
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }
}
