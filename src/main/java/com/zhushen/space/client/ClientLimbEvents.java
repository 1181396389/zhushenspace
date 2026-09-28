package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.LimbPart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;

/** 断肢客户端：第一人称隐藏断臂、封锁断臂那只手的物品互动（服务端另有兜底） */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public final class ClientLimbEvents {

    private ClientLimbEvents() {
    }

    static LimbPart armOf(LocalPlayer player, InteractionHand hand) {
        boolean rightMain = player.getMainArm() == HumanoidArm.RIGHT;
        return ((hand == InteractionHand.MAIN_HAND) == rightMain) ? LimbPart.RIGHT_ARM : LimbPart.LEFT_ARM;
    }

    static boolean handSevered(LocalPlayer player, InteractionHand hand) {
        return ClientLimbData.severed(player.getId(), armOf(player, hand));
    }

    /** 第一人称：断臂的手（连同手持物）不渲染 */
    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null && handSevered(p, event.getHand())) event.setCanceled(true);
    }

    /** 攻击 / 使用 / 选取：断臂的手无法互动 */
    @SubscribeEvent
    public static void onInteractKey(InputEvent.InteractionKeyMappingTriggered event) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        InteractionHand hand = event.isAttack() ? InteractionHand.MAIN_HAND : event.getHand();
        if (handSevered(p, hand)) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    /** 丢弃（主手断）/ 换手（任一手断）按键吞掉 */
    @SubscribeEvent
    public static void onTickPre(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || ClientLimbData.mask(p.getId()) == 0) return;
        if (handSevered(p, InteractionHand.MAIN_HAND)) {
            while (mc.options.keyDrop.consumeClick()) { }
        }
        if (handSevered(p, InteractionHand.MAIN_HAND) || handSevered(p, InteractionHand.OFF_HAND)) {
            while (mc.options.keySwapOffhand.consumeClick()) { }
        }
    }

    /** 双腿皆断：本地玩家同步强制趴伏（与服务端一致，碰撞箱 / 视角高度只剩一格） */
    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof LocalPlayer p)) return;
        int id = p.getId();
        if (ClientLimbData.severed(id, LimbPart.RIGHT_LEG) && ClientLimbData.severed(id, LimbPart.LEFT_LEG)
                && !p.isPassenger() && !p.isSleeping() && !p.getAbilities().flying
                && p.getPose() != net.minecraft.world.entity.Pose.SWIMMING) {
            p.setPose(net.minecraft.world.entity.Pose.SWIMMING);
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientLimbData.clear();
    }
}
