package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.FeatType;
import com.zhushen.space.data.LimbPart;
import com.zhushen.space.data.SkillType;
import com.zhushen.space.network.DefenseHudPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.ArrowLooseEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 主神空间防御与豁免（取代原版护甲减伤）。
 * <pre>
 * 受到的伤害 = 攻击 − 防御（再经过抗力 / 吸收 / 阈值等伤害结算）；原版护甲与保护类附魔不再按比例减伤。
 *
 * 防御 = 基础防御 + 全力防御 + 格挡防御 + 闪避防御 + 天生防御 + 盔甲防御 + 洞察防御 + 其他加值 ± 状态修正
 *   基础防御：敏捷 / 感知取高者 + 该属性的传奇值；盔甲减值、冲锋削弱只扣基础防御（最低为 0）
 *   全力防御：开启期间再加一次基础防御；发起攻击即解除
 *   格挡防御：白刃格挡 / 肉搏格挡
 *   闪避防御：体型调整、基础掌法等（可以为负）
 *   天生防御：巨大身材、息法·外息等
 *   盔甲防御：覆盖命中部位的盔甲提供的护甲值
 *   其他：自我保护、意志守御、初级防护、太极徒手护甲、石化等
 * 措手不及 / 擒抱中（面对组外攻击）：失去基础、全力、闪避加值与格挡防御（保留盾牌、天生、盔甲）。
 * 失去天生防御类状态（冻伤、冰封、石化、昏迷、睡眠、浮空、无助、视觉障碍）：失去基础、全力与闪避加值；无法格挡时同时失去格挡防御。
 *
 * 意志豁免 = 决心 + 感受 + 传奇决心 + 其他
 * 反射豁免 = 敏捷 + 运动 + 传奇敏捷 + 其他
 * 强韧豁免 = 耐力 + 求生 + 传奇耐力 + 其他
 * 范围豁免 = 反射豁免 + 对抗范围效果的加值（倒地等）；爆炸伤害以范围豁免扣减，不计防御
 * </pre>
 * 盔甲属性（护甲条）仍挂着各项加值，只作显示；结算全部在这里按来源重新计算，不受原版护甲上限影响。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class Defense {
    private Defense() {}

    private static ResourceLocation rl(String s) { return ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, s); }

    /** 全力防御的显示用修饰器 */
    static final ResourceLocation FULL_MOD = rl("full_defense");
    private static final ResourceLocation CHARGE = rl("charge_debuff");
    /** 由本类直接计算的修饰器（只作显示，不再从属性里读取） */
    private static final Set<ResourceLocation> COMPUTED = Set.of(rl("agility_armor"), rl("armor_penalty"), rl("limb_part_armor"),
            rl("grapple_no_natural_def"), FULL_MOD, CHARGE, rl("feat_giant_natural"));
    private static final Set<ResourceLocation> NATURAL = Set.of(rl("art_breath_outer"));
    private static final Set<ResourceLocation> PARRY = Set.of(rl("brawl_block"), rl("blade_block"));
    private static final Set<ResourceLocation> DODGE = Set.of(rl("art_basic_palm"));

    /** 防御组成 */
    public record Parts(int base, int full, int parry, int dodge, int natural, int armor, int insight, int other, int status) {
        public int total() { return Math.max(0, base + full + parry + dodge + natural + armor + insight + other + status); }
    }

    static int attr(ServerPlayer p, AttributeType t) { return StatusManager.attr(p, t); }

    static int legend(ServerPlayer p, AttributeType t) { return StatusManager.legend(p, t); }

    static int skill(ServerPlayer p, SkillType t) { return CombatFormula.skill(p, t); }

    // ===== 基础防御 =====

    /** 基础防御所用属性：敏捷 / 感知取高 */
    public static AttributeType baseAttr(ServerPlayer p) {
        return attr(p, AttributeType.AGILITY) >= attr(p, AttributeType.PERCEPTION) ? AttributeType.AGILITY : AttributeType.PERCEPTION;
    }

    /** 未扣盔甲减值的基础防御 = 属性 + 传奇值 */
    public static int baseRaw(ServerPlayer p) {
        AttributeType a = baseAttr(p);
        return Math.max(0, attr(p, a) + legend(p, a));
    }

    /** 基础防御（已扣盔甲减值与冲锋削弱，最低 0） */
    public static int base(ServerPlayer p) {
        int b = baseRaw(p) - ArmorPenaltyManager.penaltyOf(p);
        AttributeInstance a = p.getAttribute(Attributes.ARMOR);
        AttributeModifier ch = a == null ? null : a.getModifier(CHARGE);
        if (ch != null) b += (int) Math.round(ch.amount());
        return Math.max(0, b);
    }

    // ===== 组成 =====

    private static boolean covers(EquipmentSlot slot, LimbPart part, boolean known) {
        if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) return true; // 手持物品 / 其他栏位上的护甲值
        if (!known) return true;                                              // 不知道命中部位：整套盔甲
        if (part == LimbPart.HEAD) return slot == EquipmentSlot.HEAD;
        if (part == null || part.isArm()) return slot == EquipmentSlot.CHEST;
        return slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET;
    }

    /**
     * 玩家防御组成。
     *
     * @param src      本次伤害（null = 不知道命中部位，计入整套盔甲）
     * @param attacker 攻击者（措手不及 / 擒抱按攻击者判断，可为 null）
     * @param ranged   是否远程攻击（倒地修正）
     */
    public static Parts parts(ServerPlayer p, DamageSource src, Entity attacker, boolean ranged) {
        int base = base(p);
        int full = fullActive(p) ? base : 0;
        int parry = 0, natural = FeatEffects.has(p, FeatType.GIANT_BODY) ? 1 : 0;
        int dodge = FeatEffects.dodgeDefenseBonus(p);

        // 盔甲防御：只计覆盖命中部位的盔甲；无视护甲的伤害不计
        boolean known = src != null;
        LimbPart part = known ? LimbManager.resolvePart(p, src) : null;
        boolean bypass = known && src.is(DamageTypeTags.BYPASSES_ARMOR);
        double[] equip = new double[2]; // [0] 计入的盔甲, [1] 全部装备护甲（用于从属性修饰器中剔除）
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack st = p.getItemBySlot(slot);
            if (st.isEmpty()) continue;
            boolean counts = !bypass && covers(slot, part, known);
            st.forEachModifier(slot, (attr, mod) -> {
                if (attr.value() != Attributes.ARMOR.value() || mod.operation() != AttributeModifier.Operation.ADD_VALUE) return;
                equip[1] += mod.amount();
                if (counts) equip[0] += mod.amount();
            });
        }
        int armor = (int) Math.floor(equip[0]);

        // 其余护甲修饰器按来源归类
        double rest = 0;
        AttributeInstance inst = p.getAttribute(Attributes.ARMOR);
        if (inst != null) {
            for (AttributeModifier m : inst.getModifiers()) {
                if (m.operation() != AttributeModifier.Operation.ADD_VALUE || COMPUTED.contains(m.id())) continue;
                int v = (int) Math.round(m.amount());
                if (NATURAL.contains(m.id())) natural += v;
                else if (PARRY.contains(m.id())) parry += v;
                else if (DODGE.contains(m.id())) dodge += v;
                else rest += m.amount();
            }
        }
        int other = (int) Math.round(rest - equip[1]);

        boolean flat = flatVs(p, attacker);
        if (flat || StatusEffects.loseNatural(p)) {
            base = 0;
            full = 0;
            dodge = Math.min(0, dodge);
        }
        if (flat || StatusEffects.cantBlock(p)) parry = 0;
        int status = Math.round(StatusEffects.defenseMod(p, ranged, attacker));
        return new Parts(base, full, parry, dodge, natural, armor, 0, other, status);
    }

    /** 措手不及，或擒抱中面对组外攻击 */
    public static boolean flatVs(LivingEntity v, Entity attacker) {
        if (DamageRules.isFlatFooted(v, attacker)) return true;
        return GrappleManager.grappled(v) && (attacker == null || !GrappleManager.sameGroup(v, attacker));
    }

    /** 能否以格挡防御抵挡这次攻击（太极格挡等直接扣减类效果用） */
    public static boolean canParry(ServerPlayer p, Entity attacker) {
        return !flatVs(p, attacker) && !StatusEffects.cantBlock(p);
    }

    /**
     * 目标防御（攻击判定用）。consume = 本次攻击确实结算：消耗攻击附带的措手不及与意志守御。
     */
    public static float of(LivingEntity v, DamageSource src, boolean consume) {
        Entity att = src.getEntity();
        float d;
        if (v instanceof ServerPlayer p) {
            d = parts(p, src, att, StatusEffects.isRanged(src)).total();
            if (consume && att != null && att != p && !src.is(DamageTypeTags.BYPASSES_ARMOR)) d += WillpowerManager.consumeGuard(p);
        } else {
            d = v.getArmorValue();
            if (DamageRules.isFlatFooted(v, att)) d -= (float) DamageRules.naturalDefense(v);
        }
        if (consume) DamageRules.consumeFlat(v, att);
        return Math.max(0f, d);
    }

    /** 不知道伤害来源时（枪械 Pre 事件等）：整套盔甲 */
    public static float vs(LivingEntity v, Entity attacker, boolean ranged, boolean consume) {
        float d;
        if (v instanceof ServerPlayer p) {
            d = parts(p, null, attacker, ranged).total();
            if (consume && attacker != null && attacker != p) d += WillpowerManager.consumeGuard(p);
        } else {
            d = v.getArmorValue();
            if (DamageRules.isFlatFooted(v, attacker)) d -= (float) DamageRules.naturalDefense(v);
        }
        if (consume) DamageRules.consumeFlat(v, attacker);
        return Math.max(0f, d);
    }

    /** 接触攻击：只计基础 / 全力 / 闪避 / 天生防御与状态修正（不计盔甲、盾牌、格挡与其他加值） */
    public static float touch(LivingEntity v, Entity attacker) {
        if (!(v instanceof ServerPlayer p)) return DamageRules.isFlatFooted(v, attacker) ? 0f : (float) DamageRules.naturalDefense(v);
        Parts x = parts(p, null, attacker, false);
        return Math.max(0, x.base() + x.full() + x.dodge() + x.natural() + x.status());
    }

    /** 身体防御（基础 + 全力 + 闪避 + 天生；不考虑措手不及与状态） */
    public static int body(ServerPlayer p) {
        int base = base(p);
        int v = base + (fullActive(p) ? base : 0) + FeatEffects.dodgeDefenseBonus(p) + (FeatEffects.has(p, FeatType.GIANT_BODY) ? 1 : 0);
        AttributeInstance inst = p.getAttribute(Attributes.ARMOR);
        if (inst != null) for (AttributeModifier m : inst.getModifiers()) {
            if (NATURAL.contains(m.id()) || DODGE.contains(m.id())) v += (int) Math.round(m.amount());
        }
        return Math.max(0, v);
    }

    // ===== 豁免（确定值；实际豁免再乘 20%~100% 浮动，见 roll） =====

    /** 意志豁免 = 决心 + 感受 + 传奇决心 + 能量加值 − 减值 */
    public static int will(ServerPlayer p) {
        return will(p, PoolEffects.checkBonus(p, AttributeType.RESOLVE));
    }

    private static int will(ServerPlayer p, int bonus) {
        return attr(p, AttributeType.RESOLVE) + skill(p, SkillType.FEELING) + legend(p, AttributeType.RESOLVE)
                + bonus - StatusEffects.savePenalty(p, AttributeType.RESOLVE) - StatusEffects.willMod(p);
    }

    /** 反射豁免 = 敏捷 + 运动 + 传奇敏捷 + 能量加值 ± 修正；area = 范围豁免（对抗范围效果，倒地加值） */
    public static int reflex(ServerPlayer p, boolean area) {
        return reflex(p, area, PoolEffects.checkBonus(p, AttributeType.AGILITY));
    }

    private static int reflex(ServerPlayer p, boolean area, int bonus) {
        return attr(p, AttributeType.AGILITY) + skill(p, SkillType.ATHLETICS) + legend(p, AttributeType.AGILITY)
                + bonus - StatusEffects.savePenalty(p, AttributeType.AGILITY) + StatusEffects.reflexMod(p, area);
    }

    /** 强韧豁免 = 耐力 + 求生 + 传奇耐力 + 能量加值 − 减值 */
    public static int fort(ServerPlayer p) {
        return fort(p, PoolEffects.checkBonus(p, AttributeType.ENDURANCE));
    }

    private static int fort(ServerPlayer p, int bonus) {
        return attr(p, AttributeType.ENDURANCE) + skill(p, SkillType.SURVIVAL) + legend(p, AttributeType.ENDURANCE)
                + bonus - StatusEffects.savePenalty(p, AttributeType.ENDURANCE);
    }

    // ===== HUD 同步（不消耗能量：能量加值按「若开启则 +3」预览） =====

    public static final int HUD_FULL = 1, HUD_FLAT = 2, HUD_NO_REFLEX = 4, HUD_NO_PARRY = 8;
    private static final java.util.Map<UUID, DefenseHudPayload> LAST_HUD = new java.util.HashMap<>();

    /** 当前防御与豁免的显示值（防御按整套盔甲、无特定攻击者计算） */
    public static DefenseHudPayload hud(ServerPlayer p) {
        Parts x = parts(p, null, null, false);
        int flags = 0;
        if (fullActive(p)) flags |= HUD_FULL;
        if (flatVs(p, null) || StatusEffects.loseNatural(p)) flags |= HUD_FLAT;
        boolean reflexOk = StatusEffects.canReflex(p) && DamageRules.canReflex(p, null);
        if (!reflexOk) flags |= HUD_NO_REFLEX;
        if (flatVs(p, null) || StatusEffects.cantBlock(p)) flags |= HUD_NO_PARRY;
        int agi = PoolEffects.peekBonus(p, AttributeType.AGILITY);
        return new DefenseHudPayload(x.total(), x.base(), x.armor(),
                Math.max(0, will(p, PoolEffects.peekBonus(p, AttributeType.RESOLVE))),
                Math.max(0, reflex(p, false, agi)),
                Math.max(0, fort(p, PoolEffects.peekBonus(p, AttributeType.ENDURANCE))),
                reflexOk ? Math.max(0, reflex(p, true, agi)) : 0, flags);
    }

    private static void syncHud(ServerPlayer p, boolean force) {
        DefenseHudPayload now = hud(p);
        if (!force && now.equals(LAST_HUD.get(p.getUUID()))) return;
        LAST_HUD.put(p.getUUID(), now);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, now);
    }

    /** 豁免成功数：确定值 × 20%~100% 浮动 */
    public static int roll(LivingEntity e, int value) {
        return Math.max(0, Math.round(value * DamageVariance.roll(e.getRandom())));
    }

    /** 范围豁免成功数（措手不及 / 无法反射时为 0）；非玩家生物按其敏捷 + 运动 */
    public static int areaSave(LivingEntity v, Entity attacker) {
        if (!DamageRules.canReflex(v, attacker)) return 0;
        if (v instanceof ServerPlayer p) return StatusEffects.canReflex(p) ? roll(p, reflex(p, true)) : 0;
        var st = GrappleManager.stats(v);
        return st == null ? 0 : roll(v, st.agi() + st.athletics());
    }

    // ===== 全力防御 =====

    private static final Set<UUID> FULL = new HashSet<>();

    public static boolean fullActive(LivingEntity e) { return FULL.contains(e.getUUID()); }

    /** 动作轮盘：开启 / 关闭全力防御 */
    public static void toggleFull(ServerPlayer p) {
        if (FULL.remove(p.getUUID())) {
            p.displayClientMessage(Component.translatable("msg.zhushenspace.full_defense.off"), true);
        } else {
            if (!StatusEffects.canAct(p, true)) return;
            FULL.add(p.getUUID());
            p.displayClientMessage(Component.translatable("msg.zhushenspace.full_defense.on", base(p)), true);
            p.level().playSound(null, p.blockPosition(), SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.PLAYERS, 0.8f, 0.8f);
        }
        refreshFull(p);
        ArtManager.sync(p);
    }

    /** 发起攻击：解除全力防御 */
    public static void endFull(ServerPlayer p) {
        if (!FULL.remove(p.getUUID())) return;
        p.displayClientMessage(Component.translatable("msg.zhushenspace.full_defense.end"), true);
        refreshFull(p);
        ArtManager.sync(p);
    }

    private static void refreshFull(ServerPlayer p) {
        AttributeInstance a = p.getAttribute(Attributes.ARMOR);
        if (a == null) return;
        a.removeModifier(FULL_MOD);
        int v = fullActive(p) ? base(p) : 0;
        if (v != 0) a.addTransientModifier(new AttributeModifier(FULL_MOD, v, AttributeModifier.Operation.ADD_VALUE));
    }

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) endFull(p);
    }

    @SubscribeEvent
    public static void onLoose(ArrowLooseEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) endFull(p);
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post e) {
        int tc = e.getServer().getTickCount();
        if (tc % 5 != 0) return;
        for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) {
            if (tc % 10 == 0 && fullActive(p)) {
                if (!StatusEffects.canAct(p, false)) endFull(p);
                else refreshFull(p);
            }
            syncHud(p, tc % 400 == 0); // 防御 / 豁免 HUD：变化时同步，每 20 秒强制一次
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && FULL.remove(p.getUUID())) refreshFull(p);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        FULL.remove(e.getEntity().getUUID());
        LAST_HUD.remove(e.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) { LAST_HUD.remove(e.getEntity().getUUID()); }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) { LAST_HUD.remove(e.getEntity().getUUID()); }

    @SubscribeEvent
    public static void onDim(PlayerEvent.PlayerChangedDimensionEvent e) { LAST_HUD.remove(e.getEntity().getUUID()); }

    // ===== 结算 =====

    /** 原版护甲 / 保护类附魔不再按比例减伤（HIGH：先于一切依赖原版护甲的结算） */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onIncoming(LivingIncomingDamageEvent e) {
        DamageSource src = e.getSource();
        if (src.is(DamageTypes.GENERIC_KILL) || src.is(DamageTypes.FELL_OUT_OF_WORLD)) return;
        e.addReductionModifier(DamageContainer.Reduction.ARMOR, (c, r) -> 0f);
        e.addReductionModifier(DamageContainer.Reduction.ENCHANTMENTS, (c, r) -> 0f);
    }

    /**
     * 伤害浮动之后（由 {@link DamageVariance} 在 NORMAL 阶段末尾调用）：
     * 非玩家的攻击 = 伤害 − 防御（玩家的攻击由 {@link CombatFormula} 按公式结算）；爆炸 = 伤害 − 范围豁免。
     */
    static void apply(LivingIncomingDamageEvent e) {
        LivingEntity v = e.getEntity();
        DamageSource src = e.getSource();
        if (e.isCanceled() || e.getAmount() <= 0f || src.is(DamageTypes.GENERIC_KILL) || src.is(DamageTypes.FELL_OUT_OF_WORLD)) return;
        if (DamageRules.hasPending(v)) return; // 模组能力伤害：检定中已自行扣除防御 / 豁免
        Entity att = src.getEntity();
        float amt;
        if (src.is(DamageTypeTags.IS_EXPLOSION)) {
            amt = e.getAmount() - areaSave(v, att);
        } else if (att != null && att != v && !(att instanceof net.minecraft.world.entity.player.Player)
                && !src.is(DamageTypeTags.BYPASSES_ARMOR)) {
            // 冻寒骨爪「镇亡」：被诅咒的不死生物攻击检定 −6（亵渎）
            amt = e.getAmount() - of(v, src, true) - MagicSpells.attackCurse(att);
        } else {
            return;
        }
        if (amt <= 0f) { e.setCanceled(true); return; }
        e.setAmount(amt);
    }
}
