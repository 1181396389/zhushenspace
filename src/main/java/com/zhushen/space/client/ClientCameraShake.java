package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * 打击感：镜头微震系统。
 *
 * - 出招命中 / 八劲合一 / 撞墙钝击：服务端发送 {@code HitFeedbackPayload} 触发
 * - 本地玩家受击：客户端检测 hurtTime 从 0 跳变，自动触发小幅震动
 *
 * 震动以多频正弦叠加实现（平滑伪随机抖动），幅度随剩余时间平方衰减，
 * 通过 ViewportEvent.ComputeCameraAngles 修改相机 yaw/pitch/roll。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public class ClientCameraShake {

    private static float power;
    private static int ticks;
    private static int duration;
    private static long seed;
    private static int prevHurtTime;

    /** 触发震动（取与当前震动的较大者，避免连续命中时互相覆盖归零） */
    public static void trigger(float p, int t) {
        if (p > power || ticks <= 0) {
            power = Math.max(power, p);
            if (t > ticks) {
                ticks = t;
                duration = t;
                seed = (long) (Math.random() * 100000);
            }
        }
    }

    /** 相机角度扰动 */
    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (ticks <= 0 || power <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.isPaused()) return;

        float decay = duration > 0 ? (float) ticks / duration : 0f;
        float amp = power * decay * decay;
        double t = Util.getMillis() * 0.045;

        float yaw = (float) (Math.sin(t) + Math.sin(t * 2.7 + seed)) * amp * 0.5f;
        float pitch = (float) (Math.sin(t * 1.9 + seed * 1.3) + Math.sin(t * 3.1)) * amp * 0.4f;
        float roll = (float) Math.sin(t * 2.3 + seed * 0.7) * amp * 0.3f;

        event.setYaw(event.getYaw() + yaw);
        event.setPitch(event.getPitch() + pitch);
        event.setRoll(event.getRoll() + roll);
    }

    /** 每 tick：衰减震动 + 检测本地玩家受击 */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (ticks > 0 && --ticks == 0) {
            power = 0;
            duration = 0;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            prevHurtTime = 0;
            return;
        }
        // 受击打击感：hurtTime 从 0 跳变为正 → 刚被击中
        if (player.hurtTime > 0 && prevHurtTime == 0) {
            trigger(0.3f, 6);
        }
        prevHurtTime = player.hurtTime;
    }
}
