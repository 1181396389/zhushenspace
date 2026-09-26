package com.zhushen.space.client;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.SkillType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;

/**
 * 技能 buff 的客户端执行侧。
 *
 * 玩家移动由客户端权威预测，服务端施加的属性修改器与速度对本地玩家不可见，
 * 因此跳跃与攀爬必须在客户端同步执行：
 * - 跳跃：激活时在客户端施加 JUMP_STRENGTH 修改器（+（力量+运动）×0.1），起跳即移除
 * - 攀爬：激活期间贴墙时给予向上速度，按实际爬升高度扣减（力量+运动）点的预算
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID, value = Dist.CLIENT)
public class ClientSkillEffects {

    private static final ResourceLocation LEAP_MOD =
            ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "leap");

    private static boolean leapApplied;
    private static boolean climbActive;
    private static double climbBudget;
    private static double climbLastY;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.isRemoved()) {
            if (leapApplied && player != null) removeLeap(player);
            leapApplied = false;
            climbActive = false;
            return;
        }

        // 跳跃：客户端同步施加/移除跳跃增益修改器
        boolean leapActive = ClientSkillData.leapRemainingMs() > 0;
        if (leapActive && !leapApplied) {
            AttributeInstance jump = player.getAttribute(Attributes.JUMP_STRENGTH);
            if (jump != null) {
                jump.removeModifier(LEAP_MOD);
                int strength = ClientAttributeData.points()[AttributeType.STRENGTH.ordinal()];
                int athletics = ClientSkillData.points()[SkillType.ATHLETICS.ordinal()];
                jump.addTransientModifier(new AttributeModifier(LEAP_MOD,
                        (strength + athletics) * 0.1, AttributeModifier.Operation.ADD_VALUE));
                leapApplied = true;
            }
        } else if (!leapActive && leapApplied) {
            removeLeap(player);
        }

        // 攀爬：贴墙时给予向上速度，按实际爬升高度扣减预算
        if (climbActive) {
            if (ClientSkillData.climbRemainingMs() <= 0 || climbBudget <= 0) {
                climbActive = false;
            } else {
                if (player.horizontalCollision && player.getDeltaMovement().y < 0.4) {
                    player.setDeltaMovement(player.getDeltaMovement().x, 0.3, player.getDeltaMovement().z);
                    player.fallDistance = 0;
                }
                climbBudget -= Math.max(0, player.getY() - climbLastY);
                climbLastY = player.getY();
            }
        } else if (ClientSkillData.climbRemainingMs() > 0) {
            climbActive = true;
            int strength = ClientAttributeData.points()[AttributeType.STRENGTH.ordinal()];
            int athletics = ClientSkillData.points()[SkillType.ATHLETICS.ordinal()];
            climbBudget = strength + athletics;
            climbLastY = player.getY();
        }
    }

    /** 跳跃增益仅维持一次跳跃：起跳即移除（客户端预测侧） */
    @SubscribeEvent
    public static void onLivingJump(LivingEvent.LivingJumpEvent event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (leapApplied && player != null && event.getEntity() == player) {
            removeLeap(player);
        }
    }

    private static void removeLeap(LocalPlayer player) {
        AttributeInstance jump = player.getAttribute(Attributes.JUMP_STRENGTH);
        if (jump != null) {
            jump.removeModifier(LEAP_MOD);
        }
        leapApplied = false;
    }
}
