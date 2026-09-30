package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.Condition;
import com.zhushen.space.data.PlayerConditionData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectUtil;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * 闭气与窒息（规则书，以人类为蓝本）：
 * <ul>
 *   <li><b>闭气</b>（头没入水中且无法水下呼吸）：每点耐力可以正常行动 5 轮（耐力为 0 按 1 计算）。
 *       超过后每轮进行一次耐力检定，DC 从 1 开始每轮 +1；任何一轮失败即呼吸停止。</li>
 *   <li><b>窒息</b>（能力直接施加的固有不良状态）：最多再呼吸 1 + 传奇耐力 轮，之后呼吸停止。</li>
 *   <li><b>呼吸停止</b>：第一轮进入昏迷；第二轮濒死；第三轮死亡（若没有特殊能力）。</li>
 *   <li>恢复正常呼吸：每正常呼吸一轮，闭气时间恢复 1 点耐力的持续时间（5 轮）。</li>
 * </ul>
 * 原版的氧气条改为显示剩余的闭气时间，原版溺水伤害由本系统接管。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class BreathManager {

    private BreathManager() {
    }

    public static final int ROUND = StatusManager.ROUND;
    /** 每点耐力可闭气的轮数 */
    public static final int ROUNDS_PER_END = 5;

    private static PlayerConditionData data(ServerPlayer p) {
        return StatusManager.data(p);
    }

    /** 闭气上限（tick） */
    public static int holdLimit(ServerPlayer p) {
        if (StatusEffects.has(p, Condition.ASPHYXIA)) return (1 + StatusManager.legend(p, AttributeType.ENDURANCE)) * ROUND;
        return Math.max(1, StatusManager.attr(p, AttributeType.ENDURANCE)) * ROUNDS_PER_END * ROUND;
    }

    /** 当前能否呼吸 */
    public static boolean canBreathe(ServerPlayer p) {
        if (StatusEffects.has(p, Condition.ASPHYXIA)) return false;
        return !(p.isEyeInFluid(FluidTags.WATER) && !p.canBreatheUnderwater() && !MobEffectUtil.hasWaterBreathing(p));
    }

    /** 剩余闭气比例（-1 = 正常呼吸且没有闭气消耗；0 = 已超过上限 / 呼吸停止） */
    public static float breathFraction(ServerPlayer p) {
        PlayerConditionData d = data(p);
        if (d.apneaTicks > 0) return 0f;
        if (d.breathTicks <= 0) return -1f;
        return Math.max(0f, 1f - d.breathTicks / (float) holdLimit(p));
    }

    public static boolean apnea(ServerPlayer p) {
        return data(p).apneaTicks > 0;
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !p.isAlive()) return;
        PlayerConditionData d = data(p);
        if (p.isCreative() || p.isSpectator()) {
            d.breathTicks = 0;
            if (d.apneaTicks > 0) stopApnea(p, false);
            return;
        }
        int limit = holdLimit(p);
        if (!canBreathe(p)) {
            d.breathTicks++;
            int over = d.breathTicks - limit;
            if (d.apneaTicks == 0 && over > 0) {
                if (StatusEffects.has(p, Condition.ASPHYXIA)) startApnea(p);
                else if (over % ROUND == 0) {
                    // 每轮一次耐力检定：DC = 1 + 已超出的轮数
                    int dc = over / ROUND;
                    int end = StatusManager.attr(p, AttributeType.ENDURANCE) + PoolEffects.checkBonus(p, AttributeType.ENDURANCE)
                            - StatusEffects.savePenalty(p, AttributeType.ENDURANCE);
                    int s = Math.max(0, Math.round(end * DamageVariance.roll(p.getRandom())));
                    if (s < dc) startApnea(p);
                    else p.displayClientMessage(Component.translatable("msg.zhushenspace.breath.strain", dc), true);
                }
            }
            if (d.apneaTicks > 0) {
                d.apneaTicks++;
                if (d.apneaTicks == ROUND + 1) p.displayClientMessage(Component.translatable("msg.zhushenspace.breath.dying"), true);
                if (d.apneaTicks > ROUND * 2) {
                    MinecraftServer server = p.getServer();
                    if (server != null) server.getPlayerList().broadcastSystemMessage(
                            Component.translatable("msg.zhushenspace.breath.death", p.getDisplayName()), false);
                    stopApnea(p, false);
                    d.breathTicks = 0;
                    p.kill();
                    return;
                }
            }
        } else {
            if (d.apneaTicks > 0) stopApnea(p, true);
            // 每正常呼吸一轮恢复 1 点耐力的闭气时间（5 轮）
            if (d.breathTicks > 0) d.breathTicks = Math.max(0, d.breathTicks - ROUNDS_PER_END);
        }
        // 原版氧气条显示剩余闭气时间（保持 ≥ 0，原版不会造成溺水伤害）
        int max = p.getMaxAirSupply();
        int air = d.apneaTicks > 0 ? 0 : d.breathTicks <= 0 ? max : Math.max(0, Math.round(max * (1f - d.breathTicks / (float) limit)));
        if (p.getAirSupply() != air) p.setAirSupply(air);
    }

    private static void startApnea(ServerPlayer p) {
        PlayerConditionData d = data(p);
        d.apneaTicks = 1;
        // 第一轮：昏迷（直到恢复呼吸）
        d.condUntil[Condition.UNCONSCIOUS.ordinal()] = Long.MAX_VALUE;
        StatusEffects.onConditionStart(p, Condition.UNCONSCIOUS);
        p.displayClientMessage(Component.translatable("msg.zhushenspace.breath.stopped"), false);
        StatusManager.changed(p);
    }

    private static void stopApnea(ServerPlayer p, boolean message) {
        PlayerConditionData d = data(p);
        d.apneaTicks = 0;
        if (d.condUntil[Condition.UNCONSCIOUS.ordinal()] == Long.MAX_VALUE) d.condUntil[Condition.UNCONSCIOUS.ordinal()] = 0;
        if (message) p.displayClientMessage(Component.translatable("msg.zhushenspace.breath.resumed"), false);
        StatusManager.changed(p);
    }

    /** 原版溺水伤害由本系统接管 */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onIncoming(LivingIncomingDamageEvent e) {
        if (e.getEntity() instanceof ServerPlayer && e.getSource().is(DamageTypes.DROWN)) e.setCanceled(true);
    }
}
