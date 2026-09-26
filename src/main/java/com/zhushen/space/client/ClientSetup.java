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
 * - K 切换内力吐息（自动档）
 * - H 打坐（恢复内力）
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

    /** 内力吐息开关（默认 K） */
    public static final KeyMapping TOGGLE_BREATH = new KeyMapping(
            "key.zhushenspace.toggle_breath",
            GLFW.GLFW_KEY_K,
            "key.categories.zhushenspace");

    /** 打坐（默认 H） */
    public static final KeyMapping MEDITATE = new KeyMapping(
            "key.zhushenspace.meditate",
            GLFW.GLFW_KEY_H,
            "key.categories.zhushenspace");

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_COMBAT);
        event.register(SWITCH_SKILL_BAR);
        event.register(TOGGLE_BREATH);
        event.register(MEDITATE);
    }
}
