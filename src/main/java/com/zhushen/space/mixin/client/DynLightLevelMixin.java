package com.zhushen.space.mixin.client;

import com.zhushen.space.client.DynamicLights;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 动态光源（光亮术 / 照明术）：方块网格 / 粒子 / 方块实体取光照时叠加（取较亮者），不改动世界光照 */
@Mixin(LevelRenderer.class)
public abstract class DynLightLevelMixin {
    @Inject(method = "getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I",
            at = @At("RETURN"), cancellable = true)
    private static void zhushenspace$dynLight(BlockAndTintGetter level, BlockState state, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        int d = DynamicLights.blockLight(pos);
        if (d <= 0) return;
        int packed = cir.getReturnValueI();
        if (d > LightTexture.block(packed)) cir.setReturnValue(LightTexture.pack(d, LightTexture.sky(packed)));
    }
}
