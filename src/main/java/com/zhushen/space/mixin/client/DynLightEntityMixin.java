package com.zhushen.space.mixin.client;

import com.zhushen.space.client.DynamicLights;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 动态光源照亮实体 */
@Mixin(EntityRenderer.class)
public abstract class DynLightEntityMixin {
    @Inject(method = "getBlockLightLevel", at = @At("RETURN"), cancellable = true)
    private void zhushenspace$dynLight(Entity entity, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        int d = DynamicLights.blockLight(pos);
        if (d > cir.getReturnValueI()) cir.setReturnValue(d);
    }
}
