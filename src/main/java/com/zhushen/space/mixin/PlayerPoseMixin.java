package com.zhushen.space.mixin;

import com.zhushen.space.common.PoseControl;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 倒地 / 双腿皆断时强制趴伏：直接接管原版每 tick 的姿态计算。
 * <p>
 * 以前是在 tick 结束后把姿态改回 SWIMMING，原版会在每个 tick 里先算成 STANDING 再被改回，
 * 碰撞箱、视角高度与同步给其他玩家的姿态反复跳变，表现为「抽搐」。
 */
@Mixin(Player.class)
public abstract class PlayerPoseMixin {

    @Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
    private void zhushenspace$forceCrawl(CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (!PoseControl.keepCrawl(self)) return;
        if (self.getPose() != Pose.SWIMMING) self.setPose(Pose.SWIMMING);
        ci.cancel();
    }
}
