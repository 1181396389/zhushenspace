package com.zhushen.space.item;

import com.zhushen.space.data.ShieldType;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 主神空间的盾牌：按住右键举盾 = 【格挡】，期间获得盾牌防御（见 Defense.parts）。
 * 原版「正面完全挡住伤害」已取消（Defense.onShieldBlock）；原版盾牌不提供任何防御。
 * 无耐久、不可附魔；渲染沿用原版盾牌模型，贴图见 client/ZsShieldRenderer。
 */
public class ZsShieldItem extends ShieldItem {

    public final ShieldType type;

    public ZsShieldItem(ShieldType type, Properties props) {
        super(props);
        this.type = type;
    }

    /** 正在举盾格挡的主神盾牌（没有则 null） */
    public static ShieldType raised(LivingEntity e) {
        if (!e.isBlocking()) return null;
        return e.getUseItem().getItem() instanceof ZsShieldItem s ? s.type : null;
    }

    /** 手持（主手 / 副手）的主神盾牌（负重等用） */
    public static ShieldType of(ItemStack st) {
        return st.getItem() instanceof ZsShieldItem s ? s.type : null;
    }

    @Override
    public boolean isEnchantable(ItemStack stack) { return false; }

    @Override
    public boolean isValidRepairItem(ItemStack stack, ItemStack repair) { return false; }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tips, TooltipFlag flag) {
        tips.add(Component.translatable("tooltip.zhushenspace.shield.armor_kind").withStyle(ChatFormatting.GOLD));
        tips.add(Component.translatable(type.descKey()).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        tips.add(Component.translatable("tooltip.zhushenspace.shield.defense", type.melee, type.ranged).withStyle(ChatFormatting.WHITE));
        tips.add(Component.translatable("tooltip.zhushenspace.weapon.bulk", type.volume,
                type.weight == Math.floor(type.weight) ? String.valueOf((int) type.weight) : String.valueOf(type.weight))
                .withStyle(ChatFormatting.GRAY));
        tips.add(Component.literal("【").append(Component.translatable("weapon_trait.zhushenspace.block")).append("】")
                .withStyle(ChatFormatting.AQUA));
        boolean shift = net.neoforged.fml.loading.FMLEnvironment.dist.isClient()
                && com.zhushen.space.client.ClientHooks.shiftDown();
        if (shift) {
            tips.add(Component.literal("【").append(Component.translatable("weapon_trait.zhushenspace.block")).append("】：")
                    .withStyle(ChatFormatting.DARK_AQUA)
                    .append(Component.translatable("weapon_trait.zhushenspace.block.desc").withStyle(ChatFormatting.GRAY)));
        } else {
            tips.add(Component.translatable("tooltip.zhushenspace.weapon.shift").withStyle(ChatFormatting.DARK_GRAY));
        }
        tips.add(Component.translatable("tooltip.zhushenspace.shield.hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
