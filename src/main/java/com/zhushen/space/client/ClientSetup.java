package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端按键注册（MOD 总线）：
 * - Alt 切换战斗模式
 * - V 切换战斗预设技能栏（A/B）
 * - Z 动作轮盘（意志力、能量池用法、留手 / 增幅等）
 * - 意志力 / 意志守御：默认不绑定，可在「控制」中自行设置
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class ClientSetup {

    /** 战斗模式切换键（默认左 Alt） */
    public static final KeyMapping TOGGLE_COMBAT = new KeyMapping(
            "key.zhushenspace.toggle_combat",
            GLFW.GLFW_KEY_LEFT_ALT,
            "key.categories.zhushenspace");

    /** 战斗预设技能栏切换键（默认 V） */
    public static final KeyMapping SWITCH_SKILL_BAR = new KeyMapping(
            "key.zhushenspace.switch_bar",
            GLFW.GLFW_KEY_V,
            "key.categories.zhushenspace");

    /** 意志力（默认 G）：昏迷时强撑 / 否则预备意志加持（下一次检定 +9） */
    public static final KeyMapping WILLPOWER = new KeyMapping(
            "key.zhushenspace.willpower",
            com.mojang.blaze3d.platform.InputConstants.UNKNOWN.getValue(), // 默认不占键：改由动作轮盘使用
            "key.categories.zhushenspace");

    /** 意志守御（默认 B）：预备下一次受击 +9 护甲 / +9 韧性 */
    public static final KeyMapping WILLPOWER_GUARD = new KeyMapping(
            "key.zhushenspace.willpower_guard",
            com.mojang.blaze3d.platform.InputConstants.UNKNOWN.getValue(),
            "key.categories.zhushenspace");

    /** 技艺轮盘（默认 Z，按住显示） */
    public static final KeyMapping ART_WHEEL = new KeyMapping(
            "key.zhushenspace.art_wheel",
            GLFW.GLFW_KEY_Z,
            "key.categories.zhushenspace");

    @SubscribeEvent
    public static void onClientSetup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
        ClientMeditation.init(event);
        com.zhushen.space.common.PoseControl.CLIENT = ClientCondition::keepCrawl;
        com.zhushen.space.common.GearManager.CLIENT_LOCKED = ClientGearData::locked;
        com.zhushen.space.common.GearManager.CLIENT_CONCEPT_SWAP = ClientRest::active;
        event.enqueueWork(ClientSetup::crossbowProperties);
        event.enqueueWork(ClientSetup::bowAndShieldProperties);
    }

    /** 弓箭：拉弓谓词；盾牌：举盾谓词（与原版相同） */
    private static void bowAndShieldProperties() {
        net.minecraft.world.item.Item bow = com.zhushen.space.ZhuShenSpace.weaponItem(com.zhushen.space.data.MeleeWeapon.BOW);
        net.minecraft.client.renderer.item.ItemProperties.register(bow,
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("pull"), (stack, level, ent, seed) ->
                        ent == null || ent.getUseItem() != stack ? 0f
                                : (float) (stack.getUseDuration(ent) - ent.getUseItemRemainingTicks()) / 20.0f);
        net.minecraft.client.renderer.item.ItemProperties.register(bow,
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("pulling"), (stack, level, ent, seed) ->
                        ent != null && ent.isUsingItem() && ent.getUseItem() == stack ? 1f : 0f);
        net.minecraft.client.renderer.item.ItemProperties.register(com.zhushen.space.ZhuShenSpace.SHIELD.get(),
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("blocking"), (stack, level, ent, seed) ->
                        ent != null && ent.isUsingItem() && ent.getUseItem() == stack ? 1f : 0f);
    }

    @SubscribeEvent
    public static void onClientExtensions(net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent event) {
        event.registerItem(ZsShieldRenderer.EXTENSIONS, com.zhushen.space.ZhuShenSpace.SHIELD.get());
    }

    /** 轻弩 / 重弩：与原版弩相同的拉弦 / 装填模型谓词 */
    private static void crossbowProperties() {
        for (var w : com.zhushen.space.data.MeleeWeapon.values()) {
            if (!w.ranged()) continue;
            net.minecraft.world.item.Item item = com.zhushen.space.ZhuShenSpace.weaponItem(w);
            net.minecraft.client.renderer.item.ItemProperties.register(item,
                    net.minecraft.resources.ResourceLocation.withDefaultNamespace("pull"), (stack, level, ent, seed) -> {
                        if (ent == null || net.minecraft.world.item.CrossbowItem.isCharged(stack)) return 0f;
                        return ent.isUsingItem() && ent.getUseItem() == stack
                                ? (float) (stack.getUseDuration(ent) - ent.getUseItemRemainingTicks())
                                        / net.minecraft.world.item.CrossbowItem.getChargeDuration(stack, ent)
                                : 0f;
                    });
            net.minecraft.client.renderer.item.ItemProperties.register(item,
                    net.minecraft.resources.ResourceLocation.withDefaultNamespace("pulling"), (stack, level, ent, seed) ->
                            ent != null && ent.isUsingItem() && ent.getUseItem() == stack
                                    && !net.minecraft.world.item.CrossbowItem.isCharged(stack) ? 1f : 0f);
            net.minecraft.client.renderer.item.ItemProperties.register(item,
                    net.minecraft.resources.ResourceLocation.withDefaultNamespace("charged"), (stack, level, ent, seed) ->
                            net.minecraft.world.item.CrossbowItem.isCharged(stack) ? 1f : 0f);
        }
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_COMBAT);
        event.register(SWITCH_SKILL_BAR);
        event.register(WILLPOWER);
        event.register(WILLPOWER_GUARD);
        event.register(ART_WHEEL);
    }
}
