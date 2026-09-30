package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.DamageKind;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.PlayerConditionData;
import com.zhushen.space.data.PlayerHealthData;
import com.zhushen.space.data.SkillType;
import com.zhushen.space.data.StatusType;
import com.zhushen.space.data.StatusType.Tier;
import com.zhushen.space.network.SyncConditionPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 不良状态点数与倒地（仅玩家）。
 * <h3>不良状态点数</h3>
 * <ul>
 *   <li>每种类型的点数按阈值分档：&gt;0 轻度不良状态；≥ {@link #heavyAt} 重度不良状态；≥ {@link #destructiveAt} 毁灭性后果。
 *       <b>阈值公式为暂定</b>（规则原文的「不良状态点数计算」尚未录入），集中在这两个方法里，补全后只需改这里。</li>
 *   <li>获得点数时可进行豁免（{@link #saveRoll}，同为暂定）：每个成功数抵消 1 点。</li>
 *   <li>冻结 ↔ 燃烧相互反制：处于一方时获得另一方的点数，先扣除等量的已有点数，超出部分才获得。</li>
 *   <li>每回合（10 秒，全服统一时点）结算：燃烧伤害、冻伤、自然恢复。</li>
 * </ul>
 * <h3>倒地</h3>
 * 趴在地上：只能爬行（有蛛行术 / 飞行时不受限），爬起来需要一个移动动作（跳跃键 / 动作轮盘，约 1 秒）。
 * 受到远程攻击时防御 +3（完美）；受到近战攻击时防御 −6（无名）；对抗范围伤害的反射豁免 +3。
 * 传送后可自行决定：传送时按住潜行保持倒地，否则站起。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class StatusManager {

    private StatusManager() {
    }

    public static final int ROUND = QuickHealManager.ROUND_TICKS;
    /** 轻度不良状态的检定 / 攻击 / 防御减值 */
    public static final int LIGHT_PENALTY = 4;
    /** 燃烧（轻度）每回合结束时的火焰严重伤害 */
    public static final int BURN_DAMAGE = 4;
    /** 倒地：远程防御加值 / 近战防御减值 / 反射豁免加值 */
    public static final int PRONE_RANGED_BONUS = 3, PRONE_MELEE_PENALTY = 6, PRONE_REFLEX_BONUS = 3;
    /** 爬起来所需时间（一个移动动作） */
    public static final int STAND_TICKS = 20;

    public enum Source { NATURAL, MALICIOUS, MAGIC }

    private static final ResourceLocation FREEZE_SPEED = ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "status_freeze_speed");

    /** 正在爬起的玩家 → 完成时刻 */
    private static final Map<UUID, Long> STANDING = new HashMap<>();
    /** 扑灭火焰（标准动作）冷却 */
    private static final Map<UUID, Long> EXTINGUISH_CD = new HashMap<>();
    /** 上一次已知的档位（用于提示与触发毁灭性后果） */
    private static final Map<UUID, int[]> LAST_TIER = new HashMap<>();
    /** 燃烧点数造成的伤害进行中（不会被当作原版着火伤害取消；「每当造成火焰伤害时」类能力应忽略） */
    private static boolean statusDamage;

    public static PlayerConditionData data(Player p) {
        return p.getData(ModAttachments.PLAYER_CONDITION);
    }

    /** 当前是否在结算不良状态伤害（供「每当造成火焰伤害时」类能力判断：此时不应触发） */
    public static boolean isStatusDamage() {
        return statusDamage;
    }

    private static int attr(ServerPlayer p, AttributeType t) {
        return CombatFormula.attr(p, t) + FeatEffects.attrBonus(p)[t.ordinal()];
    }

    // ===== 阈值 / 豁免（暂定） =====

    /** 重度不良状态阈值（暂定）：5 + 2 × 关键抵抗属性中较高的一项 */
    public static int heavyAt(ServerPlayer p, StatusType t) {
        return 5 + 2 * Math.max(attr(p, t.attr1), attr(p, t.attr2));
    }

    /** 毁灭性后果阈值（暂定）：重度阈值 × 2 */
    public static int destructiveAt(ServerPlayer p, StatusType t) {
        return heavyAt(p, t) * 2;
    }

    public static Tier tier(ServerPlayer p, StatusType t) {
        int v = data(p).points(t);
        if (v <= 0) return Tier.NONE;
        if (v >= destructiveAt(p, t)) return Tier.DESTRUCTIVE;
        if (v >= heavyAt(p, t)) return Tier.HEAVY;
        return Tier.LIGHT;
    }

    public static boolean atLeast(ServerPlayer p, StatusType t, Tier tier) {
        return tier(p, t).ordinal() >= tier.ordinal();
    }

    /** 豁免成功数（暂定）：强韧 = 两项关键抵抗属性；反射 = 敏捷 + 运动；意志 = 决心 + 沉着；× 20%~100% 浮动 */
    public static int saveRoll(ServerPlayer p, StatusType t) {
        int base = switch (t.save) {
            case FORTITUDE -> attr(p, t.attr1) + attr(p, t.attr2) + PoolEffects.checkBonus(p, t.attr1, t.attr2);
            case REFLEX -> reflexBase(p);
            case WILL -> attr(p, AttributeType.RESOLVE) + attr(p, AttributeType.COMPOSURE)
                    + PoolEffects.checkBonus(p, AttributeType.RESOLVE, AttributeType.COMPOSURE);
        };
        return Math.max(0, Math.round(base * DamageVariance.roll(p.getRandom())));
    }

    private static int reflexBase(ServerPlayer p) {
        return attr(p, AttributeType.AGILITY) + CombatFormula.skill(p, SkillType.ATHLETICS)
                + PoolEffects.checkBonus(p, AttributeType.AGILITY);
    }

    // ===== 获得 / 移除点数 =====

    /**
     * 使玩家获得不良状态点数。
     *
     * @param save       是否允许豁免
     * @param source     造成点数的单位（燃烧伤害的来源；可为 null）
     * @param kind       燃烧的性质（其他类型忽略）
     * @param magicTicks 魔法火焰的持续时间
     * @return 实际获得的点数
     */
    public static int add(ServerPlayer p, StatusType t, int amount, boolean save, Entity source, Source kind, int magicTicks) {
        if (amount <= 0 || !p.isAlive() || p.isCreative() || p.isSpectator()) return 0;
        PlayerConditionData d = data(p);
        var prof = DamageRules.profile(p);
        if (t == StatusType.BURN && prof.immune.contains(DamageKind.FIRE)) return 0;
        if (t == StatusType.FREEZE && prof.immune.contains(DamageKind.COLD)) return 0;
        if (save) amount -= saveRoll(p, t);
        if (amount <= 0) return 0;
        // 冻结 ↔ 燃烧相互反制
        StatusType opposite = t == StatusType.BURN ? StatusType.FREEZE : t == StatusType.FREEZE ? StatusType.BURN : null;
        if (opposite != null && d.points(opposite) > 0) {
            int cancel = Math.min(amount, d.points(opposite));
            d.setPoints(opposite, d.points(opposite) - cancel);
            amount -= cancel;
            if (amount <= 0) {
                changed(p);
                return 0;
            }
        }
        d.setPoints(t, d.points(t) + amount);
        if (t == StatusType.BURN) {
            if (source != null) d.burnSource = source.getUUID();
            int k = kind == null ? 0 : kind.ordinal();
            if (k >= d.burnKind || d.points(t) == amount) d.burnKind = k;
            if (kind == Source.MAGIC) d.magicBurnUntil = Math.max(d.magicBurnUntil, p.level().getGameTime() + magicTicks);
        }
        changed(p);
        return amount;
    }

    public static void reduce(ServerPlayer p, StatusType t, int amount) {
        PlayerConditionData d = data(p);
        if (amount <= 0 || d.points(t) <= 0) return;
        d.setPoints(t, d.points(t) - amount);
        if (t == StatusType.BURN && d.points(t) == 0) d.burnKind = 0;
        changed(p);
    }

    /** 清除点数（t = null：全部）；permanent = true 时一并清除毁灭性后果 */
    public static void clear(ServerPlayer p, StatusType t, boolean permanent) {
        PlayerConditionData d = data(p);
        for (StatusType s : StatusType.values()) {
            if (t != null && s != t) continue;
            d.setPoints(s, 0);
            if (permanent) d.permanent &= ~s.bit();
        }
        if (t == null || t == StatusType.BURN) d.burnKind = 0;
        changed(p);
        if (permanent) {
            p.removeEffect(MobEffects.BLINDNESS);
            p.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        }
    }

    /** 点数变化：检查档位变化（提示 / 毁灭性后果），刷新属性修正并同步 */
    static void changed(ServerPlayer p) {
        PlayerConditionData d = data(p);
        int[] last = LAST_TIER.computeIfAbsent(p.getUUID(), k -> new int[StatusType.COUNT]);
        for (StatusType t : StatusType.values()) {
            Tier now = tier(p, t);
            int before = last[t.ordinal()];
            if (now.ordinal() > before) {
                if (now == Tier.DESTRUCTIVE) destructive(p, t);
                else p.displayClientMessage(Component.translatable("msg.zhushenspace.status.gain",
                        Component.translatable(t.tierKey(now))), true);
            } else if (now.ordinal() < before && now == Tier.NONE) {
                p.displayClientMessage(Component.translatable("msg.zhushenspace.status.clear",
                        Component.translatable(t.nameKey())), true);
            }
            last[t.ordinal()] = now.ordinal();
        }
        applyModifiers(p);
        sync(p);
    }

    /** 毁灭性后果 */
    private static void destructive(ServerPlayer p, StatusType t) {
        PlayerConditionData d = data(p);
        d.permanent |= t.bit();
        MinecraftServer server = p.getServer();
        Component msg = Component.translatable("msg.zhushenspace.status.destructive." + t.key, p.getDisplayName());
        if (server != null) server.getPlayerList().broadcastSystemMessage(msg, false);
        switch (t) {
            case BURN -> { // 火化
                if (p.level() instanceof ServerLevel sl) {
                    sl.sendParticles(ParticleTypes.LARGE_SMOKE, p.getX(), p.getY() + 1, p.getZ(), 40, 0.4, 0.8, 0.4, 0.02);
                    sl.sendParticles(ParticleTypes.FLAME, p.getX(), p.getY() + 1, p.getZ(), 40, 0.4, 0.8, 0.4, 0.05);
                }
                p.kill();
            }
            case FREEZE -> p.level().playSound(null, p.blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1f, 0.5f);
            case TINNITUS -> p.level().playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1f, 2f);
            case DAZZLE -> p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 100, 0, false, false));
        }
    }

    // ===== 效果查询（供战斗 / 检定公式调用） =====

    /** 冰封（永久）或困到昏睡：完全无法行动 */
    public static boolean incapacitated(ServerPlayer p) {
        PlayerConditionData d = data(p);
        return d.isPermanent(StatusType.FREEZE) || d.collapsed;
    }

    /** 看不见：双眼失去 / 目盲（永久）/ 视觉障碍（重度目眩） */
    public static boolean blind(ServerPlayer p) {
        return LimbManager.eyesLost(p) >= 2 || data(p).isPermanent(StatusType.DAZZLE) || atLeast(p, StatusType.DAZZLE, Tier.HEAVY);
    }

    /** 目标防御修正（倒地、轻度不良状态）；src 用于区分远程 / 近战 */
    public static float defenseMod(LivingEntity victim, DamageSource src) {
        if (!(victim instanceof ServerPlayer p)) return 0f;
        return defenseMod(p, isRanged(src));
    }

    public static float defenseMod(ServerPlayer p, boolean ranged) {
        float m = 0;
        PlayerConditionData d = data(p);
        if (d.prone) m += ranged ? PRONE_RANGED_BONUS : -PRONE_MELEE_PENALTY;
        if (atLeast(p, StatusType.FREEZE, Tier.LIGHT)) m -= LIGHT_PENALTY;
        if (atLeast(p, StatusType.TINNITUS, Tier.LIGHT)) m -= LIGHT_PENALTY;
        if (atLeast(p, StatusType.DAZZLE, Tier.LIGHT)) m -= LIGHT_PENALTY;
        return m;
    }

    public static boolean isRanged(DamageSource src) {
        if (src.is(DamageTypeTags.IS_PROJECTILE) || GunDamage.isGun(src)) return true;
        Entity direct = src.getDirectEntity();
        return direct != null && direct != src.getEntity();
    }

    /** 攻击检定减值（冻结轻度 −4；失去一只眼的远程攻击 −4；看不见 −8，均为正数表示扣除） */
    public static int attackPenalty(ServerPlayer p, boolean ranged) {
        int pen = 0;
        if (atLeast(p, StatusType.FREEZE, Tier.LIGHT)) pen += LIGHT_PENALTY;
        if (blind(p)) pen += 8;
        else if (ranged && LimbManager.eyesLost(p) == 1) pen += 4;
        return pen;
    }

    /** 属性检定减值：感知相关检定受耳鸣 / 目眩（轻度起）各 −4 */
    public static int checkPenalty(ServerPlayer p, AttributeType... attrs) {
        boolean per = false;
        for (AttributeType a : attrs) if (a == AttributeType.PERCEPTION) per = true;
        if (!per) return 0;
        int pen = 0;
        if (atLeast(p, StatusType.TINNITUS, Tier.LIGHT) || data(p).isPermanent(StatusType.TINNITUS)) pen += LIGHT_PENALTY;
        if (atLeast(p, StatusType.DAZZLE, Tier.LIGHT) || blind(p)) pen += LIGHT_PENALTY;
        return pen;
    }

    /** 反射豁免加值（倒地时对抗范围伤害 +3） */
    public static int reflexBonus(LivingEntity e) {
        return e instanceof ServerPlayer p && data(p).prone ? PRONE_REFLEX_BONUS : 0;
    }

    /** 冻结：基础移速降低（轻度 −30%，重度 −60%，冰封 无法移动） */
    private static void applyModifiers(ServerPlayer p) {
        AttributeInstance inst = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (inst == null) return;
        Tier ft = tier(p, StatusType.FREEZE);
        double v = data(p).isPermanent(StatusType.FREEZE) ? -1.0 : ft == Tier.NONE ? 0 : ft == Tier.LIGHT ? -0.3 : -0.6;
        inst.removeModifier(FREEZE_SPEED);
        if (v != 0) inst.addTransientModifier(new AttributeModifier(FREEZE_SPEED, v, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    // ===== 倒地 =====

    public static boolean prone(Player p) {
        return data(p).prone;
    }

    /** 有蛛行术 / 飞行能力时不受倒地的移动限制 */
    public static boolean freeMover(ServerPlayer p) {
        return p.getAbilities().flying || p.isFallFlying() || (PoolEffects.flags(p) & PoolEffects.F_SPIDER) != 0;
    }

    public static void setProne(ServerPlayer p, boolean prone, String msgKey) {
        PlayerConditionData d = data(p);
        STANDING.remove(p.getUUID());
        if (d.prone == prone) return;
        d.prone = prone;
        if (!prone && p.getPose() == Pose.SWIMMING && !p.isInWater()) p.setPose(Pose.STANDING);
        if (msgKey != null) p.displayClientMessage(Component.translatable(msgKey), true);
        sync(p);
    }

    /** 卧倒（自愿，立即） / 爬起来（一个移动动作） */
    public static void toggleProne(ServerPlayer p) {
        if (data(p).prone) standUp(p);
        else if (!p.isPassenger() && !p.isSleeping()) setProne(p, true, "msg.zhushenspace.prone.down");
    }

    public static void standUp(ServerPlayer p) {
        PlayerConditionData d = data(p);
        if (!d.prone || STANDING.containsKey(p.getUUID())) return;
        if (incapacitated(p) || HealthManager.isUnconscious(p)) {
            p.displayClientMessage(Component.translatable("msg.zhushenspace.prone.cant"), true);
            return;
        }
        var limbs = LimbManager.data(p);
        if (limbs.isSevered(com.zhushen.space.data.LimbPart.RIGHT_LEG) && limbs.isSevered(com.zhushen.space.data.LimbPart.LEFT_LEG)) {
            p.displayClientMessage(Component.translatable("msg.zhushenspace.prone.no_legs"), true);
            return;
        }
        STANDING.put(p.getUUID(), p.level().getGameTime() + STAND_TICKS);
        p.displayClientMessage(Component.translatable("msg.zhushenspace.prone.standing"), true);
    }

    /** 传送后可自行决定：按住潜行保持倒地，否则站起 */
    @SubscribeEvent
    public static void onPearl(EntityTeleportEvent.EnderPearl event) {
        afterTeleport(event.getEntity());
    }

    @SubscribeEvent
    public static void onChorus(EntityTeleportEvent.ChorusFruit event) {
        afterTeleport(event.getEntity());
    }

    private static void afterTeleport(Entity e) {
        if (e instanceof ServerPlayer p && data(p).prone && !p.isShiftKeyDown()
                && !incapacitated(p) && !HealthManager.isUnconscious(p)) {
            setProne(p, false, null);
        }
    }

    // ===== 扑灭火焰 =====

    /** 标准动作：反射豁免，每个成功数消除 1 点燃烧点数（魔法火焰无法以物理方式熄灭） */
    public static void extinguish(ServerPlayer p) {
        PlayerConditionData d = data(p);
        if (d.points(StatusType.BURN) <= 0) {
            p.displayClientMessage(Component.translatable("msg.zhushenspace.status.not_burning"), true);
            return;
        }
        if (d.burnKind == Source.MAGIC.ordinal() && d.magicBurnUntil > p.level().getGameTime()) {
            p.displayClientMessage(Component.translatable("msg.zhushenspace.status.magic_fire"), true);
            return;
        }
        long now = p.level().getGameTime();
        Long cd = EXTINGUISH_CD.get(p.getUUID());
        if (cd != null && cd > now) {
            p.displayClientMessage(Component.translatable("msg.zhushenspace.status.extinguish_cd", (cd - now + 19) / 20), true);
            return;
        }
        EXTINGUISH_CD.put(p.getUUID(), now + ROUND);
        int s = Math.max(0, Math.round(reflexBase(p) * DamageVariance.roll(p.getRandom())));
        int before = d.points(StatusType.BURN);
        reduce(p, StatusType.BURN, s);
        int removed = before - d.points(StatusType.BURN);
        p.level().playSound(null, p.blockPosition(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.6f, 1.2f);
        p.displayClientMessage(Component.translatable("msg.zhushenspace.status.extinguish", removed), true);
    }

    // ===== 环境来源 =====

    /** 爆炸：近处的玩家获得耳鸣点数（强韧豁免），看得见爆心的还会获得目眩点数 */
    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Vec3 c = event.getExplosion().center();
        float r = event.getExplosion().radius();
        if (r <= 0.5f) return;
        Entity src = event.getExplosion().getIndirectSourceEntity();
        for (ServerPlayer p : level.players()) {
            double dist = p.getEyePosition().distanceTo(c);
            double range = r * 3;
            if (dist > range) continue;
            int tin = (int) Math.round(r * 2 * (1 - dist / range));
            if (tin > 0) add(p, StatusType.TINNITUS, tin, true, src, Source.NATURAL, 0);
            if (dist < r * 2 && canSee(p, c)) {
                int daz = (int) Math.round(r * (1 - dist / (r * 2)));
                if (daz > 0) add(p, StatusType.DAZZLE, daz, true, src, Source.NATURAL, 0);
            }
        }
    }

    /** 闪电：附近看得见落点的玩家获得目眩点数 */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof LightningBolt bolt) || !(event.getLevel() instanceof ServerLevel level)) return;
        Vec3 c = bolt.position().add(0, 1, 0);
        for (ServerPlayer p : level.players()) {
            double dist = p.getEyePosition().distanceTo(c);
            if (dist > 24 || !canSee(p, c)) continue;
            Vec3 look = p.getViewVector(1f), to = c.subtract(p.getEyePosition()).normalize();
            if (look.dot(to) < 0.3) continue; // 背对闪电
            int daz = (int) Math.round(4 * (1 - dist / 24));
            if (daz > 0) add(p, StatusType.DAZZLE, daz, true, null, Source.NATURAL, 0);
        }
    }

    private static boolean canSee(ServerPlayer p, Vec3 c) {
        return p.level().clip(new ClipContext(p.getEyePosition(), c, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p))
                .getType() == HitResult.Type.MISS;
    }

    /** 原版着火 / 冰冻伤害由不良状态点数接管（接触火焰 / 岩浆的伤害仍然保留） */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onIncoming(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer)) return;
        DamageSource s = event.getSource();
        if (!statusDamage && (s.is(DamageTypes.ON_FIRE) || s.is(DamageTypes.FREEZE))) event.setCanceled(true);
    }

    private static boolean isCold(ServerPlayer p) {
        if (p.level().dimensionType().ultraWarm()) return false;
        BlockPos pos = p.blockPosition();
        if (!p.level().getBiome(pos).value().coldEnoughToSnow(pos)) return false;
        return !nearHeat(p);
    }

    private static boolean nearHeat(ServerPlayer p) {
        BlockPos c = p.blockPosition();
        for (BlockPos bp : BlockPos.betweenClosed(c.offset(-3, -1, -3), c.offset(3, 2, 3))) {
            BlockState st = p.level().getBlockState(bp);
            if (st.is(BlockTags.CAMPFIRES) || st.is(BlockTags.FIRE) || st.is(Blocks.LAVA) || st.is(Blocks.MAGMA_BLOCK)
                    || st.is(Blocks.FURNACE) && st.getLightEmission() > 0) return true;
        }
        return false;
    }

    // ===== 每 tick / 每回合 =====

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !p.isAlive()) return;
        PlayerConditionData d = data(p);
        long now = p.level().getGameTime();

        // 倒地：强制趴伏（有蛛行术 / 飞行能力时不受限），爬起完成
        if (d.prone) {
            Long st = STANDING.get(p.getUUID());
            if (st != null && now >= st) setProne(p, false, null);
            else if (!freeMover(p) && !p.isPassenger() && !p.isSleeping()) {
                if (p.getPose() != Pose.SWIMMING) p.setPose(Pose.SWIMMING);
                p.setSprinting(false);
            }
        }
        // 昏迷 / 困到昏睡时倒在地上
        if (!d.prone && (HealthManager.isUnconscious(p) || d.collapsed) && !p.isSleeping() && !p.isPassenger()) {
            setProne(p, true, null);
        }

        // 燃烧：跳入水里解除（魔法火焰除外）；原版着火转化为燃烧点数；燃烧中显示火焰
        int burn = d.points(StatusType.BURN);
        if (burn > 0 && p.isInWater() && !(d.burnKind == Source.MAGIC.ordinal() && d.magicBurnUntil > now)) {
            clear(p, StatusType.BURN, false);
            p.level().playSound(null, p.blockPosition(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.7f, 1f);
            burn = 0;
        }
        if (p.tickCount % 20 == 0 && p.getRemainingFireTicks() > 0 && !p.isInWater()) {
            add(p, StatusType.BURN, p.isInLava() ? 2 : 1, false, null, Source.NATURAL, 0);
            p.setRemainingFireTicks(0);
            burn = d.points(StatusType.BURN);
        }
        if (burn > 0) {
            p.setSharedFlagOnFire(true);
            if (p.tickCount % 5 == 0 && p.level() instanceof ServerLevel sl) {
                sl.sendParticles(ParticleTypes.FLAME, p.getX(), p.getY() + 0.9, p.getZ(), 2, 0.25, 0.5, 0.25, 0.01);
            }
        }

        // 冻结：粉雪中持续获得冻结点数；冻结时显示结霜
        if (p.tickCount % 20 == 0 && p.isInPowderSnow && p.canFreeze()) {
            add(p, StatusType.FREEZE, 1, false, null, Source.NATURAL, 0);
        }
        Tier ft = tier(p, StatusType.FREEZE);
        if (d.isPermanent(StatusType.FREEZE) || ft != Tier.NONE) {
            int need = p.getTicksRequiredToFreeze();
            int target = d.isPermanent(StatusType.FREEZE) || ft.ordinal() >= Tier.HEAVY.ordinal() ? need : need / 2;
            if (p.getTicksFrozen() < target) p.setTicksFrozen(target);
        }

        // 看不见：持续失明
        if (p.tickCount % 20 == 0 && blind(p)) {
            p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0, true, false));
        }
        // 冰封：完全无法行动
        if (d.isPermanent(StatusType.FREEZE) && p.tickCount % 20 == 0) {
            p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 9, true, false));
            p.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 60, 4, true, false));
            p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 4, true, false));
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int tick = server.getTickCount();
        if (tick % ROUND != 0) return;
        boolean coldCheck = tick % (ROUND * 3) == 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.isAlive()) continue;
            round(p, coldCheck);
        }
    }

    /** 回合结算：燃烧伤害 → 冻伤 → 自然恢复 */
    private static void round(ServerPlayer p, boolean coldCheck) {
        PlayerConditionData d = data(p);
        long now = p.level().getGameTime();
        boolean dirty = false;

        // 燃烧：每回合结束时受到不可避免的火焰严重伤害（来源 = 造成燃烧点数的单位）
        Tier bt = tier(p, StatusType.BURN);
        if (bt != Tier.NONE && bt != Tier.DESTRUCTIVE) {
            int dmg = bt == Tier.HEAVY ? BURN_DAMAGE * 2 : BURN_DAMAGE;
            Entity src = d.burnSource == null || p.getServer() == null ? null : findEntity(p.getServer(), d.burnSource);
            DamageSource ds = new DamageSource(p.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                    .getHolderOrThrow(DamageTypes.ON_FIRE), src);
            statusDamage = true;
            try {
                p.invulnerableTime = 0;
                DamageRules.deal(p, ds, dmg, DamageRules.Spec.of(PlayerHealthData.Severity.L, DamageKind.FIRE));
            } finally {
                statusDamage = false;
            }
            if (!p.isAlive()) return;
            if (d.burnKind == Source.MAGIC.ordinal() && d.magicBurnUntil <= now) d.burnKind = Source.NATURAL.ordinal();
            if (d.burnKind == Source.NATURAL.ordinal()) { d.setPoints(StatusType.BURN, d.points(StatusType.BURN) - 1); dirty = true; }
        }

        // 冻伤（重度冻结）：每回合 1 点冲击伤
        Tier ft = tier(p, StatusType.FREEZE);
        if (ft == Tier.HEAVY) HealthManager.addWound(p, PlayerHealthData.Severity.B, 1);
        // 冻结：寒冷环境（雪地生物群系、附近没有热源）中逐渐冻僵；适当的温度下每回合恢复 1 点
        boolean cold = isCold(p);
        if (cold && p.canFreeze()) {
            if (coldCheck) add(p, StatusType.FREEZE, p.isInWater() ? 2 : 1, true, null, Source.NATURAL, 0);
        } else if (d.points(StatusType.FREEZE) > 0 && !p.isInPowderSnow) {
            d.setPoints(StatusType.FREEZE, d.points(StatusType.FREEZE) - 1);
            dirty = true;
        }

        // 耳鸣 / 目眩：每回合恢复 1 点（暂定）
        for (StatusType t : new StatusType[]{StatusType.TINNITUS, StatusType.DAZZLE}) {
            if (d.points(t) > 0) { d.setPoints(t, d.points(t) - 1); dirty = true; }
        }
        if (dirty) changed(p);
    }

    private static Entity findEntity(MinecraftServer server, UUID id) {
        ServerPlayer sp = server.getPlayerList().getPlayer(id);
        if (sp != null) return sp;
        for (ServerLevel l : server.getAllLevels()) {
            Entity e = l.getEntity(id);
            if (e != null) return e;
        }
        return null;
    }

    // ===== 同步 / 生命周期 =====

    public static void sync(ServerPlayer p) {
        PlayerConditionData d = data(p);
        int[] tiers = new int[StatusType.COUNT], heavy = new int[StatusType.COUNT], destr = new int[StatusType.COUNT];
        for (StatusType t : StatusType.values()) {
            tiers[t.ordinal()] = tier(p, t).ordinal();
            heavy[t.ordinal()] = heavyAt(p, t);
            destr[t.ordinal()] = destructiveAt(p, t);
        }
        int flags = (d.exhausted ? 1 : 0) | (d.collapsed ? 2 : 0) | (d.prone ? 4 : 0)
                | (STANDING.containsKey(p.getUUID()) ? 8 : 0) | (incapacitated(p) ? 16 : 0) | (freeMover(p) ? 32 : 0);
        PacketDistributor.sendToPlayer(p, new SyncConditionPayload(d.thirst, Math.max(0, d.stamina),
                SurvivalManager.maxStamina(p), d.sleep, flags, d.points.clone(), tiers, heavy, destr, d.permanent));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            LAST_TIER.remove(p.getUUID());
            changed(p);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            LAST_TIER.remove(p.getUUID());
            STANDING.remove(p.getUUID());
            changed(p);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        UUID id = e.getEntity().getUUID();
        STANDING.remove(id);
        EXTINGUISH_CD.remove(id);
        LAST_TIER.remove(id);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent e) {
        STANDING.clear();
        EXTINGUISH_CD.clear();
        LAST_TIER.clear();
    }
}
