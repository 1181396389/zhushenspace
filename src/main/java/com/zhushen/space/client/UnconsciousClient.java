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
    public static boolean isUnconscious(LocalPlayer player) {
        float maxHp = player.getMaxHealth();
        return maxHp - ClientHealthData.total() <= 0.0f && ClientHealthData.l() > 0;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (!isUnconscious(player)) return;

        // 兜底：水平动量清零（输入层已在 MovementInputUpdateEvent 中清零）
        player.setJumping(false);
        Vec3 v = player.getDeltaMovement();
        player.setDeltaMovement(0.0, v.y, 0.0);

        // 周期性 actionbar 提示（约每 2 秒）
        if ((player.tickCount & 39) == 0) {
            player.displayClientMessage(Component.translatable(
                    "msg.zhushenspace.health.unconscious_hint"), true);
        }
    }

    /**
     * 输入层锁定：在移动输入计算完成、被玩家本 tick 使用之前清零（旧实现在 tick 结束后清零，跳跃与移动会漏过）。
     * 锁定跳跃、潜行以外的全部移动输入。
     */
    @SubscribeEvent
    public static void onMovementInput(net.neoforged.neoforge.client.event.MovementInputUpdateEvent event) {
        if (!(event.getEntity() instanceof LocalPlayer player) || !isUnconscious(player)) return;
        var input = event.getInput();
        input.jumping = false;
        input.up = input.down = input.left = input.right = false;
        input.forwardImpulse = 0.0F;
        input.leftImpulse = 0.0F;
    }

    /** 每 tick 开始前吞掉左右键与跳跃的点击缓存并松开按键，防止按住状态在苏醒瞬间或本 tick 内生效 */
    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !isUnconscious(mc.player)) return;
        for (var key : new net.minecraft.client.KeyMapping[]{mc.options.keyAttack, mc.options.keyUse,
                mc.options.keyJump, mc.options.keyPickItem}) {
            while (key.consumeClick()) { }
            key.setDown(false);
        }
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        if (mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player);
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
