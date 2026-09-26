package com.zhushen.space.item;

import com.zhushen.space.common.AttributeServer;
import com.zhushen.space.data.AttributeType;
import com.zhushen.space.screen.MeaningOfLifeScreen;
import com.zhushen.space.sound.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

import java.util.List;

public class InvitationEnvelopeItem extends Item {

    public InvitationEnvelopeItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide()) {
            // 服务端播放音效、发放 12 点自由点数并减少数量
            level.playSound(null, player.blockPosition(),
                    ModSounds.ENVELOPE_OPEN.get(), SoundSource.PLAYERS, 1.0f, 1.0f);

            if (player instanceof ServerPlayer serverPlayer) {
                AttributeServer.grantPoints(serverPlayer, AttributeType.ENVELOPE_POINTS);
            }

            // 消耗信封
            ItemStack stack = player.getItemInHand(hand);
            stack.shrink(1);

            return InteractionResultHolder.sidedSuccess(stack, false);
        } else {
            // 客户端打开 GUI
            openMeaningOfLifeScreen();
            return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), true);
        }
    }

    private void openMeaningOfLifeScreen() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(new MeaningOfLifeScreen());
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable("item.zhushenspace.invitation_envelope.tooltip"));
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }

    @Override
    public boolean isEnchantable(ItemStack stack) {
        return false;
    }

    @Override
    public boolean isRepairable(ItemStack stack) {
        return false;
    }
}
