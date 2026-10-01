package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.DamageKind;
import com.zhushen.space.data.PlayerHealthData.Severity;
import com.zhushen.space.network.DamageKeywordsPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
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
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * 伤害结算（规则书「防御类型」「伤害结算」）。
 * <p>伤害降低只有这些关键字：豁免、忽略、硬度 / 抵消、能量抗力 / 物理伤害减免（DR）、吸收、阈值、转化；
 * 伤害转移不是伤害降低。流程（{@link LivingIncomingDamageEvent} 最低优先级，在攻击公式、伤害上限与意志加值之后）：</p>
 * <ol>
 *   <li>1）攻击方伤害（含上限）已由前面的处理器算好；命中后的额外伤害放在 {@link Spec#extra}，不计上限</li>
 *   <li>2）攻击方伤害转化（一半提升 1 级、光明对黑暗生物……，{@link #addAttackProvider}）</li>
 *   <li>3）记录攻击方的击破能力（{@link Break}：无视 X 点 = 从高到低依次击破；无视某类 = 该类全部无效）</li>
 *   <li>4）免疫 → 5）忽略 → 6）硬度与伤害抵消（取高）→ 7）能量抗力 / 物理伤害减免 → 8）吸收 → 9）阈值</li>
 *   <li>10）防御方伤害转化（盾牌 → 盔甲 → 自身，同名转化只生效一次，从恶性开始各降 1 级，最低冲击）</li>
 *   <li>11）确定最终伤害：易伤、暴击等以最终伤害为基准分别追加（{@link #addFinalMod}），随后伤害转移（{@link #addTransfer}）</li>
 * </ol>
 * 伤害按等级拆成若干部分（冲击 / 严重 / 恶性），防御优先作用于较低等级的部分；每个防御值是「每次伤害」的总额。
 * 同阶段的同类防御不叠加，按伤害类型取最高；混合类型的部分取各类型最高值中的最小值（必须对全部类型都有防御）。
 * 防御数值来自 {@link Profile}，由 {@link #addProvider} 注册的提供者填充。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class DamageRules {
    private DamageRules() {}

    /** 「全部」：无视整类防御 / 转化全部伤害 */
    public static final int ALL = Integer.MAX_VALUE;

    // ===== 关键字 =====

    /** 伤害降低阶段（5~8 步）。HARDNESS 与 OFFSET 同属第 6 步（取高），RESIST 与 DR 同属第 7 步 */
    public enum Stage { IGNORE, HARDNESS, OFFSET, RESIST, DR, ABSORB }

    /** 武器 / 伤害特性：【魔法】【神兵】 */
    public enum Trait { MAGIC, DIVINE }

    /** 伤害吸收的范围 */
    public enum Scope { PHYSICAL, ENERGY, ALL }

    /** 物理伤害减免的弱点：DR X/魔法、/神兵、/穿刺、/钝击、/挥砍（无弱点 = DR X/-） */
    public enum Weakness { MAGIC, DIVINE, PIERCE, BLUNT, SLASH }

    /** 防御方伤害转化的生效层：盾牌 → 盔甲 → 角色本身 */
    public enum Layer { SHIELD, ARMOR, SELF }

    /** 一条数值型防御能力 */
    public static final class Def {
        public final Stage stage;
        /** 来源（翻译键，显示用） */
        public final String source;
        public final int value;
        /** 只对这些伤害类型生效；null = 该关键字的默认范围 */
        public final EnumSet<DamageKind> kinds;
        public final Scope scope;
        public final EnumSet<Weakness> weak;
        /** 由物品提供（精神 / 毒素伤害无视） */
        public boolean item;

        Def(Stage stage, String source, int value, EnumSet<DamageKind> kinds, Scope scope, EnumSet<Weakness> weak) {
            this.stage = stage;
            this.source = source == null ? "" : source;
            this.value = Math.max(0, value);
            this.kinds = kinds;
            this.scope = scope;
            this.weak = weak == null ? EnumSet.noneOf(Weakness.class) : weak;
        }

        public Def item() { item = true; return this; }

        /** 按关键字本身的范围是否覆盖该伤害类型 */
        public boolean covers(DamageKind k) {
            return switch (stage) {
                case IGNORE, OFFSET -> kinds == null || kinds.contains(k);
                case HARDNESS -> !k.ignoresHardness() && (kinds == null || kinds.contains(k));
                case RESIST -> k.energy() && (kinds == null || kinds.contains(k));
                case DR -> k.physical();
                case ABSORB -> switch (scope == null ? Scope.ALL : scope) {
                    case PHYSICAL -> k.physical();
                    case ENERGY -> k.energy();
                    case ALL -> true;
                };
            };
        }

        /** DR 的弱点被本次伤害穿透 */
        boolean pierced(Hit h, Part p) {
            if (stage != Stage.DR) return false;
            for (Weakness w : weak) {
                boolean hit = switch (w) {
                    case MAGIC -> h.traits.contains(Trait.MAGIC);
                    case DIVINE -> h.traits.contains(Trait.DIVINE);
                    case PIERCE -> p.kinds.contains(DamageKind.PIERCE);
                    case BLUNT -> p.kinds.contains(DamageKind.BLUNT);
                    case SLASH -> p.kinds.contains(DamageKind.SLASH);
                };
                if (hit) return true;
            }
            return false;
        }

        boolean applies(DamageKind k, Hit h, Part p) {
            return covers(k) && !(item && k.ignoresItems()) && !pierced(h, p);
        }
    }

    /** 条件型伤害忽略：满足条件的伤害全部忽略（如「忽略武器伤害 X 点以下的伤害」） */
    public static final class IgnoreRule {
        public final String source;
        public final Predicate<Hit> test;
        public boolean item;

        IgnoreRule(String source, Predicate<Hit> test) {
            this.source = source == null ? "" : source;
            this.test = test;
        }

        public IgnoreRule item() { item = true; return this; }
    }

    /** 防御方伤害转化（如【防弹】）：每次伤害中最多 points 点降低 1 级 */
    public static final class Conversion {
        /** 同名转化只生效一次（翻译键，显示用） */
        public final String id;
        public final Layer layer;
        public final int points;
        /** 生效条件（null = 任意伤害） */
        public final Predicate<Hit> when;
        public boolean item;

        Conversion(String id, Layer layer, int points, Predicate<Hit> when) {
            this.id = id;
            this.layer = layer == null ? Layer.SELF : layer;
            this.points = Math.max(0, points);
            this.when = when;
        }

        public Conversion item() { item = true; return this; }
    }

    // ===== 防御档案 =====

    /** 某一生物面对伤害时的防御能力（每次伤害重新生成） */
    public static final class Profile {
        public final EnumSet<DamageKind> immune = EnumSet.noneOf(DamageKind.class);
        /** 免疫某一等级的伤害（如免疫冲击伤害） */
        public final EnumSet<Severity> immuneSeverity = EnumSet.noneOf(Severity.class);
        public final List<Def> defs = new ArrayList<>();
        public final List<IgnoreRule> ignoreRules = new ArrayList<>();
        public final List<Conversion> conversions = new ArrayList<>();
        /** 易伤（翻倍） */
        public final EnumSet<DamageKind> vulnDouble = EnumSet.noneOf(DamageKind.class);
        /** 易伤 X（取最大） */
        public final EnumMap<DamageKind, Integer> vulnPlus = new EnumMap<>(DamageKind.class);
        /** 阈值：一次伤害低于该值时无效（取最高） */
        public int threshold;
        /** 光明生物 / 黑暗生物 */
        public boolean lightCreature, darkCreature;

        private static EnumSet<DamageKind> set(DamageKind... ks) {
            if (ks == null || ks.length == 0) return null;
            return EnumSet.copyOf(Arrays.asList(ks));
        }

        private Def add(Def d) { defs.add(d); return d; }

        public Profile immune(DamageKind... ks) { immune.addAll(Arrays.asList(ks)); return this; }

        public Profile immuneSeverity(Severity s) { immuneSeverity.add(s); return this; }

        /** 伤害忽略 X（不写类型 = 任意伤害） */
        public Def ignore(String source, int x, DamageKind... ks) { return add(new Def(Stage.IGNORE, source, x, set(ks), null, null)); }

        /** 忽略满足条件的全部伤害 */
        public IgnoreRule ignoreIf(String source, Predicate<Hit> test) {
            IgnoreRule r = new IgnoreRule(source, test);
            ignoreRules.add(r);
            return r;
        }

        /** 硬度（「获得」取高、「增加」叠加由提供者自行折算后传入） */
        public Def hardness(String source, int x) { return add(new Def(Stage.HARDNESS, source, x, null, null, null)); }

        /** 伤害抵消 X（不写类型 = 任意伤害；与硬度取高） */
        public Def offset(String source, int x, DamageKind... ks) { return add(new Def(Stage.OFFSET, source, x, set(ks), null, null)); }

        /** 能量抗力 X（不写类型 = 全能量抗力） */
        public Def resist(String source, int x, DamageKind... ks) { return add(new Def(Stage.RESIST, source, x, set(ks), null, null)); }

        /** 物理伤害减免 DR X/弱点（不写弱点 = DR X/-） */
        public Def dr(String source, int x, Weakness... weak) {
            EnumSet<Weakness> w = EnumSet.noneOf(Weakness.class);
            if (weak != null) w.addAll(Arrays.asList(weak));
            return add(new Def(Stage.DR, source, x, null, null, w));
        }

        /** 伤害吸收 X（物理 / 能量 / 全） */
        public Def absorb(String source, int x, Scope scope) { return add(new Def(Stage.ABSORB, source, x, null, scope, null)); }

        public Profile threshold(int x) { threshold = Math.max(threshold, x); return this; }

        /** 防御方伤害转化：每次伤害中最多 points 点（{@link #ALL} = 全部）降低 1 级 */
        public Conversion convert(String id, Layer layer, int points, Predicate<Hit> when) {
            Conversion c = new Conversion(id, layer, points, when);
            conversions.add(c);
            return c;
        }

        /** 易伤（翻倍） */
        public Profile vuln(DamageKind k) { vulnDouble.add(k); return this; }

        /** 易伤 X */
        public Profile vuln(DamageKind k, int x) { if (x > 0) vulnPlus.merge(k, x, Math::max); return this; }

        /** 某阶段对某伤害类型的最高值（不计击破与弱点，显示 / 查询用） */
        public int best(Stage s, DamageKind k) {
            int m = 0;
            for (Def d : defs) if (d.stage == s && d.covers(k)) m = Math.max(m, d.value);
            return m;
        }
    }

    private static final List<BiConsumer<LivingEntity, Profile>> PROVIDERS = new ArrayList<>();
    private static final List<BiConsumer<Hit, Profile>> ATTACK = new ArrayList<>();
    private static final List<ToDoubleFunction<Hit>> FINAL = new ArrayList<>();
    private static final List<Function<Hit, Share>> TRANSFERS = new ArrayList<>();

    /** 注册防御提供者（专长、状态、装备、生物种类……） */
    public static void addProvider(BiConsumer<LivingEntity, Profile> p) { PROVIDERS.add(p); }

    /** 注册攻击方能力（第 2~3 步：特性、击破、攻击方伤害转化、附加伤害部分） */
    public static void addAttackProvider(BiConsumer<Hit, Profile> p) { ATTACK.add(p); }

    /**
     * 注册最终伤害触发的追加（第 11 步，如暴击）：返回「额外造成 最终伤害 × 系数」的系数（0 = 不触发）。
     * 多个追加都以同一最终伤害为基准分别计算再相加。
     */
    public static void addFinalMod(ToDoubleFunction<Hit> f) { FINAL.add(f); }

    /** 伤害转移：转移者与本次承担的点数（向下取整后传入） */
    public record Share(LivingEntity to, int points) {}

    /** 注册伤害转移（在最终伤害确定后；不可避免的伤害无法转移） */
    public static void addTransfer(Function<Hit, Share> f) { TRANSFERS.add(f); }

    static {
        // 亡灵 = 黑暗生物
        addProvider((e, pr) -> { if (e.getType().is(EntityTypeTags.UNDEAD)) pr.darkCreature = true; });
        // 抗火药水：免疫火焰伤害
        addProvider((e, pr) -> { if (e.hasEffect(MobEffects.FIRE_RESISTANCE)) pr.immune.add(DamageKind.FIRE); });
        // 操作传奇暴击 + 感知弱点：确定最终伤害后追加
        addFinalMod(AttributeEvents::finalBonus);
        // 狼孩 / 人猿泰山：天生武器造成严重伤害
        addAttackProvider(FeatEffects::wildAttack);
    }

    public static Profile profile(LivingEntity e) {
        Profile p = new Profile();
        for (var f : PROVIDERS) f.accept(e, p);
        return p;
    }

    // ===== 攻击方：击破 / 伤害部分 / 特性 =====

    /**
     * 击破：amount = X 点时依次击破同阶段的防御（从高到低，必须击破为 0 才击破下一个）；
     * amount = {@link #ALL} 时无视该阶段的全部防御。kind 不为空时只针对该伤害类型。
     */
    public record Break(Stage stage, int amount, DamageKind kind) {
        public static Break points(Stage s, int x) { return new Break(s, Math.max(0, x), null); }

        public static Break points(Stage s, int x, DamageKind k) { return new Break(s, Math.max(0, x), k); }

        public static Break all(Stage s) { return new Break(s, ALL, null); }

        public static Break all(Stage s, DamageKind k) { return new Break(s, ALL, k); }

        public boolean whole() { return amount == ALL; }
    }

    /** 一次伤害中的一个部分（同等级、同类型） */
    public static final class Part {
        public float amount;
        public Severity severity;
        public final EnumSet<DamageKind> kinds;
        /** 不可避免：跳过免疫与全部伤害降低，不能转移 */
        public final boolean unavoidable;

        public Part(float amount, Severity severity, Set<DamageKind> kinds, boolean unavoidable) {
            this.amount = Math.max(0f, amount);
            this.severity = severity == null ? Severity.B : severity;
            this.kinds = EnumSet.noneOf(DamageKind.class);
            if (kinds != null) this.kinds.addAll(kinds);
            if (this.kinds.isEmpty()) this.kinds.add(DamageKind.BLUNT);
            this.unavoidable = unavoidable;
        }

        Part copy(boolean unavoidable) { return new Part(amount, severity, kinds, unavoidable || this.unavoidable); }
    }

    /** 一次模组伤害的附加信息 */
    public record Spec(Set<DamageKind> kinds, Severity severity, int armorPierce, int ignoreResist, boolean ranged,
                       Set<Trait> traits, List<Break> breaks, List<Part> extra, float weapon,
                       boolean unavoidable, boolean transferred, boolean halfUp) {

        public Spec(Set<DamageKind> kinds, Severity severity, int armorPierce, int ignoreResist, boolean ranged) {
            this(kinds, severity, armorPierce, ignoreResist, ranged, Set.of(), List.of(), List.of(), -1f, false, false, false);
        }

        public static Spec of(Severity s, DamageKind... kinds) {
            return new Spec(kinds.length == 0 ? EnumSet.of(DamageKind.BLUNT) : EnumSet.copyOf(Arrays.asList(kinds)), s, 0, 0, false);
        }

        /** 附带特性（【魔法】【神兵】） */
        public Spec withTraits(Trait... t) {
            EnumSet<Trait> s = EnumSet.noneOf(Trait.class);
            s.addAll(traits);
            s.addAll(Arrays.asList(t));
            return new Spec(kinds, severity, armorPierce, ignoreResist, ranged, s, breaks, extra, weapon, unavoidable, transferred, halfUp);
        }

        /** 附带击破能力 */
        public Spec withBreaks(Break... b) {
            List<Break> l = new ArrayList<>(breaks);
            l.addAll(Arrays.asList(b));
            return new Spec(kinds, severity, armorPierce, ignoreResist, ranged, traits, List.copyOf(l), extra, weapon, unavoidable, transferred, halfUp);
        }

        /** 命中后的额外伤害部分（不计入伤害上限，单独结算防御） */
        public Spec withExtra(float amount, Severity s, DamageKind... ks) {
            List<Part> l = new ArrayList<>(extra);
            l.add(new Part(amount, s, ks.length == 0 ? kinds : EnumSet.copyOf(Arrays.asList(ks)), false));
            return new Spec(kinds, severity, armorPierce, ignoreResist, ranged, traits, breaks, List.copyOf(l), weapon, unavoidable, transferred, halfUp);
        }

        Spec withParts(List<Part> parts) {
            List<Part> l = new ArrayList<>(extra);
            l.addAll(parts);
            return new Spec(kinds, severity, armorPierce, ignoreResist, ranged, traits, breaks, List.copyOf(l), weapon, unavoidable, transferred, halfUp);
        }

        /** 武器伤害（用于「忽略武器伤害 X 点以下」等条件；-1 = 不是武器伤害） */
        public Spec withWeapon(float w) {
            return new Spec(kinds, severity, armorPierce, ignoreResist, ranged, traits, breaks, extra, w, unavoidable, transferred, halfUp);
        }

        /** 不可避免的伤害 */
        public Spec asUnavoidable() {
            return new Spec(kinds, severity, armorPierce, ignoreResist, ranged, traits, breaks, extra, weapon, true, transferred, halfUp);
        }

        Spec asTransferred() {
            return new Spec(kinds, severity, armorPierce, ignoreResist, ranged, traits, breaks, extra, weapon, true, true, halfUp);
        }

        /** 攻击方伤害转化：主伤害的一半提升 1 级 */
        public Spec withHalfUp() {
            return new Spec(kinds, severity, armorPierce, ignoreResist, ranged, traits, breaks, extra, weapon, unavoidable, transferred, true);
        }
    }

    /** 一次正在结算的伤害（提供给各接口） */
    public static final class Hit {
        public final LivingEntity victim;
        public final DamageSource source;
        /** 伤害来源（可能为空） */
        public final Entity attacker;
        /** 模组伤害的附加信息（原版伤害为空） */
        public final Spec spec;
        /** 主伤害类型 */
        public final Set<DamageKind> kinds;
        public final EnumSet<Trait> traits = EnumSet.noneOf(Trait.class);
        public final boolean ranged;
        /** 是否是攻击（有来源实体） */
        public final boolean attack;
        /** 武器伤害（-1 = 不是武器伤害） */
        public float weapon = -1f;
        public final List<Part> parts = new ArrayList<>();
        public final List<Break> breaks = new ArrayList<>();

        Hit(LivingEntity victim, DamageSource source, Spec spec, Set<DamageKind> kinds, boolean ranged) {
            this.victim = victim;
            this.source = source;
            this.attacker = source.getEntity();
            this.spec = spec;
            this.kinds = kinds;
            this.ranged = ranged;
            this.attack = source.getEntity() != null;
        }

        public float total() {
            float t = 0;
            for (Part p : parts) t += p.amount;
            return t;
        }

        public boolean has(DamageKind k) {
            for (Part p : parts) if (p.amount > 0 && p.kinds.contains(k)) return true;
            return false;
        }

        public boolean transferred() { return spec != null && spec.transferred; }

        /** 追加一个伤害部分（攻击方能力） */
        public void addPart(float amount, Severity s, DamageKind... ks) {
            parts.add(new Part(amount, s, ks.length == 0 ? kinds : EnumSet.copyOf(Arrays.asList(ks)), false));
        }

        /** 攻击方伤害转化：满足条件的部分各有一半（恶性除外）提升 1 级 */
        public void upgradeHalf(Predicate<Part> which) {
            List<Part> add = new ArrayList<>();
            for (Part p : parts) {
                if (p.unavoidable || p.amount <= 0 || p.severity == Severity.A || !which.test(p)) continue;
                float half = p.amount * 0.5f;
                p.amount -= half;
                add.add(new Part(half, up(p.severity), p.kinds, false));
            }
            for (Part a : add) merge(parts, a);
        }
    }

    private static Severity up(Severity s) { return s == Severity.B ? Severity.L : Severity.A; }

    private static Severity down(Severity s) { return s == Severity.A ? Severity.L : Severity.B; }

    /** 合并到同等级、同类型的已有部分 */
    private static void merge(List<Part> parts, Part a) {
        for (Part p : parts) {
            if (p.severity == a.severity && p.unavoidable == a.unavoidable && p.kinds.equals(a.kinds)) {
                p.amount += a.amount;
                return;
            }
        }
        parts.add(a);
    }

    // ===== 模组伤害入口 =====

    private static final Map<UUID, Spec> PENDING = new HashMap<>();

    /** 以指定类型造成伤害（技能统一走这里） */
    public static boolean deal(LivingEntity target, DamageSource src, float amount, Spec spec) {
        PENDING.put(target.getUUID(), spec);
        try {
            return target.hurt(src, amount);
        } finally {
            PENDING.remove(target.getUUID());
        }
    }

    private record Timed<T>(long tick, T value) {}

    private static final Map<UUID, Timed<List<Break>>> NEXT_BREAKS = new HashMap<>();
    private static final Map<UUID, Timed<Float>> WEAPON = new HashMap<>();

    /** 为本刻对 victim 的下一次伤害追加击破能力（攻击方被动，如太极「挤」） */
    public static void breakNext(LivingEntity victim, Break b) {
        long t = victim.level().getGameTime();
        Timed<List<Break>> cur = NEXT_BREAKS.get(victim.getUUID());
        List<Break> l = cur != null && cur.tick == t ? new ArrayList<>(cur.value) : new ArrayList<>();
        l.add(b);
        NEXT_BREAKS.put(victim.getUUID(), new Timed<>(t, l));
    }

    /** 记录本刻对 victim 这次攻击的武器伤害（攻击公式调用） */
    public static void noteWeapon(LivingEntity victim, float weaponDamage) {
        WEAPON.put(victim.getUUID(), new Timed<>(victim.level().getGameTime(), weaponDamage));
    }

    private static <T> T take(Map<UUID, Timed<T>> m, LivingEntity e) {
        Timed<T> t = m.remove(e.getUUID());
        return t != null && t.tick == e.level().getGameTime() ? t.value : null;
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

    /** 当前伤害是被转移来的（转移者不视为受到了一次攻击） */
    public static boolean isTransferred(LivingEntity e) {
        Spec s = PENDING.get(e.getUUID());
        return s != null && s.transferred;
    }

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

    private static float[] fractions(Hit h) {
        float[] f = new float[3];
        float t = h.total();
        if (t <= 0) return new float[]{1, 0, 0};
        for (Part p : h.parts) f[p.severity.ordinal()] += p.amount / t;
        return f;
    }

    // ===== 结算 =====

    /** 在公式攻击、防御、伤害浮动、各类加值、伤害上限与意志加值（LOW）之后 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onIncoming(LivingIncomingDamageEvent e) {
        LivingEntity v = e.getEntity();
        DamageSource src = e.getSource();
        List<Break> oneShot = take(NEXT_BREAKS, v);
        Float weapon = take(WEAPON, v);
        if (bypass(src) || e.getAmount() <= 0) return;
        Spec spec = PENDING.get(v.getUUID());
        Set<DamageKind> ks = kinds(src, v);
        float amt = e.getAmount();
        Severity sev = spec != null ? spec.severity : Severity.B;

        // 坠落：起算 3 米，每超 2 米 1 点物理钝击严重伤害（原版伤害已扣除起算高度、安全高度与软地形），上限 100，无视护甲，倒地
        if (isFall(src)) {
            amt = Math.min(100, (float) Math.ceil(amt / 2f));
            sev = Severity.L;
            e.addReductionModifier(DamageContainer.Reduction.ARMOR, (c, r) -> 0f);
            e.addReductionModifier(DamageContainer.Reduction.ENCHANTMENTS, (c, r) -> 0f);
            if (amt > 0) v.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 4, false, false));
        } else if (isFallingObject(src)) {
            amt = Math.min(20, amt);
            sev = Severity.L;
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
        boolean ranged = spec != null ? spec.ranged : attack && src.getDirectEntity() != src.getEntity();
        Hit h = new Hit(v, src, spec, ks, ranged);
        boolean unav = spec != null && spec.unavoidable;
        h.parts.add(new Part(amt, sev, ks, unav));
        if (spec != null) {
            for (Part x : spec.extra) h.parts.add(x.copy(unav));
            h.traits.addAll(spec.traits);
            h.breaks.addAll(spec.breaks);
            if (spec.ignoreResist > 0) h.breaks.add(Break.points(Stage.RESIST, spec.ignoreResist));
            h.weapon = spec.weapon;
        } else if (weapon != null) {
            h.weapon = weapon;
        } else if (attack) {
            h.weapon = amt; // 生物的近战 / 弹射物：原版伤害即武器伤害
        }
        if (oneShot != null) h.breaks.addAll(oneShot);

        // 2) 攻击方伤害转化
        if (spec != null && spec.halfUp && !h.parts.isEmpty()) {
            Part main = h.parts.get(0);
            h.upgradeHalf(p -> p == main);
        }
        // 光明对黑暗生物 / 黑暗对光明生物：一半伤害提升 1 级
        if (pr.darkCreature) h.upgradeHalf(p -> p.kinds.contains(DamageKind.HOLY));
        if (pr.lightCreature) h.upgradeHalf(p -> p.kinds.contains(DamageKind.UNHOLY));
        // 2~3) 攻击方能力：特性 / 击破 / 转化 / 附加部分
        if (!h.transferred()) for (var f : ATTACK) f.accept(h, pr);
        // 3) 破甲：依次击破盾牌 / 盔甲 / 天生防御，剩余击破硬度
        int pierce = spec != null ? spec.armorPierce : 0;
        if (pierce > 0) {
            int left = pierce - armorLayers(v);
            if (left > 0) h.breaks.add(Break.points(Stage.HARDNESS, left));
        }

        resolve(h, pr);

        float total = h.total();
        if (total <= 0) {
            e.setCanceled(true);
            return;
        }
        e.setAmount(total);
        if (v instanceof ServerPlayer) SPLIT.put(v.getUUID(), fractions(h));
    }

    /** 4~11 步 + 伤害转移（不含事件操作，可单独调用做预估） */
    public static void resolve(Hit h, Profile pr) {
        // 4) 免疫：部分的全部类型都免疫，或免疫该等级
        h.parts.removeIf(p -> !p.unavoidable && (pr.immuneSeverity.contains(p.severity)
                || (!p.kinds.isEmpty() && pr.immune.containsAll(p.kinds))));
        // 5) 伤害忽略：条件型（全部忽略）→ 数值型
        if (!pr.ignoreRules.isEmpty()) {
            for (Part p : h.parts) {
                if (p.unavoidable || p.amount <= 0 || blocked(h, Stage.IGNORE, p)) continue;
                boolean itemsOff = p.kinds.stream().anyMatch(DamageKind::ignoresItems);
                for (IgnoreRule r : pr.ignoreRules) {
                    if (r.item && itemsOff) continue;
                    if (r.test != null && r.test.test(h)) {
                        p.amount = 0;
                        break;
                    }
                }
            }
        }
        reduce(h, pr, EnumSet.of(Stage.IGNORE));
        // 6) 硬度与伤害抵消（取高）
        reduce(h, pr, EnumSet.of(Stage.HARDNESS, Stage.OFFSET));
        // 7) 能量抗力 / 物理伤害减免
        reduce(h, pr, EnumSet.of(Stage.RESIST, Stage.DR));
        // 8) 伤害吸收
        reduce(h, pr, EnumSet.of(Stage.ABSORB));
        // 9) 阈值
        if (pr.threshold > 0) {
            float avoidable = 0;
            for (Part p : h.parts) if (!p.unavoidable) avoidable += p.amount;
            if (avoidable < pr.threshold) for (Part p : h.parts) if (!p.unavoidable) p.amount = 0;
        }
        // 10) 防御方伤害转化
        convert(h, pr);
        h.parts.removeIf(p -> p.amount <= 0);
        // 11) 最终伤害：易伤 / 暴击等以同一最终伤害为基准分别追加
        finalStage(h, pr);
        // 伤害转移：只针对最终伤害
        if (!h.transferred()) transfer(h);
        h.parts.removeIf(p -> p.amount <= 0);
    }

    /** 整类无视：该阶段对此部分无效 */
    private static boolean blocked(Hit h, Stage s, Part p) {
        for (Break b : h.breaks) {
            if (b.stage() == s && b.whole() && (b.kind() == null || p.kinds.contains(b.kind()))) return true;
        }
        return false;
    }

    /** 防御是否与本次伤害相关（击破对象） */
    private static boolean relevant(Def d, Hit h, DamageKind only) {
        for (Part p : h.parts) {
            if (p.unavoidable || p.amount <= 0) continue;
            for (DamageKind k : p.kinds) {
                if ((only == null || k == only) && d.applies(k, h, p)) return true;
            }
        }
        return false;
    }

    private static void reduce(Hit h, Profile pr, Set<Stage> stages) {
        List<Def> defs = new ArrayList<>();
        for (Def d : pr.defs) if (stages.contains(d.stage) && d.value > 0) defs.add(d);
        if (defs.isEmpty()) return;
        float[] rem = new float[defs.size()];
        for (int i = 0; i < rem.length; i++) rem[i] = defs.get(i).value;

        // 3) 击破：防御方的同类防御从高到低依次击破
        for (Break b : h.breaks) {
            if (!stages.contains(b.stage()) || b.whole() || b.amount() <= 0) continue;
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < defs.size(); i++) {
                Def d = defs.get(i);
                if (d.stage == b.stage() && relevant(d, h, b.kind())) idx.add(i);
            }
            idx.sort((a, c) -> Float.compare(rem[c], rem[a]));
            float left = b.amount();
            for (int i : idx) {
                if (left <= 0) break;
                float t = Math.min(left, rem[i]);
                rem[i] -= t;
                left -= t;
            }
        }

        // 防御优先作用于较低等级的部分
        List<Part> order = new ArrayList<>(h.parts);
        order.sort(Comparator.comparingInt(p -> p.severity.ordinal()));
        for (Part p : order) {
            if (p.unavoidable || p.amount <= 0) continue;
            float best = Float.MAX_VALUE;
            Set<Integer> used = new HashSet<>();
            boolean any = false;
            for (DamageKind k : p.kinds) {
                if (pr.immune.contains(k)) continue; // 免疫的类型视为无限防御
                int bi = -1;
                for (int i = 0; i < defs.size(); i++) {
                    Def d = defs.get(i);
                    if (rem[i] <= 0 || !d.applies(k, h, p) || blocked(h, d.stage, p)) continue;
                    if (bi < 0 || rem[i] > rem[bi]) bi = i;
                }
                if (bi < 0) {
                    best = 0;
                    break;
                }
                any = true;
                best = Math.min(best, rem[bi]);
                used.add(bi);
            }
            if (!any || best <= 0) continue;
            float r = Math.min(best, p.amount);
            p.amount -= r;
            for (int i : used) rem[i] -= r;
        }
    }

    private static void convert(Hit h, Profile pr) {
        if (pr.conversions.isEmpty()) return;
        // 同名转化只生效一次：取点数最多的一条
        Map<String, Conversion> byId = new LinkedHashMap<>();
        for (Conversion c : pr.conversions) {
            Conversion o = byId.get(c.id);
            if (o == null || c.points > o.points) byId.put(c.id, c);
        }
        List<Conversion> list = new ArrayList<>(byId.values());
        list.sort(Comparator.comparingInt(c -> c.layer.ordinal()));
        for (Conversion c : list) {
            if (c.points <= 0 || (c.when != null && !c.when.test(h))) continue;
            float budget = c.points == ALL ? Float.MAX_VALUE : c.points;
            // 以转化前的数量为准，从恶性开始各降 1 级（同一点只降一次）
            List<Part> snapshot = new ArrayList<>(h.parts);
            float[] before = new float[snapshot.size()];
            for (int i = 0; i < before.length; i++) before[i] = snapshot.get(i).amount;
            List<Part> moved = new ArrayList<>();
            for (Severity from : new Severity[]{Severity.A, Severity.L}) {
                for (int i = 0; i < snapshot.size() && budget > 0; i++) {
                    Part p = snapshot.get(i);
                    if (p.severity != from || p.unavoidable || before[i] <= 0) continue;
                    if (c.item && p.kinds.stream().anyMatch(DamageKind::ignoresItems)) continue;
                    float t = Math.min(budget, before[i]);
                    p.amount -= t;
                    budget -= t;
                    moved.add(new Part(t, down(from), p.kinds, false));
                }
            }
            for (Part m : moved) merge(h.parts, m);
        }
    }

    private static void finalStage(Hit h, Profile pr) {
        float f = h.total();
        if (f <= 0) return;
        double frac = 0;
        if (!h.transferred()) {
            for (var m : FINAL) {
                double x = m.applyAsDouble(h);
                if (x > 0) frac += x;
            }
        }
        float[] before = new float[h.parts.size()];
        for (int i = 0; i < before.length; i++) before[i] = h.parts.get(i).amount;
        // 易伤 X：取最大，加在含该类型的最低等级部分
        int plus = 0;
        Part plusTo = null;
        List<Part> order = new ArrayList<>(h.parts);
        order.sort(Comparator.comparingInt(p -> p.severity.ordinal()));
        for (Part p : order) {
            for (DamageKind k : p.kinds) {
                int x = pr.vulnPlus.getOrDefault(k, 0);
                if (x > plus || (x > 0 && x == plus && plusTo != null && p.severity.ordinal() < plusTo.severity.ordinal())) {
                    plus = x;
                    plusTo = p;
                }
            }
        }
        for (int i = 0; i < before.length; i++) {
            Part p = h.parts.get(i);
            float add = (float) (before[i] * frac);
            for (DamageKind k : p.kinds) {
                if (pr.vulnDouble.contains(k)) {
                    add += before[i]; // 易伤：翻倍
                    break;
                }
            }
            p.amount += add;
        }
        if (plusTo != null) plusTo.amount += plus;
    }

    private static void transfer(Hit h) {
        for (var t : TRANSFERS) {
            if (h.total() <= 0) return;
            Share s = t.apply(h);
            if (s == null || s.to() == null || s.to() == h.victim || !s.to().isAlive() || s.points() <= 0) continue;
            float left = s.points();
            List<Part> taken = new ArrayList<>();
            List<Part> order = new ArrayList<>(h.parts);
            order.sort(Comparator.comparingInt(p -> -p.severity.ordinal()));
            for (Part p : order) {
                if (left <= 0) break;
                if (p.unavoidable || p.amount <= 0) continue; // 不可避免的伤害无法转移
                float x = Math.min(left, p.amount);
                p.amount -= x;
                left -= x;
                taken.add(new Part(x, p.severity, p.kinds, true));
            }
            if (taken.isEmpty()) continue;
            Part first = taken.remove(0);
            // 转移者承受不可避免的伤害；来源仍是最初的伤害来源，但没有直接来源（不视为受到攻击）
            DamageSource ds = new DamageSource(h.source.typeHolder(), null, h.source.getEntity());
            Spec spec = new Spec(first.kinds, first.severity, 0, 0, h.ranged).asTransferred().withParts(taken);
            LivingEntity to = s.to();
            to.invulnerableTime = 0;
            deal(to, ds, first.amount, spec);
        }
    }

    /** 破甲依次击破 盾牌 / 盔甲 / 天生防御，剩余才击破硬度：这里以当前护甲值近似前三者 */
    private static int armorLayers(LivingEntity v) {
        if (v instanceof ServerPlayer sp) {
            Defense.Parts x = Defense.parts(sp, null, null, false);
            return x.armor() + x.natural();
        }
        return (int) Math.floor(v.getArmorValue());
    }

    // ===== 显示：当前的减伤关键字（属性面板 / 防御 HUD 悬停） =====

    private static final Map<UUID, DamageKeywordsPayload> LAST_KW = new HashMap<>();

    private static int mask(Collection<? extends Enum<?>> c) {
        int m = 0;
        if (c != null) for (Enum<?> x : c) m |= 1 << x.ordinal();
        return m;
    }

    public static List<DamageKeywordsPayload.Entry> describe(Profile pr) {
        List<DamageKeywordsPayload.Entry> l = new ArrayList<>();
        if (!pr.immune.isEmpty()) l.add(new DamageKeywordsPayload.Entry(DamageKeywordsPayload.IMMUNE, 0, mask(pr.immune), 0, ""));
        if (!pr.immuneSeverity.isEmpty())
            l.add(new DamageKeywordsPayload.Entry(DamageKeywordsPayload.IMMUNE_SEV, 0, 0, mask(pr.immuneSeverity), ""));
        List<Def> defs = new ArrayList<>(pr.defs);
        defs.sort(Comparator.comparingInt((Def d) -> d.stage.ordinal()).thenComparingInt(d -> -d.value));
        for (Def d : defs) {
            if (d.value <= 0) continue;
            int type = switch (d.stage) {
                case IGNORE -> DamageKeywordsPayload.IGNORE;
                case HARDNESS -> DamageKeywordsPayload.HARDNESS;
                case OFFSET -> DamageKeywordsPayload.OFFSET;
                case RESIST -> DamageKeywordsPayload.RESIST;
                case DR -> DamageKeywordsPayload.DR;
                case ABSORB -> DamageKeywordsPayload.ABSORB;
            };
            int extra = d.stage == Stage.DR ? mask(d.weak) : d.stage == Stage.ABSORB ? (d.scope == null ? Scope.ALL : d.scope).ordinal() : 0;
            if (d.item) extra |= DamageKeywordsPayload.ITEM_BIT;
            l.add(new DamageKeywordsPayload.Entry(type, d.value, d.kinds == null ? 0 : mask(d.kinds), extra, d.source));
        }
        for (IgnoreRule r : pr.ignoreRules)
            l.add(new DamageKeywordsPayload.Entry(DamageKeywordsPayload.IGNORE_IF, 0, 0, r.item ? DamageKeywordsPayload.ITEM_BIT : 0, r.source));
        if (pr.threshold > 0) l.add(new DamageKeywordsPayload.Entry(DamageKeywordsPayload.THRESHOLD, pr.threshold, 0, 0, ""));
        Set<String> seen = new HashSet<>();
        for (Conversion c : pr.conversions) {
            if (c.points <= 0 || !seen.add(c.id)) continue;
            int extra = c.layer.ordinal() | (c.item ? DamageKeywordsPayload.ITEM_BIT : 0);
            l.add(new DamageKeywordsPayload.Entry(DamageKeywordsPayload.CONVERT, c.points == ALL ? -1 : c.points, 0, extra, c.id));
        }
        if (!pr.vulnDouble.isEmpty()) l.add(new DamageKeywordsPayload.Entry(DamageKeywordsPayload.VULN_DOUBLE, 0, mask(pr.vulnDouble), 0, ""));
        for (var en : pr.vulnPlus.entrySet())
            l.add(new DamageKeywordsPayload.Entry(DamageKeywordsPayload.VULN, en.getValue(), 1 << en.getKey().ordinal(), 0, ""));
        if (pr.lightCreature) l.add(new DamageKeywordsPayload.Entry(DamageKeywordsPayload.LIGHT, 0, 0, 0, ""));
        if (pr.darkCreature) l.add(new DamageKeywordsPayload.Entry(DamageKeywordsPayload.DARK, 0, 0, 0, ""));
        return l;
    }

    /** 当前减伤关键字变化时同步给本人（与防御 HUD 同频） */
    public static void syncKeywords(ServerPlayer p, boolean force) {
        DamageKeywordsPayload now = new DamageKeywordsPayload(describe(profile(p)));
        if (!force && now.equals(LAST_KW.get(p.getUUID()))) return;
        LAST_KW.put(p.getUUID(), now);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, now);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        LAST_KW.remove(e.getEntity().getUUID());
        SPLIT.remove(e.getEntity().getUUID());
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

    /** 身体防御（玩家：基础 + 全力 + 闪避 + 天生，见 {@link Defense#body}）：措手不及 / 擒抱中失去其中大部分 */
    public static double naturalDefense(LivingEntity m) {
        if (m instanceof net.minecraft.server.level.ServerPlayer sp) return Defense.body(sp);
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
