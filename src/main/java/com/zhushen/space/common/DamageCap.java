package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.SkillType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * 伤害上限：本模组对一次攻击的全部加成合计不超过「基础伤害 × 上限倍率」，上限随对应技能点数上涨。
 * <pre>
 *   上限倍率 = {@link #BASE_CAP} + {@link #CAP_PER_POINT} × 技能点数     （不设封顶）
 * </pre>
 * <ul>
 *   <li><b>枪械</b>：按「枪械」技能点数；基础伤害 = TACZ 结算的弹头伤害（含距离衰减），
 *       计入技能乘区（暴击 × 弱点另有枪械专用的总倍率上限），TACZ 自带的爆头倍率不计入（见 {@link GunDamage}）。</li>
 *   <li><b>近战</b>：徒手按「肉搏」、冷兵器（剑 / 斧 / 三叉戟 / 重锤）按「白刃」、其他手持物按 0 点；
 *       基础伤害 = 原版结算的本次近战伤害（徒手或武器伤害，含攻击力属性、附魔与跳劈）。
 *       计入攻击方的全部加成：肉搏 / 白刃加伤、冲锋攻击、太极拳徒手加成、八劲、内力吐息等。</li>
 * </ul>
 * 实现：
 * <ol>
 *   <li>{@link LivingIncomingDamageEvent} 最高优先级记录基础伤害，LOW 截断到 基础 × 上限（之后 DamageRules 才结算目标的伤害降低；
 *       暴击、弱点等「确定最终伤害后」的追加不计入上限）；</li>
 *   <li>{@link LivingDamageEvent.Pre}（护甲 / 附魔减伤之后）最高优先级按护甲减伤比例折算出本阶段的允许值，
 *       最低优先级把太极 / 八劲 / 内力吐息等之后追加的伤害截断到该允许值。</li>
 * </ol>
 * 上限只会压低伤害，不会抬高；受击方的减伤（格挡、大厅免伤等）照常生效。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class DamageCap {

    /** 0 点时的上限倍率 */
    public static final float BASE_CAP = 1.5f;
    /** 每个技能点提升的上限倍率 */
    public static final float CAP_PER_POINT = 1.0f;

    private DamageCap() {
    }

    /** 给定技能点数下的上限倍率 */
    public static float capFor(int points) {
        return BASE_CAP + CAP_PER_POINT * Math.max(0, points);
    }

    /** 枪械上限倍率（按枪械技能点数） */
    public static float gunCap(ServerPlayer player) {
        return capFor(player.getData(ModAttachments.PLAYER_SKILLS).get(SkillType.FIREARMS.ordinal()));
    }

    /** 近战上限倍率：徒手按肉搏，冷兵器按白刃，其他手持物按 0 点 */
    public static float meleeCap(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        var skills = player.getData(ModAttachments.PLAYER_SKILLS);
        if (main.isEmpty()) return capFor(skills.get(SkillType.BRAWL.ordinal()));
        com.zhushen.space.data.MeleeWeapon mw = com.zhushen.space.data.MeleeWeapon.of(main);
        if (mw != null) return capFor(skills.get(mw.category.skill.ordinal())); // 拳套 = 肉搏，其余冷兵器 = 白刃
        if (isColdWeapon(main)) return capFor(skills.get(SkillType.BLADE.ordinal()));
        return capFor(0);
    }

    /** 冷兵器判定（与白刃技能一致） */
    public static boolean isColdWeapon(ItemStack stack) {
        return stack.getItem() instanceof SwordItem
                || stack.getItem() instanceof com.zhushen.space.item.ZsWeaponItem
                || stack.getItem() instanceof AxeItem
                || stack.getItem() instanceof TridentItem
                || stack.getItem() instanceof MaceItem;
    }

    // ===== 近战上限 =====

    /** 一次近战命中的结算上下文（受击方 entityId → 上下文，同 tick 有效） */
    private static final class Melee {
        int attackerId;
        long tick;
        /** 原版基础伤害 */
        float base;
        /** 本次命中的上限倍率 */
        float cap;
        /** 数值阶段结束时的伤害（护甲前） */
        float incomingFinal = -1;
        /** 护甲后阶段的允许最大值 */
        float allowedPost = -1;
    }

    private static final Map<Integer, Melee> MELEE = new HashMap<>();

    /** 玩家亲手近战（排除弹射物与 TACZ 伪装近战的子弹） */
    static ServerPlayer meleeAttacker(DamageSource source, LivingEntity victim) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return null;
        if (player == victim || source.getDirectEntity() != player) return null;
        if (GunDamage.isGun(source)) return null;
        return player;
    }

    private static Melee current(ServerPlayer player, LivingEntity victim) {
        Melee m = MELEE.get(victim.getId());
        if (m == null || m.attackerId != player.getId() || player.getServer() == null
                || m.tick != player.getServer().getTickCount()) {
            return null;
        }
        return m;
    }

    /**
     * 伤害浮动（{@link DamageVariance}）掷出后按同一比例缩放本次近战的基础伤害，
     * 使上限始终是「浮动后基础伤害 × 上限倍率」，浮动不会被上限抵消或放大。
     */
    /** 攻击判定公式替换了本次近战的基础伤害：上限以公式结果为基础 */
    public static void setMeleeBase(ServerPlayer player, LivingEntity victim, float base) {
        Melee m = current(player, victim);
        if (m != null) m.base = Math.max(0f, base);
    }

    public static void scaleMeleeBase(ServerPlayer player, LivingEntity victim, float factor) {
        Melee m = current(player, victim);
        if (m != null) m.base *= factor;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onIncomingFirst(LivingIncomingDamageEvent event) {
        ServerPlayer player = meleeAttacker(event.getSource(), event.getEntity());
        if (player == null || player.getServer() == null) return;
        Melee m = new Melee();
        m.attackerId = player.getId();
        m.tick = player.getServer().getTickCount();
        m.base = Math.max(0f, event.getAmount());
        m.cap = meleeCap(player);
        // 被取消的受击不会走到最后阶段：顺手清理非本 tick 的残留上下文
        if (MELEE.size() > 64) MELEE.values().removeIf(x -> x.tick != m.tick);
        MELEE.put(event.getEntity().getId(), m);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onIncomingLast(LivingIncomingDamageEvent event) {
        ServerPlayer player = meleeAttacker(event.getSource(), event.getEntity());
        if (player == null) return;
        Melee m = current(player, event.getEntity());
        if (m == null) return;
        float limit = m.base * m.cap;
        if (event.getAmount() > limit) event.setAmount(limit);
        // 意志加持：+9 完美加值，在浮动与上限截断之后追加，不受其影响
        int will = WillpowerManager.consumeCheckBonus(player);
        if (will > 0) {
            event.setAmount(event.getAmount() + will);
            m.cap += will / Math.max(0.001f, m.base); // 护甲后阶段同比放行这部分加值
        }
        m.incomingFinal = event.getAmount();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDamageFirst(LivingDamageEvent.Pre event) {
        ServerPlayer player = meleeAttacker(event.getSource(), event.getEntity());
        if (player == null) return;
        Melee m = current(player, event.getEntity());
        if (m == null || m.incomingFinal < 0) return;
        float post = event.getNewDamage();
        if (m.incomingFinal <= 0f) {
            m.allowedPost = post;
            return;
        }
        // 护甲 / 附魔 / 抗性后的伤害按同一比例折算：允许值 = 护甲后伤害 × (基础 × 上限 / 数值阶段伤害)
        float ratio = post / m.incomingFinal;
        m.allowedPost = Math.max(post, m.base * m.cap * ratio);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDamageLast(LivingDamageEvent.Pre event) {
        ServerPlayer player = meleeAttacker(event.getSource(), event.getEntity());
        if (player == null) return;
        Melee m = current(player, event.getEntity());
        if (m == null || m.allowedPost < 0) return;
        if (event.getNewDamage() > m.allowedPost) event.setNewDamage(m.allowedPost);
        MELEE.remove(event.getEntity().getId());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        MELEE.clear();
    }
}
