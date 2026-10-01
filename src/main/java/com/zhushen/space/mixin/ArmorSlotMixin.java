package com.zhushen.space.mixin;

import com.zhushen.space.common.GearManager;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 装备位穿脱时间：已生效、需要穿脱时间的头盔 / 靴子不能直接从原版护甲栏取出——
 * 尝试取出即开始「解下」，解下完成（已解开）后才可以取出（见 GearManager）。
 */
@Mixin(targets = "net.minecraft.world.inventory.ArmorSlot")
public abstract class ArmorSlotMixin {

    @Shadow @Final private LivingEntity owner;
    @Shadow @Final private EquipmentSlot slot;

    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void zhushenspace$gearLock(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (GearManager.blockArmorPickup(player, owner, slot)) cir.setReturnValue(false);
    }
}
