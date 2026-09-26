package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;

/**
 * 昏迷客户端硬控（玩家移动是客户端权威，服务端效果对本地玩家无效，必须客户端禁输入）：
 * <ul>
 *   <li>禁移动：前后左右输入清零 + 水平动量清零</li>
 *   <li>禁跳跃</li>
 *   <li>禁挖掘/攻击/右键交互（InteractionKeyMappingTriggered 取消，覆盖按住的持续挖掘与使用）</li>
 *   <li>每 2 秒 actionbar 提示昏迷原因与苏醒条件</li>
 * </ul>
 * 昏迷判定与服务端一致：完好生命值归零 且 严重伤（L）未清零。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public class UnconsciousClient {

    /** 与服务端 HealthManager 一致的昏迷判定 */
    private static boolean isUnconscious(LocalPlayer player) {
        float maxHp = player.getMaxHealth();
        return maxHp - ClientHealthData.total() <= 0.0f && ClientHealthData.l() > 0;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (!isUnconscious(player)) return;

        // 禁移动与跳跃
        player.xxa = 0.0F;
        player.zza = 0.0F;
        player.setJumping(false);
        Vec3 v = player.getDeltaMovement();
        player.setDeltaMovement(0.0, v.y, 0.0);

        // 周期性 actionbar 提示（约每 2 秒）
        if ((player.tickCount & 39) == 0) {
            player.displayClientMessage(Component.translatable(
                    "msg.zhushenspace.health.unconscious_hint"), true);
        }
    }

    /** 昏迷时禁用左键攻击/挖掘（含按住的持续挖掘）与右键使用 */
    @SubscribeEvent
    public static void onInteractKey(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && isUnconscious(mc.player)) {
            event.setCanceled(true);
        }
    }
}
