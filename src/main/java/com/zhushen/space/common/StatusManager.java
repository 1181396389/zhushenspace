package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.Condition;
import com.zhushen.space.data.DamageKind;
import com.zhushen.space.data.LimbPart;
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
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 不良状态点数与倒地（仅玩家）。效果查询见 {@link StatusEffects}。
 * <h3>不良状态点数的计算（规则书）</h3>
 * <ul>
 *   <li><b>轻度不良状态</b>：只要拥有点数。</li>
 *   <li><b>重度不良状态</b>：点数 &gt; 关键抵抗属性较高者 ×（该属性上的传奇属性 + 1）。</li>
 *   <li><b>毁灭性后果</b>：点数 &gt; 关键抵抗属性之和 ×（关键抵抗属性上的传奇属性之和 + 1）。
 *       流血只有耐力一项，计算两遍耐力。毁灭性后果不会因点数恢复而移除（自然环境造成的冰封、以点数计算伤害的火化除外）。</li>
 *   <li>本模组中属性值为 0 时按 1 计算阈值（避免 1 点点数即触发毁灭性后果）。传奇属性 = 该属性超出 4 的部分（与技艺一致）。</li>
 *   <li><b>可叠加 / 不可叠加</b>：可叠加的点数无论来源都相加；不可叠加的点数同一来源取优、不同来源分别记录；
 *       生效时取所有条目中最高者。豁免 / 反制 / 自然恢复扣除点数时，每次从当前最高的条目扣 1 点。</li>
 *   <li><b>恢复</b>：短休 / 长休时对每种点数用关键抵抗属性进行一次豁免，每个成功数降低 1 点
 *       （由饥渴 / 疲惫造成的疲乏点数只能通过进食饮水 / 睡眠解除）。</li>
 *   <li><b>反制</b>：冻结 ↔ 燃烧、沮丧 ↔ 亢奋：获得一方点数时先扣除等量的另一方点数（最多扣到 0），超出部分才获得。</li>
 *   <li>每回合（10 秒，全服统一时点）结算：燃烧 / 流血 / 开放性创口伤害、多系统器官功能衰竭、冻结与燃烧的自然恢复、精神奴役的挣脱。</li>
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
    /** 燃烧 / 流血（轻度）每回合结束时的严重伤害 */
    public static final int BURN_DAMAGE = 4, BLEED_DAMAGE = 4;
    /** 开放性创口：每处每回合的挥砍严重伤害（规则原文缺少定义，暂定） */
    public static final int OPEN_WOUND_DAMAGE = 2;
    /** 多系统器官功能衰竭：每回合失去的耐力 */
    public static final int MODS_END_LOSS = 4;
    /** 倒地：远程防御加值 / 近战防御减值 / 反射豁免加值 */
    public static final int PRONE_RANGED_BONUS = 3, PRONE_MELEE_PENALTY = 6, PRONE_REFLEX_BONUS = 3;
    /** 爬起来所需时间（一个移动动作） */
    public static final int STAND_TICKS = 20;
    /** 不可叠加条目的来源键：饥渴 / 疲惫造成的疲乏点数 */
    public static final String SRC_STARVING = "survival:starving", SRC_WEARY = "survival:weary";

    public enum Source { NATURAL, MALICIOUS, MAGIC }

    /** 正在爬起的玩家 → 完成时刻 */
    private static final Map<UUID, Long> STANDING = new HashMap<>();
    /** 扑灭火焰 / 急救（标准动作）冷却 */
    private static final Map<UUID, Long> EXTINGUISH_CD = new HashMap<>(), AID_CD = new HashMap<>();
    /** 上一次已知的档位（用于提示与触发毁灭性后果）；肢体妨害按肢体 */
    private static final Map<UUID, int[]> LAST_TIER = new HashMap<>(), LAST_LIMB = new HashMap<>();
    /** 不良状态点数造成的伤害进行中（不是 DOT；「每当造成伤害时」类能力应忽略） */
    private static boolean statusDamage;

    public static PlayerConditionData data(Player p) {
        return p.getData(ModAttachments.PLAYER_CONDITION);
    }

    /** 当前是否在结算不良状态点数造成的伤害（此时「每当造成伤害 / 火焰伤害时」类能力不应触发） */
    public static boolean isStatusDamage() {
        return statusDamage;
    }

    static int attr(ServerPlayer p, AttributeType t) {
        return CombatFormula.attr(p, t) + FeatEffects.attrBonus(p)[t.ordinal()];
    }

    /** 传奇属性 = 属性超出 4 的部分（属性达到 5 视为 1 点传奇） */
    static int legend(ServerPlayer p, AttributeType t) {
        return Math.max(0, attr(p, t) - 4);
    }

    // ===== 阈值 =====

    /** 第一项关键抵抗属性（肢体妨害：力量 / 敏捷取高） */
    static AttributeType key1(ServerPlayer p, StatusType t) {
        if (t == StatusType.LIMB) return attr(p, AttributeType.STRENGTH) >= attr(p, AttributeType.AGILITY)
                ? AttributeType.STRENGTH : AttributeType.AGILITY;
        return t.attr1;
    }

    /** 重度不良状态阈值：点数大于该值即进入重度 */
    public static int heavyAt(ServerPlayer p, StatusType t) {
        AttributeType a = key1(p, t), b = t.attr2;
        AttributeType hi = attr(p, a) >= attr(p, b) ? a : b;
        return Math.max(1, attr(p, hi)) * (legend(p, hi) + 1);
    }

    /** 毁灭性后果阈值：点数大于该值即触发（流血计算两遍耐力） */
    public static int destructiveAt(ServerPlayer p, StatusType t) {
        AttributeType a = key1(p, t), b = t.attr2;
        return (Math.max(1, attr(p, a)) + Math.max(1, attr(p, b))) * (legend(p, a) + legend(p, b) + 1);
    }

    static Tier tierOf(ServerPlayer p, StatusType t, int v) {
        if (v <= 0) return Tier.NONE;
        if (v > destructiveAt(p, t)) return Tier.DESTRUCTIVE;
        if (v > heavyAt(p, t)) return Tier.HEAVY;
        return Tier.LIGHT;
    }

    public static Tier tier(ServerPlayer p, StatusType t) {
        return tierOf(p, t, data(p).points(t));
    }

    public static Tier limbTier(ServerPlayer p, LimbPart part) {
        return tierOf(p, StatusType.LIMB, data(p).limb[part.ordinal()]);
    }

    public static boolean atLeast(ServerPlayer p, StatusType t, Tier tier) {
        return tier(p, t).ordinal() >= tier.ordinal();
    }

    // ===== 豁免 =====

    /** 关键抵抗属性之和（短休 / 长休的恢复豁免；流血 = 两遍耐力） */
    static int keySum(ServerPlayer p, StatusType t) {
        AttributeType a = key1(p, t);
        return attr(p, a) + attr(p, t.attr2) + PoolEffects.checkBonus(p, a, t.attr2)
                - StatusEffects.savePenalty(p, a, t.attr2);
    }

    /** 强韧豁免 = 耐力 + 求生 + 传奇耐力 + 其他（见 Defense） */
    static int fortBase(ServerPlayer p, StatusType t) {
        return Defense.fort(p);
    }

    /** 反射豁免 = 敏捷 + 运动 + 传奇敏捷 + 其他 */
    static int reflexBase(ServerPlayer p) {
        return Defense.reflex(p, false);
    }

    /** 意志豁免 = 决心 + 感受 + 传奇决心 + 其他 */
    static int willBase(ServerPlayer p) {
        return Defense.will(p);
    }

    /** 获得点数时的豁免成功数（按类型的默认豁免；「强韧或意志」等取较高者）× 20%~100% 浮动 */
    public static int saveRoll(ServerPlayer p, StatusType t) {
        int base = switch (t.save) {
            case FORTITUDE -> fortBase(p, t);
            case REFLEX -> StatusEffects.canReflex(p) ? reflexBase(p) : 0;
            case WILL -> willBase(p);
            case FORT_OR_WILL -> Math.max(fortBase(p, t), willBase(p));
            case FORT_OR_REFLEX -> Math.max(fortBase(p, t), StatusEffects.canReflex(p) ? reflexBase(p) : 0);
        };
        return Math.max(0, Math.round(base * DamageVariance.roll(p.getRandom())));
    }

    // ===== 获得 / 移除点数 =====

    /** 可叠加点数（环境 / 旧接口） */
    public static int add(ServerPlayer p, StatusType t, int amount, boolean save, Entity source, Source kind, int magicTicks) {
        return add(p, t, amount, save, source, kind, magicTicks, true, null);
    }

    /**
     * 使玩家获得不良状态点数。
     *
     * @param save       是否允许豁免（每个成功数抵消 1 点）
     * @param source     造成点数的单位（燃烧 / 流血伤害的来源、魅惑 / 恐惧的目标、精神奴役的支配者；可为 null）
     * @param kind       来源性质（燃烧：自然 / 恶意 / 魔法；冻结：非自然来源的冰封不会因回温解除）
     * @param magicTicks 魔法火焰的持续时间
     * @param stackable  可叠加 / 不可叠加
     * @param part       肢体妨害的肢体（null = 随机一条未断的四肢）
     * @return 实际获得的点数
     */
    public static int add(ServerPlayer p, StatusType t, int amount, boolean save, Entity source, Source kind, int magicTicks,
                          boolean stackable, LimbPart part) {
        return addKeyed(p, t, amount, save, source, kind, magicTicks, stackable ? null : sourceKey(source), part);
    }

    static String sourceKey(Entity source) {
        return source == null ? "env" : source.getUUID().toString();
    }

    /** nonStackKey = null 时为可叠加点数 */
    public static int addKeyed(ServerPlayer p, StatusType t, int amount, boolean save, Entity source, Source kind, int magicTicks,
                               String nonStackKey, LimbPart part) {
        if (amount <= 0 || !p.isAlive() || p.isCreative() || p.isSpectator()) return 0;
        if (StatusEffects.has(p, Condition.BANISHED)) return 0; // 放逐：任何能力都无法影响
        PlayerConditionData d = data(p);
        var prof = DamageRules.profile(p);
        if (t == StatusType.BURN && prof.immune.contains(DamageKind.FIRE)) return 0;
        if (t == StatusType.FREEZE && prof.immune.contains(DamageKind.COLD)) return 0;
        if (save) amount -= saveRoll(p, t);
        if (amount <= 0) return 0;
        // 相互反制：先扣除等量的另一方点数
        StatusType opposite = t.opposite();
        if (opposite != null && d.any(opposite)) {
            amount -= d.deduct(opposite, Math.min(amount, d.points(opposite)));
            if (amount <= 0) {
                changed(p);
                return 0;
            }
        }
        if (t == StatusType.LIMB) {
            if (part == null || !part.severable()) part = randomLimb(p);
            if (part == null) return 0;
            d.limb[part.ordinal()] += amount;
        } else if (nonStackKey == null) {
            d.stack[t.ordinal()] += amount;
        } else {
            d.nonStack[t.ordinal()].merge(nonStackKey, amount, Math::max);
        }
        UUID src = source == null ? null : source.getUUID();
        switch (t) {
            case BURN -> {
                if (src != null) d.burnSource = src;
                int k = kind == null ? 0 : kind.ordinal();
                if (k >= d.burnKind || d.points(t) == amount) d.burnKind = k;
                if (kind == Source.MAGIC) d.magicBurnUntil = Math.max(d.magicBurnUntil, p.level().getGameTime() + magicTicks);
            }
            case FREEZE -> { if (kind != null && kind != Source.NATURAL) d.freezeUnnatural = true; }
            case BLEED -> { if (src != null) d.bleedSource = src; }
            case CHARM -> { if (src != null) { d.charmTarget = src; d.master = src; } }
            case FEAR -> { if (src != null) d.fearTarget = src; }
            case BIND -> { if (src != null) d.master = src; }
            case DROWSY -> d.wokeUp = false;
            default -> {}
        }
        changed(p);
        return amount;
    }

    private static LimbPart randomLimb(ServerPlayer p) {
        List<LimbPart> ok = new ArrayList<>();
        for (LimbPart lp : LimbPart.values()) if (lp.severable() && !LimbManager.data(p).isSevered(lp)) ok.add(lp);
        return ok.isEmpty() ? null : ok.get(p.getRandom().nextInt(ok.size()));
    }

    /** 扣除点数（从当前最高的条目开始） */
    public static int reduce(ServerPlayer p, StatusType t, int amount) {
        PlayerConditionData d = data(p);
        if (amount <= 0 || !d.any(t)) return 0;
        int done = d.deduct(t, amount);
        if (t == StatusType.BURN && !d.any(t)) d.burnKind = 0;
        changed(p);
        return done;
    }

    /** 「若目标有该类点数，则将其点数增加 X 点」：所有条目一起增加 */
    public static void raiseAll(ServerPlayer p, StatusType t, int amount) {
        PlayerConditionData d = data(p);
        if (amount <= 0 || !d.any(t)) return;
        d.raiseAll(t, amount);
        changed(p);
    }

    /** 清除点数（t = null：全部）；permanent = true 时一并清除毁灭性后果、固有不良状态与属性伤害 */
    public static void clear(ServerPlayer p, StatusType t, boolean permanent) {
        PlayerConditionData d = data(p);
        for (StatusType s : StatusType.values()) {
            if (t != null && s != t) continue;
            d.wipe(s);
            if (permanent) d.permanent &= ~s.bit();
        }
        if (t == null || t == StatusType.BURN) d.burnKind = 0;
        if (t == null || t == StatusType.FREEZE) d.freezeUnnatural = false;
        if (permanent && t == null) {
            java.util.Arrays.fill(d.condUntil, 0L);
            d.openWounds = 0;
            d.modsEnd = 0;
            d.wokeUp = false;
            d.master = d.charmTarget = d.fearTarget = d.taunter = null;
            AttributeApplier.apply(p);
        }
        changed(p);
    }

    /** 直接施加固有不良状态（ticks ≤ 0 = 直到解除）；target 用于嘲讽来源 */
    public static void addCondition(ServerPlayer p, Condition c, int ticks, Entity target) {
        if (!p.isAlive() || p.isCreative() || p.isSpectator()) return;
        PlayerConditionData d = data(p);
        long until = ticks <= 0 ? Long.MAX_VALUE : p.level().getGameTime() + ticks;
        // 相同的不良状态不叠加，取最优者（持续时间最长）
        d.condUntil[c.ordinal()] = Math.max(d.condUntil[c.ordinal()], until);
        if (c == Condition.TAUNTED && target != null) d.taunter = target.getUUID();
        if (c == Condition.ENSLAVED && target != null) d.master = target.getUUID();
        if (c == Condition.FLAT_FOOTED) DamageRules.flatFooted(p, null, ticks <= 0 ? 20 * 60 * 60 : ticks);
        if (c == Condition.OPEN_WOUND) d.openWounds++;
        StatusEffects.onConditionStart(p, c);
        changed(p);
    }

    public static void removeCondition(ServerPlayer p, Condition c) {
        PlayerConditionData d = data(p);
        boolean was = d.condUntil[c.ordinal()] > p.level().getGameTime();
        d.condUntil[c.ordinal()] = 0;
        if (was) StatusEffects.onConditionEnd(p, c);
        if (c == Condition.OPEN_WOUND) d.openWounds = 0;
        changed(p);
    }

    /** 点数变化：检查档位变化（提示 / 毁灭性后果），刷新效果并同步 */
    static void changed(ServerPlayer p) {
        PlayerConditionData d = data(p);
        int[] last = LAST_TIER.computeIfAbsent(p.getUUID(), k -> new int[StatusType.COUNT]);
        for (StatusType t : StatusType.values()) {
            if (t == StatusType.LIMB) continue;
            Tier now = tier(p, t);
            int before = last[t.ordinal()];
            if (now.ordinal() > before) {
                if (now == Tier.DESTRUCTIVE) destructive(p, t);
                else {
                    p.displayClientMessage(Component.translatable("msg.zhushenspace.status.gain",
                            Component.translatable(t.tierKey(now))), true);
                    if (now == Tier.HEAVY) heavy(p, t);
                }
                if (now == Tier.DESTRUCTIVE && before < Tier.HEAVY.ordinal()) heavy(p, t);
            } else if (now.ordinal() < before && now == Tier.NONE) {
                p.displayClientMessage(Component.translatable("msg.zhushenspace.status.clear",
                        Component.translatable(t.nameKey())), true);
            }
            last[t.ordinal()] = now.ordinal();
        }
        // 肢体妨害：逐条肢体
        int[] lastL = LAST_LIMB.computeIfAbsent(p.getUUID(), k -> new int[LimbPart.COUNT]);
        for (LimbPart lp : LimbPart.values()) {
            if (!lp.severable()) continue;
            Tier now = limbTier(p, lp);
            int before = lastL[lp.ordinal()];
            if (now.ordinal() > before) {
                if (now.ordinal() >= Tier.HEAVY.ordinal() && before < Tier.HEAVY.ordinal()) limbDisabled(p, lp);
                if (now == Tier.DESTRUCTIVE) limbLost(p, lp);
                else p.displayClientMessage(Component.translatable("msg.zhushenspace.status.limb_gain",
                        Component.translatable(lp.nameKey()), Component.translatable(StatusType.LIMB.tierKey(now))), true);
            }
            lastL[lp.ordinal()] = now.ordinal();
        }
        StatusEffects.invalidate(p);
        StatusEffects.apply(p);
        sync(p);
    }

    /** 进入重度不良状态时的一次性效果 */
    private static void heavy(ServerPlayer p, StatusType t) {
        PlayerConditionData d = data(p);
        switch (t) {
            case FEAR -> d.condUntil[Condition.PANIC.ordinal()] = p.level().getGameTime() + ROUND; // 惊慌逃窜一轮
            case DROWSY -> { if (!d.wokeUp) StatusEffects.dropHands(p); }                        // 睡着：手持物掉落
            case NAUSEA -> p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_BURP, SoundSource.PLAYERS, 0.8f, 0.6f);
            default -> {}
        }
    }

    /** 是否锁存为永久（自然环境的冰封会回温解除；火化按当前点数计算伤害；肢体妨害由断肢系统负责） */
    private static boolean latches(ServerPlayer p, StatusType t) {
        if (t == StatusType.BURN || t == StatusType.LIMB) return false;
        if (t == StatusType.FREEZE) return data(p).freezeUnnatural;
        return true;
    }

    /** 毁灭性后果 */
    private static void destructive(ServerPlayer p, StatusType t) {
        PlayerConditionData d = data(p);
        if (latches(p, t)) d.permanent |= t.bit();
        MinecraftServer server = p.getServer();
        Component msg = Component.translatable("msg.zhushenspace.status.destructive", p.getDisplayName(),
                Component.translatable(t.destructive.nameKey()));
        if (server != null) server.getPlayerList().broadcastSystemMessage(msg, false);
        switch (t) {
            case BURN -> {
                if (p.level() instanceof ServerLevel sl)
                    sl.sendParticles(ParticleTypes.LARGE_SMOKE, p.getX(), p.getY() + 1, p.getZ(), 40, 0.4, 0.8, 0.4, 0.02);
            }
            case FREEZE -> p.level().playSound(null, p.blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1f, 0.5f);
            case TINNITUS -> p.level().playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1f, 2f);
            case CRYSTAL -> p.level().playSound(null, p.blockPosition(), SoundEvents.STONE_PLACE, SoundSource.PLAYERS, 1.2f, 0.5f);
            case DAZE, PAIN, DROWSY -> StatusEffects.dropHands(p);
            default -> {}
        }
    }

    /** 肢体残障（重度肢体妨害）：该肢体暂时无法使用；手臂所持物品掉落 */
    private static void limbDisabled(ServerPlayer p, LimbPart lp) {
        p.displayClientMessage(Component.translatable("msg.zhushenspace.status.limb_disabled", Component.translatable(lp.nameKey())), true);
        if (lp.isArm()) {
            for (InteractionHand h : InteractionHand.values()) {
                if (LimbManager.armOf(p, h) != lp) continue;
                ItemStack st = p.getItemInHand(h);
                if (!st.isEmpty()) {
                    p.drop(st.copy(), true);
                    p.setItemInHand(h, ItemStack.EMPTY);
                }
                if (p.isUsingItem() && p.getUsedItemHand() == h) p.stopUsingItem();
            }
        }
    }

    /** 毁灭性肢体妨害：永远失去该肢体（断肢），并在修复前进入开放性创口（可叠加） */
    private static void limbLost(ServerPlayer p, LimbPart lp) {
        PlayerConditionData d = data(p);
        d.limb[lp.ordinal()] = 0; // 点数由断肢状态取代
        if (!LimbManager.data(p).isSevered(lp)) LimbManager.sever(p, lp);
        d.openWounds++;
        MinecraftServer server = p.getServer();
        if (server != null) server.getPlayerList().broadcastSystemMessage(Component.translatable("msg.zhushenspace.status.limb_lost",
                p.getDisplayName(), Component.translatable(lp.nameKey())), false);
    }

    // ===== 委托给 StatusEffects 的查询（保留旧接口） =====

    public static boolean incapacitated(ServerPlayer p) {
        return StatusEffects.incapacitated(p);
    }

    public static boolean blind(ServerPlayer p) {
        return StatusEffects.blind(p);
    }

    public static float defenseMod(LivingEntity victim, DamageSource src) {
        return StatusEffects.defenseMod(victim, src);
    }

    public static float defenseMod(ServerPlayer p, boolean ranged) {
        return StatusEffects.defenseMod(p, ranged, null);
    }

    public static int attackPenalty(ServerPlayer p, boolean ranged) {
        return StatusEffects.attackPenalty(p, ranged, null);
    }

    public static int attackPenalty(ServerPlayer p, boolean ranged, Entity target) {
        return StatusEffects.attackPenalty(p, ranged, target);
    }

    public static int checkPenalty(ServerPlayer p, AttributeType... attrs) {
        return StatusEffects.checkPenalty(p, attrs);
    }

    /** 反射豁免修正（area = 对抗范围伤害：倒地 +3） */
    public static int reflexBonus(LivingEntity e, boolean area) {
        return e instanceof ServerPlayer p ? StatusEffects.reflexMod(p, area) : 0;
    }

    public static boolean isRanged(DamageSource src) {
        return StatusEffects.isRanged(src);
    }

    // ===== 倒地 =====

    public static boolean prone(Player p) {
        return data(p).prone;
    }

    /** 有蛛行术 / 飞行能力时不受倒地的移动限制 */
    public static boolean freeMover(ServerPlayer p) {
        return p.getAbilities().flying || p.isFallFlying() || (PoolEffects.flags(p) & PoolEffects.F_SPIDER) != 0;
    }

    public static boolean standingUp(ServerPlayer p) {
        return STANDING.containsKey(p.getUUID());
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
        if (StatusEffects.incapacitated(p) || HealthManager.isUnconscious(p) || StatusEffects.speedFactor(p) <= 0) {
            p.displayClientMessage(Component.translatable("msg.zhushenspace.prone.cant"), true);
            return;
        }
        var limbs = LimbManager.data(p);
        if (limbs.isSevered(LimbPart.RIGHT_LEG) && limbs.isSevered(LimbPart.LEFT_LEG)) {
            p.displayClientMessage(Component.translatable("msg.zhushenspace.prone.no_legs"), true);
            return;
        }
        STANDING.put(p.getUUID(), p.level().getGameTime() + STAND_TICKS * (StatusEffects.has(p, Condition.UNBALANCED) ? 2 : 1));
        p.displayClientMessage(Component.translatable("msg.zhushenspace.prone.standing"), true);
    }

    /** 传送后可自行决定：按住潜行保持倒地，否则站起；禁锢状态下无法传送 */
    @SubscribeEvent
    public static void onPearl(EntityTeleportEvent.EnderPearl event) {
        if (blockTeleport(event.getEntity())) { event.setCanceled(true); return; }
        afterTeleport(event.getEntity());
    }

    @SubscribeEvent
    public static void onChorus(EntityTeleportEvent.ChorusFruit event) {
        if (blockTeleport(event.getEntity())) { event.setCanceled(true); return; }
        afterTeleport(event.getEntity());
    }

    private static boolean blockTeleport(Entity e) {
        return e instanceof ServerPlayer p && StatusEffects.has(p, Condition.IMPRISONED);
    }

    private static void afterTeleport(Entity e) {
        if (e instanceof ServerPlayer p && data(p).prone && !p.isShiftKeyDown()
                && !StatusEffects.incapacitated(p) && !HealthManager.isUnconscious(p)) {
            setProne(p, false, null);
        }
    }

    // ===== 扑灭火焰 / 急救（标准动作） =====

    /** 标准动作：反射豁免，每个成功数消除 1 点燃烧点数（魔法火焰无法以物理方式熄灭） */
    public static void extinguish(ServerPlayer p) {
        PlayerConditionData d = data(p);
        if (!d.any(StatusType.BURN)) {
            p.displayClientMessage(Component.translatable("msg.zhushenspace.status.not_burning"), true);
            return;
        }
        if (d.burnKind == Source.MAGIC.ordinal() && d.magicBurnUntil > p.level().getGameTime()) {
            p.displayClientMessage(Component.translatable("msg.zhushenspace.status.magic_fire"), true);
            return;
        }
        if (!StatusEffects.canAct(p, true)) return;
        long now = p.level().getGameTime();
        Long cd = EXTINGUISH_CD.get(p.getUUID());
        if (cd != null && cd > now) {
            p.displayClientMessage(Component.translatable("msg.zhushenspace.status.extinguish_cd", (cd - now + 19) / 20), true);
            return;
        }
        EXTINGUISH_CD.put(p.getUUID(), now + ROUND);
        int s = StatusEffects.canReflex(p) ? Math.max(0, Math.round(reflexBase(p) * DamageVariance.roll(p.getRandom()))) : 0;
        int removed = reduce(p, StatusType.BURN, s);
        p.level().playSound(null, p.blockPosition(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.6f, 1.2f);
        p.displayClientMessage(Component.translatable("msg.zhushenspace.status.extinguish", removed), true);
    }

    /**
     * 急救（标准动作）：对自己或触及范围内注视的另一名玩家进行 敏捷 + 医疗（急救）检定（本模组没有医疗技能，只计敏捷），
     * 每个成功数解除 1 点流血点数；成功数 ≥ 3 时额外包扎一处开放性创口（暂定）。
     */
    public static void firstAid(ServerPlayer p) {
        if (!StatusEffects.canAct(p, true)) return;
        long now = p.level().getGameTime();
        Long cd = AID_CD.get(p.getUUID());
        if (cd != null && cd > now) {
            p.displayClientMessage(Component.translatable("msg.zhushenspace.status.aid_cd", (cd - now + 19) / 20), true);
            return;
        }
        ServerPlayer target = p;
        LivingEntity look = ArtManager.target(p, p.entityInteractionRange());
        if (look instanceof ServerPlayer other) target = other;
        PlayerConditionData d = data(target);
        if (!d.any(StatusType.BLEED) && d.openWounds <= 0) {
            p.displayClientMessage(Component.translatable("msg.zhushenspace.status.aid_nothing", target.getDisplayName()), true);
            return;
        }
        AID_CD.put(p.getUUID(), now + ROUND);
        int base = attr(p, AttributeType.AGILITY) + PoolEffects.checkBonus(p, AttributeType.AGILITY)
                - StatusEffects.checkPenalty(p, AttributeType.AGILITY) - StatusEffects.handPenalty(p);
        int s = Math.max(0, Math.round(base * DamageVariance.roll(p.getRandom())));
        int removed = reduce(target, StatusType.BLEED, s);
        boolean closed = false;
        if (s >= 3 && d.openWounds > 0) {
            d.openWounds--;
            closed = true;
            changed(target);
        }
        p.swing(InteractionHand.MAIN_HAND, true);
        p.level().playSound(null, target.blockPosition(), SoundEvents.WOOL_PLACE, SoundSource.PLAYERS, 0.8f, 1.2f);
        Component msg = Component.translatable(closed ? "msg.zhushenspace.status.aid_closed" : "msg.zhushenspace.status.aid",
                target.getDisplayName(), removed);
        p.displayClientMessage(msg, true);
        if (target != p) target.displayClientMessage(msg, true);
    }

    // ===== 休息时的恢复豁免 =====

    /** 短休 / 长休：每种点数用关键抵抗属性豁免一次，每个成功数降低 1 点。返回显示用的摘要 */
    public static List<String> restSaves(ServerPlayer p) {
        PlayerConditionData d = data(p);
        List<String> parts = new ArrayList<>();
        boolean any = false;
        for (StatusType t : StatusType.values()) {
            if (!d.any(t)) continue;
            // 饥渴 / 疲惫造成的疲乏点数只能通过进食饮水 / 睡眠解除：暂时移出
            Integer starving = null, weary = null;
            if (t == StatusType.FATIGUE) {
                starving = d.nonStack[t.ordinal()].remove(SRC_STARVING);
                weary = d.nonStack[t.ordinal()].remove(SRC_WEARY);
            }
            int s = Math.max(0, Math.round(keySum(p, t) * DamageVariance.roll(p.getRandom())));
            int done = d.deduct(t, s);
            if (starving != null) d.nonStack[t.ordinal()].put(SRC_STARVING, starving);
            if (weary != null) d.nonStack[t.ordinal()].put(SRC_WEARY, weary);
            if (done > 0) {
                parts.add(Component.translatable(t.nameKey()).getString() + "-" + done);
                any = true;
            }
        }
        if (!d.any(StatusType.BURN)) d.burnKind = 0;
        if (any) changed(p);
        return parts;
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

    /** 吃下腐肉 / 蜘蛛眼 / 河豚 / 生鸡肉 / 毒马铃薯：获得恶心点数（强韧豁免） */
    @SubscribeEvent
    public static void onEat(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        ItemStack st = event.getItem();
        int n = st.is(Items.ROTTEN_FLESH) ? 3 : st.is(Items.SPIDER_EYE) ? 3 : st.is(Items.PUFFERFISH) ? 6
                : st.is(Items.CHICKEN) ? 2 : st.is(Items.POISONOUS_POTATO) ? 3 : 0;
        if (n > 0) add(p, StatusType.NAUSEA, n, true, null, Source.NATURAL, 0);
    }

    static boolean canSee(ServerPlayer p, Vec3 c) {
        return p.level().clip(new ClipContext(p.getEyePosition(), c, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p))
                .getType() == HitResult.Type.MISS;
    }

    /** 原版着火 / 冰冻伤害由不良状态点数接管（接触火焰 / 岩浆的伤害仍然保留）；石化 / 放逐时的伤害处理 */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onIncoming(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        DamageSource s = event.getSource();
        if (!statusDamage && (s.is(DamageTypes.ON_FIRE) || s.is(DamageTypes.FREEZE))) event.setCanceled(true);
        if (StatusEffects.has(p, Condition.BANISHED) && !s.is(DamageTypes.GENERIC_KILL) && !s.is(DamageTypes.FELL_OUT_OF_WORLD)) {
            event.setCanceled(true);
        }
    }

    /** 受到伤害：睡眠中的人物自动醒来（永眠除外） */
    @SubscribeEvent
    public static void onDamaged(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || event.getNewDamage() <= 0) return;
        PlayerConditionData d = data(p);
        boolean changed = false;
        if (d.condUntil[Condition.ASLEEP.ordinal()] > 0) {
            d.condUntil[Condition.ASLEEP.ordinal()] = 0;
            changed = true;
        }
        if (atLeast(p, StatusType.DROWSY, Tier.HEAVY) && !d.wokeUp && !d.isPermanent(StatusType.DROWSY)) {
            d.wokeUp = true;
            changed = true;
        }
        if (changed) {
            p.displayClientMessage(Component.translatable("msg.zhushenspace.status.woke"), true);
            changed(p);
        }
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

        // 固有不良状态到期
        boolean expired = false;
        for (int i = 0; i < d.condUntil.length; i++) {
            long u = d.condUntil[i];
            if (u > 0 && u != Long.MAX_VALUE && u <= now) {
                d.condUntil[i] = 0;
                StatusEffects.onConditionEnd(p, com.zhushen.space.data.Condition.values()[i]);
                expired = true;
            }
        }
        if (expired) changed(p);

        // 倒地：强制趴伏（有蛛行术 / 飞行能力时不受限），爬起完成
        if (d.prone) {
            Long st = STANDING.get(p.getUUID());
            if (st != null && now >= st) setProne(p, false, null);
            else if (!freeMover(p) && !p.isPassenger() && !p.isSleeping()) {
                if (p.getPose() != Pose.SWIMMING) p.setPose(Pose.SWIMMING);
                p.setSprinting(false);
            }
        }
        // 昏迷 / 睡眠 / 困到昏睡 / 无助时倒在地上
        if (!d.prone && (HealthManager.isUnconscious(p) || d.collapsed || StatusEffects.lying(p)) && !p.isSleeping() && !p.isPassenger()
                && !StatusEffects.has(p, Condition.FLOATING) && !StatusEffects.has(p, Condition.IMPRISONED)) {
            setProne(p, true, null);
        }

        // 燃烧：跳入水里解除（魔法火焰除外）；原版着火转化为燃烧点数；燃烧中显示火焰
        boolean burning = d.any(StatusType.BURN);
        if (burning && p.isInWater() && !(d.burnKind == Source.MAGIC.ordinal() && d.magicBurnUntil > now)) {
            clear(p, StatusType.BURN, false);
            p.level().playSound(null, p.blockPosition(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.7f, 1f);
            burning = false;
        }
        if (p.tickCount % 20 == 0 && p.getRemainingFireTicks() > 0 && !p.isInWater()) {
            add(p, StatusType.BURN, p.isInLava() ? 2 : 1, false, null, Source.NATURAL, 0);
            p.setRemainingFireTicks(0);
            burning = d.any(StatusType.BURN);
        }
        if (burning) {
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
        boolean frozen = StatusEffects.has(p, Condition.FROZEN);
        if (frozen || ft != Tier.NONE) {
            int need = p.getTicksRequiredToFreeze();
            int target = frozen || ft.ordinal() >= Tier.HEAVY.ordinal() ? need : need / 2;
            if (p.getTicksFrozen() < target) p.setTicksFrozen(target);
        }

        // 流血：滴血
        if (d.any(StatusType.BLEED) || d.openWounds > 0) {
            if (p.tickCount % 10 == 0 && p.level() instanceof ServerLevel sl) {
                sl.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK,
                        Blocks.REDSTONE_BLOCK.defaultBlockState()), p.getX(), p.getY() + 0.8, p.getZ(), 2, 0.2, 0.3, 0.2, 0);
            }
        }

        StatusEffects.tick(p);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int tick = server.getTickCount();
        if (tick % ROUND != 0) return;
        boolean coldCheck = tick % (ROUND * 3) == 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.isAlive() || p.isCreative() || p.isSpectator()) continue;
            round(p, coldCheck);
        }
    }

    private static DamageSource source(ServerPlayer p, ResourceKey<DamageType> type, UUID src) {
        Entity e = src == null || p.getServer() == null ? null : findEntity(p.getServer(), src);
        return new DamageSource(p.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(type), e);
    }

    /** 不良状态点数造成的不可避免伤害（不是 DOT，不触发「每当造成伤害时」类能力） */
    private static void statusHurt(ServerPlayer p, DamageSource ds, int dmg, DamageKind kind) {
        if (dmg <= 0 || !p.isAlive()) return;
        statusDamage = true;
        try {
            p.invulnerableTime = 0;
            DamageRules.deal(p, ds, dmg, DamageRules.Spec.of(PlayerHealthData.Severity.L, kind));
        } finally {
            statusDamage = false;
        }
    }

    /** 回合结算 */
    private static void round(ServerPlayer p, boolean coldCheck) {
        PlayerConditionData d = data(p);
        long now = p.level().getGameTime();
        boolean dirty = false;

        // 燃烧：每回合结束时受到不可避免的 4 点火焰严重伤害；焚烧 + 点数/4，火化 + 点数/2（取代焚烧）
        int burn = d.points(StatusType.BURN);
        if (burn > 0) {
            int dmg = BURN_DAMAGE;
            if (StatusEffects.has(p, Condition.CREMATING)) dmg += burn / 2;
            else if (StatusEffects.has(p, Condition.INCINERATING)) dmg += burn / 4;
            statusHurt(p, source(p, DamageTypes.ON_FIRE, d.burnSource), dmg, DamageKind.FIRE);
            if (!p.isAlive()) return;
            if (d.burnKind == Source.MAGIC.ordinal() && d.magicBurnUntil <= now) d.burnKind = Source.NATURAL.ordinal();
            if (d.burnKind == Source.NATURAL.ordinal()) { d.deduct(StatusType.BURN, 1); dirty = true; }
            if (!d.any(StatusType.BURN)) d.burnKind = 0;
        }

        // 流血：每回合结束时受到不可避免的 4 点挥砍严重伤害（来源 = 造成流血点数的单位）
        if (d.any(StatusType.BLEED)) {
            statusHurt(p, source(p, DamageTypes.GENERIC, d.bleedSource), BLEED_DAMAGE, DamageKind.SLASH);
            if (!p.isAlive()) return;
        }
        // 开放性创口（可叠加）
        if (d.openWounds > 0) {
            statusHurt(p, source(p, DamageTypes.GENERIC, null), OPEN_WOUND_DAMAGE * d.openWounds, DamageKind.SLASH);
            if (!p.isAlive()) return;
        }

        // 多系统器官功能衰竭：每回合失去 4 点耐力，耐力归零即死亡
        if (StatusEffects.has(p, Condition.MODS)) {
            d.modsEnd += MODS_END_LOSS;
            AttributeApplier.apply(p);
            p.displayClientMessage(Component.translatable("msg.zhushenspace.status.mods_tick", MODS_END_LOSS), true);
            if (attr(p, AttributeType.ENDURANCE) <= 0) {
                MinecraftServer server = p.getServer();
                if (server != null) server.getPlayerList().broadcastSystemMessage(
                        Component.translatable("msg.zhushenspace.status.mods_death", p.getDisplayName()), false);
                p.kill();
                return;
            }
            dirty = true;
        }

        // 冻结：寒冷环境（雪地生物群系、附近没有热源）中逐渐冻僵；适当的温度下每回合恢复 1 点
        boolean cold = isCold(p);
        if (cold && p.canFreeze()) {
            if (coldCheck) add(p, StatusType.FREEZE, p.isInWater() ? 2 : 1, true, null, Source.NATURAL, 0);
        } else if (d.any(StatusType.FREEZE) && !p.isInPowderSnow) {
            d.deduct(StatusType.FREEZE, 1);
            if (!d.any(StatusType.FREEZE)) d.freezeUnnatural = false;
            dirty = true;
        }

        // 精神奴役：从进入的下一轮开始，每轮结束时以意志豁免对抗支配者的心灵检定，胜出即解除
        if (StatusEffects.has(p, Condition.ENSLAVED)) {
            int save = Math.max(0, Math.round(willBase(p) * DamageVariance.roll(p.getRandom())));
            Entity m = d.master == null || p.getServer() == null ? null : findEntity(p.getServer(), d.master);
            int vs = m instanceof ServerPlayer mp ? Math.round(CheckRules.mind(mp, 0).value()) : 0;
            if (save > vs) {
                breakSlavery(p);
                dirty = true;
            }
        }
        if (dirty) changed(p);
    }

    /** 挣脱精神奴役：解除毁灭性后果，点数降到毁灭阈值（不会立即再次触发） */
    private static void breakSlavery(ServerPlayer p) {
        PlayerConditionData d = data(p);
        d.condUntil[Condition.ENSLAVED.ordinal()] = 0;
        for (StatusType t : new StatusType[]{StatusType.BIND, StatusType.CHARM}) {
            if (d.isPermanent(t) || tier(p, t) == Tier.DESTRUCTIVE) {
                d.permanent &= ~t.bit();
                int over = d.points(t) - destructiveAt(p, t);
                if (over > 0) d.deduct(t, over);
            }
        }
        d.master = null;
        p.displayClientMessage(Component.translatable("msg.zhushenspace.status.slavery_broken"), false);
    }

    static Entity findEntity(MinecraftServer server, UUID id) {
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
        int[] pts = new int[StatusType.COUNT], tiers = new int[StatusType.COUNT], heavy = new int[StatusType.COUNT],
                destr = new int[StatusType.COUNT];
        for (StatusType t : StatusType.values()) {
            pts[t.ordinal()] = d.points(t);
            tiers[t.ordinal()] = tier(p, t).ordinal();
            heavy[t.ordinal()] = heavyAt(p, t);
            destr[t.ordinal()] = destructiveAt(p, t);
        }
        int flags = (d.exhausted ? 1 : 0) | (d.collapsed ? 2 : 0) | (d.prone ? 4 : 0)
                | (STANDING.containsKey(p.getUUID()) ? 8 : 0) | (StatusEffects.incapacitated(p) ? 16 : 0) | (freeMover(p) ? 32 : 0)
                | (StatusEffects.noSprint(p) ? 64 : 0) | (StatusEffects.speedFactor(p) <= 0 ? 128 : 0);
        PacketDistributor.sendToPlayer(p, new SyncConditionPayload(d.thirst, Math.max(0, d.stamina),
                SurvivalManager.maxStamina(p), d.sleep, flags, pts, tiers, heavy, destr, d.permanent,
                StatusEffects.mask(p), StatusEffects.limbDisabledMask(p), d.openWounds, BreathManager.breathFraction(p)));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            LAST_TIER.remove(p.getUUID());
            LAST_LIMB.remove(p.getUUID());
            // 登录时直接记下当前档位（不重复触发毁灭性后果的一次性效果）
            int[] last = new int[StatusType.COUNT];
            for (StatusType t : StatusType.values()) last[t.ordinal()] = tier(p, t).ordinal();
            LAST_TIER.put(p.getUUID(), last);
            int[] lastL = new int[LimbPart.COUNT];
            for (LimbPart lp : LimbPart.values()) lastL[lp.ordinal()] = limbTier(p, lp).ordinal();
            LAST_LIMB.put(p.getUUID(), lastL);
            AttributeApplier.apply(p);
            changed(p);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            LAST_TIER.remove(p.getUUID());
            LAST_LIMB.remove(p.getUUID());
            STANDING.remove(p.getUUID());
            StatusEffects.reset(p);
            AttributeApplier.apply(p);
            changed(p);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        UUID id = e.getEntity().getUUID();
        STANDING.remove(id);
        EXTINGUISH_CD.remove(id);
        AID_CD.remove(id);
        LAST_TIER.remove(id);
        LAST_LIMB.remove(id);
        StatusEffects.forget(id);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent e) {
        STANDING.clear();
        EXTINGUISH_CD.clear();
        AID_CD.clear();
        LAST_TIER.clear();
        LAST_LIMB.clear();
        StatusEffects.forgetAll();
    }
}
