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
 * - G 意志力：强撑 / 意志加持；B 意志守御
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
            GLFW.GLFW_KEY_G,
            "key.categories.zhushenspace");

    /** 意志守御（默认 B）：预备下一次受击 +9 护甲 / +9 韧性 */
    public static final KeyMapping WILLPOWER_GUARD = new KeyMapping(
            "key.zhushenspace.willpower_guard",
            GLFW.GLFW_KEY_B,
            "key.categories.zhushenspace");

    @SubscribeEvent
    public static void onClientSetup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
        ClientMeditation.init(event);
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_COMBAT);
        event.register(SWITCH_SKILL_BAR);
        event.register(WILLPOWER);
        event.register(WILLPOWER_GUARD);
    }
}
