package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.PlayerSkillData;
import com.zhushen.space.data.SkillAbility;
import com.zhushen.space.data.SkillType;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 主动技能执行与服务端状态管理。
 *
 * 技能效果：
 * - 自我保护：护甲 +（敏捷+运动）点数，持续 5 秒，冷却 30 秒
 * - 跳跃：跳跃力 +（力量+运动）×0.1，仅维持一次跳跃（起跳即消耗）
 * - 攀爬：开启后 8 秒内可贴墙爬升（力量+运动）点数的高度
 * - 肉搏格挡：护甲 +肉搏点数，持续 5 秒，冷却 20 秒
 * - 摔绊：下次命中使目标倒地（大幅减速+虚弱），飞行中的目标直接坠落，冷却 12 秒
 * - 冲锋攻击：开启后 10 秒内每移动 1 格 +1 攻击伤害（上限 15），
 *   命中时消耗并暂时失去等量护甲 3 秒，冷却 30 秒
 * - 白刃格挡：护甲 +白刃点数，持续 5 秒，冷却 20 秒
 *
 * 被动效果：
 * - 肉搏：每点 +1 徒手攻击力
 * - 白刃：每点 +1 冷兵器（剑/斧/三叉戟/重锤等非枪械武器）攻击力
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public class SkillManager {

    private static final long BUFF_DURATION_MS = SkillAbility.BUFF_DURATION_TICKS * 50L;
    private static final long CLIMB_DURATION_MS = SkillAbility.CLIMB_DURATION_TICKS * 50L;
    private static final long TRIP_WINDOW_MS = SkillAbility.TRIP_WINDOW_TICKS * 50L;
    private static final long CHARGE_WINDOW_MS = SkillAbility.CHARGE_WINDOW_TICKS * 50L;
    private static final long CHARGE_DEBUFF_MS = SkillAbility.CHARGE_DEBUFF_TICKS * 50L;
    private static final long TINGJIN_DURATION_MS = SkillAbility.TINGJIN_DURATION_TICKS * 50L;
    /** 引手姿态持续时长（毫秒） */
    private static final long YINSHOU_DURATION_MS = SkillAbility.YINSHOU_DURATION_TICKS * 50L;
    /** 揽雀尾待势窗口（毫秒） */
    private static final long LANQUEWEI_WINDOW_MS = SkillAbility.LANQUEWEI_WINDOW_TICKS * 50L;
    /** 云手待势窗口（毫秒） */
    private static final long CLOUD_HANDS_WINDOW_MS = SkillAbility.CLOUD_HANDS_WINDOW_TICKS * 50L;
    /** 海底针待势窗口（毫秒） */
    private static final long SEA_BOTTOM_WINDOW_MS = SkillAbility.SEA_BOTTOM_WINDOW_TICKS * 50L;
    /** 太极化劲待势窗口（毫秒） */
    private static final long DISSOLVE_WINDOW_MS = SkillAbility.DISSOLVE_WINDOW_TICKS * 50L;
    /** 摔绊倒地最长持续时间（落地即提前恢复） */
    private static final long KNOCKDOWN_DURATION_MS = 3000;

    private static final ResourceLocation SELF_PROTECTION_MOD =
            ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "self_protection");
    private static final ResourceLocation BRAWL_BLOCK_MOD =
            ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "brawl_block");
    private static final ResourceLocation BLADE_BLOCK_MOD =
            ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "blade_block");
    private static final ResourceLocation CHARGE_DEBUFF_MOD =
            ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "charge_debuff");
    private static final ResourceLocation LEAP_MOD =
            ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "leap");

    private static final Map<UUID, State> STATES = new HashMap<>();
    /** 摔绊倒地中的目标（飞行生物停摆 AI / 鞘翅玩家持续下拉），到期或落地后恢复 */
    private static final Map<UUID, Knockdown> KNOCKED = new HashMap<>();

    /** 单个玩家的技能瞬态状态（冷却与进行中的技能效果） */
    private static class State {
        final long[] cooldownEndMs = new long[SkillAbility.COUNT];
        // 临时护甲/跳跃增益到期时间
        long selfProtectionEnd, brawlBlockEnd, bladeBlockEnd, chargeDebuffEnd, leapExpire;
        boolean leapActive;
        // 攀爬（剩余时间同步给客户端，实际爬升由客户端执行）
        boolean climbActive;
        long climbExpire;
        // 冲锋
        boolean chargeActive;
        long chargeExpire;
        double chargeDistance;
        double chargeLastX, chargeLastZ;
        // 摔绊待触发窗口
        long tripPendingEnd;
        // 听劲（太极拳主动技）：自身攻击 -20%、受伤 -20%，持续 10 秒
        long tingjinEnd;
        // 引手姿态（太极拳主动技）：期间受击为攻击者叠加减值层，持续 1 分钟
        long yinshouEnd;
        // 揽雀尾待势窗口（太极拳主动技）：期间首次受到近战攻击时结算缴械对抗
        long lanqueweiPendingEnd;
        // 云手待势窗口：期间首次受到近战攻击时结算擒抱对抗
        long cloudhandsPendingEnd;
        // 海底针待势窗口：期间受到近战攻击或身边目标主动移动时结算摔绊对抗
        long seabottomPendingEnd;
        // 太极化劲待势窗口：期间受到使用能量池的近战攻击时反击并连锁
        long dissolvePendingEnd;
    }

    /** 摔绊倒地状态：aiDisabled=已停摆目标 AI（飞行生物），pullDown=持续施加下拉速度（鞘翅玩家） */
    private static class Knockdown {
        final LivingEntity entity;
        final long expireMs;
        final boolean aiDisabled;
        final boolean pullDown;

        Knockdown(LivingEntity entity, long expireMs, boolean aiDisabled, boolean pullDown) {
            this.entity = entity;
            this.expireMs = expireMs;
            this.aiDisabled = aiDisabled;
            this.pullDown = pullDown;
        }
    }

    private static State state(ServerPlayer player) {
        return STATES.computeIfAbsent(player.getUUID(), k -> new State());
    }

    /** 各主动技能剩余冷却（tick，用于客户端同步） */
    public static int[] remainingCooldowns(ServerPlayer player) {
        State st = STATES.get(player.getUUID());
        int[] result = new int[SkillAbility.COUNT];
        if (st == null) return result;
        long now = System.currentTimeMillis();
        for (int i = 0; i < SkillAbility.COUNT; i++) {
            long remainMs = st.cooldownEndMs[i] - now;
            result[i] = remainMs > 0 ? (int) Math.ceil(remainMs / 50.0) : 0;
        }
        return result;
    }

    /** 进行中的增益剩余时间（tick，用于客户端同步）：[0]=跳跃 buff，[1]=攀爬 buff，不活跃为 0 */
    public static int[] activeStateTicks(ServerPlayer player) {
        State st = STATES.get(player.getUUID());
        int[] result = new int[2];
        if (st == null) return result;
        long now = System.currentTimeMillis();
        if (st.leapActive) {
            result[0] = (int) Math.max(0, Math.ceil((st.leapExpire - now) / 50.0));
        }
        if (st.climbActive) {
            result[1] = (int) Math.max(0, Math.ceil((st.climbExpire - now) / 50.0));
        }
        return result;
    }

    // ===== 技能使用 =====

    public static void useSkill(ServerPlayer player, int bar, int slot) {
        useSkill(player, bar, slot, null, -1);
    }

    static void releaseCharged(ServerPlayer player, int bar, int slot, com.zhushen.space.data.ArtSkill expected, int ticks) {
        useSkill(player, bar, slot, expected, Math.max(0, Math.min(ArtCharge.MAX_TICKS, ticks)));
    }

    private static void useSkill(ServerPlayer player, int bar, int slot, com.zhushen.space.data.ArtSkill expected, int chargeTicks) {
        if (ArtCharge.charging(player)) return;
        if (bar < 0 || bar >= PlayerSkillData.BAR_COUNT) return;
        if (slot < 0 || slot >= 9) return;
        PlayerSkillData data = player.getData(ModAttachments.PLAYER_SKILLS);
        int abilityId = data.bar(bar)[slot];
        if (abilityId < 0 || abilityId >= SkillAbility.COUNT) return;
        SkillAbility ability = SkillAbility.values()[abilityId];
        if (expected != null && expected.ability != ability) return;
        if (ArtCharge.supports(com.zhushen.space.data.ArtSkill.of(ability)) && expected == null) return;
        State st = state(player);
        long now = System.currentTimeMillis();
        if (now < st.cooldownEndMs[abilityId]) return; // 冷却检查先行，避免先扣内力再退出
        if (ability.owner() != null) {
            if (!ability.unlockedBy(data.points())) return;
        } else if (ability.isArtAbility()) {
            // 技艺：施放失败（条件不足 / 能量不足 / 无目标）不进入冷却
            if (!ArtManager.cast(player, ability, chargeTicks)) return;
        } else if (ability.isNeiliAbility()) {
            // 内力系：需拥有内力池（获得内力池时自动解锁）
            if (player.getData(ModAttachments.PLAYER_ENERGY)
                    .getPool(EnergyManager.POOL_NEILI) == null) return;
        } else if (ability.isSchoolAbility()) {
            // 流派系：需已购买技能且装备流派饰品，并消耗内力
            // （八式按八劲合一 1~2 点；引手 3 点；揽雀尾启动不耗，结算时耗 3 点）
            if (!TaiChiManager.isSkillUsable(player, ability)) return;
            double cost = TaiChiManager.abilityNeiliCost(player, ability);
            if (cost > 0 && !player.getData(ModAttachments.PLAYER_ENERGY)
                    .consume(EnergyManager.POOL_NEILI, cost)) {
                player.displayClientMessage(Component.translatable("msg.zhushenspace.neili.lack"), true);
                return;
            }
            EnergyManager.sync(player);
        }

        var attrs = player.getData(ModAttachments.PLAYER_ATTRIBUTES);
        int strength = attrs.get(AttributeType.STRENGTH.ordinal());
        int agility = attrs.get(AttributeType.AGILITY.ordinal());
        int athletics = data.get(SkillType.ATHLETICS.ordinal());
        int brawl = data.get(SkillType.BRAWL.ordinal());
        int blade = data.get(SkillType.BLADE.ordinal());

        switch (ability) {
            case SELF_PROTECTION -> {
                applyTempArmor(player, SELF_PROTECTION_MOD, agility + athletics);
                st.selfProtectionEnd = now + BUFF_DURATION_MS;
            }
            case LEAP -> {
                applyTempJump(player, (strength + athletics) * 0.1);
                st.leapActive = true;
                st.leapExpire = now + BUFF_DURATION_MS;
            }
            case CLIMB -> {
                st.climbActive = true;
                st.climbExpire = now + CLIMB_DURATION_MS;
            }
            case BRAWL_BLOCK -> {
                applyTempArmor(player, BRAWL_BLOCK_MOD, brawl);
                st.brawlBlockEnd = now + BUFF_DURATION_MS;
            }
            case TRIP -> st.tripPendingEnd = now + TRIP_WINDOW_MS;
            case GRAPPLE -> {
                if (!GrappleManager.activate(player)) return;
            }
            case CHARGE_ATTACK -> {
                st.chargeActive = true;
                st.chargeExpire = now + CHARGE_WINDOW_MS;
                st.chargeDistance = 0;
                st.chargeLastX = player.getX();
                st.chargeLastZ = player.getZ();
            }
            case BLADE_BLOCK -> {
                applyTempArmor(player, BLADE_BLOCK_MOD, blade);
                st.bladeBlockEnd = now + BUFF_DURATION_MS;
            }
            case NEILI_BREATH -> EnergyManager.toggleBreath(player);
            case NEILI_MEDITATE -> {
                // 未真正开始打坐（已满/进行中）不进入冷却
                if (!EnergyManager.tryMeditate(player)) return;
            }
            case COILING_SILK -> {
                // 缠丝劲为被动，无需主动施放
                return;
            }
            case WARD_OFF, ROLL_BACK, PRESS, PUSH, PULL, SPLIT, ELBOW, SHOULDER -> {
                // 太极八式：无目标时照常出招打空（useMove 返回 false 时退还内力且不进冷却）
                if (!TaiChiManager.useMove(player, ability)) {
                    player.getData(ModAttachments.PLAYER_ENERGY).restore(
                            EnergyManager.POOL_NEILI, TaiChiManager.moveNeiliCost(player));
                    EnergyManager.sync(player);
                    return;
                }
            }
            case TING_JIN -> {
                // 听劲：以柔化刚（无需目标），持续期间攻击与受伤均 -20%
                st.tingjinEnd = now + TINGJIN_DURATION_MS;
                player.displayClientMessage(Component.translatable("msg.zhushenspace.taiji.tingjin_on"), true);
                TaiChiManager.tingjinActivateEffects(player);
            }
            case YIN_SHOU -> {
                // 引手：进入引手姿态，期间受击为攻击者叠加减值层（无需目标）
                st.yinshouEnd = now + YINSHOU_DURATION_MS;
                player.displayClientMessage(Component.translatable("msg.zhushenspace.taiji.yinshou_on"), true);
                TaiChiManager.yinshouActivateEffects(player);
            }
            case LAN_QUE_WEI -> {
                // 揽雀尾：进入待势，期间首次受到近战攻击时结算缴械对抗（无需目标）
                st.lanqueweiPendingEnd = now + LANQUEWEI_WINDOW_MS;
                player.displayClientMessage(Component.translatable("msg.zhushenspace.taiji.lanquewei_ready"), true);
                TaiChiManager.lanqueweiReadyEffects(player);
            }
            case CLOUD_HANDS -> {
                // 云手：进入待势，期间首次受到近战攻击时结算擒抱对抗
                st.cloudhandsPendingEnd = now + CLOUD_HANDS_WINDOW_MS;
                player.displayClientMessage(Component.translatable("msg.zhushenspace.taiji.cloudhands_ready"), true);
                TaiChiManager.cloudhandsReadyEffects(player);
            }
            case SEA_BOTTOM_NEEDLE -> {
                // 海底针：进入待势，期间受到近战攻击或身边目标主动移动时结算摔绊对抗
                st.seabottomPendingEnd = now + SEA_BOTTOM_WINDOW_MS;
                player.displayClientMessage(Component.translatable("msg.zhushenspace.taiji.seabottom_ready"), true);
                TaiChiManager.seabottomReadyEffects(player);
            }
            case TAI_CHI_DISSOLVE -> {
                // 太极化劲：进入待势，期间受到使用能量池的近战攻击时反击并连锁
                st.dissolvePendingEnd = now + DISSOLVE_WINDOW_MS;
                player.displayClientMessage(Component.translatable("msg.zhushenspace.taiji.dissolve_ready"), true);
                TaiChiManager.dissolveReadyEffects(player);
            }
        }

        st.cooldownEndMs[abilityId] = now + ability.cooldownTicks() * 50L;
        if (!ability.isArtAbility()) player.level().playSound(null, player.blockPosition(),
                SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.7f, 1.4f);
        SkillServer.sync(player);
        TrialManager.onSkillUsed(player, ability); // 新手试炼：使用技能 / 施放技艺的目标
    }

    /** 肉搏格挡 buff 是否生效中（太极拳被动结算用） */
    public static boolean isBrawlBlockActive(ServerPlayer player) {
        return state(player).brawlBlockEnd > System.currentTimeMillis();
    }

    /** 听劲是否生效中（太极拳主动技：攻击与受伤均 -20%） */
    public static boolean isTingjinActive(ServerPlayer player) {
        return state(player).tingjinEnd > System.currentTimeMillis();
    }

    /** 引手姿态是否生效中（期间受击为攻击者叠加减值层） */
    public static boolean isYinshouActive(ServerPlayer player) {
        return state(player).yinshouEnd > System.currentTimeMillis();
    }

    /** 揽雀尾是否待势中（期间首次受到近战攻击时结算缴械对抗） */
    public static boolean isLanqueweiPending(ServerPlayer player) {
        return state(player).lanqueweiPendingEnd > System.currentTimeMillis();
    }

    /** 揽雀尾结算：结束待势并进入真实冷却（tick） */
    public static void settleLanquewei(ServerPlayer player, int cooldownTicks) {
        State st = state(player);
        st.lanqueweiPendingEnd = 0;
        st.cooldownEndMs[SkillAbility.LAN_QUE_WEI.ordinal()] =
                System.currentTimeMillis() + cooldownTicks * 50L;
    }

    /** 云手是否待势中 */
    public static boolean isCloudhandsPending(ServerPlayer player) {
        return state(player).cloudhandsPendingEnd > System.currentTimeMillis();
    }

    /** 云手结算：结束待势并进入真实冷却（tick） */
    public static void settleCloudhands(ServerPlayer player, int cooldownTicks) {
        State st = state(player);
        st.cloudhandsPendingEnd = 0;
        st.cooldownEndMs[SkillAbility.CLOUD_HANDS.ordinal()] =
                System.currentTimeMillis() + cooldownTicks * 50L;
    }

    /** 海底针是否待势中 */
    public static boolean isSeabottomPending(ServerPlayer player) {
        return state(player).seabottomPendingEnd > System.currentTimeMillis();
    }

    /** 海底针结算：结束待势并进入真实冷却（tick） */
    public static void settleSeabottom(ServerPlayer player, int cooldownTicks) {
        State st = state(player);
        st.seabottomPendingEnd = 0;
        st.cooldownEndMs[SkillAbility.SEA_BOTTOM_NEEDLE.ordinal()] =
                System.currentTimeMillis() + cooldownTicks * 50L;
    }

    /** 太极化劲是否待势中 */
    public static boolean isDissolvePending(ServerPlayer player) {
        return state(player).dissolvePendingEnd > System.currentTimeMillis();
    }

    /** 太极化劲结算：结束待势并进入真实冷却（tick） */
    public static void settleDissolve(ServerPlayer player, int cooldownTicks) {
        State st = state(player);
        st.dissolvePendingEnd = 0;
        st.cooldownEndMs[SkillAbility.TAI_CHI_DISSOLVE.ordinal()] =
                System.currentTimeMillis() + cooldownTicks * 50L;
    }

    // ===== 事件处理 =====

    /**
     * 伤害面板用：当前手持物下本模组给近战追加的固定伤害（肉搏 / 白刃被动 + 冲锋中按已冲锋距离的加成），
     * 与 {@link #onIncomingDamage} 的结算口径一致。
     */
    public static float meleePanelBonus(ServerPlayer player) {
        PlayerSkillData skills = player.getData(ModAttachments.PLAYER_SKILLS);
        ItemStack mainhand = player.getMainHandItem();
        float bonus = 0f;
        // 肉搏 / 白刃技能已并入攻击判定公式（CombatFormula），此处只剩冲锋加成
        State st = STATES.get(player.getUUID());
        if (st != null && st.chargeActive) {
            bonus += Math.min((int) st.chargeDistance, SkillAbility.CHARGE_DAMAGE_CAP);
        }
        return bonus;
    }

    /** 被动：肉搏徒手伤害 / 白刃冷兵器伤害；主动：摔绊 / 冲锋攻击命中结算
     *  （HIGH：先于伤害浮动与暴击 / 弱点结算，固定加成计入浮动前的面板伤害） */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGH)
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (WillpowerManager.isBonusStrike(player)) return; // 意志加持追加伤害：固定 9 点
        State st = STATES.get(player.getUUID());
        PlayerSkillData skills = player.getData(ModAttachments.PLAYER_SKILLS);

        // 被动加成（仅近战直击：投掷物/弹射物/枪弹等由玩家发出但非亲手命中的伤害不计入）
        ItemStack mainhand = player.getMainHandItem();
        // TACZ 对部分实体以「伪装近战」结算子弹（直接来源为玩家），需按伤害类型排除
        boolean melee = event.getSource().getDirectEntity() == player && !GunDamage.isGun(event.getSource());
        // 肉搏 / 白刃技能已并入攻击判定公式（CombatFormula）

        if (st == null) return;

        // 摔绊：待触发窗口内命中 → 目标倒地
        if (st.tripPendingEnd > 0) {
            if (System.currentTimeMillis() <= st.tripPendingEnd && !resistTrip(player, event.getEntity())) {
                applyKnockdown(event.getEntity());
            }
            st.tripPendingEnd = 0;
        }

        // 冲锋攻击：开启期间命中 → 按冲锋距离结算伤害并暂时失去等量护甲
        if (st.chargeActive) {
            st.chargeActive = false;
            int bonus = Math.min((int) st.chargeDistance, SkillAbility.CHARGE_DAMAGE_CAP);
            if (bonus > 0) {
                event.setAmount(event.getAmount() + bonus);
                applyTempArmor(player, CHARGE_DEBUFF_MOD, -bonus);
                st.chargeDebuffEnd = System.currentTimeMillis() + CHARGE_DEBUFF_MS;
            }
        }
    }

    /**
     * 对抗摔绊：目标拥有摔绊专长加值（狼孩）时进行对抗——
     * 攻击者 (力量 + 肉搏 + 专长加值) 对 目标 (敏捷 + 运动 + 专长加值)，各自 20%~100% 浮动；目标胜出则不倒地。
     */
    private static boolean resistTrip(ServerPlayer attacker, LivingEntity target) {
        int tb = FeatEffects.tripBonus(target);
        if (tb <= 0 || !(target instanceof ServerPlayer tp)) return false;
        float mine = (StatusManager.attr(attacker, com.zhushen.space.data.AttributeType.STRENGTH)
                + attacker.getData(ModAttachments.PLAYER_SKILLS).get(SkillType.BRAWL.ordinal()) + FeatEffects.tripBonus(attacker))
                * DamageVariance.roll(attacker.getRandom());
        float theirs = (StatusManager.attr(tp, com.zhushen.space.data.AttributeType.AGILITY)
                + tp.getData(ModAttachments.PLAYER_SKILLS).get(SkillType.ATHLETICS.ordinal()) + tb)
                * DamageVariance.roll(tp.getRandom());
        if (theirs < mine) return false;
        attacker.displayClientMessage(net.minecraft.network.chat.Component.translatable("msg.zhushenspace.trip.resisted", tp.getDisplayName()), true);
        tp.displayClientMessage(net.minecraft.network.chat.Component.translatable("msg.zhushenspace.trip.resisted_self"), true);
        return true;
    }

    /** 跳跃：短时间跳跃增益仅维持一次跳跃，起跳即移除 */
    @SubscribeEvent
    public static void onLivingJump(LivingEvent.LivingJumpEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        State st = STATES.get(player.getUUID());
        if (st == null || !st.leapActive) return;
        removeModifier(player, LEAP_MOD, Attributes.JUMP_STRENGTH);
        st.leapActive = false;
    }

    /** 服务端 tick：临时增益到期、摔绊倒地恢复、冲锋距离累积（攀爬/跳跃由客户端执行，见 ClientSkillEffects） */
    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        long now = System.currentTimeMillis();
        processKnockdowns(now);
        if (STATES.isEmpty()) return;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            State st = STATES.get(player.getUUID());
            if (st == null) continue;

            // 到期移除临时护甲增益
            if (st.selfProtectionEnd > 0 && now >= st.selfProtectionEnd) {
                removeModifier(player, SELF_PROTECTION_MOD, Attributes.ARMOR);
                st.selfProtectionEnd = 0;
            }
            if (st.brawlBlockEnd > 0 && now >= st.brawlBlockEnd) {
                removeModifier(player, BRAWL_BLOCK_MOD, Attributes.ARMOR);
                st.brawlBlockEnd = 0;
            }
            if (st.bladeBlockEnd > 0 && now >= st.bladeBlockEnd) {
                removeModifier(player, BLADE_BLOCK_MOD, Attributes.ARMOR);
                st.bladeBlockEnd = 0;
            }
            if (st.chargeDebuffEnd > 0 && now >= st.chargeDebuffEnd) {
                removeModifier(player, CHARGE_DEBUFF_MOD, Attributes.ARMOR);
                st.chargeDebuffEnd = 0;
            }
            if (st.leapActive && now >= st.leapExpire) {
                removeModifier(player, LEAP_MOD, Attributes.JUMP_STRENGTH);
                st.leapActive = false;
            }
            if (st.climbActive && now >= st.climbExpire) {
                st.climbActive = false;
            }

            // 冲锋：累积水平移动距离
            if (st.chargeActive) {
                if (now >= st.chargeExpire) {
                    st.chargeActive = false;
                } else {
                    double dx = player.getX() - st.chargeLastX;
                    double dz = player.getZ() - st.chargeLastZ;
                    st.chargeDistance += Math.sqrt(dx * dx + dz * dz);
                }
                st.chargeLastX = player.getX();
                st.chargeLastZ = player.getZ();
            }
        }
    }

    /** 摔绊倒地处理：到期/落地/死亡后恢复（飞行生物恢复 AI），鞘翅滑翔者持续下拉直至落地 */
    private static void processKnockdowns(long now) {
        if (KNOCKED.isEmpty()) return;
        Iterator<Map.Entry<UUID, Knockdown>> it = KNOCKED.entrySet().iterator();
        while (it.hasNext()) {
            Knockdown kd = it.next().getValue();
            LivingEntity entity = kd.entity;
            boolean done = !entity.isAlive() || entity.isRemoved()
                    || now >= kd.expireMs || entity.onGround();
            if (!done && kd.pullDown) {
                entity.setDeltaMovement(entity.getDeltaMovement().x,
                        Math.min(entity.getDeltaMovement().y, -0.4), entity.getDeltaMovement().z);
                entity.hurtMarked = true;
            }
            if (done) {
                if (kd.aiDisabled && entity instanceof Mob mob) {
                    mob.setNoAi(false);
                }
                it.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        // 仅清理进行中的技能效果；保留冷却，防止退出重进刷新冷却（如打坐 5 分钟冷却）
        State old = STATES.remove(event.getEntity().getUUID());
        if (old == null) return;
        long now = System.currentTimeMillis();
        boolean cooling = false;
        for (long end : old.cooldownEndMs) {
            if (end > now) { cooling = true; break; }
        }
        if (cooling) {
            State fresh = new State();
            System.arraycopy(old.cooldownEndMs, 0, fresh.cooldownEndMs, 0, fresh.cooldownEndMs.length);
            STATES.put(event.getEntity().getUUID(), fresh);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        // 关服前恢复被停摆 AI 的生物，避免 noAi 状态被持久化
        for (Knockdown kd : KNOCKED.values()) {
            if (kd.aiDisabled && kd.entity instanceof Mob mob) {
                mob.setNoAi(false);
            }
        }
        KNOCKED.clear();
        STATES.clear();
    }

    // ===== 工具方法 =====

    /** 冷兵器：剑 / 斧 / 三叉戟 / 重锤（枪械暂不存在于原版） */
    private static boolean isColdWeapon(ItemStack stack) {
        return stack.getItem() instanceof SwordItem
                || stack.getItem() instanceof AxeItem
                || stack.getItem() instanceof TridentItem
                || stack.getItem() instanceof MaceItem;
    }

    /**
     * 目标倒地：大幅减速 + 虚弱；飞行目标直接坠落。
     * 飞行生物（蜜蜂/鹦鹉/恶魂/幻翼等 FlyingMoveControl）的 AI 每刻重算速度并覆盖外部推力，
     * 因此停摆其 AI（setNoAi）使其失去悬停能力自然坠落，落地或超时后恢复；
     * 鞘翅滑翔的玩家（客户端权威预测）改为持续施加下拉速度（hurtMarked 同步）。
     */
    private static void applyKnockdown(LivingEntity target) {
        target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 3));
        target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 40, 1));
        if (target.hasEffect(MobEffects.LEVITATION)) {
            target.removeEffect(MobEffects.LEVITATION);
        }
        target.fallDistance += 1.0f;

        boolean flyingMob = target instanceof Mob mob && mob.getMoveControl() instanceof FlyingMoveControl;
        boolean gliding = target.isFallFlying();
        if (!flyingMob && !gliding) return;

        // 初始下坠动量
        target.setDeltaMovement(target.getDeltaMovement().x * 0.3, -0.5, target.getDeltaMovement().z * 0.3);
        target.hurtMarked = true;

        KNOCKED.put(target.getUUID(), new Knockdown(
                target,
                System.currentTimeMillis() + KNOCKDOWN_DURATION_MS,
                flyingMob,
                gliding && !flyingMob));
        if (flyingMob) {
            ((Mob) target).setNoAi(true);
        }
    }

    /** 添加瞬态护甲修改器（同 id 先移除再添加） */
    private static void applyTempArmor(ServerPlayer player, ResourceLocation id, double amount) {
        if (amount == 0) return;
        AttributeInstance attr = player.getAttribute(Attributes.ARMOR);
        if (attr == null) return;
        attr.removeModifier(id);
        attr.addTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_VALUE));
    }

    /** 添加瞬态跳跃修改器 */
    private static void applyTempJump(ServerPlayer player, double amount) {
        if (amount == 0) return;
        AttributeInstance attr = player.getAttribute(Attributes.JUMP_STRENGTH);
        if (attr == null) return;
        attr.removeModifier(LEAP_MOD);
        attr.addTransientModifier(new AttributeModifier(LEAP_MOD, amount, AttributeModifier.Operation.ADD_VALUE));
    }

    /** 移除指定 id 的修改器 */
    private static void removeModifier(ServerPlayer player, ResourceLocation id, Holder<Attribute> attribute) {
        AttributeInstance attr = player.getAttribute(attribute);
        if (attr != null) {
            attr.removeModifier(id);
        }
    }
}
