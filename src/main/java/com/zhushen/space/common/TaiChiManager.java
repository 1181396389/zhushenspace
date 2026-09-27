package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.PlayerEnergyData;
import com.zhushen.space.data.PlayerHealthData;
import com.zhushen.space.data.PlayerSkillData;
import com.zhushen.space.data.SchoolType;
import com.zhushen.space.data.SkillAbility;
import com.zhushen.space.data.SkillType;
import com.zhushen.space.network.HitFeedbackPayload;
import com.zhushen.space.sound.ModSounds;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 太极拳流派（装备饰品后生效）。
 *
 * 太极拳饰品购买后需装备在「流派」饰品栏才生效：
 * - 获得内力能量池（容量 = 耐力 + 感知），并随附内力吐息与打坐
 * - 太极被动：徒手（双手没有任何武器）时，天生武器肉搏攻击 +6 攻击力、护甲 +6
 * - 肉搏格挡期间格挡护甲额外 +8（摊手）
 * - 已购买的技能（八式需在商城购买，听劲随流派赠送）方可使用
 *
 * 卸下饰品后以上全部失效（内力池移除、护甲加成移除、技能不可用）。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public class TaiChiManager {

    /** 每式内力消耗（习得八劲合一后降为 1 点） */
    public static final double MOVE_NEILI_COST = 2.0;
    /** 八劲合一后每式内力消耗 */
    private static final double MOVE_NEILI_COST_REDUCED = 1.0;
    /** 八劲合一：徒手普攻附带八劲的伤害门槛（本次肉搏攻击的最终伤害，含技能点与太极被动） */
    private static final double EIGHT_POWERS_DAMAGE_THRESHOLD = 6.0;
    /** 太极：徒手攻击加成 */
    private static final float TAIJI_DAMAGE_BONUS = 6.0f;
    /** 太极：徒手护甲（属性修饰器） */
    private static final double TAIJI_ARMOR = 6.0;
    /** 肉搏格挡期间的格挡护甲（摊手 +4 与太极格挡 +4 合计） */
    private static final float TAIJI_BLOCK_ARMOR = 8.0f;
    /** 徒手护甲修饰器 id */
    private static final ResourceLocation TAIJI_ARMOR_MOD =
            ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "taiji_unarmed_armor");
    /** 严重伤害的实体修饰器 id（最大生命值削减，瞬态，死亡/重登后消退） */
    private static final ResourceLocation SEVERE_ID =
            ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "severe_damage");

    /** 靠·撞墙判定：目标 UUID → 判定数据（记录攻击者以便结算时回传打击感） */
    private record WallCheck(LivingEntity target, ServerPlayer attacker, int damage, long endTime) {
    }

    private static final Map<UUID, WallCheck> WALL_CHECKS = new HashMap<>();

    /** 待结算的生命上限削减（八劲·采在 LivingDamageEvent.Pre 中触发，须等本次受击结算完毕再削减上限） */
    private record PendingCap(LivingEntity target, double hpLoss) {
    }

    private static final java.util.List<PendingCap> PENDING_SEVERE_CAP = new java.util.ArrayList<>();

    /** 引手减值层：带层实体 UUID → {层数, 到期时间}（每层：攻击 -3 / 防御 -1，持续 1 分钟，叠层刷新） */
    private record YinStack(int layers, long expireMs) {
    }

    private static final Map<UUID, YinStack> YINSHOU_STACKS = new HashMap<>();

    /** 装备状态缓存：玩家 UUID → 是否装备饰品（用于状态切换时提示与池同步） */
    private static final Map<UUID, Boolean> EQUIPPED_CACHE = new HashMap<>();
    /** 徒手护甲修饰器状态缓存：玩家 UUID → 修饰器是否已添加 */
    private static final Map<UUID, Boolean> ARMOR_MOD_CACHE = new HashMap<>();

    /** 太极拳饰品是否装备在「流派」饰品栏（生效开关） */
    public static boolean isTaiChiEquipped(ServerPlayer player) {
        return CuriosApi.getCuriosHelper()
                .findFirstCurio(player, ZhuShenSpace.TAI_CHI_EMBLEM.get()).isPresent();
    }

    /** 太极拳是否已购买（购买记录，不代表已装备生效） */
    public static boolean hasTaiChi(ServerPlayer player) {
        return player.getData(ModAttachments.PLAYER_SCHOOLS).isUnlocked(SchoolType.TAI_CHI);
    }

    /** 太极拳技能是否已购买（听劲随流派赠送，八式需商城购买） */
    public static boolean isSkillPurchased(ServerPlayer player, SkillAbility ability) {
        return player.getData(ModAttachments.PLAYER_SCHOOLS)
                .isSkillPurchased(SchoolType.TAI_CHI.ordinal(), ability.ordinal());
    }

    /** 太极拳技能当前是否可用：已购买 + 饰品已装备 */
    public static boolean isSkillUsable(ServerPlayer player, SkillAbility ability) {
        return isTaiChiEquipped(player) && isSkillPurchased(player, ability);
    }

    /** 八劲合一是否生效（已购买 + 饰品已装备） */
    public static boolean hasEightPowers(ServerPlayer player) {
        return isTaiChiEquipped(player) && isSkillPurchased(player, SkillAbility.EIGHT_POWERS);
    }

    /** 当前每式内力消耗（八劲合一：1 点，否则 2 点） */
    public static double moveNeiliCost(ServerPlayer player) {
        return hasEightPowers(player) ? MOVE_NEILI_COST_REDUCED : MOVE_NEILI_COST;
    }

    /** 流派技能启动内力消耗：引手 3 点 / 揽雀尾 0（结算时消耗）/ 八式按八劲合一 1~2 点 */
    public static double abilityNeiliCost(ServerPlayer player, SkillAbility ability) {
        return switch (ability) {
            case YIN_SHOU -> 3.0;
            case LAN_QUE_WEI -> 0.0;
            default -> moveNeiliCost(player);
        };
    }

    /** 引手减值层数（0 = 无效层） */
    public static int yinshouLayers(LivingEntity entity, long now) {
        YinStack s = YINSHOU_STACKS.get(entity.getUUID());
        return s != null && now < s.expireMs() ? s.layers() : 0;
    }

    /** 是否徒手（主手与副手均为空） */
    public static boolean isUnarmed(ServerPlayer player) {
        return player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty();
    }

    /** 招式攻击标记：useMove 结算 hurt 期间置位，防止八劲合一在招式伤害上重复触发 */
    private static final java.util.Set<UUID> MOVE_ATTACK_FLAG = new java.util.HashSet<>();

    /** ===== 装备状态协调（每 10 tick 对账：内力池发放/移除、护甲修饰器维护） ===== */

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // 八劲·采：延迟到本刻末（受击流程已完整结束、生命值已扣除）再削减生命上限
        if (!PENDING_SEVERE_CAP.isEmpty()) {
            for (PendingCap pending : PENDING_SEVERE_CAP) {
                if (pending.target().isAlive()) {
                    reduceMaxHealth(pending.target(), pending.hpLoss());
                }
            }
            PENDING_SEVERE_CAP.clear();
        }

        // 靠·撞墙判定推进（horizontalCollision 是逐刻标志，必须在窗口内每刻检查，
        // 否则到 400ms 到期时标志早已复位，撞墙后续效果永远不会触发）
        if (!WALL_CHECKS.isEmpty()) {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<UUID, WallCheck>> it = WALL_CHECKS.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, WallCheck> entry = it.next();
                WallCheck check = entry.getValue();
                boolean collided = check.target().isAlive() && check.target().horizontalCollision;
                if (collided || now >= check.endTime()) {
                    it.remove();
                    if (collided) {
                        // 靠·撞墙：钝击闷响 + 冲击爆发 + 强打击感 + 严重伤害
                        severeDamage(check.target(), Math.min(check.damage() / 3, 2));
                        wallImpactEffects(check.target());
                        hitFeel(check.attacker(), 1.0f, 8);
                    }
                }
            }
        }

        // 引手减值层到期清理
        if (!YINSHOU_STACKS.isEmpty()) {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<UUID, YinStack>> ys = YINSHOU_STACKS.entrySet().iterator();
            while (ys.hasNext()) {
                if (now >= ys.next().getValue().expireMs()) ys.remove();
            }
        }

        // 太极化劲封印到期清理
        if (!SEALED_UNTIL.isEmpty()) {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<UUID, Long>> si = SEALED_UNTIL.entrySet().iterator();
            while (si.hasNext()) {
                if (now >= si.next().getValue()) si.remove();
            }
        }

        // 每 10 tick（0.5 秒）对账一次装备状态
        long tick = event.getServer().getTickCount();
        if (tick % 10 != 0) return;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            // 海底针：待势期间身边目标主动移动 → 摔绊
            if (SkillManager.isSeabottomPending(player) && isTaiChiEquipped(player)) {
                seabottomMovementCheck(player);
            }
            reconcile(player);
        }
    }

    /** 海底针移动触发：检测身边 3 格内主动移动的目标 → 摔绊对抗 */
    private static void seabottomMovementCheck(ServerPlayer player) {
        AABB box = player.getBoundingBox().inflate(3.0);
        for (LivingEntity entity : player.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e != player && e.isAlive() && e.isAttackable()
                        && e.getDeltaMovement().horizontalDistanceSqr() > 0.05)) {
            // 触发摔绊（非受击模式）
            seabottomResolve(player, entity, null, false);
            return; // 一次待势仅触发一次
        }
    }

    /** 装备状态对账：内力池与护甲修饰器随饰品装备状态变化 */
    private static void reconcile(ServerPlayer player) {
        boolean equipped = isTaiChiEquipped(player);
        boolean wasEquipped = EQUIPPED_CACHE.getOrDefault(player.getUUID(), equipped);

        PlayerEnergyData energy = player.getData(ModAttachments.PLAYER_ENERGY);
        boolean hasPool = energy.getPool(EnergyManager.POOL_NEILI) != null;

        if (equipped && !hasPool) {
            // 装备饰品：发放内力池（容量 = 耐力 + 感知），并恢复上次摘下时的余量
            int[] pts = player.getData(ModAttachments.PLAYER_ATTRIBUTES).points();
            double max = pts[AttributeType.ENDURANCE.ordinal()] + pts[AttributeType.PERCEPTION.ordinal()];
            EnergyManager.grantPool(player, EnergyManager.POOL_NEILI, max);
            // 曾经摘下过就恢复当时的余量（包括 0），仅首次装备才是满的。
            // setAmount 内部按实际上限（含传奇加成）截断
            if (energy.hasNeiliCarryover()) {
                EnergyManager.setAmount(player, EnergyManager.POOL_NEILI, energy.neiliCarryover());
            }
            player.displayClientMessage(Component.translatable("msg.zhushenspace.taiji.equipped_on"), true);
        } else if (!equipped && hasPool) {
            // 卸下饰品：暂存内力余量后移除内力池（吐息随之一并关闭）
            PlayerEnergyData.Pool pool = energy.getPool(EnergyManager.POOL_NEILI);
            if (pool != null) {
                energy.setNeiliCarryover(pool.current);
            }
            EnergyManager.removePool(player, EnergyManager.POOL_NEILI);
            // 清除预设槽位中的内力系/流派系技能（从预设栏与战斗 HUD 消失）
            player.getData(ModAttachments.PLAYER_SKILLS).clearGatedSlots();
            SkillServer.sync(player);
            player.displayClientMessage(Component.translatable("msg.zhushenspace.taiji.equipped_off"), true);
        }
        if (equipped != wasEquipped) {
            EQUIPPED_CACHE.put(player.getUUID(), equipped);
        }

        // 太极护甲：装备饰品且徒手时 +6 护甲（真实护甲属性，护甲条可见）
        boolean armorDesired = equipped && isUnarmed(player);
        boolean armorApplied = ARMOR_MOD_CACHE.getOrDefault(player.getUUID(), false);
        if (armorDesired != armorApplied) {
            AttributeInstance armor = player.getAttribute(Attributes.ARMOR);
            if (armor != null) {
                if (armorDesired) {
                    armor.removeModifier(TAIJI_ARMOR_MOD);
                    armor.addTransientModifier(new AttributeModifier(TAIJI_ARMOR_MOD, TAIJI_ARMOR,
                            AttributeModifier.Operation.ADD_VALUE));
                } else {
                    armor.removeModifier(TAIJI_ARMOR_MOD);
                }
            }
            ARMOR_MOD_CACHE.put(player.getUUID(), armorDesired);
        }
    }

    /** 登出清理状态缓存（护甲修饰器随瞬态属性自然消失，无需处理） */
    @SubscribeEvent
    public static void onPlayerLoggedOut(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        EQUIPPED_CACHE.remove(event.getEntity().getUUID());
        ARMOR_MOD_CACHE.remove(event.getEntity().getUUID());
        EIGHT_POWERS_NEXT.remove(event.getEntity().getUUID());
    }

    /** ===== 太极拳被动 ===== */

    /** 徒手攻击加成与听劲（玩家近战直击时结算） */
    @SubscribeEvent
    public static void onOutgoingDamage(LivingDamageEvent.Pre event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) return;
        if (player == event.getEntity()) return;
        if (event.getSource().getDirectEntity() != player) return;
        if (!isTaiChiEquipped(player)) return;

        float amount = event.getNewDamage();
        if (isUnarmed(player)) {
            amount += TAIJI_DAMAGE_BONUS;
        }
        // 听劲：收敛攻势（自身攻击 -20%）
        if (SkillManager.isTingjinActive(player)) {
            amount *= 0.8f;
        }
        event.setNewDamage(amount);

        // 八劲合一（被动）：需装备饰品且已购买（hasEightPowers 双重校验）。
        // 门槛 = 本次肉搏攻击的最终伤害（已含肉搏技能点 +1/点与太极被动 +6，事件链早期结算）
        // 大于 6 点时，附带一种劲力（按 掤→捋→挤→按→采→挒→肘→靠 顺序轮转）。
        if (isUnarmed(player)
                && !MOVE_ATTACK_FLAG.contains(player.getUUID())
                && hasEightPowers(player)
                && amount > EIGHT_POWERS_DAMAGE_THRESHOLD) {
            appendEightPowers(player, event.getEntity(), event);
        }
    }

    /** 八劲轮转顺序：掤→捋→挤→按→采→挒→肘→靠 */
    private static final SkillAbility[] EIGHT_POWERS_CYCLE = {
            SkillAbility.WARD_OFF, SkillAbility.ROLL_BACK, SkillAbility.PRESS, SkillAbility.PUSH,
            SkillAbility.PULL, SkillAbility.SPLIT, SkillAbility.ELBOW, SkillAbility.SHOULDER};

    /** 八劲轮转进度：玩家 UUID → 下一次触发的劲序号 */
    private static final Map<UUID, Integer> EIGHT_POWERS_NEXT = new HashMap<>();

    /**
     * 八劲合一：每次触发附带一种劲力（轮转）——
     * 掤 +6 / 捋 +6 / 按 +3 伤害，挤破魔（无视最多 6 点伤害吸收），
     * 采削减生机，挒压制（虚弱+缓慢），肘破甲（护甲 20% 转化，上限 4），靠击退撞墙。
     */
    private static void appendEightPowers(ServerPlayer player, LivingEntity target, LivingDamageEvent.Pre event) {
        int idx = EIGHT_POWERS_NEXT.getOrDefault(player.getUUID(), 0) % EIGHT_POWERS_CYCLE.length;
        EIGHT_POWERS_NEXT.put(player.getUUID(), (idx + 1) % EIGHT_POWERS_CYCLE.length);
        SkillAbility jin = EIGHT_POWERS_CYCLE[idx];

        float amount = event.getNewDamage();
        switch (jin) {
            case WARD_OFF, ROLL_BACK -> amount += 6; // 掤 / 捋：劲力直透
            case PRESS -> amount += Math.min(6f, target.getAbsorptionAmount()); // 挤：破魔
            case PUSH -> amount += 3; // 按：下按
            case PULL -> { // 采：削减生机
                // 严重伤害的即时部分并入本次攻击（嵌套 hurt 会被受击无敌帧吞掉），
                // 生命上限削减延迟到受击结算之后
                if (target instanceof ServerPlayer victim) {
                    HealthManager.addWound(victim, PlayerHealthData.Severity.L, 1);
                } else {
                    double hpLoss = severeHpLoss(1);
                    amount += (float) hpLoss;
                    PENDING_SEVERE_CAP.add(new PendingCap(target, hpLoss));
                }
            }
            case SPLIT -> { // 挒：压制
                target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 0));
                target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 0));
            }
            case ELBOW -> amount += Math.min(4f, (float) (target.getAttributeValue(Attributes.ARMOR) * 0.2)); // 肘：破甲
            case SHOULDER -> { // 靠：击退 + 撞墙钝击判定（撞墙削减生机）
                Vec3 dir = target.position().subtract(player.position()).normalize();
                target.push(dir.x * 0.6, 0.2, dir.z * 0.6);
                target.hurtMarked = true;
                WALL_CHECKS.put(target.getUUID(), new WallCheck(target, player, 3, System.currentTimeMillis() + 400));
            }
            default -> {
            }
        }
        event.setNewDamage(amount);

        // 劲力提示（动作栏）+ 特效：该劲在目标周身对应方位迸出内力 + 轻出招声
        player.displayClientMessage(Component.translatable("msg.zhushenspace.taiji.eight_powers_jin",
                Component.translatable(jin.nameKey())), true);
        if (player.level() instanceof ServerLevel level) {
            double ty = target.getY() + target.getBbHeight() * 0.5;
            double a = Math.PI * 2 * idx / EIGHT_POWERS_CYCLE.length;
            for (int i = 0; i < 3; i++) {
                level.sendParticles(ParticleTypes.END_ROD,
                        target.getX() + Math.cos(a) * 0.6, ty + (i - 1) * 0.15,
                        target.getZ() + Math.sin(a) * 0.6, 1, 0.03, 0.05, 0.03, 0.02);
            }
        }
        player.level().playSound(null, target.blockPosition(),
                ModSounds.TAI_CHI_MOVE.get(), SoundSource.PLAYERS, 0.5f, 1.2f);
        // 打击感：单劲透发，轻震动
        hitFeel(player, 0.4f, 4);
    }

    /** 命中打击感：向攻击者发送镜头微震反馈 */
    private static void hitFeel(ServerPlayer attacker, float power, int ticks) {
        if (attacker != null) {
            PacketDistributor.sendToPlayer(attacker, new HitFeedbackPayload(power, ticks));
        }
    }

    /**
     * 承伤结算（LivingIncomingDamageEvent，伤害数值计算阶段）：
     * - 引手减值（全局，挂在带层实体上）：带层攻击者 → 攻击伤害 -3×层；
     *   带层受击者 → 防御减值 → 承受伤害 +1×层
     * - 引手叠层：姿态期间玩家受击 → 攻击者 +1 层（上限 = 肉搏技能等级），刷新 1 分钟
     * - 揽雀尾：待势期间玩家首次受到触及范围内近战攻击 → 攻击力对拼，成功缴械并取消该次攻击
     * - 格挡护甲与听劲防御（太极玩家承伤减免）
     */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        long now = System.currentTimeMillis();
        LivingEntity victim = event.getEntity();

        // 引手减值：攻击者带层 → 其攻击承受 -3/层
        if (event.getSource().getEntity() instanceof LivingEntity attacker
                && attacker != victim) {
            int al = yinshouLayers(attacker, now);
            if (al > 0) {
                event.setAmount(Math.max(0f, event.getAmount() - 3f * al));
            }
        }
        // 引手减值：受击者带层 → 防御减值 → 承受伤害 +1/层
        int vl = yinshouLayers(victim, now);
        if (vl > 0) {
            event.setAmount(event.getAmount() + 1f * vl);
        }

        if (!(victim instanceof ServerPlayer player)) return;

        // 引手：姿态期间受击 → 攻击者叠加减值层
        if (SkillManager.isYinshouActive(player) && isTaiChiEquipped(player)
                && event.getSource().getEntity() instanceof LivingEntity attacker
                && attacker != player) {
            applyYinshouStack(player, attacker, now);
        }

        // 揽雀尾：待势期间首次受到近战攻击（触及 5 格内）→ 缴械对抗
        boolean reactiveConsumed = false;
        if (SkillManager.isLanqueweiPending(player) && isTaiChiEquipped(player)
                && !event.getSource().is(DamageTypeTags.IS_PROJECTILE)
                && event.getSource().getEntity() instanceof LivingEntity attacker
                && attacker != player
                && player.distanceToSqr(attacker) <= 25.0) {
            lanqueweiResolve(player, attacker, event);
            reactiveConsumed = event.isCanceled();
        }

        // 太极化劲：待势期间受到使用能量池的近战攻击 → 反击 + 连锁 + 封印
        if (!reactiveConsumed && SkillManager.isDissolvePending(player) && isTaiChiEquipped(player)
                && !event.getSource().is(DamageTypeTags.IS_PROJECTILE)
                && event.getSource().getEntity() instanceof LivingEntity attacker2
                && attacker2 != player
                && player.distanceToSqr(attacker2) <= 25.0
                && attackerUsedEnergy(attacker2)) {
            reactiveConsumed = dissolveResolve(player, attacker2, event);
        }

        // 云手：待势期间首次受到近战攻击 → 擒抱对抗
        if (!reactiveConsumed && SkillManager.isCloudhandsPending(player) && isTaiChiEquipped(player)
                && !event.getSource().is(DamageTypeTags.IS_PROJECTILE)
                && event.getSource().getEntity() instanceof LivingEntity attacker3
                && attacker3 != player
                && player.distanceToSqr(attacker3) <= 25.0) {
            reactiveConsumed = cloudhandsResolve(player, attacker3, event);
        }

        // 海底针：待势期间受到近战攻击 → 摔绊对抗
        if (!reactiveConsumed && SkillManager.isSeabottomPending(player) && isTaiChiEquipped(player)
                && !event.getSource().is(DamageTypeTags.IS_PROJECTILE)
                && event.getSource().getEntity() instanceof LivingEntity attacker4
                && attacker4 != player
                && player.distanceToSqr(attacker4) <= 25.0) {
            seabottomResolve(player, attacker4, event, true);
        }

        if (!isTaiChiEquipped(player)) return;
        float amount = event.getAmount();
        if (SkillManager.isBrawlBlockActive(player)) {
            amount = Math.max(0, amount - TAIJI_BLOCK_ARMOR);
        }
        // 听劲：化解来力（受到伤害 -20%）
        if (SkillManager.isTingjinActive(player)) {
            amount *= 0.8f;
        }
        event.setAmount(amount);
    }

    /** 引手叠层：攻击者获得 1 层减值（每层攻击 -3 / 防御 -1），上限 = 肉搏技能等级，刷新 1 分钟 */
    private static void applyYinshouStack(ServerPlayer player, LivingEntity attacker, long now) {
        int brawl = player.getData(ModAttachments.PLAYER_SKILLS).get(SkillType.BRAWL.ordinal());
        if (brawl <= 0) return; // 未习肉搏：无从引手
        YinStack cur = YINSHOU_STACKS.get(attacker.getUUID());
        int layers = Math.min((cur != null && now < cur.expireMs() ? cur.layers() : 0) + 1, brawl);
        YINSHOU_STACKS.put(attacker.getUUID(),
                new YinStack(layers, now + SkillAbility.YINSHOU_LAYER_TICKS * 50L));
        player.displayClientMessage(Component.translatable(
                "msg.zhushenspace.taiji.yinshou_stack", layers), true);
        // 特效：攻击者周身内力缠丝（层数越多圈越密）
        if (player.level() instanceof ServerLevel level) {
            double ty = attacker.getY() + attacker.getBbHeight() * 0.5;
            int dots = Math.max(3, layers * 3);
            for (int i = 0; i < dots; i++) {
                double a = Math.PI * 2 * i / dots;
                level.sendParticles(ParticleTypes.END_ROD,
                        attacker.getX() + Math.cos(a) * 0.7, ty,
                        attacker.getZ() + Math.sin(a) * 0.7, 1, 0, 0.05, 0, 0.02);
            }
        }
        player.level().playSound(null, attacker.blockPosition(),
                ModSounds.NEILI_CHIME.get(), SoundSource.PLAYERS, 0.4f, 1.3f);
    }

    /**
     * 揽雀尾结算：消耗 3 内力 → 攻击力对拼（我方含肉搏技能与徒手太极被动，无视命中直接对抗）。
     * 胜利且目标持有主手物品 → 缴械（物品掉落）并取消本次攻击；否则攻击照常。
     */
    private static void lanqueweiResolve(ServerPlayer player, LivingEntity attacker,
                                         LivingIncomingDamageEvent event) {
        // 结算消耗 3 内力：不足则不触发（待势保留，等待下次受击）
        if (!player.getData(ModAttachments.PLAYER_ENERGY).consume(EnergyManager.POOL_NEILI, 3.0)) {
            player.displayClientMessage(Component.translatable("msg.zhushenspace.neili.lack"), true);
            return;
        }
        EnergyManager.sync(player);
        SkillManager.settleLanquewei(player, SkillAbility.LANQUEWEI_COOLDOWN_TICKS);

        int brawl = player.getData(ModAttachments.PLAYER_SKILLS).get(SkillType.BRAWL.ordinal());
        float mine = (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE)
                + brawl + (isUnarmed(player) ? (float) TAIJI_DAMAGE_BONUS : 0f);
        float theirs = (float) attacker.getAttributeValue(Attributes.ATTACK_DAMAGE);

        if (mine > theirs && !attacker.getMainHandItem().isEmpty()) {
            // 缴械：主手物品掉落，本次攻击落空
            ItemStack weapon = attacker.getMainHandItem();
            attacker.spawnAtLocation(weapon.copy());
            attacker.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            event.setCanceled(true);
            player.displayClientMessage(Component.translatable(
                    "msg.zhushenspace.taiji.lanquewei_success", attacker.getDisplayName()), true);
            if (player.level() instanceof ServerLevel level) {
                double ty = attacker.getY() + attacker.getBbHeight() * 0.5;
                level.sendParticles(ParticleTypes.SWEEP_ATTACK,
                        attacker.getX(), ty, attacker.getZ(), 1, 0, 0, 0, 0);
                level.sendParticles(ParticleTypes.CRIT,
                        attacker.getX(), ty, attacker.getZ(), 10, 0.3, 0.4, 0.3, 0.1);
            }
            player.level().playSound(null, attacker.blockPosition(),
                    ModSounds.TAI_CHI_WALL.get(), SoundSource.PLAYERS, 0.7f, 1.4f);
            hitFeel(player, 0.6f, 5);
        } else {
            // 对拼失败（或目标徒手无可缴械）：攻击照常
            player.displayClientMessage(Component.translatable(
                    "msg.zhushenspace.taiji.lanquewei_fail"), true);
            if (player.level() instanceof ServerLevel level) {
                level.sendParticles(ParticleTypes.SMOKE,
                        attacker.getX(), attacker.getY() + attacker.getBbHeight() * 0.5,
                        attacker.getZ(), 6, 0.2, 0.3, 0.2, 0.01);
            }
        }
    }

    /** 引手启动特效：内力升腾环绕 */
    public static void yinshouActivateEffects(ServerPlayer player) {
        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.END_ROD,
                    player.getX(), player.getY() + 1.0, player.getZ(), 16, 0.5, 0.6, 0.5, 0.03);
        }
        player.level().playSound(null, player.blockPosition(),
                ModSounds.NEILI_CHIME.get(), SoundSource.PLAYERS, 0.8f, 0.9f);
    }

    /** 揽雀尾待势特效：气聚双手 */
    public static void lanqueweiReadyEffects(ServerPlayer player) {
        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.END_ROD,
                    player.getX(), player.getY() + 1.2, player.getZ(), 8, 0.3, 0.4, 0.3, 0.02);
        }
        player.level().playSound(null, player.blockPosition(),
                ModSounds.NEILI_CHIME.get(), SoundSource.PLAYERS, 0.6f, 0.7f);
    }

    /** ===== 能量池封印（太极化劲） ===== */

    /** 封印到期时间：实体 UUID → 到期时间戳（ms） */
    private static final Map<UUID, Long> SEALED_UNTIL = new HashMap<>();

    /** 能量池是否被封印（太极化劲效果，期间不可消耗/吐息） */
    public static boolean isPoolSealed(ServerPlayer player) {
        Long until = SEALED_UNTIL.get(player.getUUID());
        return until != null && until > System.currentTimeMillis();
    }

    /** 封印能量池（持续 tick） */
    private static void sealPool(LivingEntity target, int ticks) {
        SEALED_UNTIL.put(target.getUUID(), System.currentTimeMillis() + ticks * 50L);
    }

    /** ===== 缠丝劲（被动） ===== */

    /** 缠丝劲是否生效（已购买 + 饰品已装备 + 内力池不空） */
    public static boolean hasCoilingSilk(ServerPlayer player) {
        if (!isTaiChiEquipped(player) || !isSkillPurchased(player, SkillAbility.COILING_SILK)) {
            return false;
        }
        var pool = player.getData(ModAttachments.PLAYER_ENERGY).getPool(EnergyManager.POOL_NEILI);
        return pool != null && pool.current > 0;
    }

    /** 对抗加值（缠丝劲）：内力池不空时 +8 */
    public static int contestBonus(ServerPlayer player) {
        return hasCoilingSilk(player) ? SkillAbility.COILING_SILK_BONUS : 0;
    }

    /** 对抗力（攻击力对拼）：基础攻击 + 肉搏 + 徒手太极被动 + 缠丝劲 */
    private static float contestPower(ServerPlayer player) {
        int brawl = player.getData(ModAttachments.PLAYER_SKILLS).get(SkillType.BRAWL.ordinal());
        return (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE)
                + brawl + (isUnarmed(player) ? (float) TAIJI_DAMAGE_BONUS : 0f)
                + contestBonus(player);
    }

    /** 攻击者是否使用了能量池（玩家且有非空能量池且吐息开启） */
    private static boolean attackerUsedEnergy(LivingEntity attacker) {
        if (!(attacker instanceof ServerPlayer ap)) return false;
        var data = ap.getData(ModAttachments.PLAYER_ENERGY);
        return !data.isEmpty() && data.breathEnabled()
                && data.getPool(EnergyManager.POOL_NEILI) != null;
    }

    /** ===== 云手（擒抱对抗） ===== */

    /**
     * 云手结算：消耗 3 内力 → 擒抱对抗。
     * 胜利则使本次受到的攻击伤害减少对抗差值，并对攻击者施加缓慢 IV（擒抱）。
     * 返回 true 表示本次受击已消耗反应（后续反应不再触发）。
     */
    private static boolean cloudhandsResolve(ServerPlayer player, LivingEntity attacker,
                                              LivingIncomingDamageEvent event) {
        if (!player.getData(ModAttachments.PLAYER_ENERGY).consume(EnergyManager.POOL_NEILI, 3.0)) {
            player.displayClientMessage(Component.translatable("msg.zhushenspace.neili.lack"), true);
            return false;
        }
        EnergyManager.sync(player);
        SkillManager.settleCloudhands(player, SkillAbility.CLOUD_HANDS_COOLDOWN_TICKS);

        float mine = contestPower(player);
        float theirs = (float) attacker.getAttributeValue(Attributes.ATTACK_DAMAGE);
        float margin = mine - theirs;

        if (margin > 0) {
            // 擒抱成功：本次伤害减少差值，攻击者缓慢 IV（被擒抱，3 秒）
            event.setAmount(Math.max(0f, event.getAmount() - margin));
            attacker.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 3));
            player.displayClientMessage(Component.translatable(
                    "msg.zhushenspace.taiji.cloudhands_success", attacker.getDisplayName()), true);
            if (player.level() instanceof ServerLevel level) {
                double ty = attacker.getY() + attacker.getBbHeight() * 0.5;
                for (int i = 0; i < 10; i++) {
                    double a = Math.PI * 2 * i / 10;
                    level.sendParticles(ParticleTypes.END_ROD,
                            attacker.getX() + Math.cos(a) * 0.5, ty,
                            attacker.getZ() + Math.sin(a) * 0.5, 1, 0, 0.03, 0, 0.01);
                }
            }
            player.level().playSound(null, attacker.blockPosition(),
                    ModSounds.NEILI_CHIME.get(), SoundSource.PLAYERS, 0.6f, 0.8f);
        } else {
            player.displayClientMessage(Component.translatable(
                    "msg.zhushenspace.taiji.cloudhands_fail"), true);
        }
        return true;
    }

    /** ===== 海底针（摔绊对抗） ===== */

    /**
     * 海底针结算：消耗 3 内力 → 摔绊对抗。
     * @param fromAttack true=受到近战攻击触发（伤害减少差值/2），false=身边目标移动触发（使其失去移动力）
     */
    private static boolean seabottomResolve(ServerPlayer player, LivingEntity attacker,
                                             LivingIncomingDamageEvent event, boolean fromAttack) {
        if (!player.getData(ModAttachments.PLAYER_ENERGY).consume(EnergyManager.POOL_NEILI, 3.0)) {
            player.displayClientMessage(Component.translatable("msg.zhushenspace.neili.lack"), true);
            return false;
        }
        EnergyManager.sync(player);
        SkillManager.settleSeabottom(player, SkillAbility.SEA_BOTTOM_COOLDOWN_TICKS);

        float mine = contestPower(player);
        float theirs = (float) attacker.getAttributeValue(Attributes.ATTACK_DAMAGE);
        float margin = mine - theirs;

        if (margin > 0) {
            // 摔绊成功：攻击者缓慢 IV + 虚弱 I（倒地，3 秒）
            attacker.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 3));
            attacker.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 0));
            attacker.fallDistance += 1.0f;
            if (fromAttack) {
                // 受击触发：本次伤害减少差值/2
                event.setAmount(Math.max(0f, event.getAmount() - margin / 2f));
                player.displayClientMessage(Component.translatable(
                        "msg.zhushenspace.taiji.seabottom_success", attacker.getDisplayName()), true);
            } else {
                // 移动触发：使其失去移动力（已施加缓慢 IV）
                player.displayClientMessage(Component.translatable(
                        "msg.zhushenspace.taiji.seabottom_trip", attacker.getDisplayName()), true);
            }
            if (player.level() instanceof ServerLevel level) {
                level.sendParticles(ParticleTypes.CLOUD,
                        attacker.getX(), attacker.getY() + 0.1, attacker.getZ(),
                        8, 0.3, 0.05, 0.3, 0.05);
            }
            player.level().playSound(null, attacker.blockPosition(),
                    ModSounds.TAI_CHI_MOVE.get(), SoundSource.PLAYERS, 0.7f, 0.6f);
        } else {
            player.displayClientMessage(Component.translatable(
                    "msg.zhushenspace.taiji.seabottom_fail"), true);
        }
        return true;
    }

    /** ===== 太极化劲（反击 + 连锁 + 封印） ===== */

    /**
     * 太极化劲结算：消耗 5 内力 → 对攻击者发起近战肉搏反击。
     * 命中后连锁一次揽雀尾/云手/海底针（按情境择一，需已习得）。
     * 若反击造成伤害或连锁成功 → 封印攻击者能量池 1 分钟。
     * 返回 true 表示本次受击已消耗反应。
     */
    private static boolean dissolveResolve(ServerPlayer player, LivingEntity attacker,
                                            LivingIncomingDamageEvent event) {
        if (!player.getData(ModAttachments.PLAYER_ENERGY).consume(EnergyManager.POOL_NEILI, 5.0)) {
            player.displayClientMessage(Component.translatable("msg.zhushenspace.neili.lack"), true);
            return false;
        }
        EnergyManager.sync(player);
        SkillManager.settleDissolve(player, SkillAbility.DISSOLVE_COOLDOWN_TICKS);

        // 反击：近战肉搏攻击
        float dmg = contestPower(player);
        float before = attacker.getHealth();
        MOVE_ATTACK_FLAG.add(player.getUUID());
        boolean hurt = attacker.hurt(player.damageSources().playerAttack(player), dmg);
        MOVE_ATTACK_FLAG.remove(player.getUUID());
        float dealt = hurt ? Math.max(0, before - attacker.getHealth()) : 0f;

        boolean chainSuccess = false;
        // 连锁：命中后择一施展（需已习得）
        if (dealt > 0) {
            chainSuccess = chainDissolveMove(player, attacker);
        }

        // 封印：造成伤害或连锁成功 → 封印能量池 1 分钟
        if (dealt > 0 || chainSuccess) {
            sealPool(attacker, SkillAbility.DISSOLVE_SEAL_TICKS);
            player.displayClientMessage(Component.translatable(
                    "msg.zhushenspace.taiji.dissolve_seal", attacker.getDisplayName()), true);
        }

        // 特效：内力爆发
        if (player.level() instanceof ServerLevel level) {
            double ty = attacker.getY() + attacker.getBbHeight() * 0.5;
            level.sendParticles(ParticleTypes.END_ROD,
                    attacker.getX(), ty, attacker.getZ(), 14, 0.4, 0.4, 0.4, 0.06);
            level.sendParticles(ParticleTypes.SWEEP_ATTACK,
                    attacker.getX(), ty, attacker.getZ(), 1, 0, 0, 0, 0);
        }
        player.level().playSound(null, attacker.blockPosition(),
                ModSounds.TAI_CHI_MOVE.get(), SoundSource.PLAYERS, 1.0f, 0.8f);
        hitFeel(player, 0.7f, 6);
        return true;
    }

    /**
     * 化劲连锁：按情境择一施展揽雀尾/云手/海底针（需已习得）。
     * 返回 true 表示连锁招式成功（缴械/擒抱/摔绊）。
     */
    private static boolean chainDissolveMove(ServerPlayer player, LivingEntity target) {
        // 有武器 → 揽雀尾（缴械）；正在移动 → 海底针（摔绊）；否则 → 云手（擒抱）
        SkillAbility chain = null;
        if (!target.getMainHandItem().isEmpty() && isSkillPurchased(player, SkillAbility.LAN_QUE_WEI)) {
            chain = SkillAbility.LAN_QUE_WEI;
        } else if (target.getDeltaMovement().horizontalDistanceSqr() > 0.01
                && isSkillPurchased(player, SkillAbility.SEA_BOTTOM_NEEDLE)) {
            chain = SkillAbility.SEA_BOTTOM_NEEDLE;
        } else if (isSkillPurchased(player, SkillAbility.CLOUD_HANDS)) {
            chain = SkillAbility.CLOUD_HANDS;
        } else if (isSkillPurchased(player, SkillAbility.LAN_QUE_WEI)) {
            chain = SkillAbility.LAN_QUE_WEI;
        } else if (isSkillPurchased(player, SkillAbility.SEA_BOTTOM_NEEDLE)) {
            chain = SkillAbility.SEA_BOTTOM_NEEDLE;
        }
        if (chain == null) return false;

        float mine = contestPower(player);
        float theirs = (float) target.getAttributeValue(Attributes.ATTACK_DAMAGE);
        boolean win = mine > theirs;

        if (chain == SkillAbility.LAN_QUE_WEI && win && !target.getMainHandItem().isEmpty()) {
            ItemStack weapon = target.getMainHandItem();
            target.spawnAtLocation(weapon.copy());
            target.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.displayClientMessage(Component.translatable(
                    "msg.zhushenspace.taiji.dissolve_chain_disarm", target.getDisplayName()), true);
            return true;
        } else if (chain == SkillAbility.SEA_BOTTOM_NEEDLE && win) {
            target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 3));
            target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 0));
            target.fallDistance += 1.0f;
            player.displayClientMessage(Component.translatable(
                    "msg.zhushenspace.taiji.dissolve_chain_trip", target.getDisplayName()), true);
            return true;
        } else if (chain == SkillAbility.CLOUD_HANDS && win) {
            target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 3));
            player.displayClientMessage(Component.translatable(
                    "msg.zhushenspace.taiji.dissolve_chain_grapple", target.getDisplayName()), true);
            return true;
        }
        return false;
    }

    /** ===== 待势特效 ===== */

    /** 云手待势特效：双手画圆 */
    public static void cloudhandsReadyEffects(ServerPlayer player) {
        if (player.level() instanceof ServerLevel level) {
            for (int i = 0; i < 8; i++) {
                double a = Math.PI * 2 * i / 8;
                level.sendParticles(ParticleTypes.END_ROD,
                        player.getX() + Math.cos(a) * 0.8, player.getY() + 1.0,
                        player.getZ() + Math.sin(a) * 0.8, 1, 0, 0.02, 0, 0.01);
            }
        }
        player.level().playSound(null, player.blockPosition(),
                ModSounds.NEILI_CHIME.get(), SoundSource.PLAYERS, 0.6f, 0.8f);
    }

    /** 海底针待势特效：下沉气劲 */
    public static void seabottomReadyEffects(ServerPlayer player) {
        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.CLOUD,
                    player.getX(), player.getY() + 0.1, player.getZ(),
                    6, 0.3, 0.05, 0.3, 0.02);
        }
        player.level().playSound(null, player.blockPosition(),
                ModSounds.TAI_CHI_MOVE.get(), SoundSource.PLAYERS, 0.5f, 0.5f);
    }

    /** 太极化劲待势特效：气漩汇聚 */
    public static void dissolveReadyEffects(ServerPlayer player) {
        if (player.level() instanceof ServerLevel level) {
            for (int i = 0; i < 12; i++) {
                double a = Math.PI * 2 * i / 12;
                double r = 0.6 + (i % 2) * 0.4;
                level.sendParticles(ParticleTypes.END_ROD,
                        player.getX() + Math.cos(a) * r, player.getY() + 0.8 + i * 0.04,
                        player.getZ() + Math.sin(a) * r, 1, 0, 0.03, 0, 0.01);
            }
        }
        player.level().playSound(null, player.blockPosition(),
                ModSounds.NEILI_CHIME.get(), SoundSource.PLAYERS, 0.8f, 0.6f);
    }

    /** ===== 八式 ===== */

    /**
     * 执行一式：锁定视线目标 → 基础攻击 + 招式效果。
     * 返回 false 表示未开始（无目标），不消耗内力、不进入冷却。
     */
    public static boolean useMove(ServerPlayer player, SkillAbility ability) {
        LivingEntity target = findTarget(player);
        if (target == null) {
            player.displayClientMessage(Component.translatable("msg.zhushenspace.taiji.no_target"), true);
            return false;
        }

        float damage = (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE);
        switch (ability) {
            case WARD_OFF -> damage += 27f;
            case PUSH -> damage += 9f;
            case PRESS -> {
                damage += 6f; // 破魔：额外无视最多 6 点伤害吸收
                if (target.getAbsorptionAmount() > 0) {
                    damage += Math.min(target.getAbsorptionAmount(), 6f);
                }
            }
            case ELBOW -> damage += 6f * target.getArmorValue() / 20f; // 破甲 6
            // 采：3 点严重伤害的即时部分并入本次攻击（命中后再单独 hurt 会被受击无敌帧吞掉）
            case PULL -> {
                if (!(target instanceof ServerPlayer)) damage += (float) severeHpLoss(3);
            }
            default -> {
            }
        }

        float before = target.getHealth();
        MOVE_ATTACK_FLAG.add(player.getUUID()); // 招式伤害不计入八劲合一普攻附加
        boolean hurt = target.hurt(player.damageSources().playerAttack(player), damage);
        MOVE_ATTACK_FLAG.remove(player.getUUID());
        if (!hurt) return true; // 攻击已消耗（目标无敌帧等），照常进入冷却
        float dealt = before - target.getHealth();

        // 通用特效：太极横扫光环 + 出招风声（重击额外暴星）
        double ty = target.getY() + target.getBbHeight() * 0.6;
        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.SWEEP_ATTACK,
                    target.getX(), ty, target.getZ(), 1, 0, 0, 0, 0);
            if (dealt >= 12) {
                level.sendParticles(ParticleTypes.CRIT,
                        target.getX(), ty, target.getZ(), 12, 0.3, 0.4, 0.3, 0.12);
            }
        }
        player.level().playSound(null, target.blockPosition(),
                ModSounds.TAI_CHI_MOVE.get(), SoundSource.PLAYERS, 0.9f, 1.0f);
        // 打击感：命中镜头微震（随伤害强度）
        if (dealt > 0) {
            hitFeel(player, Math.min(0.9f, 0.3f + dealt * 0.02f), 5);
        }

        switch (ability) {
            case ROLL_BACK -> {
                MOVE_ATTACK_FLAG.add(player.getUUID());
                // 追加伤害是独立一击：清除受击无敌帧，否则只会结算 (21 - 本次攻击伤害) 的差值
                target.invulnerableTime = 0;
                target.hurt(player.damageSources().playerAttack(player), 21f);
                MOVE_ATTACK_FLAG.remove(player.getUUID());
            }
            case PULL -> { // 采：黑烟缠绕（削减生机）
                if (target instanceof ServerPlayer victim) {
                    HealthManager.addWound(victim, PlayerHealthData.Severity.L, 3);
                } else if (target.isAlive()) {
                    reduceMaxHealth(target, severeHpLoss(3)); // 即时伤害已并入主攻击
                }
                if (player.level() instanceof ServerLevel level) {
                    level.sendParticles(ParticleTypes.SMOKE,
                            target.getX(), target.getY() + target.getBbHeight() * 0.8, target.getZ(),
                            14, 0.3, 0.5, 0.3, 0.01);
                    level.sendParticles(ParticleTypes.DAMAGE_INDICATOR,
                            target.getX(), target.getY() + target.getBbHeight() * 0.7, target.getZ(),
                            6, 0.25, 0.3, 0.25, 0.1);
                }
            }
            case PRESS -> { // 挤：破魔碎屑（伤害吸收被打碎）
                if (player.level() instanceof ServerLevel level) {
                    level.sendParticles(ParticleTypes.END_ROD,
                            target.getX(), target.getY() + target.getBbHeight() * 0.6, target.getZ(),
                            10, 0.3, 0.4, 0.3, 0.08);
                }
            }
            case SPLIT -> { // 挒：高速优势压制
                target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 100, 1));
                target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 100, 1));
                if (player.level() instanceof ServerLevel level) {
                    level.sendParticles(ParticleTypes.WITCH,
                            target.getX(), target.getY() + target.getBbHeight() * 0.6, target.getZ(),
                            18, 0.3, 0.5, 0.3, 0.02);
                }
            }
            case ELBOW -> { // 肘：破甲碎裂
                player.level().playSound(null, target.blockPosition(),
                        net.minecraft.sounds.SoundEvents.SHIELD_BREAK, SoundSource.PLAYERS, 0.6f, 0.7f);
                if (player.level() instanceof ServerLevel level) {
                    level.sendParticles(ParticleTypes.CRIT,
                            target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(),
                            8, 0.25, 0.3, 0.25, 0.1);
                }
            }
            case SHOULDER -> { // 靠：按伤害击退，撞墙受钝击严重伤害
                Vec3 view = player.getViewVector(1.0f).normalize();
                double kb = Math.min(Math.max(dealt, 1), 10) * 0.25;
                target.push(view.x * kb, 0.35, view.z * kb);
                target.hurtMarked = true;
                // 冲击波气浪：沿击退方向喷出云团
                if (player.level() instanceof ServerLevel level) {
                    Vec3 dir = target.position().subtract(player.position()).normalize();
                    level.sendParticles(ParticleTypes.CLOUD,
                            target.getX() + dir.x * 0.7, target.getY() + target.getBbHeight() * 0.5,
                            target.getZ() + dir.z * 0.7, 10, 0.2, 0.25, 0.2, 0.06);
                }
                if (dealt > 0) {
                    WALL_CHECKS.put(target.getUUID(), new WallCheck(target, player,
                            (int) Math.ceil(dealt), System.currentTimeMillis() + 400));
                }
            }
            default -> {
            }
        }
        return true;
    }

    /** 靠·撞墙结算：钝击闷响 + 冲击爆发 */
    private static void wallImpactEffects(LivingEntity target) {
        target.level().playSound(null, target.blockPosition(),
                ModSounds.TAI_CHI_WALL.get(), SoundSource.PLAYERS, 1.0f, 1.0f);
        if (target.level() instanceof ServerLevel level) {
            double ty = target.getY() + target.getBbHeight() * 0.5;
            level.sendParticles(ParticleTypes.EXPLOSION,
                    target.getX(), ty, target.getZ(), 1, 0, 0, 0, 0);
            level.sendParticles(ParticleTypes.CRIT,
                    target.getX(), ty, target.getZ(), 16, 0.4, 0.4, 0.4, 0.15);
        }
    }

    /** 听劲启动：钟鸣气场 + 以自身为中心的双层符文环 */
    public static void tingjinActivateEffects(ServerPlayer player) {
        player.level().playSound(null, player.blockPosition(),
                ModSounds.TAI_CHI_TINGJIN.get(), SoundSource.PLAYERS, 1.0f, 0.9f);
        if (player.level() instanceof ServerLevel level) {
            for (int i = 0; i < 16; i++) {
                double a = Math.PI * 2 * i / 16;
                double px = player.getX() + Math.cos(a) * 1.1;
                double pz = player.getZ() + Math.sin(a) * 1.1;
                level.sendParticles(ParticleTypes.ENCHANT,
                        px, player.getY() + 1.2, pz, 2, 0, 0.1, 0, 0.05);
                level.sendParticles(ParticleTypes.END_ROD,
                        px, player.getY() + 0.4, pz, 1, 0, 0.05, 0, 0.01);
            }
        }
    }

    /** 视线范围内选取最近的可攻击目标（视野夹角 0.4 以上、距离 4.5 格内） */
    private static LivingEntity findTarget(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 view = player.getViewVector(1.0f);
        AABB box = player.getBoundingBox().expandTowards(view.scale(3.5)).inflate(1.5);
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity entity : player.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e != player && e.isAlive() && e.isAttackable() && player.hasLineOfSight(e))) {
            Vec3 to = entity.getBoundingBox().getCenter().subtract(eye);
            double dist = to.length();
            if (dist > 4.5 || dist < 1.0E-4 || to.normalize().dot(view) < 0.4) continue;
            if (dist < bestDist) {
                bestDist = dist;
                best = entity;
            }
        }
        return best;
    }

    /** 严重伤害的即时/上限部分：每 3 点严重伤害 = 1 点生命 */
    private static double severeHpLoss(int severe) {
        return severe / 3.0;
    }

    /**
     * 严重伤害（独立一击，如靠·撞墙）：
     * - 玩家目标：直接计入 B/L/A 系统的严重（L）伤势池；
     * - 其余生物：先造成 severe/3 点即时伤害（清除受击无敌帧，保证能结算，且不致死），
     *   再削减等量最大生命值（见 {@link #reduceMaxHealth}）。
     * 招式/八劲内的严重伤害不走这里：即时部分直接并入主攻击伤害。
     */
    private static void severeDamage(LivingEntity target, int severe) {
        if (severe <= 0 || !target.isAlive()) return;
        if (target instanceof ServerPlayer player) {
            HealthManager.addWound(player, PlayerHealthData.Severity.L, severe);
            return;
        }
        double hpLoss = severeHpLoss(severe);
        float instant = (float) Math.min(hpLoss, target.getHealth() - 1.0e-4);
        if (instant > 0) {
            target.invulnerableTime = 0;
            target.hurt(target.damageSources().generic(), instant);
        }
        if (target.isAlive()) {
            reduceMaxHealth(target, hpLoss);
        }
    }

    /**
     * 削减生物最大生命值（瞬态修饰器，死亡/重登后消退；上限不低于 1）。
     * <p>
     * 上限绝不压到当前生命值以下：否则原版会在下一刻以 setHealth(上限) 静默钳制生命值，
     * 这次扣血绕过受伤流程，会让依赖生命追踪的实体错乱——例如 MmmMmmMmmMmm 训练假人
     * （无限血模式下生命值恒满）会把这段差值当作「真实伤害」每刻反复显示 0.3 伤害 / +0.3 治疗。
     * 因此即时伤害未能扣到（无敌、免疫、假人无限血等）时，上限也相应不削减。
     */
    private static void reduceMaxHealth(LivingEntity target, double hpLoss) {
        if (hpLoss <= 0 || target instanceof ServerPlayer) return;
        AttributeInstance attr = target.getAttribute(Attributes.MAX_HEALTH);
        if (attr == null) return;
        AttributeModifier old = attr.getModifier(SEVERE_ID);
        double prev = old != null ? old.amount() : 0;
        double base = attr.getBaseValue();
        double maxWithout = target.getMaxHealth() - prev;       // 不含本修饰器的上限
        double floorByHealth = target.getHealth() - maxWithout; // 上限 ≥ 当前生命值
        double total = Math.max(prev - hpLoss, Math.max(-(base - 1), floorByHealth));
        total = Math.min(total, 0);
        if (total >= prev - 1.0e-6) return; // 无可削减

        attr.removeModifier(SEVERE_ID);
        attr.addTransientModifier(new AttributeModifier(SEVERE_ID, total,
                AttributeModifier.Operation.ADD_VALUE));
        // 兜底：存在乘算修饰器时换算可能有偏差，若上限仍低于生命值则回滚
        if (target.getMaxHealth() < target.getHealth() - 1.0e-4) {
            attr.removeModifier(SEVERE_ID);
            if (old != null) attr.addTransientModifier(old);
        }
    }
}
