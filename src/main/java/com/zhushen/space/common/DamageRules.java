package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.DamageKind;
import com.zhushen.space.data.PlayerHealthData.Severity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.*;
import java.util.function.BiConsumer;

/**
 * 伤害类型结算。流程（对应规则书「伤害结算」2~11 步）：
 * <ol>
 *   <li>识别伤害类型（原版伤害按来源映射；模组技能用 {@link #deal} 指定类型 / 伤害等级 / 破甲 / 无视抗力）</li>
 *   <li>免疫 → 忽略 → 硬度与伤害抵消（取高）→ 能量抗力 / 伤害减免 → 伤害吸收 → 阈值 → 防御方伤害转化（接口）</li>
 *   <li>最终伤害确定后触发易伤（翻倍或 +X，不叠加）</li>
 * </ol>
 * 混合伤害：每一步的防御值取「各类型对应防御的最小值」（免疫 = 无穷大），即必须对全部类型都有防御才完全生效。
 * 防御数值来自 {@link Profile}，由 {@link #addProvider} 注册的提供者填充（专长 / 状态 / 装备 / 生物种类）。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class DamageRules {
    private DamageRules() {}

    // ===== 防御档案 =====

    /** 某一生物面对伤害时的防御数值（每次伤害重新生成） */
    public static final class Profile {
        public final EnumSet<DamageKind> immune = EnumSet.noneOf(DamageKind.class);
        public final EnumMap<DamageKind, Integer> ignore = new EnumMap<>(DamageKind.class);
        public final EnumMap<DamageKind, Integer> resist = new EnumMap<>(DamageKind.class);
        public final EnumMap<DamageKind, Integer> absorb = new EnumMap<>(DamageKind.class);
        /** 易伤：0 = 无，-1 = 翻倍，X>0 = 易伤 X */
        public final EnumMap<DamageKind, Integer> vuln = new EnumMap<>(DamageKind.class);
        public int allIgnore, allAbsorb;
        /** 全能量抗力 */
        public int allEnergyResist;
        /** 物理伤害减免 */
        public int physicalReduction;
        /** 硬度（「获得」取高，「增加」叠加，由提供者自行处理） */
        public int hardness;
        /** 伤害抵消（与硬度取高） */
        public int offset;
        /** 阈值：低于该值的伤害无效 */
        public int threshold;
        /** 光明生物 / 黑暗生物 */
        public boolean lightCreature, darkCreature;

        public void resist(DamageKind k, int v) { resist.merge(k, v, Integer::sum); }
    }

    private static final List<BiConsumer<LivingEntity, Profile>> PROVIDERS = new ArrayList<>();

    /** 注册防御提供者（专长、状态、装备、生物种类……） */
    public static void addProvider(BiConsumer<LivingEntity, Profile> p) { PROVIDERS.add(p); }

    static {
        // 亡灵 = 黑暗生物
        addProvider((e, pr) -> { if (e.getType().is(EntityTypeTags.UNDEAD)) pr.darkCreature = true; });
        // 抗火药水：免疫火焰伤害
        addProvider((e, pr) -> { if (e.hasEffect(MobEffects.FIRE_RESISTANCE)) pr.immune.add(DamageKind.FIRE); });
    }

    public static Profile profile(LivingEntity e) {
        Profile p = new Profile();
        for (var f : PROVIDERS) f.accept(e, p);
        return p;
    }

    // ===== 模组伤害入口 =====

    /** 一次模组伤害的附加信息 */
    public record Spec(Set<DamageKind> kinds, Severity severity, int armorPierce, int ignoreResist, boolean ranged) {
        public static Spec of(Severity s, DamageKind... kinds) {
            return new Spec(kinds.length == 0 ? EnumSet.of(DamageKind.BLUNT) : EnumSet.copyOf(Arrays.asList(kinds)), s, 0, 0, false);
        }
    }

    private static final Map<UUID, Spec> PENDING = new HashMap<>();

    /** 以指定类型造成伤害（后续技能统一走这里） */
    public static boolean deal(LivingEntity target, DamageSource src, float amount, Spec spec) {
        PENDING.put(target.getUUID(), spec);
        try {
            return target.hurt(src, amount);
        } finally {
            PENDING.remove(target.getUUID());
        }
    }

    // ===== 类型识别 =====

    public static Set<DamageKind> kinds(DamageSource src, LivingEntity victim) {
        Spec sp = PENDING.get(victim.getUUID());
        if (sp != null) return sp.kinds;
        DamageKind art = ArtDamage.kindOf(src);
        if (art != null) return EnumSet.of(art);
        if (src.is(DamageTypes.IN_FIRE) || src.is(DamageTypes.ON_FIRE) || src.is(DamageTypes.LAVA) || src.is(DamageTypes.HOT_FLOOR)
                || src.is(DamageTypes.CAMPFIRE))
            return EnumSet.of(DamageKind.FIRE);
        if (src.is(DamageTypes.FIREBALL) || src.is(DamageTypes.UNATTRIBUTED_FIREBALL)) return EnumSet.of(DamageKind.FIRE, DamageKind.BLUNT);
        if (src.is(DamageTypes.FREEZE)) return EnumSet.of(DamageKind.COLD);
        if (src.is(DamageTypes.LIGHTNING_BOLT)) return EnumSet.of(DamageKind.LIGHTNING);
        if (src.is(DamageTypes.WITHER) || src.is(DamageTypes.WITHER_SKULL)) return EnumSet.of(DamageKind.UNHOLY);
        if (src.is(DamageTypes.SONIC_BOOM)) return EnumSet.of(DamageKind.SONIC);
        if (src.is(DamageTypes.DRAGON_BREATH)) return EnumSet.of(DamageKind.PURE_ENERGY);
        if (src.is(DamageTypes.MAGIC) || src.is(DamageTypes.INDIRECT_MAGIC)) {
            // 中毒效果的伤害也是 MAGIC：无来源且身中剧毒 → 毒素
            if (src.getEntity() == null && victim.hasEffect(MobEffects.POISON)) return EnumSet.of(DamageKind.TOXIN);
            return EnumSet.of(DamageKind.PURE_ENERGY);
        }
        if (src.is(DamageTypes.ARROW) || src.is(DamageTypes.TRIDENT) || src.is(DamageTypes.STING) || src.is(DamageTypes.CACTUS)
                || src.is(DamageTypes.SWEET_BERRY_BUSH) || src.is(DamageTypes.STALAGMITE) || src.is(DamageTypes.FALLING_STALACTITE)
                || src.is(DamageTypes.THORNS))
            return EnumSet.of(DamageKind.PIERCE);
        return EnumSet.of(DamageKind.BLUNT);
    }

    public static boolean hasPending(LivingEntity e) { return PENDING.containsKey(e.getUUID()); }

    public static boolean isFall(DamageSource s) { return s.is(DamageTypes.FALL) || s.is(DamageTypes.FLY_INTO_WALL); }

    public static boolean isFallingObject(DamageSource s) {
        return s.is(DamageTypes.FALLING_BLOCK) || s.is(DamageTypes.FALLING_ANVIL) || s.is(DamageTypes.FALLING_STALACTITE);
    }

    private static boolean bypass(DamageSource s) { return s.is(DamageTypes.GENERIC_KILL) || s.is(DamageTypes.FELL_OUT_OF_WORLD); }

    // ===== 伤害等级拆分（给 HealthManager） =====

    private static final Map<UUID, float[]> SPLIT = new HashMap<>();

    /** 取出本次伤害的等级比例 {B, L, A}（默认全部冲击） */
    public static int[] takeSplit(LivingEntity e, int amount) {
        float[] f = SPLIT.remove(e.getUUID());
        if (f == null) return new int[]{amount, 0, 0};
        int l = Math.round(amount * f[1]), a = Math.round(amount * f[2]);
        a = Math.min(a, amount);
        l = Math.min(l, amount - a);
        return new int[]{amount - l - a, l, a};
    }

    private static float[] base(Severity s) {
        return switch (s) { case B -> new float[]{1, 0, 0}; case L -> new float[]{0, 1, 0}; case A -> new float[]{0, 0, 1}; };
    }

    /** 一半伤害提升 1 级（冲击 → 严重 → 恶性） */
    private static float[] upgradeHalf(float[] f) {
        return new float[]{f[0] * 0.5f, f[1] * 0.5f + f[0] * 0.5f, f[2] + f[1] * 0.5f};
    }

    // ===== 结算 =====

    /** 在公式攻击（HIGHEST）之后、原版护甲之前 */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onIncoming(LivingIncomingDamageEvent e) {
        LivingEntity v = e.getEntity();
        DamageSource src = e.getSource();
        if (bypass(src) || e.getAmount() <= 0) return;
        Spec spec = PENDING.get(v.getUUID());
        Set<DamageKind> ks = kinds(src, v);
        float amt = e.getAmount();
        float[] split = base(spec != null ? spec.severity : Severity.B);

        // 坠落：起算 3 米，每超 2 米 1 点物理钝击严重伤害（原版伤害已扣除起算高度、安全高度与软地形），上限 100，无视护甲，倒地
        if (isFall(src)) {
            amt = Math.min(100, (float) Math.ceil(amt / 2f));
            split = base(Severity.L);
            e.addReductionModifier(DamageContainer.Reduction.ARMOR, (c, r) -> 0f);
            e.addReductionModifier(DamageContainer.Reduction.ENCHANTMENTS, (c, r) -> 0f);
            if (amt > 0) v.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 4, false, false));
        } else if (isFallingObject(src)) {
            amt = Math.min(20, amt);
            split = base(Severity.L);
        }

        boolean attack = src.getEntity() != null;
        // 模组能力伤害：检定中已扣除目标防御，不再计算原版护甲
        if (spec != null) e.addReductionModifier(DamageContainer.Reduction.ARMOR, (c, r) -> 0f);
        boolean anyItems = ks.stream().anyMatch(DamageKind::ignoresItems);
        boolean allEnergyLike = ks.stream().noneMatch(DamageKind::physical);
        // 精神 / 毒素：无视物品带来的伤害降低（护甲、附魔）
        if (anyItems) {
            e.addReductionModifier(DamageContainer.Reduction.ARMOR, (c, r) -> 0f);
            e.addReductionModifier(DamageContainer.Reduction.ENCHANTMENTS, (c, r) -> 0f);
        } else if (allEnergyLike && !attack) {
            // 环境能量伤害（火、冻、雷……）不是攻击，不受防御（护甲）影响
            e.addReductionModifier(DamageContainer.Reduction.ARMOR, (c, r) -> 0f);
        }

        Profile pr = profile(v);
        // 4) 免疫：全部类型都免疫才免疫
        if (!ks.isEmpty() && pr.immune.containsAll(ks)) { e.setCanceled(true); return; }
        // 5) 忽略
        amt -= minOver(ks, pr, k -> pr.allIgnore + pr.ignore.getOrDefault(k, 0));
        // 6) 硬度与伤害抵消（取高，不叠加）
        boolean hard = ks.stream().allMatch(k -> !k.ignoresHardness());
        if (hard) {
            int pierce = spec != null ? spec.armorPierce : 0;
            amt -= Math.max(0, Math.max(pr.hardness - Math.max(0, pierce - armorLayers(v)), pr.offset));
        }
        // 7) 能量抗力 / 物理伤害减免
        boolean reducible = ks.stream().noneMatch(DamageKind::ignoresReduction);
        if (reducible) {
            int ign = spec != null ? spec.ignoreResist : 0;
            int allE = Math.max(0, pr.allEnergyResist - ign);
            amt -= minOver(ks, pr, k -> k.physical() ? pr.physicalReduction : allE + pr.resist.getOrDefault(k, 0));
        }
        // 8) 吸收
        amt -= minOver(ks, pr, k -> pr.allAbsorb + pr.absorb.getOrDefault(k, 0));
        // 9) 阈值
        if (amt < pr.threshold) amt = 0;
        // 10) 防御方伤害转化：接口（后续能力）
        amt = Math.max(0, amt);
        // 光明对黑暗生物 / 黑暗对光明生物：一半伤害提升 1 级
        if ((ks.contains(DamageKind.HOLY) && pr.darkCreature) || (ks.contains(DamageKind.UNHOLY) && pr.lightCreature)) split = upgradeHalf(split);
        if (amt <= 0) { e.setCanceled(true); return; }
        e.setAmount(amt);
        SPLIT.put(v.getUUID(), split);
        LAST_KINDS.put(v.getUUID(), ks);
    }

    private static final Map<UUID, Set<DamageKind>> LAST_KINDS = new HashMap<>();

    /** 11) 最终伤害确定后：易伤（翻倍，或 +X；不叠加，取最大） */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onFinal(LivingDamageEvent.Pre e) {
        LivingEntity v = e.getEntity();
        Set<DamageKind> ks = LAST_KINDS.remove(v.getUUID());
        if (ks == null || e.getNewDamage() <= 0) return;
        Profile pr = profile(v);
        boolean dbl = false;
        int plus = 0;
        for (DamageKind k : ks) {
            int x = pr.vuln.getOrDefault(k, 0);
            if (x < 0) dbl = true;
            else plus = Math.max(plus, x);
        }
        float d = e.getNewDamage();
        if (dbl) d *= 2;
        d += plus;
        e.setNewDamage(d);
    }

    /** 破甲依次击破 盾牌 / 盔甲 / 天生防御，剩余才击破硬度：这里以当前护甲值近似前三者 */
    private static int armorLayers(LivingEntity v) {
        return (int) Math.floor(v.getArmorValue());
    }

    private static int minOver(Set<DamageKind> ks, Profile pr, java.util.function.ToIntFunction<DamageKind> f) {
        int m = Integer.MAX_VALUE;
        for (DamageKind k : ks) m = Math.min(m, pr.immune.contains(k) ? Integer.MAX_VALUE : f.applyAsInt(k));
        return m == Integer.MAX_VALUE ? 0 : Math.max(0, m);
    }

    // ===== 措手不及 =====

    private static final Map<UUID, Map<UUID, Long>> FLAT = new HashMap<>();
    private static final Map<UUID, Long> FLAT_ALL = new HashMap<>();

    /** 使 target 对 source 措手不及 ticks 刻（source 为 null = 对所有人，如突袭轮） */
    public static void flatFooted(LivingEntity target, Entity source, int ticks) {
        long until = target.level().getGameTime() + ticks;
        if (source == null) FLAT_ALL.put(target.getUUID(), until);
        else FLAT.computeIfAbsent(target.getUUID(), k -> new HashMap<>()).put(source.getUUID(), until);
    }

    /** 免疫措手不及（如息法·中息） */
    public static java.util.function.Predicate<LivingEntity> FLAT_IMMUNE = e -> false;

    public static boolean isFlatFooted(LivingEntity target, Entity attacker) {
        if (FLAT_IMMUNE.test(target)) return false;
        long now = target.level().getGameTime();
        Long all = FLAT_ALL.get(target.getUUID());
        if (all != null && all > now) return true;
        if (attacker == null) return false;
        Map<UUID, Long> m = FLAT.get(target.getUUID());
        Long t = m == null ? null : m.get(attacker.getUUID());
        return t != null && t > now;
    }

    /** 由攻击附带的措手不及：持续一次攻击，命中后清除 */
    public static void consumeFlat(LivingEntity target, Entity attacker) {
        Map<UUID, Long> m = FLAT.get(target.getUUID());
        if (m != null && attacker != null) m.remove(attacker.getUUID());
    }

    /** 天生防御（敏捷护甲 + 巨大身材天生防御）：措手不及 / 擒抱中失去 */
    public static double naturalDefense(LivingEntity m) {
        AttributeInstance a = m.getAttribute(Attributes.ARMOR);
        if (a == null) return 0;
        double v = 0;
        for (String id : new String[]{"agility_armor", "feat_giant_natural"}) {
            AttributeModifier mod = a.getModifier(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, id));
            if (mod != null) v += mod.amount();
        }
        return v;
    }

    /** 反射动作是否可用（措手不及时不可用） */
    public static boolean canReflex(LivingEntity e, Entity attacker) { return !isFlatFooted(e, attacker); }

    static boolean isPlayer(Entity e) { return e instanceof Player; }
}
