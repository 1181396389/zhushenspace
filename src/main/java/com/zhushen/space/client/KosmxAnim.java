package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonConfiguration;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;

/**
 * KosmX Player Animator 联动（仅在安装 playeranimator 时由 {@link ClientAnims} 调用，未安装时此类永不加载）。
 * <p>
 * 动作文件：assets/zhushenspace/player_animations/*.json（GeckoLib 格式，由 tools/gen_taiji_anims.py 生成）。
 * 所有动作使用 {@link FirstPersonMode#THIRD_PERSON_MODEL}：第一人称下双臂按第三人称模型的动作挥动（手中物品照常显示）。
 */
final class KosmxAnim {

    private KosmxAnim() {
    }

    static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "action");
    static final ResourceLocation LIMB_LAYER = ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "limbs");

    /** 注册动作层：优先级 1000，高于行走 / 手持姿态 */
    static void register() {
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(LAYER, 1000, player -> new ModifierLayer<>());
        // 断肢层：最高优先级，最后覆盖缩放（已断部位缩放为 0）
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(LIMB_LAYER, 5000, KosmxLimbLayer::new);
    }

    @SuppressWarnings("unchecked")
    private static ModifierLayer<IAnimation> layer(AbstractClientPlayer player) {
        IAnimation a = PlayerAnimationAccess.getPlayerAssociatedData(player).get(LAYER);
        return a instanceof ModifierLayer<?> m ? (ModifierLayer<IAnimation>) m : null;
    }

    /**
     * 播放动作（name 为空则淡出停止）。
     *
     * @param fadeTicks 与当前姿态的过渡时长
     */
    static void play(AbstractClientPlayer player, String name, int fadeTicks) {
        ModifierLayer<IAnimation> layer = layer(player);
        if (layer == null) return;
        AbstractFadeModifier fade = AbstractFadeModifier.standardFadeIn(Math.max(1, fadeTicks), Ease.INOUTSINE);
        if (name == null || name.isEmpty()) {
            layer.replaceAnimationWithFade(fade, null);
            return;
        }
        if (!(PlayerAnimationRegistry.getAnimation(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, name))
                instanceof KeyframeAnimation data)) {
            return;
        }
        KeyframeAnimationPlayer anim = new KeyframeAnimationPlayer(data)
                .setFirstPersonMode(FirstPersonMode.THIRD_PERSON_MODEL)
                .setFirstPersonConfiguration(new FirstPersonConfiguration(true, true, true, true));
        layer.replaceAnimationWithFade(fade, anim, true);
    }
}
