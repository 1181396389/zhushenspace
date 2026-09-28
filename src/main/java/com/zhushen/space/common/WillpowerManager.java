package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.PlayerEnergyData;
import com.zhushen.space.data.PlayerHealthData;
import com.zhushen.space.network.SyncWillpowerPayload;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.SleepFinishedTimeEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 意志力池（独立于所有能量池，不参与主能量池判定，也不会被太极化劲封印）。
 * <p>
 * 上限 = 决心加点；决心满 5 点时每点传奇点数额外 +3 上限（决心传奇只扩充意志力池，不再扩充任何能量池）。
 * 恢复：睡觉跳过夜晚时回满。
 * <p>
 * 用法（每次支付 1 点意志力）：
 * <ol>
 *   <li><b>意志加持</b>（G 键预备）：下一次检定换算为 9 点不受浮动影响的伤害——
 *       战斗攻击时直接加在本次命中伤害上（浮动与伤害上限之后）；对抗（缴械 / 擒抱 / 摔绊）时对抗权重 +9 优势，并直接对对手追加 9 点伤害。
 *       一次行动仅生效一次（首个检定消耗后即解除）。</li>
 *   <li><b>意志守御</b>（B 键预备）：下一次受到攻击时，针对该次攻击获得 +9 护甲与 +9 护甲韧性。</li>
 *   <li><b>强撑</b>：因伤势过重昏迷时按 G 花费 1 点意志力，继续行动 1 分钟（期间不会因伤势过重昏迷）。
 *       到期时若仍伤势满载：非战斗中自动续付 1 点；战斗中给出 5 秒抉择窗口，按 G 续付，否则立即昏迷。</li>
 *   <li>强撑<b>不减免任何伤害</b>：受击照常记录伤势并向下转化（B→L→A），全身恶性满载照常死亡，只是不会昏迷。</li>
 * </ol>
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class WillpowerManager {

    public static final String POOL_ID = "willpower";
    public static final int PERFECT_BONUS = 9;
    public static final double GUARD_ARMOR = 9.0;
    public static final double GUARD_TOUGHNESS = 9.0;
    public static final double CAPACITY_PER_LEGENDARY = 3.0;
    /** 强撑时长：1 分钟 */
    public static final int SUSTAIN_TICKS = 1200;
    /** 战斗中续付抉择窗口：5 秒 */
    public static final int GRACE_TICKS = 100;
    /** 最近 15 秒内攻击 / 受击视为战斗中 */
    private static final int COMBAT_WINDOW_TICKS = 300;

    private static final ResourceLocation GUARD_ARMOR_ID =
            ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "willpower_guard_armor");
    private static final ResourceLocation GUARD_TOUGH_ID =
            ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "willpower_guard_toughness");

    /** 客户端 → 服务端动作 */
    public static final int ACTION_PRIMARY = 0;   // G：强撑 / 预备意志加持
    public static final int ACTION_GUARD = 1;     // B：预备意志守御

    private static final class State {
        boolean armedCheck;
        boolean armedGuard;
        long sustainEnd = -1;
        long graceEnd = -1;
        long guardAppliedTick = -1;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    private WillpowerManager() {
    }

    private static State state(ServerPlayer p) {
        return STATES.computeIfAbsent(p.getUUID(), k -> new State());
    }

    private static long now(ServerPlayer p) {
        return p.getServer() == null ? 0 : p.getServer().getTickCount();
    }

    // ===== 池容量 =====

    /** 按决心重算意志力池上限（由 EnergyManager.syncLegendaryPools 调用，调用方负责同步） */
    public static void recalc(ServerPlayer player, PlayerEnergyData data) {
        int[] p = player.getData(ModAttachments.PLAYER_ATTRIBUTES).points();
        int resolve = p[AttributeType.RESOLVE.ordinal()];
        double max = resolve;
        if (resolve >= AttributeType.MAX_POINTS) {
            max += AttributeType.legendaryCount(p) * CAPACITY_PER_LEGENDARY;
        }
        if (max <= 0) {
            data.removePool(POOL_ID);
            return;
        }
        PlayerEnergyData.Pool pool = data.getPool(POOL_ID);
        if (pool == null) {
            data.pools().put(POOL_ID, new PlayerEnergyData.Pool(max, max, max));
        } else {
            pool.base = max;
            pool.max = max;
            pool.current = Math.min(pool.current, max);
        }
    }

    public static double current(ServerPlayer player) {
        PlayerEnergyData.Pool pool = player.getData(ModAttachments.PLAYER_ENERGY).getPool(POOL_ID);
        return pool == null ? 0 : pool.current;
    }

    private static boolean spend(ServerPlayer player) {
        if (!player.getData(ModAttachments.PLAYER_ENERGY).consume(POOL_ID, 1.0)) return false;
        EnergyManager.sync(player);
        return true;
    }

    // ===== 玩家按键 =====

    public static void handleAction(ServerPlayer player, int action) {
        if (!player.isAlive()) return;
        State s = state(player);
        long t = now(player);
        if (action == ACTION_PRIMARY) {
            // 1) 战斗中强撑到期的抉择窗口：续付
            if (s.graceEnd >= 0) {
                if (spend(player)) {
                    s.graceEnd = -1;
                    s.sustainEnd = t + SUSTAIN_TICKS;
                    player.displayClientMessage(Component.translatable("msg.zhushenspace.willpower.sustain_renew"), true);
                    willFx(player, true);
                } else {
                    lack(player);
                }
                sync(player);
                return;
            }
            // 2) 因伤势过重昏迷：花费 1 点强撑
            if (isWoundedOut(player) && !isSustained(player)) {
                if (spend(player)) {
                    s.sustainEnd = t + SUSTAIN_TICKS;
                    HealthManager.releaseByWillpower(player);
                    player.displayClientMessage(Component.translatable("msg.zhushenspace.willpower.sustain_start"), false);
                    willFx(player, true);
                } else {
                    lack(player);
                }
                sync(player);
                return;
            }
            // 3) 预备 / 取消意志加持
            if (!s.armedCheck && current(player) < 1) {
                lack(player);
                return;
            }
            s.armedCheck = !s.armedCheck;
            player.displayClientMessage(Component.translatable(s.armedCheck
                    ? "msg.zhushenspace.willpower.check_armed" : "msg.zhushenspace.willpower.check_cancel"), true);
            sync(player);
        } else if (action == ACTION_GUARD) {
            if (!s.armedGuard && current(player) < 1) {
                lack(player);
                return;
            }
            s.armedGuard = !s.armedGuard;
            player.displayClientMessage(Component.translatable(s.armedGuard
                    ? "msg.zhushenspace.willpower.guard_armed" : "msg.zhushenspace.willpower.guard_cancel"), true);
            sync(player);
        }
    }

    private static void lack(ServerPlayer player) {
        player.displayClientMessage(Component.translatable("msg.zhushenspace.willpower.lack"), true);
    }

    // ===== 意志加持：检定 +9 =====

    /**
     * 一次检定询问意志加持：已预备且成功支付 1 点意志力时返回 {@link #PERFECT_BONUS} 并解除预备，否则返回 0。
     * 供攻击伤害、对抗等所有检定调用；首个检定消耗后即失效，因此一次行动只会生效一次。
     */
    public static int consumeCheckBonus(ServerPlayer player) {
        if (BONUS_STRIKE.contains(player.getUUID())) return 0;
        State s = STATES.get(player.getUUID());
        if (s == null || !s.armedCheck) return 0;
        s.armedCheck = false;
        if (!spend(player)) {
            lack(player);
            sync(player);
            return 0;
        }
        player.displayClientMessage(Component.translatable("msg.zhushenspace.willpower.check_used", PERFECT_BONUS), true);
        willFx(player, false);
        sync(player);
        return PERFECT_BONUS;
    }

    /** 意志加持追加伤害进行中（跳过伤害浮动、暴击 / 弱点等倍率，也不会再次询问加持） */
    private static final java.util.Set<UUID> BONUS_STRIKE = new java.util.HashSet<>();

    public static boolean isBonusStrike(ServerPlayer player) {
        return BONUS_STRIKE.contains(player.getUUID());
    }

    /**
     * 对抗中的意志加持：已预备则支付 1 点意志力，直接对对手造成 9 点不受浮动影响的伤害，
     * 并返回 9 供调用方计入对抗权重（同一次支付，两者同时生效）。
     * （战斗攻击中则是在本次命中伤害上直接 +9，见 DamageCap / onIncomingLast）
     */
    public static int contestStrike(ServerPlayer player, LivingEntity target) {
        if (target == null || !target.isAlive()) return 0;
        int bonus = consumeCheckBonus(player);
        if (bonus <= 0) return 0;
        BONUS_STRIKE.add(player.getUUID());
        try {
            target.invulnerableTime = 0;
            target.hurt(player.damageSources().playerAttack(player), bonus);
        } finally {
            BONUS_STRIKE.remove(player.getUUID());
        }
        return bonus;
    }

    /** 非近战（弹射物 / 枪械 / 技能伤害）的玩家攻击：数值阶段最后追加完好加值（近战在 DamageCap 截断之后追加） */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onIncomingLast(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        DamageSource src = event.getSource();
        if (src.getEntity() instanceof ServerPlayer attacker && attacker != victim
                && DamageCap.meleeAttacker(src, victim) == null && event.getAmount() > 0f) {
            int bonus = consumeCheckBonus(attacker);
            if (bonus > 0) event.setAmount(event.getAmount() + bonus);
        }
        // 意志守御：受到攻击（有攻击者）时本次护甲 +9、韧性 +9
        if (victim instanceof ServerPlayer player && src.getEntity() != null && src.getEntity() != player
                && !src.is(DamageTypeTags.BYPASSES_ARMOR)) {
            State s = STATES.get(player.getUUID());
            if (s != null && s.armedGuard) {
                s.armedGuard = false;
                if (spend(player)) {
                    applyGuard(player);
                    s.guardAppliedTick = now(player);
                    player.displayClientMessage(Component.translatable("msg.zhushenspace.willpower.guard_used",
                            (int) GUARD_ARMOR, (int) GUARD_TOUGHNESS), true);
                    willFx(player, false);
                } else {
                    lack(player);
                }
                sync(player);
            }
        }
    }

    private static void applyGuard(ServerPlayer player) {
        AttributeInstance armor = player.getAttribute(Attributes.ARMOR);
        AttributeInstance tough = player.getAttribute(Attributes.ARMOR_TOUGHNESS);
        if (armor != null && !armor.hasModifier(GUARD_ARMOR_ID)) {
            armor.addTransientModifier(new AttributeModifier(GUARD_ARMOR_ID, GUARD_ARMOR, AttributeModifier.Operation.ADD_VALUE));
        }
        if (tough != null && !tough.hasModifier(GUARD_TOUGH_ID)) {
            tough.addTransientModifier(new AttributeModifier(GUARD_TOUGH_ID, GUARD_TOUGHNESS, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    private static void removeGuard(ServerPlayer player) {
        AttributeInstance armor = player.getAttribute(Attributes.ARMOR);
        AttributeInstance tough = player.getAttribute(Attributes.ARMOR_TOUGHNESS);
        if (armor != null) armor.removeModifier(GUARD_ARMOR_ID);
        if (tough != null) tough.removeModifier(GUARD_TOUGH_ID);
    }

    /** 护甲结算完毕：撤去意志守御的临时护甲 */
    @SubscribeEvent
    public static void onDamagePost(LivingDamageEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            State s = STATES.get(player.getUUID());
            if (s != null && s.guardAppliedTick >= 0) {
                s.guardAppliedTick = -1;
                removeGuard(player);
            }
        }
    }

    // ===== 强撑（延后昏迷） =====

    private static boolean isWoundedOut(ServerPlayer player) {
        PlayerHealthData data = player.getData(ModAttachments.PLAYER_HEALTH);
        return data.total() >= Math.round(player.getMaxHealth()) && data.l() > 0;
    }

    /** 是否处于强撑持续时间或抉择窗口内（不会因伤势过重昏迷） */
    public static boolean isSustained(ServerPlayer player) {
        State s = STATES.get(player.getUUID());
        if (s == null) return false;
        long t = now(player);
        return t < s.sustainEnd || (s.graceEnd >= 0 && t < s.graceEnd);
    }

    /** 战斗判定：最近 15 秒内攻击过 / 被攻击过，或附近有以自己为目标的生物 */
    public static boolean inCombat(ServerPlayer player) {
        int tc = player.tickCount;
        if (player.getLastHurtByMob() != null && tc - player.getLastHurtByMobTimestamp() < COMBAT_WINDOW_TICKS) return true;
        if (player.getLastHurtMob() != null && tc - player.getLastHurtMobTimestamp() < COMBAT_WINDOW_TICKS) return true;
        return !player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(16),
                m -> m.isAlive() && m.getTarget() == player).isEmpty();
    }

    /**
     * 昏迷看护询问（HealthManager 每 10 tick 调用）：伤势满载时是否仍凭意志力站着。
     * 负责强撑到期后的续付：非战斗自动续付，战斗中开启 5 秒抉择窗口。
     */
    public static boolean holdsOn(ServerPlayer player, boolean wounded) {
        State s = STATES.get(player.getUUID());
        if (s == null) return false;
        long t = now(player);
        if (!wounded) {
            if (s.graceEnd >= 0) {
                s.graceEnd = -1;
                sync(player);
            }
            return false;
        }
        if (t < s.sustainEnd) return true;
        if (s.sustainEnd < 0) return false; // 从未强撑 / 已放弃：保持昏迷，可随时按 G 强撑
        // 强撑已到期
        if (s.graceEnd >= 0) {
            if (t < s.graceEnd) return true;
            s.graceEnd = -1;
            s.sustainEnd = -1;
            sync(player);
            return false; // 未续付 → 立即昏迷
        }
        if (current(player) < 1) {
            s.sustainEnd = -1;
            sync(player);
            return false;
        }
        if (!inCombat(player)) {
            spend(player);
            s.sustainEnd = t + SUSTAIN_TICKS;
            player.displayClientMessage(Component.translatable("msg.zhushenspace.willpower.sustain_auto"), true);
            sync(player);
            return true;
        }
        s.graceEnd = t + GRACE_TICKS;
        player.displayClientMessage(Component.translatable("msg.zhushenspace.willpower.sustain_decide"), false);
        player.level().playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_BELL.value(),
                SoundSource.PLAYERS, 0.8f, 0.6f);
        sync(player);
        return true;
    }

    // ===== 恢复 / 生命周期 =====

    @SubscribeEvent
    public static void onSleepFinished(SleepFinishedTimeEvent event) {
        for (var entity : event.getLevel().players()) {
            if (!(entity instanceof ServerPlayer player)) continue;
            if (player.getData(ModAttachments.PLAYER_ENERGY).restore(POOL_ID, Double.MAX_VALUE) > 0) {
                player.displayClientMessage(Component.translatable("msg.zhushenspace.willpower.sleep"), false);
                EnergyManager.sync(player);
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        long t = event.getServer().getTickCount();
        for (Map.Entry<UUID, State> e : STATES.entrySet()) {
            State s = e.getValue();
            // 兜底：受击被取消等情况下 Post 未触发，下一 tick 撤去临时护甲
            if (s.guardAppliedTick >= 0 && s.guardAppliedTick < t) {
                ServerPlayer p = event.getServer().getPlayerList().getPlayer(e.getKey());
                s.guardAppliedTick = -1;
                if (p != null) removeGuard(p);
            }
        }
    }

    private static void reset(ServerPlayer player) {
        STATES.remove(player.getUUID());
        removeGuard(player);
        sync(player);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) reset(p);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) reset(p);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        STATES.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        STATES.clear();
    }

    // ===== 同步 / 特效 =====

    public static void sync(ServerPlayer player) {
        State s = STATES.get(player.getUUID());
        long t = now(player);
        int sustain = 0, grace = 0;
        boolean check = false, guard = false;
        if (s != null) {
            sustain = (int) Math.max(0, s.sustainEnd - t);
            grace = s.graceEnd >= 0 ? (int) Math.max(0, s.graceEnd - t) : 0;
            check = s.armedCheck;
            guard = s.armedGuard;
        }
        PacketDistributor.sendToPlayer(player, new SyncWillpowerPayload(sustain, grace, check, guard));
    }

    private static void willFx(ServerPlayer player, boolean big) {
        player.level().playSound(null, player.blockPosition(), SoundEvents.BEACON_POWER_SELECT,
                SoundSource.PLAYERS, big ? 0.9f : 0.5f, big ? 0.8f : 1.4f);
        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.FLAME, player.getX(), player.getY() + 1.0, player.getZ(),
                    big ? 24 : 8, 0.35, 0.5, 0.35, 0.02);
        }
    }
}
