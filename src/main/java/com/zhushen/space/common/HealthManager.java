package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.PlayerHealthData;
import com.zhushen.space.network.SyncHealthPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * B/L/A 生命状态系统（仅玩家；生物沿用原版生命）。
 *
 * - 原版血条显示的恒为「完好生命值」＝ 上限 −（冲击 + 严重 + 恶性）
 * - 受击（LivingDamageEvent.Post，取最终伤害）默认计入冲击（B）池；
 *   特殊能力（如太极采劲/撞墙）可指定记入严重（L）池，
 *   池满自动向下转化（2B→1L、2L→1A，整池转化单数向上取整）
 * - 全身恶性且 ≥ 上限 → 死亡；伤势满载（无完好生命值）→ 昏迷：
 *   完全无法行动（客户端禁移动/跳跃/挖掘/攻击/交互，服务端强减速+挖掘禁止+虚弱），
 *   且昏迷不会自行苏醒——严重伤（L）被彻底治愈（L 清零）后才能醒来，苏醒后仍可带恶性伤行动
 * - 原版一切治疗（再生/药水/食物等，LivingHealEvent）被拦截，
 *   改为从冲击开始 1:1 移除伤势并回复完好生命值
 * - /kill 与虚空伤害绕过本系统，按原版结算
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public class HealthManager {

    /** 昏迷状态缓存：玩家 UUID → 当前是否昏迷（用于一次性提示） */
    private static final Map<UUID, Boolean> UNCONSCIOUS = new HashMap<>();
    /** 仅因头部血量清空而昏迷（伤势未满载）：头部恢复即可苏醒，不要求严重伤清零 */
    private static final java.util.Set<UUID> HEAD_ONLY = new java.util.HashSet<>();

    // ===== 对外接口 =====

    /** 直接记录一档伤势（太极采劲/撞墙等「严重伤害」走 L 池） */
    public static void addWound(ServerPlayer player, PlayerHealthData.Severity severity, int amount) {
        if (amount <= 0) return;
        int maxHp = Math.round(player.getMaxHealth());
        PlayerHealthData data = player.getData(ModAttachments.PLAYER_HEALTH);
        PlayerHealthData.Result res = data.apply(severity, amount, maxHp);
        if (res.bToL() > 0) {
            player.displayClientMessage(Component.translatable(
                    "msg.zhushenspace.health.converted_bl", res.bToL()), true);
        }
        if (res.lToA() > 0) {
            player.displayClientMessage(Component.translatable(
                    "msg.zhushenspace.health.converted_la", res.lToA()), true);
        }
        sync(player);
        normalize(player);
    }

    /** 技艺治疗后：归位生命值 + 同步 + 苏醒检查 */
    public static void afterHeal(ServerPlayer player, int limbHeal) {
        if (limbHeal > 0) LimbManager.heal(player, limbHeal);
        normalize(player);
        sync(player);
        checkWake(player, player.getData(ModAttachments.PLAYER_HEALTH));
    }

    /** 将生命值归位为「完好生命值」（昏迷保 0.5，全身恶性满载则处决） */
    private static void normalize(ServerPlayer player) {
        PlayerHealthData data = player.getData(ModAttachments.PLAYER_HEALTH);
        int maxHp = Math.round(player.getMaxHealth());
        if (data.b() == 0 && data.l() == 0 && data.a() > 0 && data.a() >= maxHp) {
            // 全身恶性且满载 → 死亡（走 genericKill，Post 处放行）
            if (!player.isDeadOrDying()) {
                player.kill();
            }
            return;
        }
        int intact = data.intact(maxHp);
        player.setHealth(intact > 0 ? intact : 0.5f);
    }

    public static void sync(ServerPlayer player) {
        PlayerHealthData data = player.getData(ModAttachments.PLAYER_HEALTH);
        PacketDistributor.sendToPlayer(player,
                new SyncHealthPayload(data.b(), data.l(), data.a(), Math.round(player.getMaxHealth())));
    }

    // ===== 事件 =====

    /** 受击结算：最终伤害默认记为冲击（B）+ 伤势转化 + 生命值归位 */
    @SubscribeEvent
    public static void onDamagePost(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        DamageSource src = event.getSource();
        // /kill 与虚空伤害：绕过系统，原版结算
        if (src.is(DamageTypes.GENERIC_KILL) || src.is(DamageTypes.FELL_OUT_OF_WORLD)) return;
        float amount = event.getNewDamage();
        if (amount <= 0) {
            normalize(player); // 被完全吸收/减免的伤害不记录伤势，仅归位生命值
            return;
        }
        int[] sp = DamageRules.takeSplit(player, Math.round(amount));
        if (sp[2] > 0) addWound(player, PlayerHealthData.Severity.A, sp[2]);
        if (sp[1] > 0) addWound(player, PlayerHealthData.Severity.L, sp[1]);
        if (sp[0] > 0) addWound(player, PlayerHealthData.Severity.B, sp[0]);
    }

    /** 原版治疗拦截：改为移除伤势（冲击 → 严重 → 恶性，1:1）并回复完好生命值。
     *  饱食度 / 饱和度的自然回血（FoodData）一律取消：伤势只能靠休息、快速医疗与治疗能力处理 */
    @SubscribeEvent
    public static void onHeal(LivingHealEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        event.setCanceled(true);
        if (isFoodRegen()) {
            // FoodData 回血后会追加「回血量 × 6」的消耗：同 tick 退还，饥饿度不会为不存在的回血买单
            FOOD_REFUND.merge(player.getUUID(), event.getAmount() * 6f, Float::sum);
            return;
        }
        PlayerHealthData data = player.getData(ModAttachments.PLAYER_HEALTH);
        data.heal(Math.round(event.getAmount()));
        LimbManager.heal(player, Math.round(event.getAmount())); // 未断部位同步回复（头优先）
        normalize(player);
        sync(player);
        checkWake(player, data);
    }

    /** 本 tick 待退还的饥饿消耗（饱食回血被取消时） */
    private static final Map<UUID, Float> FOOD_REFUND = new HashMap<>();

    /** 调用栈中是否有 FoodData（饱食度 / 饱和度的自然回血） */
    private static boolean isFoodRegen() {
        return StackWalker.getInstance().walk(frames -> frames.limit(32)
                .anyMatch(f -> f.getClassName().equals("net.minecraft.world.food.FoodData")));
    }

    /** 退还被取消的饱食回血所追加的饥饿消耗（FoodData 在回血之后才追加消耗，故放在玩家 tick 之后） */
    @SubscribeEvent
    public static void onPlayerTickPost(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (FOOD_REFUND.isEmpty() || !(event.getEntity() instanceof ServerPlayer player)) return;
        Float refund = FOOD_REFUND.remove(player.getUUID());
        if (refund == null || refund <= 0f) return;
        var food = player.getFoodData();
        float back = Math.min(refund, food.getExhaustionLevel());
        if (back > 0f) food.addExhaustion(-back);
    }

    /** 重生：伤势清零（Attachment 未设 copyOnDeath，双保险同步一次） */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            player.getData(ModAttachments.PLAYER_HEALTH).reset();
            UNCONSCIOUS.put(player.getUUID(), false);
            sync(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sync(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UNCONSCIOUS.remove(event.getEntity().getUUID());
    }

    /**
     * 昏迷看护：伤势满载（无完好生命值）且仍有严重伤（L）→ 持续昏迷。
     * 昏迷不会自行苏醒（不再是几秒的临时状态），只有严重伤被彻底治愈（L 清零）才能醒来。
     * 服务端持续施加强减速 + 挖掘禁止 + 虚弱兜底；客户端另行禁用输入（UnconsciousClient）。
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 10 != 0) return;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            PlayerHealthData data = player.getData(ModAttachments.PLAYER_HEALTH);
            int maxHp = Math.round(player.getMaxHealth());
            // 昏迷条件：伤势满载（仍有严重伤） 或 头部血量清空
            boolean wounded = (data.total() >= maxHp && data.l() > 0) || LimbManager.headOut(player);
            // 意志力强撑：持续时间内不会因伤势过重昏迷
            boolean out = wounded && !WillpowerManager.holdsOn(player, wounded);
            boolean prev = UNCONSCIOUS.getOrDefault(player.getUUID(), false);
            if (out) {
                // 60t 效果每 10t 无缝重加，覆盖昏迷全程
                player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 9, true, false));
                player.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 60, 0, true, false));
                player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 3, true, false));
                if (!prev) {
                    if (!(data.total() >= maxHp && data.l() > 0)) HEAD_ONLY.add(player.getUUID());
                    else HEAD_ONLY.remove(player.getUUID());
                    player.displayClientMessage(Component.translatable(
                            "msg.zhushenspace.health.unconscious"), false);
                    if (WillpowerManager.current(player) >= 1) {
                        player.displayClientMessage(Component.translatable(
                                "msg.zhushenspace.willpower.can_sustain"), false);
                    }
                }
                UNCONSCIOUS.put(player.getUUID(), true);
            } else if (wounded) {
                // 意志力强撑中：解除昏迷硬控
                if (prev) releaseByWillpower(player);
            } else {
                // L 已清零（或伤势未满载）→ 从昏迷中醒来
                checkWake(player, data);
            }
        }
    }

    /** 花费意志力强撑：立即解除昏迷状态与效果（伤势不变） */
    public static void releaseByWillpower(ServerPlayer player) {
        if (!UNCONSCIOUS.getOrDefault(player.getUUID(), false)) return;
        UNCONSCIOUS.put(player.getUUID(), false);
        player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        player.removeEffect(MobEffects.DIG_SLOWDOWN);
        player.removeEffect(MobEffects.WEAKNESS);
    }

    /** 苏醒检查：此前处于昏迷且严重伤已清零 → 清除昏迷效果并提示（死亡时不提示） */
    private static void checkWake(ServerPlayer player, PlayerHealthData data) {
        if (!UNCONSCIOUS.getOrDefault(player.getUUID(), false)) return;
        if (player.isDeadOrDying()) return;
        if (LimbManager.headOut(player)) return; // 头部血量仍为空：继续昏迷
        boolean woundOut = data.total() >= Math.round(player.getMaxHealth()) && data.l() > 0;
        if (HEAD_ONLY.contains(player.getUUID()) ? woundOut : data.l() > 0) return;
        HEAD_ONLY.remove(player.getUUID());
        UNCONSCIOUS.put(player.getUUID(), false);
        player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        player.removeEffect(MobEffects.DIG_SLOWDOWN);
        player.removeEffect(MobEffects.WEAKNESS);
        player.displayClientMessage(Component.translatable(
                "msg.zhushenspace.health.wake"), false);
    }
}
