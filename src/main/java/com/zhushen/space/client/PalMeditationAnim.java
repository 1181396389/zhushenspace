package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zigythebird.playeranim.animation.PlayerAnimationController;
import com.zigythebird.playeranim.api.PlayerAnimationAccess;
import com.zigythebird.playeranim.api.PlayerAnimationFactory;
import com.zigythebird.playeranimcore.animation.layered.modifier.AbstractFadeModifier;
import com.zigythebird.playeranimcore.easing.EasingType;
import com.zigythebird.playeranimcore.enums.PlayState;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;

/**
 * PlayerAnimationLibrary 联动（仅在安装该模组时由 {@link ClientMeditation} 调用，未安装时此类永不加载）：
 * 打坐时淡入「盘坐调息」循环动作（assets/zhushenspace/player_animations/meditate.json），
 * 结束时淡入「收功起身」动作回到站姿。
 */
final class PalMeditationAnim {

    private PalMeditationAnim() {
    }

    static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "meditation");
    static final ResourceLocation MEDITATE = ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "meditate");
    static final ResourceLocation MEDITATE_END = ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "meditate_end");

    static void register() {
        // 优先级 1000：高于大多数动作层，盘坐时覆盖行走/手持姿态
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(LAYER, 1000,
                player -> new PlayerAnimationController(player, (controller, data, setter) -> PlayState.STOP));
    }

    static void play(AbstractClientPlayer player, boolean start) {
        if (!(PlayerAnimationAccess.getPlayerAnimationLayer(player, LAYER) instanceof PlayerAnimationController c)) {
            return;
        }
        c.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(start ? 8 : 4, EasingType.EASE_IN_OUT_SINE),
                start ? MEDITATE : MEDITATE_END);
    }
}
