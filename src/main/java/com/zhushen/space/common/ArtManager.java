package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.ArtSkill;
import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.DamageKind;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.PlayerArtData;
import com.zhushen.space.data.PlayerCurrencyData;
import com.zhushen.space.data.PlayerHealthData;
import com.zhushen.space.data.SkillAbility;
import com.zhushen.space.data.SkillType;
import com.zhushen.space.network.ArtActionPayload;
import com.zhushen.space.network.SyncArtsPayload;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3f;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 技艺：购买 / 选项 / 研发 / 施放 / 持续效果。
 *
 * 通用规则：
 * - 施法判定 = 关键属性 + 神秘学（天生魔力以风度代替智力）；未说明的判定即施法判定。
 * - 伤害上限（未说明时）：持武器 = 武器×1.5 + 关键属性/3 + 神秘学；否则 = 威力×3 + 关键属性/3 + 神秘学。
 * - 1DP = 3 点伤害；附加成功在波动后直接加；意志豁免 = 决心+镇静，反射豁免 = 敏捷+运动，豁免值从伤害中扣除。
 * - 【高速X】目标防御 −X；【破甲X】防御再 −X；【破魔X】无视 X 点能量抗力；接触攻击只计天生防御。
 * - 姿势成分：需一只空手，擒抱 / 措手不及时不可施放；语言成分：聊天栏念出法术名。
 * - 留手：设定 %，施放时 +1D51−25，截断 0~100；0 = 施放失败（能量照扣）；成功数 × % 向下取整。
 * - 增幅：默认关闭，潜行施放 = 用满。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class ArtManager {
    private ArtManager() {}

    // ===== 数据 / 同步 =====

    public static PlayerArtData data(Player p) { return p.getData(ModAttachments.PLAYER_ARTS); }

    public static boolean owns(ServerPlayer p, SkillAbility a) {
        ArtSkill s = ArtSkill.of(a);
        return s != null && data(p).owns(s);
    }

    public static void sync(ServerPlayer p) {
        PlayerArtData d = data(p);
        PacketDistributor.sendToPlayer(p, new SyncArtsPayload(d.owned, d.optionBits.clone(), d.current.clone(),
                d.researchBits.clone(), d.holdback, d.amplify, p.getData(ModAttachments.PLAYER_CURRENCY).xp()));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) sync(p);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) sync(p);
    }

    /** 施法者等级（D1 C2 B3 A4 S5 SSS6 九S7，取最高施法等级）：目前体质专长均为 D 级 */
    public static int casterLevel(ServerPlayer p) { return 1; }

    public static boolean hasPool(ServerPlayer p, ArtSkill s) {
        return p.getData(ModAttachments.PLAYER_ENERGY).getPool(s.pool.id) != null;
    }

    /** 额外前置（波动拳：肉搏 ≥ 3 + 含肉搏的流派/称号 + 内力池） */
    public static String prereq(ServerPlayer p, ArtSkill s) {
        if (!hasPool(p, s)) return "msg.zhushenspace.art.no_pool";
        if (s == ArtSkill.HADOKEN) {
            if (skill(p, SkillType.BRAWL) < 3) return "msg.zhushenspace.art.hadoken_prereq";
            if (!p.getData(ModAttachments.PLAYER_SCHOOLS).isUnlocked(com.zhushen.space.data.SchoolType.values()[0]))
                return "msg.zhushenspace.art.hadoken_prereq";
        }
        return null;
    }

    public static void handle(ServerPlayer p, ArtActionPayload pl) {
        PlayerArtData d = data(p);
        if (pl.action() == 3) { d.holdback = pl.value() < 0 ? -1 : Math.max(1, Math.min(100, pl.value())); sync(p); return; }
        if (pl.action() == 4) { d.amplify = pl.value() != 0; sync(p); return; }
        if (pl.art() < 0 || pl.art() >= ArtSkill.COUNT) return;
        ArtSkill s = ArtSkill.values()[pl.art()];
        PlayerCurrencyData cur = p.getData(ModAttachments.PLAYER_CURRENCY);
        String err = null;
        switch (pl.action()) {
            case 0 -> {
                if (d.owns(s)) return;
                err = prereq(p, s);
                if (err == null) {
                    if (s.branchTier >= 0 && cur.branch(s.branchTier) < s.branchCost || cur.score() < s.scoreCost) {
                        err = "commands.zhushenspace.school.lack_currency";
                    } else {
                        if (s.branchTier >= 0) cur.addBranch(s.branchTier, -s.branchCost);
                        cur.addScore(-s.scoreCost);
                        d.owned |= 1L << s.ordinal();
                        if (s.mode == ArtSkill.Mode.CYCLE) d.optionBits[s.ordinal()] = (1 << s.options.length) - 1;
                        p.displayClientMessage(Component.translatable("msg.zhushenspace.art.bought",
                                Component.translatable(s.ability.nameKey())), true);
                    }
                }
            }
            case 1 -> {
                if (!d.owns(s) || pl.value() < 0 || pl.value() >= s.options.length) return;
                if (d.hasOption(s, pl.value())) { d.current[s.ordinal()] = pl.value(); break; }
                if (s.mode != ArtSkill.Mode.PICK) return;
                boolean first = d.optionBits[s.ordinal()] == 0;
                if (!first) {
                    if (s.extraCost < 0) { err = "msg.zhushenspace.art.fixed"; break; }
                    if (cur.xp() < s.extraCost) { err = "msg.zhushenspace.art.lack_xp"; break; }
                    cur.addXp(-s.extraCost);
                }
                d.optionBits[s.ordinal()] |= 1 << pl.value();
                if (first) d.current[s.ordinal()] = pl.value();
            }
            case 2 -> {
                if (!d.owns(s) || pl.value() < 0 || pl.value() >= s.researches.length || d.hasResearch(s, pl.value())) return;
                ArtSkill.Research r = s.researches[pl.value()];
                if (casterLevel(p) < r.minCaster()) { err = "msg.zhushenspace.art.caster_low"; break; }
                if (cur.xp() < r.xp()) { err = "msg.zhushenspace.art.lack_xp"; break; }
                cur.addXp(-r.xp());
                d.researchBits[s.ordinal()] |= 1 << pl.value();
            }
            case 5 -> {
                if (!d.owns(s) || s.options.length == 0) return;
                int n = s.options.length, c = d.current[s.ordinal()];
                for (int i = 1; i <= n; i++) {
                    int k = Math.floorMod(c + (pl.value() < 0 ? -i : i), n);
                    if (d.hasOption(s, k)) { d.current[s.ordinal()] = k; break; }
                }
            }
            default -> { return; }
        }
        if (err != null) p.displayClientMessage(Component.translatable(err), true);
        ProgressManager.sync(p);
        sync(p);
    }

    // ===== 数值 =====

    static int attr(ServerPlayer p, AttributeType t) {
        return p.getData(ModAttachments.PLAYER_ATTRIBUTES).points()[t.ordinal()] + FeatEffects.attrBonus(p)[t.ordinal()];
    }

    static int skill(ServerPlayer p, SkillType t) { return p.getData(ModAttachments.PLAYER_SKILLS).get(t.ordinal()); }

    /** 传奇属性数量（属性达到 5 视为 1 点传奇） */
    static int legendary(ServerPlayer p, AttributeType t) { return Math.max(0, attr(p, t) - 4); }

    /** 各体系关键属性：魔法（天生魔力 → 风度）、道术风度、佛法决心、查克拉感知 */
    static int keyAttr(ServerPlayer p, FeatEffects.Pool pool) {
        return switch (pool) {
            case MAGIC -> FeatEffects.has(p, pool.feat) ? attr(p, AttributeType.CHARM) : attr(p, AttributeType.INTELLIGENCE);
            case DAO, YOKAI -> attr(p, AttributeType.CHARM);
            case BUDDHA -> attr(p, AttributeType.RESOLVE);
            case CHAKRA -> attr(p, AttributeType.PERCEPTION);
            default -> Math.max(attr(p, AttributeType.RESOLVE), attr(p, AttributeType.COMPOSURE));
        };
    }

    static int spellCheck(ServerPlayer p, FeatEffects.Pool pool) { return keyAttr(p, pool) + skill(p, SkillType.OCCULTISM); }

    /** 未说明时的伤害上限 */
    static int spellCap(ServerPlayer p, FeatEffects.Pool pool, int power, float weapon) {
        int k = keyAttr(p, pool) / 3 + skill(p, SkillType.OCCULTISM);
        return weapon > 0 ? Math.round(weapon * 1.5f) + k : power * 3 + k;
    }

    static int mindValue(ServerPlayer p) { return attr(p, AttributeType.RESOLVE) + attr(p, AttributeType.COMPOSURE); }

    /** 手持武器伤害（不含力量加成） */
    static float heldWeapon(ServerPlayer p) {
        if (p.getMainHandItem().isEmpty()) return 0;
        return CombatFormula.weaponDamage(p, (float) p.getAttributeValue(Attributes.ATTACK_DAMAGE));
    }

    static float roll(ServerPlayer p) { return DamageVariance.roll(p.getRandom()); }

    /** 留手：返回 -1 = 失败；否则缩放后的值 */
    static float holdback(ServerPlayer p, float v) {
        int hb = data(p).holdback;
        if (hb < 0) return v;
        int pct = Math.max(0, Math.min(100, hb + p.getRandom().nextInt(51) - 25));
        if (pct == 0) return -1;
        return (float) Math.floor(v * pct / 100f);
    }

    static boolean amplify(ServerPlayer p) { return p.isShiftKeyDown() || data(p).amplify; }

    /** 目标防御（高速 / 破甲 / 接触） */
    static float defense(ServerPlayer p, LivingEntity t, int speed, int pierce, boolean touch) {
        DamageSource src = p.damageSources().indirectMagic(p, p);
        float d = touch ? (float) (DamageRules.isFlatFooted(t, p) ? 0 : DamageRules.naturalDefense(t))
                : CombatFormula.defense(t, src);
        return Math.max(0, d - speed - pierce);
    }

    static int willSave(LivingEntity t) {
        if (t instanceof ServerPlayer sp) return Math.round(mindValue(sp) * DamageVariance.roll(sp.getRandom()));
        return 0; // 非玩家生物：意志数据待接入
    }

    static int reflexSave(LivingEntity t, Entity attacker) {
        if (!DamageRules.canReflex(t, attacker)) return 0;
        if (t instanceof ServerPlayer sp)
            return Math.round((attr(sp, AttributeType.AGILITY) + skill(sp, SkillType.ATHLETICS)) * DamageVariance.roll(sp.getRandom()));
        var st = GrappleManager.stats(t);
        return st == null ? 0 : Math.round((st.agi() + st.athletics()) * DamageVariance.roll(t.getRandom()));
    }

    // ===== 目标 =====

    static LivingEntity target(ServerPlayer p, double range) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getViewVector(1f);
        Vec3 end = eye.add(look.scale(range));
        HitResult block = p.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (block.getType() != HitResult.Type.MISS) end = block.getLocation();
        AABB box = p.getBoundingBox().expandTowards(look.scale(range)).inflate(1.5);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(p.level(), p, eye, end, box,
                e -> e instanceof LivingEntity le && le.isAlive() && !e.isSpectator() && e != p, 0.3f);
        return hit != null ? (LivingEntity) hit.getEntity() : null;
    }

    static void deny(ServerPlayer p, String key) { p.displayClientMessage(Component.translatable(key), true); }

    static void beam(ServerPlayer p, Vec3 to, ParticleOptions part) {
        if (!(p.level() instanceof ServerLevel sl)) return;
        Vec3 from = p.getEyePosition().add(0, -0.3, 0);
        Vec3 d = to.subtract(from);
        int n = (int) Math.max(4, d.length() * 3);
        for (int i = 0; i <= n; i++) {
            Vec3 v = from.add(d.scale(i / (double) n));
            sl.sendParticles(part, v.x, v.y, v.z, 1, 0.02, 0.02, 0.02, 0);
        }
    }

    static DustParticleOptions dust(int rgb) {
        return new DustParticleOptions(new Vector3f(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f), 1.2f);
    }

    /** 语言成分：聊天栏念出法术名 */
    static void announce(ServerPlayer p, ArtSkill s) {
        Component msg = Component.translatable("msg.zhushenspace.art.chant", p.getDisplayName(),
                Component.translatable(s.ability.nameKey()));
        for (ServerPlayer o : p.serverLevel().players()) if (o.distanceToSqr(p) < 48 * 48) o.sendSystemMessage(msg);
    }

    /** 姿势成分 */
    static boolean gesture(ServerPlayer p) {
        if (!p.getMainHandItem().isEmpty() && !p.getOffhandItem().isEmpty()) { deny(p, "msg.zhushenspace.art.need_hand"); return false; }
        if (GrappleManager.grappled(p) || DamageRules.isFlatFooted(p, null)) { deny(p, "msg.zhushenspace.art.gesture_blocked"); return false; }
        return true;
    }

    static boolean isSpell(ArtSkill s) {
        return switch (s.pool) { case MAGIC, DAO, BUDDHA, CHAKRA -> true; default -> false; };
    }

    static boolean pay(ServerPlayer p, ArtSkill s, double cost) {
        if (cost <= 0) return true;
        if (!EnergyManager.consume(p, s.pool.id, cost)) { deny(p, "msg.zhushenspace.art.lack_energy"); return false; }
        return true;
    }

    static double energy(ServerPlayer p, ArtSkill s) {
        var pool = p.getData(ModAttachments.PLAYER_ENERGY).getPool(s.pool.id);
        return pool == null ? 0 : pool.current;
    }

    static void hit(ServerPlayer p, LivingEntity t, float amount, DamageRules.Spec spec) {
        if (amount <= 0) {
            p.displayClientMessage(Component.translatable("msg.zhushenspace.art.miss"), true);
            return;
        }
        DamageRules.deal(t, p.damageSources().indirectMagic(p, p), amount, spec);
    }

    static DamageRules.Spec spec(PlayerHealthData.Severity sev, int armorPierce, int ignoreResist, boolean ranged, DamageKind... ks) {
        return new DamageRules.Spec(EnumSet.copyOf(List.of(ks)), sev, armorPierce, ignoreResist, ranged);
    }

    /** 标准攻击：(判定 + mod − 防御) × 波动 + 附加成功，截断上限，再留手 */
    static float attackRoll(ServerPlayer p, int check, int mod, float def, int cap, int bonus) {
        float raw = (check + mod - def) * roll(p) + bonus;
        float v = Math.max(0, Math.min(cap, raw));
        return holdback(p, v);
    }

    // ===== 持续效果 =====

    private static final Map<UUID, Map<ArtSkill, Long>> BUFFS = new HashMap<>();
    private static final Map<UUID, Integer> VIGOR = new HashMap<>();
    private static final Map<UUID, Long> VIGOR_UNTIL = new HashMap<>();
    /** 无视我：已看破的生物（本场景免疫） */
    private static final Map<UUID, Set<UUID>> SEEN_THROUGH = new HashMap<>();

    static final ResourceLocation MOD_OUTER = rl("art_breath_outer"), MOD_WARD = rl("art_minor_ward"), MOD_PALM = rl("art_basic_palm");

    static ResourceLocation rl(String s) { return ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, s); }

    public static boolean buff(LivingEntity e, ArtSkill s) {
        Map<ArtSkill, Long> m = BUFFS.get(e.getUUID());
        Long t = m == null ? null : m.get(s);
        return t != null && t > e.level().getGameTime();
    }

    static void addBuff(LivingEntity e, ArtSkill s, int ticks) {
        BUFFS.computeIfAbsent(e.getUUID(), k -> new HashMap<>()).put(s, e.level().getGameTime() + ticks);
    }

    static void armor(LivingEntity e, ResourceLocation id, double v) {
        AttributeInstance a = e.getAttribute(Attributes.ARMOR);
        if (a == null) return;
        a.removeModifier(id);
        if (v != 0) a.addTransientModifier(new AttributeModifier(id, v, AttributeModifier.Operation.ADD_VALUE));
    }

    /** 息法·内息：免疫心灵影响（供心灵类效果查询） */
    public static boolean mindImmune(LivingEntity e) {
        return e instanceof ServerPlayer p && buff(p, ArtSkill.BREATH_METHOD) && data(p).hasOption(ArtSkill.BREATH_METHOD, 2);
    }

    static {
        DamageRules.FLAT_IMMUNE = e -> e instanceof ServerPlayer p && buff(p, ArtSkill.BREATH_METHOD)
                && data(p).hasOption(ArtSkill.BREATH_METHOD, 1);
        // 初级防护：伤害忽略 1（不叠加，取最高）
        DamageRules.addProvider((e, pr) -> { if (buff(e, ArtSkill.MINOR_WARD)) pr.allIgnore = Math.max(pr.allIgnore, 1); });
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % 10 != 0) return;
        for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) {
            armor(p, MOD_OUTER, buff(p, ArtSkill.BREATH_METHOD) && data(p).hasOption(ArtSkill.BREATH_METHOD, 0) ? 3 : 0);
            armor(p, MOD_WARD, buff(p, ArtSkill.MINOR_WARD) ? 6 : 0);
            armor(p, MOD_PALM, buff(p, ArtSkill.BASIC_PALM) ? 6 : 0);
            Map<ArtSkill, Long> m = BUFFS.get(p.getUUID());
            // 夜叉空行结束：收回飞行
            if (m != null && m.containsKey(ArtSkill.YAKSHA_FLIGHT) && !buff(p, ArtSkill.YAKSHA_FLIGHT)) {
                m.remove(ArtSkill.YAKSHA_FLIGHT);
                if (!p.isCreative() && !p.isSpectator()) {
                    p.getAbilities().mayfly = false;
                    p.getAbilities().flying = false;
                    p.onUpdateAbilities();
                }
            }
            if (m != null && m.containsKey(ArtSkill.IGNORE_ME) && !buff(p, ArtSkill.IGNORE_ME)) breakIgnore(p);
        }
    }

    static void breakIgnore(ServerPlayer p) {
        Map<ArtSkill, Long> m = BUFFS.get(p.getUUID());
        if (m == null || m.remove(ArtSkill.IGNORE_ME) == null) return;
        p.removeEffect(MobEffects.INVISIBILITY);
        deny(p, "msg.zhushenspace.art.ignore_broken");
    }

    /** 无视我：生物无法锁定（已看破者除外） */
    @SubscribeEvent
    public static void onTarget(LivingChangeTargetEvent e) {
        if (e.getNewAboutToBeSetTarget() instanceof ServerPlayer p && buff(p, ArtSkill.IGNORE_ME)) {
            Set<UUID> seen = SEEN_THROUGH.get(p.getUUID());
            if (seen == null || !seen.contains(e.getEntity().getUUID())) e.setCanceled(true);
        }
    }

    /** 与对象互动（攻击 / 右键生物）打破无视我，被互动者本场景免疫 */
    @SubscribeEvent
    public static void onAttack(AttackEntityEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && buff(p, ArtSkill.IGNORE_ME)) {
            SEEN_THROUGH.computeIfAbsent(p.getUUID(), k -> new HashSet<>()).add(e.getTarget().getUUID());
            breakIgnore(p);
        }
    }

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract e) {
        if (e.getEntity() instanceof ServerPlayer p && buff(p, ArtSkill.IGNORE_ME)) {
            SEEN_THROUGH.computeIfAbsent(p.getUUID(), k -> new HashSet<>()).add(e.getTarget().getUUID());
            breakIgnore(p);
        }
    }

    /** 近战加成：基础掌法（肉搏 +3DP = +9）/ 黄泉活力（附加成功 +1 与增幅） */
    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onMelee(LivingIncomingDamageEvent e) {
        if (!(e.getSource().getEntity() instanceof ServerPlayer p) || e.getSource().getDirectEntity() != p) return;
        if (DamageRules.hasPending(e.getEntity())) return;
        float add = 0;
        if (buff(p, ArtSkill.BASIC_PALM) && p.getMainHandItem().isEmpty()) add += 9;
        Integer v = VIGOR.remove(p.getUUID());
        Long until = VIGOR_UNTIL.remove(p.getUUID());
        if (v != null && until != null && until >= p.level().getGameTime()) add += 1 + 9 * v;
        if (add > 0) e.setAmount(e.getAmount() + add);
    }

    // ===== 施放 =====

    /** 施放技艺：返回 false 时不进入冷却 */
    public static boolean cast(ServerPlayer p, SkillAbility a) {
        ArtSkill s = ArtSkill.of(a);
        if (s == null || !data(p).owns(s)) return false;
        String err = prereq(p, s);
        if (err != null) { deny(p, err); return false; }
        if (s.mode == ArtSkill.Mode.PICK && data(p).optionBits[s.ordinal()] == 0) { deny(p, "msg.zhushenspace.art.pick_first"); return false; }
        if (energy(p, s) < s.cost) { deny(p, "msg.zhushenspace.art.lack_energy"); return false; }
        if (isSpell(s) && !gesture(p)) return false;
        boolean ok = switch (s) {
            case SPIRIT_SLASH -> spiritSlash(p, s);
            case SPIRIT_HEAL -> spiritHeal(p, s);
            case MIND_BLAST -> mindBlast(p, s);
            case MIND_SHOCK -> mindShock(p, s);
            case HADOKEN -> hadoken(p, s);
            case BREATH_METHOD -> selfBuff(p, s, 10 * 60 * 20, ParticleTypes.END_ROD);
            case YAKSHA_FLIGHT -> yaksha(p, s);
            case MAGIC_BURST -> magicBurst(p, s);
            case MINOR_WARD -> selfBuff(p, s, 60 * 20, ParticleTypes.ENCHANT);
            case FIVE_ELEMENTS -> fiveElements(p, s);
            case EIGHT_FORMATION -> eightFormation(p, s);
            case IGNORE_ME -> ignoreMe(p, s);
            case BIO_LIGHTNING -> bioLightning(p, s);
            case NETHER_VIGOR -> netherVigor(p, s);
            case WIND_SLASH -> windSlash(p, s);
            case BASIC_PALM -> selfBuff(p, s, 60 * 20, ParticleTypes.CLOUD);
            case REVIVE -> revive(p, s);
            case PHOENIX_FIRE -> phoenixFire(p, s);
            case GREAT_FIREBALL -> greatFireball(p, s);
        };
        if (ok && isSpell(s)) announce(p, s);
        return ok;
    }

    static boolean selfBuff(ServerPlayer p, ArtSkill s, int ticks, ParticleOptions part) {
        if (!pay(p, s, s.cost)) return false;
        if (holdback(p, 1) < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        addBuff(p, s, ticks);
        p.serverLevel().sendParticles(part, p.getX(), p.getY() + 1, p.getZ(), 30, 0.5, 0.8, 0.5, 0.05);
        p.level().playSound(null, p.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 0.6f, 1.4f);
        return true;
    }

    /** 灵斩：以持握武器作为心灵检定的武器伤害，距离 = 决心 米 */
    static boolean spiritSlash(ServerPlayer p, ArtSkill s) {
        LivingEntity t = target(p, Math.max(2, attr(p, AttributeType.RESOLVE)));
        if (t == null) { deny(p, "msg.zhushenspace.art.no_target"); return false; }
        float w = heldWeapon(p);
        int cap = mindValue(p) + Math.round(w);
        float v = attackRoll(p, mindValue(p) + Math.round(w), 0, defense(p, t, 0, 0, false), cap, 0);
        beam(p, t.getEyePosition(), dust(0x9FE8FF));
        p.level().playSound(null, t.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1f, 1.5f);
        if (v < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        hit(p, t, v, spec(PlayerHealthData.Severity.B, 0, 0, false, DamageKind.PURE_ENERGY));
        return true;
    }

    /** 灵力治疗：6 点治疗，严重优先、然后冲击；潜行 = 自己 */
    static boolean spiritHeal(ServerPlayer p, ArtSkill s) {
        LivingEntity t = p.isShiftKeyDown() ? p : target(p, Math.max(2, attr(p, AttributeType.RESOLVE)));
        if (t == null) t = p;
        float amt = holdback(p, 6);
        if (amt < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        heal(t, (int) amt, false);
        p.serverLevel().sendParticles(ParticleTypes.HEART, t.getX(), t.getY() + 1.2, t.getZ(), 6, 0.4, 0.4, 0.4, 0);
        return true;
    }

    /** 治疗：玩家移除伤势（严重 → 冲击；reviveMode：每点 1 冲击/严重，恶性 3 点一处，恶性优先） */
    static void heal(LivingEntity t, int pts, boolean reviveMode) {
        if (pts <= 0) return;
        if (t instanceof ServerPlayer sp) {
            PlayerHealthData h = sp.getData(ModAttachments.PLAYER_HEALTH);
            int healed = 0;
            if (reviveMode) {
                int a = h.healSeverity(PlayerHealthData.Severity.A, pts / 3);
                pts -= a * 3; healed += a;
            }
            int l = h.healSeverity(PlayerHealthData.Severity.L, pts); pts -= l; healed += l;
            int b = h.healSeverity(PlayerHealthData.Severity.B, pts); healed += b;
            HealthManager.afterHeal(sp, healed);
        } else {
            t.heal(pts);
        }
    }

    /** 精神冲击：远程心灵攻击 20 米，纯能量（研发后可为心灵） */
    static boolean mindBlast(ServerPlayer p, ArtSkill s) {
        LivingEntity t = target(p, 20);
        if (t == null) { deny(p, "msg.zhushenspace.art.no_target"); return false; }
        float v = attackRoll(p, mindValue(p), 0, defense(p, t, 0, 0, false), mindValue(p), 0);
        boolean psy = data(p).hasResearch(s, 0);
        beam(p, t.getEyePosition(), psy ? dust(0xC77DFF) : dust(0xE0F4FF));
        if (v < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        hit(p, t, v, spec(PlayerHealthData.Severity.L, 0, 0, true, psy ? DamageKind.PSYCHIC : DamageKind.PURE_ENERGY));
        return true;
    }

    /** 精神震荡：20 米内目标受 13 点纯能量伤害，意志豁免减免 */
    static boolean mindShock(ServerPlayer p, ArtSkill s) {
        LivingEntity t = target(p, 20);
        if (t == null) { deny(p, "msg.zhushenspace.art.no_target"); return false; }
        if (!pay(p, s, s.cost)) return false;
        float v = holdback(p, 13);
        p.serverLevel().sendParticles(ParticleTypes.SONIC_BOOM, t.getX(), t.getY() + 1, t.getZ(), 1, 0, 0, 0, 0);
        if (v < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        hit(p, t, v - willSave(t), spec(PlayerHealthData.Severity.B, 0, 0, true, DamageKind.PURE_ENERGY));
        return true;
    }

    /** 波动拳：远程肉搏攻击 50 米，力量 + 肉搏 + 天生武器 1，【高速8】 */
    static boolean hadoken(ServerPlayer p, ArtSkill s) {
        LivingEntity t = target(p, 50);
        if (t == null) { deny(p, "msg.zhushenspace.art.no_target"); return false; }
        if (!pay(p, s, s.cost)) return false;
        int check = attr(p, AttributeType.STRENGTH) + skill(p, SkillType.BRAWL) + 1;
        float v = attackRoll(p, check, 0, defense(p, t, 8, 0, false), check, 0);
        beam(p, t.getEyePosition(), dust(0x4FC3FF));
        p.level().playSound(null, p.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 0.8f, 1.6f);
        if (v < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        hit(p, t, v, spec(PlayerHealthData.Severity.B, 0, 0, true, DamageKind.BLUNT));
        return true;
    }

    /** 夜叉空行：自身 + 触及范围内准星所指玩家获得飞行 10 分钟（最多 决心 个目标） */
    static boolean yaksha(ServerPlayer p, ArtSkill s) {
        if (!pay(p, s, s.cost)) return false;
        if (holdback(p, 1) < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        int max = Math.max(1, attr(p, AttributeType.RESOLVE));
        List<ServerPlayer> ts = new java.util.ArrayList<>();
        ts.add(p);
        LivingEntity look = target(p, 4);
        if (look instanceof ServerPlayer o && ts.size() < max) ts.add(o);
        for (ServerPlayer t : ts) {
            addBuff(t, s, 10 * 60 * 20);
            t.getAbilities().mayfly = true;
            t.onUpdateAbilities();
            t.serverLevel().sendParticles(ParticleTypes.CHERRY_LEAVES, t.getX(), t.getY() + 1, t.getZ(), 20, 0.5, 0.5, 0.5, 0.02);
        }
        return true;
    }

    /** 魔能爆：远程法术攻击，距离 = 关键属性 + 神秘学 米，威力 0，物理钝击（研发后力场） */
    static boolean magicBurst(ServerPlayer p, ArtSkill s) {
        LivingEntity t = target(p, Math.max(4, spellCheck(p, s.pool)));
        if (t == null) { deny(p, "msg.zhushenspace.art.no_target"); return false; }
        int check = spellCheck(p, s.pool);
        float v = attackRoll(p, check, 0, defense(p, t, 0, 0, false), spellCap(p, s.pool, 0, 0), 0);
        boolean force = data(p).hasResearch(s, 0);
        beam(p, t.getEyePosition(), dust(force ? 0x7C4DFF : 0xB388FF));
        if (v < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        hit(p, t, v, spec(PlayerHealthData.Severity.L, 0, 0, true, force ? DamageKind.FORCE : DamageKind.BLUNT));
        return true;
    }

    /** 五行道法：远程法术攻击，距离 = 风度 米，纯能量（关键词仅作表现） */
    static boolean fiveElements(ServerPlayer p, ArtSkill s) {
        LivingEntity t = target(p, Math.max(4, attr(p, AttributeType.CHARM)));
        if (t == null) { deny(p, "msg.zhushenspace.art.no_target"); return false; }
        float v = attackRoll(p, spellCheck(p, s.pool), 0, defense(p, t, 0, 0, false), spellCap(p, s.pool, 0, 0), 0);
        int[] colors = {0xFFF176, 0xB2FF59, 0x40C4FF, 0xFF7043, 0xBCAAA4};
        beam(p, t.getEyePosition(), dust(colors[Math.min(4, data(p).current[s.ordinal()])]));
        if (v < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        hit(p, t, v, spec(PlayerHealthData.Severity.L, 0, 0, true, DamageKind.PURE_ENERGY));
        return true;
    }

    /** 八阵图：远程法术攻击 20 米，【高速8】，元素由轮盘 / 选项切换 */
    static boolean eightFormation(ServerPlayer p, ArtSkill s) {
        LivingEntity t = target(p, 20);
        if (t == null) { deny(p, "msg.zhushenspace.art.no_target"); return false; }
        float v = attackRoll(p, spellCheck(p, s.pool), 0, defense(p, t, 8, 0, false), spellCap(p, s.pool, 3, 0), 0);
        DamageKind[] ks = {DamageKind.FIRE, DamageKind.COLD, DamageKind.LIGHTNING, DamageKind.ACID, DamageKind.PURE_ENERGY};
        int[] colors = {0xFF6D00, 0x80D8FF, 0xFFFF00, 0x76FF03, 0xFFFFFF};
        int c = Math.min(4, data(p).current[s.ordinal()]);
        beam(p, t.getEyePosition(), dust(colors[c]));
        if (v < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        hit(p, t, v, spec(PlayerHealthData.Severity.L, 0, 0, true, ks[c]));
        return true;
    }

    /** 无视我：10 分钟隐身，生物不锁定；互动即打破 */
    static boolean ignoreMe(ServerPlayer p, ArtSkill s) {
        if (!pay(p, s, s.cost)) return false;
        if (holdback(p, 1) < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        addBuff(p, s, 10 * 60 * 20);
        p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 10 * 60 * 20, 0, false, false, true));
        for (Mob m : p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(32))) {
            if (m.getTarget() == p) {
                Set<UUID> seen = SEEN_THROUGH.get(p.getUUID());
                if (seen == null || !seen.contains(m.getUUID())) m.setTarget(null);
            }
        }
        return true;
    }

    /** 生物闪电：远程心灵接触攻击 20 米，【高速4】【破甲3】【破魔3】，闪电 */
    static boolean bioLightning(ServerPlayer p, ArtSkill s) {
        LivingEntity t = target(p, 20);
        if (t == null) { deny(p, "msg.zhushenspace.art.no_target"); return false; }
        if (!pay(p, s, s.cost)) return false;
        float v = attackRoll(p, mindValue(p), 0, defense(p, t, 4, 3, true), mindValue(p), 0);
        beam(p, t.getEyePosition(), ParticleTypes.ELECTRIC_SPARK);
        p.level().playSound(null, t.blockPosition(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 0.4f, 1.8f);
        if (v < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        hit(p, t, v, spec(PlayerHealthData.Severity.L, 3, 3, true, DamageKind.LIGHTNING));
        return true;
    }

    /** 黄泉活力：附于下一次近战（10 秒内）：附加成功 +1；增幅每点妖力 +3DP（上限 传奇风度 次） */
    static boolean netherVigor(ServerPlayer p, ArtSkill s) {
        if (!pay(p, s, s.cost)) return false;
        int amp = 0;
        if (amplify(p)) {
            int max = legendary(p, AttributeType.CHARM);
            while (amp < max && EnergyManager.consume(p, s.pool.id, 1)) amp++;
        }
        VIGOR.put(p.getUUID(), amp);
        VIGOR_UNTIL.put(p.getUUID(), p.level().getGameTime() + 200);
        p.serverLevel().sendParticles(ParticleTypes.SOUL_FIRE_FLAME, p.getX(), p.getY() + 1, p.getZ(), 20, 0.4, 0.6, 0.4, 0.02);
        return true;
    }

    /** 风斩：白刃近战，目标措手不及，+传奇敏捷 DP，【击飞】 */
    static boolean windSlash(ServerPlayer p, ArtSkill s) {
        LivingEntity t = target(p, p.entityInteractionRange());
        if (t == null) { deny(p, "msg.zhushenspace.art.no_target"); return false; }
        if (!pay(p, s, s.cost)) return false;
        float w = heldWeapon(p);
        int check = attr(p, AttributeType.STRENGTH) + skill(p, SkillType.BLADE) + Math.round(w);
        float def = defense(p, t, 0, 0, false);
        def = Math.max(0, def - (float) DamageRules.naturalDefense(t)); // 措手不及：失去天生防御
        int bonus = 3 * legendary(p, AttributeType.AGILITY);
        float raw = (check - def) * roll(p) + bonus;
        float v = holdback(p, Math.max(0, raw));
        p.serverLevel().sendParticles(ParticleTypes.SWEEP_ATTACK, t.getX(), t.getY() + 1, t.getZ(), 3, 0.3, 0.3, 0.3, 0);
        p.level().playSound(null, t.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1f, 0.8f);
        if (v < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        hit(p, t, v, spec(PlayerHealthData.Severity.B, 0, 0, false, DamageKind.SLASH));
        knockUp(p, t, v);
        return true;
    }

    /**
     * 【击飞】：距离（米）= 伤害 − max(体型调整+5, 耐力, 力量)；
     * 目标反射豁免 < 距离 → 倒地。
     */
    public static void knockUp(Entity src, LivingEntity t, float damage) {
        int resist;
        if (t instanceof ServerPlayer sp) {
            resist = Math.max(5, Math.max(attr(sp, AttributeType.ENDURANCE), attr(sp, AttributeType.STRENGTH)));
        } else {
            var st = GrappleManager.stats(t);
            int vol = st == null ? 0 : st.volume();
            resist = Math.max(vol + 5, st == null ? 0 : st.str());
        }
        float dist = damage - resist;
        if (dist <= 0) return;
        Vec3 dir = t.position().subtract(src.position()).multiply(1, 0, 1).normalize();
        double speed = Math.min(4.0, 0.35 * Math.sqrt(dist));
        t.push(dir.x * speed, 0.25 + 0.05 * Math.min(dist, 10), dir.z * speed);
        t.hurtMarked = true;
        if (reflexSave(t, src) < dist) {
            t.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 4, false, false)); // 倒地
        }
    }

    /** 起死回生：触及，治疗点数 = 肉搏等级（冲击/严重 1 点，恶性 3 点） */
    static boolean revive(ServerPlayer p, ArtSkill s) {
        LivingEntity t = p.isShiftKeyDown() ? p : target(p, p.entityInteractionRange());
        if (t == null) t = p;
        if (!pay(p, s, s.cost)) return false;
        float pts = holdback(p, skill(p, SkillType.BRAWL));
        if (pts < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        heal(t, (int) pts, true);
        p.serverLevel().sendParticles(ParticleTypes.TOTEM_OF_UNDYING, t.getX(), t.getY() + 1, t.getZ(), 30, 0.4, 0.6, 0.4, 0.2);
        return true;
    }

    /** 凤仙火之术：远程法术攻击 20 米，【高速4】，火焰；增幅每点查克拉 威力+2（上限 传奇感知 次） */
    static boolean phoenixFire(ServerPlayer p, ArtSkill s) {
        LivingEntity t = target(p, 20);
        if (t == null) { deny(p, "msg.zhushenspace.art.no_target"); return false; }
        if (!pay(p, s, s.cost)) return false;
        int power = 3;
        if (amplify(p)) {
            int max = legendary(p, AttributeType.PERCEPTION), n = 0;
            while (n < max && EnergyManager.consume(p, s.pool.id, 1)) n++;
            power += 2 * n;
        }
        float v = attackRoll(p, spellCheck(p, s.pool), 0, defense(p, t, 4, 0, false), spellCap(p, s.pool, power, 0), 0);
        beam(p, t.getEyePosition(), ParticleTypes.FLAME);
        p.level().playSound(null, p.blockPosition(), SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1f, 1.3f);
        if (v < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        hit(p, t, v, spec(PlayerHealthData.Severity.L, 0, 0, true, DamageKind.FIRE));
        return true;
    }

    /** 豪火球之术：前方 20 米锥形，威力 4，火焰；每个目标反射豁免 −6DP（−18） */
    static boolean greatFireball(ServerPlayer p, ArtSkill s) {
        if (!pay(p, s, s.cost)) return false;
        int cap = spellCap(p, s.pool, 4, 0);
        float dmg = holdback(p, Math.max(0, Math.min(cap, spellCheck(p, s.pool) * roll(p))));
        Vec3 eye = p.getEyePosition(), look = p.getViewVector(1f);
        ServerLevel sl = p.serverLevel();
        for (int i = 1; i <= 20; i++) {
            Vec3 c = eye.add(look.scale(i));
            double r = i * 0.45;
            sl.sendParticles(ParticleTypes.FLAME, c.x, c.y, c.z, 6 + i, r * 0.5, r * 0.5, r * 0.5, 0.02);
        }
        p.level().playSound(null, p.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 1.5f, 0.6f);
        if (dmg < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        for (LivingEntity t : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(20),
                e -> e != p && e.isAlive())) {
            Vec3 to = t.getBoundingBox().getCenter().subtract(eye);
            double d = to.length();
            if (d > 20 || d < 0.01) continue;
            if (to.normalize().dot(look) < Math.cos(Math.toRadians(30))) continue;
            float v = dmg - Math.max(0, reflexSave(t, p) - 18);
            if (v > 0) {
                DamageRules.deal(t, p.damageSources().indirectMagic(p, p), v,
                        spec(PlayerHealthData.Severity.L, 0, 0, true, DamageKind.FIRE));
                t.igniteForSeconds(3);
            }
        }
        return true;
    }
}
