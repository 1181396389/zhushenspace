package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.Condition;
import com.zhushen.space.data.LimbPart;
import com.zhushen.space.data.PlayerConditionData;
import com.zhushen.space.data.StatusType;
import com.zhushen.space.data.StatusType.Tier;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 不良状态的效果（仅玩家）：由不良状态点数的档位派生出的固有不良状态 + 直接施加的固有不良状态，
 * 换算成移动速度、防御、攻击 / 检定 / 施法 / 豁免的修正与各种行为限制。
 * <p>
 * 本模组没有先攻顺序，「先攻值降低」无对应效果；移动速度的降低按百分比计算：
 * 轻度不良状态「速度 −4 米」= 移速 −{@link #SPEED_LIGHT_PCT}%，失速「基础速度 −12 米」= 移速 −{@link #SPEED_SLOW_PCT}%，
 * 多项相加，降至 0% 时无法移动（飞行中的人物坠落）；「每移动 1 米耗费 2 米」类效果 = 剩余移速减半。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class StatusEffects {

    private StatusEffects() {
    }

    /** 轻度不良状态「速度 −4 米」对应的移速降低百分比 */
    public static final int SPEED_LIGHT_PCT = 30;
    /** 失速「基础速度 −12 米」对应的移速降低百分比 */
    public static final int SPEED_SLOW_PCT = 60;

    private static ResourceLocation rl(String s) { return ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, s); }

    private static final ResourceLocation SPEED = rl("status_speed"), JUMP = rl("status_jump"), ATK_SPEED = rl("status_attack_speed"),
            KB = rl("status_knockback"), ARMOR = rl("status_armor"), FEAR_SPEED = rl("status_fear_speed");

    /** 每 tick 缓存一次的固有不良状态位掩码 */
    private static final Map<UUID, long[]> CACHE = new HashMap<>();
    /** 由本系统关闭了重力的玩家 */
    private static final Set<UUID> NO_GRAVITY = new HashSet<>();

    static PlayerConditionData data(ServerPlayer p) {
        return StatusManager.data(p);
    }

    private static int pts(ServerPlayer p, StatusType t) {
        return data(p).points(t);
    }

    private static boolean light(ServerPlayer p, StatusType t) {
        return pts(p, t) > 0;
    }

    // ===== 固有不良状态位掩码 =====

    public static void invalidate(ServerPlayer p) {
        CACHE.remove(p.getUUID());
    }

    public static void forget(UUID id) {
        CACHE.remove(id);
        NO_GRAVITY.remove(id);
    }

    public static void forgetAll() {
        CACHE.clear();
        NO_GRAVITY.clear();
    }

    public static long mask(ServerPlayer p) {
        long tick = p.level().getGameTime();
        long[] c = CACHE.get(p.getUUID());
        if (c != null && c[0] == tick) return c[1];
        long m = compute(p);
        CACHE.put(p.getUUID(), new long[]{tick, m});
        return m;
    }

    private static long compute(ServerPlayer p) {
        PlayerConditionData d = data(p);
        long now = p.level().getGameTime();
        long m = 0;
        for (StatusType t : StatusType.values()) {
            if (t == StatusType.LIMB) continue;
            Tier tr = StatusManager.tier(p, t);
            if (tr.ordinal() >= Tier.HEAVY.ordinal()) {
                switch (t) {
                    case FEAR -> { }                                            // 惊慌逃窜：进入重度时一轮（计时）
                    case DROWSY -> { if (!d.wokeUp) m |= Condition.ASLEEP.bit(); }
                    case BURN -> { if (tr == Tier.HEAVY) m |= Condition.INCINERATING.bit(); }
                    default -> m |= t.heavy.bit();
                }
            }
            if (tr == Tier.DESTRUCTIVE || d.isPermanent(t)) {
                if (t == StatusType.BURN) { if (tr == Tier.DESTRUCTIVE) m |= Condition.CREMATING.bit(); }
                else m |= t.destructive.bit();
            }
        }
        if (limbDisabledMask(p) != 0) m |= Condition.LIMB_DISABLED.bit();
        if (d.openWounds > 0) m |= Condition.OPEN_WOUND.bit();
        for (Condition c : Condition.values()) if (d.timed(c, now)) m |= c.bit();
        if (LimbManager.eyesLost(p) >= 2) m |= Condition.BLIND.bit();
        if (d.deprivedTicks >= RestManager.DAY_TICKS) m |= Condition.STARVING.bit();
        if (d.wearyTicks >= RestManager.DAY_TICKS) m |= Condition.WEARY.bit();
        // 正面影响：目盲不再受视觉障碍影响，耳聋不再受听觉障碍影响
        if ((m & Condition.BLIND.bit()) != 0) m &= ~Condition.VISION_IMPAIRED.bit();
        if ((m & Condition.DEAF.bit()) != 0) m &= ~Condition.HEARING_IMPAIRED.bit();
        return m;
    }

    public static boolean has(ServerPlayer p, Condition c) {
        return (mask(p) & c.bit()) != 0;
    }

    private static boolean any(ServerPlayer p, Condition... cs) {
        long m = mask(p);
        for (Condition c : cs) if ((m & c.bit()) != 0) return true;
        return false;
    }

    /** 肢体残障（重度肢体妨害）的肢体位掩码 */
    public static int limbDisabledMask(ServerPlayer p) {
        int mask = 0;
        for (LimbPart lp : LimbPart.values()) {
            if (lp.severable() && StatusManager.limbTier(p, lp).ordinal() >= Tier.HEAVY.ordinal()) mask |= lp.bit();
        }
        return mask;
    }

    public static boolean limbDisabled(ServerPlayer p, LimbPart lp) {
        return (limbDisabledMask(p) & lp.bit()) != 0;
    }

    /** 肢体无法使用（断肢或肢体残障） */
    public static boolean limbUnusable(ServerPlayer p, LimbPart lp) {
        return LimbManager.data(p).isSevered(lp) || limbDisabled(p, lp);
    }

    // ===== 行为限制 =====

    /** 完全无法行动：冰封、石化、昏迷、睡眠、永眠、精神奴役、震慑、无助、放逐、困到昏睡 */
    public static boolean incapacitated(ServerPlayer p) {
        return data(p).collapsed || any(p, Condition.FROZEN, Condition.PETRIFIED, Condition.UNCONSCIOUS, Condition.ASLEEP,
                Condition.ETERNAL_SLEEP, Condition.ENSLAVED, Condition.STUNNED, Condition.HELPLESS, Condition.BANISHED);
    }

    /** 倒在地上：昏迷、睡眠、永眠、无助 */
    public static boolean lying(ServerPlayer p) {
        return any(p, Condition.UNCONSCIOUS, Condition.ASLEEP, Condition.ETERNAL_SLEEP, Condition.HELPLESS);
    }

    /** 看不见：目盲（含失去双眼） */
    public static boolean blind(ServerPlayer p) {
        return has(p, Condition.BLIND);
    }

    private static void deny(ServerPlayer p, String key) {
        p.displayClientMessage(Component.translatable(key), true);
    }

    /** 能否主动行动（标准动作：扑灭火焰、急救等） */
    public static boolean canAct(ServerPlayer p, boolean message) {
        if (incapacitated(p)) { if (message) deny(p, "msg.zhushenspace.status.cant_act"); return false; }
        if (any(p, Condition.DISABLED, Condition.MISANTHROPY)) { if (message) deny(p, "msg.zhushenspace.status.no_active"); return false; }
        return true;
    }

    /** 能否攻击（target 可为 null） */
    public static String attackBlock(ServerPlayer p, Entity target) {
        if (incapacitated(p)) return "msg.zhushenspace.status.cant_act";
        if (any(p, Condition.DISABLED, Condition.MISANTHROPY)) return "msg.zhushenspace.status.no_active";
        if (has(p, Condition.PARALYZED)) return "msg.zhushenspace.status.paralyzed";
        PlayerConditionData d = data(p);
        if (target != null && has(p, Condition.INFATUATED) && target.getUUID().equals(d.charmTarget))
            return "msg.zhushenspace.status.infatuated";
        if (target != null && has(p, Condition.TAUNTED) && d.taunter != null && !target.getUUID().equals(d.taunter))
            return "msg.zhushenspace.status.taunted";
        return null;
    }

    /** 能否施放技艺（spell = 法术：需要语言 / 姿势成分） */
    public static boolean canCast(ServerPlayer p, boolean spell) {
        if (!canAct(p, true)) return false;
        if (spell && has(p, Condition.SILENCED)) { deny(p, "msg.zhushenspace.status.silenced"); return false; }
        if (spell && (any(p, Condition.PARALYZED, Condition.FROZEN)
                || limbUnusable(p, LimbPart.RIGHT_ARM) && limbUnusable(p, LimbPart.LEFT_ARM))) {
            deny(p, "msg.zhushenspace.status.no_gesture");
            return false;
        }
        return true;
    }

    /** 能否休息 / 冥想（多系统器官功能衰竭时无法休息；狂躁 / 歇斯底里无法静下来） */
    public static String restBlock(ServerPlayer p) {
        if (has(p, Condition.MODS)) return "msg.zhushenspace.status.mods_no_rest";
        if (any(p, Condition.MANIC, Condition.HYSTERIA)) return "msg.zhushenspace.status.manic_no_rest";
        return null;
    }

    /** 狂躁：无法使用意志力 */
    public static boolean canUseWillpower(ServerPlayer p) {
        return !any(p, Condition.MANIC, Condition.HYSTERIA) && !incapacitated(p);
    }

    /** 失血过多：治疗冲击 / 严重伤害如同恶性伤害一般困难（每 3 点治疗量治愈 1 点），恶性伤害无法治疗 */
    public static boolean bloodLoss(ServerPlayer p) {
        return has(p, Condition.BLOOD_LOSS);
    }

    /** 力竭 / 反胃 / 失衡：无法冲刺 */
    public static boolean noSprint(ServerPlayer p) {
        return any(p, Condition.EXHAUSTED, Condition.NAUSEOUS, Condition.UNBALANCED) || speedFactor(p) < 0.5;
    }

    // ===== 数值修正 =====

    /** 基础移动速度倍率（0 = 无法移动） */
    public static double speedFactor(ServerPlayer p) {
        if (any(p, Condition.FROZEN, Condition.PETRIFIED, Condition.ROOTED, Condition.PARALYZED, Condition.UNCONSCIOUS,
                Condition.ASLEEP, Condition.ETERNAL_SLEEP, Condition.ENSLAVED, Condition.STUNNED, Condition.HELPLESS,
                Condition.FLOATING, Condition.IMPRISONED, Condition.BANISHED, Condition.DISABLED)) return 0;
        // 按百分比降低（多项相加，最低 0%）
        int pct = 0;
        for (StatusType t : new StatusType[]{StatusType.FREEZE, StatusType.CRYSTAL, StatusType.ENTANGLE, StatusType.PARALYSIS, StatusType.FATIGUE}) {
            if (light(p, t)) pct += SPEED_LIGHT_PCT;
        }
        if (light(p, StatusType.SLOW)) pct += SPEED_SLOW_PCT;
        double f = Math.max(0, (100 - pct) / 100.0);
        int legs = (limbDisabled(p, LimbPart.RIGHT_LEG) ? 1 : 0) + (limbDisabled(p, LimbPart.LEFT_LEG) ? 1 : 0);
        if (legs >= 2) return 0;
        if (legs == 1) f *= 0.5;                                   // 单腿残障：每移动 1 米耗费 2 点移动力
        if (has(p, Condition.EXHAUSTED)) f *= 0.1;                 // 力竭：基础移动速度降为 1/10
        if (has(p, Condition.BLIND)) f *= 0.5;                     // 目盲：每移动 1 米耗费 2 米移动力
        if (has(p, Condition.NAUSEOUS)) f *= 0.5;                  // 反胃：每回合失去一个移动动作
        if (has(p, Condition.UNBALANCED)) f *= 0.5;                // 失衡：所需动作提升一级
        return f;
    }

    /** 失去基础防御与闪避加值：冻伤、冰封、石化、昏迷、睡眠、浮空、无助、视觉障碍（强制措手不及） */
    public static boolean loseNatural(ServerPlayer p) {
        return any(p, Condition.FROSTBITE, Condition.FROZEN, Condition.PETRIFIED, Condition.UNCONSCIOUS, Condition.ASLEEP,
                Condition.ETERNAL_SLEEP, Condition.FLOATING, Condition.HELPLESS, Condition.VISION_IMPAIRED);
    }

    /** 无法格挡（举盾）：冻伤、冰封、石化、昏迷、睡眠、无助、震慑 */
    public static boolean cantBlock(ServerPlayer p) {
        return any(p, Condition.FROSTBITE, Condition.FROZEN, Condition.PETRIFIED, Condition.UNCONSCIOUS, Condition.ASLEEP,
                Condition.ETERNAL_SLEEP, Condition.HELPLESS, Condition.STUNNED);
    }

    public static boolean isRanged(DamageSource src) {
        if (src.is(DamageTypeTags.IS_PROJECTILE) || GunDamage.isGun(src)) return true;
        Entity direct = src.getDirectEntity();
        return direct != null && direct != src.getEntity();
    }

    public static float defenseMod(LivingEntity victim, DamageSource src) {
        if (!(victim instanceof ServerPlayer p)) return 0f;
        return defenseMod(p, isRanged(src), src.getEntity());
    }

    /** 防御修正：倒地、轻度不良状态（各 −4）、亢奋（−12） */
    public static float defenseMod(ServerPlayer p, boolean ranged, Entity attacker) {
        float m = 0;
        PlayerConditionData d = data(p);
        if (d.prone) m += ranged ? StatusManager.PRONE_RANGED_BONUS : -StatusManager.PRONE_MELEE_PENALTY;
        int n = 0;
        if (light(p, StatusType.FREEZE)) n++;
        if (light(p, StatusType.TINNITUS) && !has(p, Condition.DEAF)) n++;
        if (light(p, StatusType.DAZZLE) && !has(p, Condition.BLIND)) n++;
        if (light(p, StatusType.CRYSTAL)) n++;
        if (light(p, StatusType.ENTANGLE)) n++;
        if (light(p, StatusType.PARALYSIS)) n++;
        if (light(p, StatusType.DAZE)) n++;
        m -= StatusManager.LIGHT_PENALTY * n;
        if (light(p, StatusType.EXCITEMENT)) m -= 12; // 不会减至负数（防御最终截断为 0）
        // 失去基础 / 闪避 / 格挡防御的状态由 Defense.parts 处理
        return m;
    }

    /** 攻击检定减值（正数 = 扣除） */
    public static int attackPenalty(ServerPlayer p, boolean ranged, Entity target) {
        int pen = 0;
        for (StatusType t : new StatusType[]{StatusType.FREEZE, StatusType.NAUSEA, StatusType.PARALYSIS, StatusType.DAZE,
                StatusType.FATIGUE, StatusType.DROWSY}) {
            if (light(p, t)) pen += StatusManager.LIGHT_PENALTY;
        }
        if (light(p, StatusType.DEPRESSION)) pen += 9;
        // 肢体妨害：以该肢体为主的攻击 −6（近战 = 主手；远程 = 双手）
        PlayerConditionData d = data(p);
        LimbPart main = LimbManager.armOf(p, InteractionHand.MAIN_HAND), off = LimbManager.armOf(p, InteractionHand.OFF_HAND);
        if (d.limb[main.ordinal()] > 0 || ranged && d.limb[off.ordinal()] > 0) pen += 6;
        if (blind(p)) pen += 8;
        else if (ranged && LimbManager.eyesLost(p) == 1) pen += 4;
        // 恐惧：主动对恐惧目标发起的检定 −4
        if (target != null && light(p, StatusType.FEAR) && target.getUUID().equals(d.fearTarget)) pen += StatusManager.LIGHT_PENALTY;
        // 冻寒骨爪「镇亡」（不死生物玩家，如亡灵种族）
        pen += MagicSpells.attackCurse(p);
        return pen;
    }

    /** 肌肉痉挛：生理系检定（含以生理系技能进行的攻击）失去一半的自然成功数 */
    public static float successFactor(ServerPlayer p) {
        return has(p, Condition.SPASM) ? 0.5f : 1f;
    }

    /** 肌肉痉挛：仅当检定属性包含生理系（力量 / 敏捷 / 耐力）时减半 */
    public static float successFactor(ServerPlayer p, AttributeType... attrs) {
        for (AttributeType a : attrs)
            if (a == AttributeType.STRENGTH || a == AttributeType.AGILITY || a == AttributeType.ENDURANCE) return successFactor(p);
        return 1f;
    }

    /** 主动发起的属性检定减值（正数 = 扣除；≥ 999 = 自动失败） */
    public static int checkPenalty(ServerPlayer p, AttributeType... attrs) {
        return penalty(p, true, attrs);
    }

    /** 豁免检定中的属性减值（豁免不是主动发起的检定：不受沮丧 / 厌世 / 失能影响） */
    public static int savePenalty(ServerPlayer p, AttributeType... attrs) {
        return penalty(p, false, attrs);
    }

    private static int penalty(ServerPlayer p, boolean active, AttributeType... attrs) {
        boolean per = false, end = false, strAgi = false, mental = false, social = false, physical = false;
        for (AttributeType a : attrs) {
            switch (a) {
                case STRENGTH, AGILITY -> { strAgi = true; physical = true; }
                case ENDURANCE -> { end = true; physical = true; }
                case INTELLIGENCE, RESOLVE -> mental = true;
                case PERCEPTION -> { per = true; mental = true; }
                case CHARM, OPERATION, COMPOSURE -> social = true;
            }
        }
        if (active) {
            if (any(p, Condition.DISABLED, Condition.MISANTHROPY)) return 999;
            if (physical && has(p, Condition.FROZEN)) return 999;
            if (strAgi && has(p, Condition.PARALYZED)) return 999;
        }
        int pen = 0;
        if (per) {
            if (light(p, StatusType.TINNITUS) && !has(p, Condition.DEAF)) pen += StatusManager.LIGHT_PENALTY;
            if (light(p, StatusType.DAZZLE) && !has(p, Condition.BLIND)) pen += StatusManager.LIGHT_PENALTY;
        }
        if (end && light(p, StatusType.CRYSTAL)) pen += StatusManager.LIGHT_PENALTY + 1;
        if (mental && light(p, StatusType.BIND)) pen += StatusManager.LIGHT_PENALTY;
        if (mental && light(p, StatusType.PAIN)) pen += StatusManager.LIGHT_PENALTY;
        if (social && light(p, StatusType.PAIN)) pen += StatusManager.LIGHT_PENALTY;
        if (strAgi && light(p, StatusType.FATIGUE)) pen += StatusManager.LIGHT_PENALTY;
        if (active && light(p, StatusType.DEPRESSION)) pen += 9;
        if (active && physical) pen += LoadManager.checkPenalty(p); // 负重：主动身体检定减值
        return pen;
    }

    /** 以手进行的检定（急救等）的减值：主手肢体妨害 −6，双臂都无法使用时自动失败 */
    public static int handPenalty(ServerPlayer p) {
        if (limbUnusable(p, LimbPart.RIGHT_ARM) && limbUnusable(p, LimbPart.LEFT_ARM)) return 999;
        return data(p).limb[LimbManager.armOf(p, InteractionHand.MAIN_HAND).ordinal()] > 0 ? 6 : 0;
    }

    /** 施法 / 心灵检定减值（spell = 需要姿势成分的法术） */
    public static int castPenalty(ServerPlayer p, boolean spell) {
        int pen = 0;
        if (light(p, StatusType.DAZE)) pen += StatusManager.LIGHT_PENALTY;
        if (light(p, StatusType.DROWSY)) pen += StatusManager.LIGHT_PENALTY;
        if (light(p, StatusType.BIND)) pen += StatusManager.LIGHT_PENALTY;
        if (light(p, StatusType.PAIN)) pen += StatusManager.LIGHT_PENALTY;
        if (light(p, StatusType.DEPRESSION)) pen += 9;
        if (spell) {
            PlayerConditionData d = data(p);
            if (d.limb[LimbPart.RIGHT_ARM.ordinal()] > 0 || d.limb[LimbPart.LEFT_ARM.ordinal()] > 0) pen += 6;
        }
        return pen;
    }

    /** 反射豁免修正：倒地对抗范围伤害 +3；麻痹 −4；纠缠失去 1 点自然成功数 */
    public static int reflexMod(ServerPlayer p, boolean area) {
        int m = 0;
        if (area && data(p).prone) m += StatusManager.PRONE_REFLEX_BONUS;
        if (light(p, StatusType.PARALYSIS)) m -= StatusManager.LIGHT_PENALTY;
        if (light(p, StatusType.ENTANGLE)) m -= 1;
        return m;
    }

    /** 能否进行反射豁免（石化 / 昏迷 / 睡眠 / 冰封 / 无助时不能） */
    public static boolean canReflex(ServerPlayer p) {
        return !any(p, Condition.PETRIFIED, Condition.UNCONSCIOUS, Condition.ASLEEP, Condition.ETERNAL_SLEEP, Condition.FROZEN,
                Condition.HELPLESS);
    }

    /** 意志豁免减值：精神束缚失去 1 点自然成功数 */
    public static int willMod(ServerPlayer p) {
        return light(p, StatusType.BIND) ? 1 : 0;
    }

    // ===== 施加到实体上 =====

    private static void set(ServerPlayer p, Holder<Attribute> attr, ResourceLocation id, double v, AttributeModifier.Operation op) {
        AttributeInstance inst = p.getAttribute(attr);
        if (inst == null) return;
        AttributeModifier cur = inst.getModifier(id);
        if (cur != null && cur.amount() == v) return;
        inst.removeModifier(id);
        if (v != 0) inst.addTransientModifier(new AttributeModifier(id, v, op));
    }

    /** 刷新属性修正（移速 / 跳跃 / 攻速 / 击退抗性 / 石化装甲） */
    public static void apply(ServerPlayer p) {
        double f = speedFactor(p);
        set(p, Attributes.MOVEMENT_SPEED, SPEED, Math.max(-1.0, f - 1.0), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        set(p, Attributes.JUMP_STRENGTH, JUMP, f <= 0 ? -1.0 : 0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        set(p, Attributes.ATTACK_SPEED, ATK_SPEED, has(p, Condition.UNBALANCED) ? -0.5 : 0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        set(p, Attributes.KNOCKBACK_RESISTANCE, KB, any(p, Condition.PETRIFIED, Condition.IMPRISONED) ? 1.0 : 0, AttributeModifier.Operation.ADD_VALUE);
        set(p, Attributes.ARMOR, ARMOR, has(p, Condition.PETRIFIED) ? Math.max(0, StatusManager.attr(p, AttributeType.ENDURANCE)) : 0,
                AttributeModifier.Operation.ADD_VALUE);
    }

    /** 每 tick：重力、强制逃离、画面效果、禁止举盾 / 飞行 */
    public static void tick(ServerPlayer p) {
        long m = mask(p);
        boolean hold = (m & (Condition.FLOATING.bit() | Condition.IMPRISONED.bit() | Condition.BANISHED.bit())) != 0;
        if (hold) {
            if (!p.isNoGravity()) {
                p.setNoGravity(true);
                NO_GRAVITY.add(p.getUUID());
            }
            Vec3 v = p.getDeltaMovement();
            if (Math.abs(v.y) > 0.001) {
                p.setDeltaMovement(v.x, 0, v.z);
                p.hurtMarked = true;
            }
        } else if (NO_GRAVITY.remove(p.getUUID())) {
            p.setNoGravity(false);
        }
        if (p.tickCount % 10 != 0) return;
        apply(p);
        double f = speedFactor(p);
        // 失速降至 0 / 定身等：飞行中开始坠落
        if (f <= 0 && !hold) {
            if (p.isFallFlying()) p.stopFallFlying();
            if (p.getAbilities().flying && !p.isCreative() && !p.isSpectator()) {
                p.getAbilities().flying = false;
                p.onUpdateAbilities();
            }
        }
        if (cantBlock(p) && p.isUsingItem() && p.getUseItem().getItem() instanceof ShieldItem) p.stopUsingItem();
        if (incapacitated(p) && p.isUsingItem()) p.stopUsingItem();
        if ((m & Condition.NAUSEOUS.bit()) != 0) p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 120, 0, true, false));
        if ((m & Condition.BLIND.bit()) != 0) p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0, true, false));
        if ((m & Condition.BANISHED.bit()) != 0) p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 60, 0, true, false));
        if (noSprint(p)) p.setSprinting(false);
        fear(p, m);
    }

    /** 恐惧：向恐惧目标移动时移速减半；惊慌逃窜 / 惊惧：被迫远离恐惧目标 */
    private static void fear(ServerPlayer p, long m) {
        PlayerConditionData d = data(p);
        Entity src = d.fearTarget == null || p.getServer() == null ? null : StatusManager.findEntity(p.getServer(), d.fearTarget);
        boolean near = src != null && src.isAlive() && src.level() == p.level() && src.distanceToSqr(p) < 24 * 24;
        double slow = 0;
        if (near && light(p, StatusType.FEAR)) {
            Vec3 to = src.position().subtract(p.position()).multiply(1, 0, 1);
            Vec3 v = p.getDeltaMovement().multiply(1, 0, 1);
            if (v.lengthSqr() > 1e-4 && to.lengthSqr() > 1e-4 && v.normalize().dot(to.normalize()) > 0.3) slow = -0.5;
        }
        set(p, Attributes.MOVEMENT_SPEED, FEAR_SPEED, slow, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        boolean flee = (m & (Condition.PANIC.bit() | Condition.TERROR.bit())) != 0;
        if (flee && near && speedFactor(p) > 0) {
            Vec3 away = p.position().subtract(src.position()).multiply(1, 0, 1);
            if (away.lengthSqr() < 1e-4) away = new Vec3(1, 0, 0);
            away = away.normalize().scale(0.35);
            p.setDeltaMovement(away.x, p.getDeltaMovement().y, away.z);
            p.hurtMarked = true;
        }
    }

    /** 固有不良状态开始时的一次性效果 */
    public static void onConditionStart(ServerPlayer p, Condition c) {
        switch (c) {
            case HELPLESS, UNCONSCIOUS, ASLEEP -> dropHands(p);
            case FLOATING -> { p.setDeltaMovement(p.getDeltaMovement().add(0, 0.4, 0)); p.hurtMarked = true; }
            default -> {}
        }
        invalidate(p);
    }

    public static void onConditionEnd(ServerPlayer p, Condition c) {
        if (c == Condition.BANISHED) p.removeEffect(MobEffects.INVISIBILITY);
        invalidate(p);
    }

    /** 双手持握的物品直接掉落 */
    public static void dropHands(ServerPlayer p) {
        for (InteractionHand h : InteractionHand.values()) {
            ItemStack st = p.getItemInHand(h);
            if (st.isEmpty()) continue;
            p.drop(st.copy(), true);
            p.setItemInHand(h, ItemStack.EMPTY);
        }
        if (p.isUsingItem()) p.stopUsingItem();
    }

    public static void reset(ServerPlayer p) {
        invalidate(p);
        if (NO_GRAVITY.remove(p.getUUID())) p.setNoGravity(false);
    }

    // ===== 事件：行为限制 =====

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p.isCreative()) return;
        String why = attackBlock(p, e.getTarget());
        if (why != null) {
            e.setCanceled(true);
            deny(p, why);
        }
    }

    /** 远程 / 技艺造成的伤害同样受攻击限制（不良状态点数自身的伤害除外） */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onIncoming(LivingIncomingDamageEvent e) {
        if (!(e.getSource().getEntity() instanceof ServerPlayer p) || p == e.getEntity() || p.isCreative()) return;
        if (StatusManager.isStatusDamage()) return;
        if (attackBlock(p, e.getEntity()) != null) e.setCanceled(true);
    }

    /** 举盾 / 拉弓：无法格挡或无法攻击时不能开始 */
    @SubscribeEvent
    public static void onUseStart(LivingEntityUseItemEvent.Start e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p.isCreative()) return;
        var item = e.getItem().getItem();
        if (item instanceof ShieldItem && cantBlock(p)) e.setCanceled(true);
        else if ((item instanceof BowItem || item instanceof CrossbowItem || item instanceof TridentItem) && attackBlock(p, null) != null) {
            e.setCanceled(true);
            deny(p, attackBlock(p, null));
        } else if (incapacitated(p)) e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem e) {
        if (e.getEntity() instanceof ServerPlayer p && !p.isCreative() && incapacitated(p)) e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        if (e.getEntity() instanceof ServerPlayer p && !p.isCreative() && incapacitated(p)) e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract e) {
        if (e.getEntity() instanceof ServerPlayer p && !p.isCreative() && incapacitated(p)) e.setCanceled(true);
    }

    /** 沉默：无法发出声音（聊天）；无法行动时同样说不出话 */
    @SubscribeEvent
    public static void onChat(ServerChatEvent e) {
        ServerPlayer p = e.getPlayer();
        if (p.isCreative() || p.isSpectator()) return;
        if (has(p, Condition.SILENCED) || incapacitated(p)) {
            e.setCanceled(true);
            p.displayClientMessage(Component.translatable("msg.zhushenspace.status.mute"), true);
        }
    }
}
