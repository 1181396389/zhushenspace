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
 * - 1DP = 3 点伤害；附加成功在波动后直接加；意志豁免 = 决心+感受+传奇决心，反射豁免 = 敏捷+运动+传奇敏捷，范围豁免 = 反射豁免+范围加值；豁免值从伤害中扣除。
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
                d.researchBits.clone(), d.holdback, d.amplify, availableXp(p),
                PoolEffects.flags(p)));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) sync(p);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) sync(p);
    }

    /**
     * 重置加点与全部购买（建卡、流派、流派技能、技艺及其选项 / 研发、轮盘设置）。
     * refund = true 时退还购买所花的积分 / 支线 / XP。返回退还的积分。
     */
    public static int resetAll(ServerPlayer p, boolean refund) {
        PlayerCurrencyData cur = p.getData(ModAttachments.PLAYER_CURRENCY);
        com.zhushen.space.data.PlayerSchoolData sch = p.getData(ModAttachments.PLAYER_SCHOOLS);
        PlayerArtData d = data(p);
        int score = 0;
        if (refund) {
            for (com.zhushen.space.data.SchoolType st : com.zhushen.space.data.SchoolType.values()) {
                if (!sch.isUnlocked(st)) continue;
                cur.addBranch(st.branchTier(), st.branchCost());
                score += st.scoreCost();
                for (SkillAbility a : SkillAbility.values()) {
                    if (!a.isSchoolAbility() || !sch.isSkillPurchased(st.ordinal(), a.ordinal())) continue;
                    if (st.key().equals(a.gate()) && !isFreeSchoolSkill(a)) {
                        cur.addBranch(ProgressManager.abilityBranchTier(a), ProgressManager.abilityBranchCost(a));
                        score += ProgressManager.abilityScoreCost(a);
                    }
                }
            }
            for (ArtSkill s : ArtSkill.values()) {
                if (!d.owns(s)) continue;
                if (s.branchTier >= 0) cur.addBranch(s.branchTier, s.branchCost);
                score += s.scoreCost;
                // 研发 / 额外选项花费的 XP 随建卡重置（BuildServer.reset 清空 artXp）一并退还
            }
            cur.addScore(score);
        }
        sch.reset();
        p.setData(ModAttachments.PLAYER_ARTS, new PlayerArtData());
        BUFFS.remove(p.getUUID());
        VIGOR.remove(p.getUUID());
        endVigorFx(p);
        armor(p, MOD_OUTER, 0);
        armor(p, MOD_WARD, 0);
        armor(p, MOD_PALM, 0);
        p.removeEffect(MobEffects.INVISIBILITY);
        if (!p.isCreative() && !p.isSpectator()) {
            p.getAbilities().mayfly = false;
            p.getAbilities().flying = false;
            p.onUpdateAbilities();
        }
        EnergyManager.removePool(p, EnergyManager.POOL_NEILI);
        com.zhushen.space.data.PlayerSkillData sd = p.getData(ModAttachments.PLAYER_SKILLS);
        sd.clearGatedSlots();
        for (int b = 0; b < com.zhushen.space.data.PlayerSkillData.BAR_COUNT; b++) {
            int[] bar = sd.bar(b).clone();
            for (int i = 0; i < bar.length; i++)
                if (bar[i] >= 0 && bar[i] < SkillAbility.COUNT && SkillAbility.values()[bar[i]].isArtAbility()) bar[i] = -1;
            sd.setBar(b, bar);
        }
        BuildServer.reset(p);
        ProgressManager.sync(p);
        sync(p);
        return score;
    }

    /**
     * 研发 / 额外选项可用的 XP：建卡系统里尚未分配的 XP（邀请函、管理员发放的 XP）
     * + 旧版本遗留在货币里的 XP（先扣这部分）。
     */
    public static int availableXp(ServerPlayer p) {
        return p.getData(ModAttachments.PLAYER_CURRENCY).xp() + BuildServer.freeXp(p);
    }

    static boolean spendXp(ServerPlayer p, int n) {
        if (n <= 0) return true;
        PlayerCurrencyData cur = p.getData(ModAttachments.PLAYER_CURRENCY);
        if (availableXp(p) < n) return false;
        int legacy = Math.min(cur.xp(), n);
        cur.addXp(-legacy);
        if (n - legacy > 0) BuildServer.spendArtXp(p, n - legacy);
        return true;
    }

    /** 购买流派时赠送的技能（听劲）不退款 */
    private static boolean isFreeSchoolSkill(SkillAbility a) { return a == SkillAbility.TING_JIN; }

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
        if (pl.action() >= 10) { PoolEffects.action(p, pl.action()); return; }
        PlayerArtData d = data(p);
        if (pl.action() == 3) { d.holdback = pl.value() < 0 ? -1 : Math.max(1, Math.min(100, pl.value())); sync(p); return; }
        if (pl.action() == 4) { d.amplify = pl.value() != 0; sync(p); return; }
        if (pl.art() < 0 || pl.art() >= ArtSkill.COUNT) return;
        ArtSkill s = ArtSkill.values()[pl.art()];
        PlayerCurrencyData cur = p.getData(ModAttachments.PLAYER_CURRENCY);
        String err = null;
        switch (pl.action()) {
            case 0 -> {
                if (d.owns(s) || s.innate()) return;
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
                    if (!spendXp(p, s.extraCost)) { err = "msg.zhushenspace.art.lack_xp"; break; }
                }
                d.optionBits[s.ordinal()] |= 1 << pl.value();
                if (first) d.current[s.ordinal()] = pl.value();
            }
            case 2 -> {
                if (!d.owns(s) || pl.value() < 0 || pl.value() >= s.researches.length || d.hasResearch(s, pl.value())) return;
                ArtSkill.Research r = s.researches[pl.value()];
                if (casterLevel(p) < r.minCaster()) { err = "msg.zhushenspace.art.caster_low"; break; }
                if (!spendXp(p, r.xp())) { err = "msg.zhushenspace.art.lack_xp"; break; }
                d.researchBits[s.ordinal()] |= 1 << pl.value();
                p.displayClientMessage(Component.translatable("msg.zhushenspace.art.researched",
                        Component.translatable(s.researchKey(pl.value()))), true);
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
        DamageSource src = ArtDamage.source(null, p, DamageKind.PURE_ENERGY);
        float d = touch ? Defense.touch(t, p) : CombatFormula.defense(t, src);
        return Math.max(0, d - speed - pierce);
    }

    static int willSave(LivingEntity t) {
        if (t instanceof ServerPlayer sp) {
            if (mindImmune(sp)) return 999;
            // 意志豁免（决心 + 感受 + 传奇决心 + 其他）；佛力：抵抗心灵影响 +2DP
            return Defense.roll(sp, Defense.will(sp) + PoolEffects.buddhaSave(sp));
        }
        return 0; // 非玩家生物：意志数据待接入
    }

    static int reflexSave(LivingEntity t, Entity attacker) {
        return reflexSave(t, attacker, false);
    }

    /** 反射豁免；area = 对抗范围效果（倒地 +3） */
    static int reflexSave(LivingEntity t, Entity attacker, boolean area) {
        if (!DamageRules.canReflex(t, attacker)) return 0;
        if (t instanceof ServerPlayer sp) {
            if (!StatusEffects.canReflex(sp)) return 0; // 石化 / 昏迷 / 睡眠 / 冰封 / 无助
            return Defense.roll(sp, Defense.reflex(sp, area)); // 反射豁免（area = 范围豁免）
        }
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

    /** 视线终点（射程内第一块方块或射程末端）：无目标施放时光束 / 特效打向这里 */
    static Vec3 rayEnd(ServerPlayer p, double range) {
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getViewVector(1f).scale(range));
        HitResult block = p.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return block.getType() != HitResult.Type.MISS ? block.getLocation() : end;
    }

    /** 无目标施放：照常消耗与冷却，提示打空 */
    static void whiff(ServerPlayer p) { p.displayClientMessage(Component.translatable("msg.zhushenspace.art.whiff"), true); }

    /** 【锁定】技艺的锁定距离 */
    static double lockRange(ServerPlayer p, ArtSkill s) { return 20; }

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
        if (cost <= 0 || s.pool == FeatEffects.Pool.SPIRIT) return true;
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
        // 远程技艺：注册的对应伤害类型；直接来源为空（不是亲手近战，不触发近战类加成 / 上限）
        DamageRules.deal(t, ArtDamage.source(null, p, spec), amount, spec);
    }

    static DamageRules.Spec spec(PlayerHealthData.Severity sev, int armorPierce, int ignoreResist, boolean ranged, DamageKind... ks) {
        return new DamageRules.Spec(EnumSet.copyOf(List.of(ks)), sev, armorPierce, ignoreResist, ranged);
    }

    /** 标准攻击：(判定 + mod − 防御) × 波动 + 附加成功，截断上限，再留手 */
    /** 本次施放的能量加值（施放开始时结算一次） */
    private static int castBoost;

    static float attackRoll(ServerPlayer p, int check, int mod, float def, int cap, int bonus) {
        float raw = (check + mod + castBoost - def) * roll(p) + bonus;
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

    /** buff 到期的游戏刻（无则 0） */
    static long buffUntil(LivingEntity e, ArtSkill s) {
        Map<ArtSkill, Long> m = BUFFS.get(e.getUUID());
        Long t = m == null ? null : m.get(s);
        return t == null ? 0 : t;
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
        DamageRules.addProvider((e, pr) -> { if (buff(e, ArtSkill.MINOR_WARD)) pr.ignore(SkillAbility.MINOR_WARD.nameKey(), 1); });
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
        if (v != null) endVigorFx(p);
        if (v != null && until != null && until >= p.level().getGameTime()) add += 1 + 9 * v;
        if (add > 0) e.setAmount(e.getAmount() + add);
    }

    // ===== 施放 =====

    /** 施放技艺：返回 false 时不进入冷却 */
    public static boolean cast(ServerPlayer p, SkillAbility a) { return cast(p, a, -1); }

    static boolean cast(ServerPlayer p, SkillAbility a, int chargeTicks) {
        ArtSkill s = ArtSkill.of(a);
        if (s == null || !data(p).owns(s)) return false;
        if (ArtCharge.supports(s) && chargeTicks < 0) return false;
        String err = prereq(p, s);
        if (err != null) { deny(p, err); return false; }
        if (s.mode == ArtSkill.Mode.PICK && data(p).optionBits[s.ordinal()] == 0) { deny(p, "msg.zhushenspace.art.pick_first"); return false; }
        // 不良状态：无法行动 / 失能 / 厌世 / 沉默 / 瘫痪 / 双臂无法使用……
        if (!StatusEffects.canCast(p, isSpell(s))) return false;
        if (s.pool == FeatEffects.Pool.SPIRIT) {
            // 灵力：启动检定 + 灵感疲劳（消耗/3），不直接扣能量
            if (isSpell(s) && !gesture(p)) return false;
            if (!PoolEffects.spiritActivate(p, s.cost)) return s.cost > 0;
        } else {
            if (energy(p, s) < s.cost) { deny(p, "msg.zhushenspace.art.lack_energy"); return false; }
            if (isSpell(s) && s.somatic() && !gesture(p)) return false;
        }
        // 【锁定】：必须先锁定目标；其余技艺无目标时照常施放（打空）
        if (s.lockOn() && target(p, lockRange(p, s)) == null) { deny(p, "msg.zhushenspace.art.need_lock"); return false; }
        // 晕眩 / 欲眠 / 精神束缚 / 剧痛 / 沮丧 / 肢体妨害（姿势）的施法与心灵检定减值
        castBoost = boostFor(p, s) - StatusEffects.castPenalty(p, isSpell(s));
        boolean ok = switch (s) {
            case SPIRIT_SLASH -> ArtBallistics.cast(p, s, castBoost, chargeTicks);
            case SPIRIT_HEAL -> spiritHeal(p, s);
            case MIND_BLAST -> mindBlast(p, s);
            case MIND_SHOCK -> mindShock(p, s);
            case HADOKEN -> ArtBallistics.cast(p, s, castBoost, chargeTicks);
            case BREATH_METHOD -> breathMethod(p, s);
            case YAKSHA_FLIGHT -> yaksha(p, s);
            case MAGIC_BURST -> magicBurst(p, s);
            case MINOR_WARD -> minorWard(p, s);
            case FIVE_ELEMENTS -> fiveElements(p, s);
            case EIGHT_FORMATION -> ArtBallistics.cast(p, s, castBoost, chargeTicks);
            case IGNORE_ME -> ignoreMe(p, s);
            case BIO_LIGHTNING -> bioLightning(p, s);
            case NETHER_VIGOR -> netherVigor(p, s);
            case WIND_SLASH -> ArtBallistics.cast(p, s, castBoost, chargeTicks);
            case BASIC_PALM -> basicPalm(p, s);
            case REVIVE -> revive(p, s);
            case PHOENIX_FIRE -> phoenixFire(p, s);
            case GREAT_FIREBALL -> ArtBallistics.cast(p, s, castBoost, chargeTicks);
            case THUNDER_SWORD -> MagicSpells.thunderSword(p, s);
            case LIGHT -> MagicSpells.light(p, s);
            case ILLUMINATION -> MagicSpells.illumination(p, s);
            case FROST_CLAW -> MagicSpells.frostClaw(p, s);
            case TK_ATTACK -> Telekinesis.attack(p, s);
            case TK_MANIP -> Telekinesis.manipulate(p, s);
        };
        castBoost = 0;
        if (ok && isSpell(s)) { announce(p, s); PoolEffects.onSpellCast(p, s); }
        return ok;
    }

    public static int formationColor(ServerPlayer p) {
        int[] colors = {0xFFFF6D18, 0xFF80D8FF, 0xFFFFE76B, 0xFF9DEB63, 0xFFD9CEFF};
        return colors[Math.max(0, Math.min(4, data(p).current[ArtSkill.EIGHT_FORMATION.ordinal()]))];
    }

    /** 能量加值：道术优先用道力 +3DP；其余按判定属性取适用能量池 +1DP */
    static int boostFor(ServerPlayer p, ArtSkill s) {
        if (s.pool == FeatEffects.Pool.DAO) {
            int dao = PoolEffects.daoBonus(p);
            if (dao > 0) return dao;
        }
        return switch (s) {
            case SPIRIT_SLASH, MIND_BLAST, BIO_LIGHTNING, TK_ATTACK -> PoolEffects.checkBonus(p, AttributeType.RESOLVE, AttributeType.COMPOSURE);
            case HADOKEN, WIND_SLASH -> PoolEffects.checkBonus(p, AttributeType.STRENGTH);
            case MAGIC_BURST, THUNDER_SWORD, FROST_CLAW ->
                    PoolEffects.checkBonus(p, FeatEffects.has(p, s.pool.feat) ? AttributeType.CHARM : AttributeType.INTELLIGENCE);
            case FIVE_ELEMENTS, EIGHT_FORMATION -> PoolEffects.checkBonus(p, AttributeType.CHARM);
            case PHOENIX_FIRE, GREAT_FIREBALL -> PoolEffects.checkBonus(p, AttributeType.PERCEPTION);
            default -> 0;
        };
    }

    // ===== 演出（动漫风 v3：动作 → 出手帧生成视觉特效 → 特效到位时结算） =====

    /** 出手点：右手前方 */
    static Vec3 handPos(ServerPlayer p) {
        Vec3 v = p.getViewVector(1);
        double y = Math.toRadians(p.getYRot());
        Vec3 right = new Vec3(-Math.cos(y), 0, -Math.sin(y));
        return clampFromEye(p, p.getEyePosition().add(v.scale(0.55)).add(right.scale(0.22)).add(0, -0.32, 0));
    }

    /** 额前（精神系）/ 口前（火遁） */
    static Vec3 browPos(ServerPlayer p) { return clampFromEye(p, p.getEyePosition().add(p.getViewVector(1).scale(0.4)).add(0, 0.05, 0)); }

    static Vec3 mouthPos(ServerPlayer p) { return clampFromEye(p, p.getEyePosition().add(p.getViewVector(1).scale(0.45)).add(0, -0.16, 0)); }

    /** 出手点被墙挡住时贴回眼前，光束永远不会从墙后发出 */
    private static Vec3 clampFromEye(ServerPlayer p, Vec3 want) {
        Vec3 eye = p.getEyePosition();
        HitResult hit = p.level().clip(new ClipContext(eye, want, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (hit.getType() == HitResult.Type.MISS) return want;
        return eye.add(hit.getLocation().subtract(eye).scale(0.8));
    }

    /** 光束落点：目标躯干 */
    static Vec3 aim(LivingEntity t) { return t.position().add(0, t.getBbHeight() * 0.6, 0); }

    static void later(ServerPlayer p, int ticks, Runnable r) { ArtBallistics.later(p, ticks, r); }

    static void hitSfx(ServerPlayer p, ArtSkill s, LivingEntity t) {
        ArtFx.soundAt(p, t.position(), com.zhushen.space.sound.ModSounds.artSfx(s.pool).hit().get(), 0.7f, 1f);
    }

    /** 黄泉活力：附身的鬼火（下一次近战消耗时提前熄灭） */
    private static final Map<UUID, com.zhushen.space.entity.art.ArtVfx> VIGOR_FX = new HashMap<>();

    static void endVigorFx(ServerPlayer p) {
        var fx = VIGOR_FX.remove(p.getUUID());
        if (fx != null && !fx.isRemoved()) fx.finish(8);
    }

    /** 息法：每个已习得的选项各有一层光环（外息金色护体环 / 中息白色定身轮 / 内息紫色心灯） */
    static boolean breathMethod(ServerPlayer p, ArtSkill s) {
        if (!pay(p, s, s.cost)) return false;
        if (holdback(p, 1) < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        addBuff(p, s, 10 * 60 * 20);
        int bits = data(p).optionBits[s.ordinal()] & 7;
        ArtFx.anim(p, "art_breath");
        ArtFx.castSfx(p, s.pool, 0.3f);
        later(p, 8, () -> {
            com.zhushen.space.entity.art.ArtVfx.on(p, com.zhushen.space.entity.art.ArtVfx.BREATH, 0xFFFFD35A, bits, 46, p);
            ArtFx.releaseSfx(p, s.pool, 0.25f);
        });
        return true;
    }

    /** 初级防护：六角结界由下而上展开 */
    static boolean minorWard(ServerPlayer p, ArtSkill s) {
        if (!pay(p, s, s.cost)) return false;
        if (holdback(p, 1) < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        addBuff(p, s, 60 * 20);
        ArtFx.anim(p, "art_minor_ward");
        ArtFx.castSfx(p, s.pool, 0.3f);
        later(p, 5, () -> {
            com.zhushen.space.entity.art.ArtVfx.on(p, com.zhushen.space.entity.art.ArtVfx.WARD, 0xFF6F8CFF, 0, 34, p);
            ArtFx.releaseSfx(p, s.pool, 0.3f);
            p.level().playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1f, 0.8f);
        });
        return true;
    }

    /** 基础掌法：马步推掌，巨大的气劲掌印向前推出 */
    static boolean basicPalm(ServerPlayer p, ArtSkill s) {
        if (!pay(p, s, s.cost)) return false;
        if (holdback(p, 1) < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        addBuff(p, s, 60 * 20);
        ArtFx.anim(p, "art_basic_palm");
        ArtFx.castSfx(p, s.pool, 0.3f);
        later(p, 6, () -> {
            Vec3 v = p.getViewVector(1);
            com.zhushen.space.entity.art.ArtVfx.at(p, com.zhushen.space.entity.art.ArtVfx.PALM, 0xFF4DE0C0, 0, 16,
                    clampFromEye(p, p.getEyePosition().add(v.scale(0.9)).add(0, -0.35, 0)), v);
            ArtFx.releaseSfx(p, s.pool, 0.45f);
        });
        return true;
    }

    /** 灵力治疗：6 点治疗，严重优先、然后冲击；潜行 = 自己 */
    static boolean spiritHeal(ServerPlayer p, ArtSkill s) {
        LivingEntity t0 = p.isShiftKeyDown() ? p : target(p, Math.max(2, attr(p, AttributeType.RESOLVE)));
        final LivingEntity t = t0 == null ? p : t0;
        float amt = holdback(p, 6);
        if (amt < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        ArtFx.anim(p, "art_spirit_heal");
        ArtFx.castSfx(p, s.pool, 0.25f);
        later(p, 4, () -> {
            if (!t.isAlive()) return;
            com.zhushen.space.entity.art.ArtVfx.on(p, com.zhushen.space.entity.art.ArtVfx.HEAL, 0xFF7CF2D8, t == p ? 0 : 1, 36, t);
            ArtFx.releaseSfx(p, s.pool, 0.25f);
        });
        later(p, t == p ? 7 : 10, () -> {
            if (!t.isAlive()) return;
            heal(t, (int) amt, false);
            p.level().playSound(null, t.blockPosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 0.8f, 1.6f);
        });
        return true;
    }

    /** 治疗：玩家移除伤势（严重 → 冲击；reviveMode：每点 1 冲击/严重，恶性 3 点一处，恶性优先） */
    static void heal(LivingEntity t, int pts, boolean reviveMode) {
        if (pts <= 0) return;
        if (t instanceof ServerPlayer sp) {
            PlayerHealthData h = sp.getData(ModAttachments.PLAYER_HEALTH);
            int healed = 0;
            if (StatusEffects.bloodLoss(sp)) {
                // 失血过多：冲击 / 严重伤害的治愈难度如同恶性伤害（3 点治疗量 1 处），恶性伤害无法治疗
                pts /= 3;
                reviveMode = false;
            }
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

    /** 精神冲击：远程心灵攻击 20 米，纯能量（研发后可为心灵）。额前「第三只眼」射出精神涟漪 */
    static boolean mindBlast(ServerPlayer p, ArtSkill s) {
        LivingEntity t = target(p, 20);
        Vec3 end = t != null ? aim(t) : rayEnd(p, 20);
        float v = t == null ? 0 : attackRoll(p, mindValue(p), 0, defense(p, t, 0, 0, false), mindValue(p), 0);
        boolean psy = data(p).hasResearch(s, 0);
        if (v < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        ArtFx.anim(p, "art_mind_blast");
        later(p, 3, () -> {
            com.zhushen.space.entity.art.ArtVfx.beam(p, com.zhushen.space.entity.art.ArtVfx.MIND_BEAM,
                    psy ? 0xFFC77DFF : 0xFFE0F4FF, psy ? 1 : 0, 13, browPos(p), t != null ? aim(t) : end);
            ArtFx.releaseSfx(p, s.pool, 0.35f);
        });
        later(p, 6, () -> {
            if (t == null) { whiff(p); return; }
            if (!t.isAlive()) return;
            hit(p, t, v, spec(PlayerHealthData.Severity.L, 0, 0, true, psy ? DamageKind.PSYCHIC : DamageKind.PURE_ENERGY));
            hitSfx(p, s, t);
        });
        return true;
    }

    /** 精神震荡：20 米内目标受 13 点纯能量伤害，意志豁免减免。目标头部炸开精神震波 */
    static boolean mindShock(ServerPlayer p, ArtSkill s) {
        LivingEntity t = target(p, 20);
        if (!pay(p, s, s.cost)) return false;
        float v = holdback(p, 13);
        if (v < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        ArtFx.anim(p, "art_mind_shock");
        ArtFx.castSfx(p, s.pool, 0.3f);
        if (t == null) {
            // 无目标：震波在视线落点处炸开
            Vec3 end = rayEnd(p, 20);
            later(p, 5, () -> {
                com.zhushen.space.entity.art.ArtVfx.at(p, com.zhushen.space.entity.art.ArtVfx.MIND_QUAKE, 0xFFD9CEFF, 0, 18,
                        end.add(0, -0.9, 0), p.getViewVector(1));
                ArtFx.soundAt(p, end, com.zhushen.space.sound.ModSounds.artSfx(s.pool).impact().get(), 0.7f, 1.2f);
                whiff(p);
            });
            return true;
        }
        later(p, 5, () -> {
            if (!t.isAlive()) return;
            com.zhushen.space.entity.art.ArtVfx.on(p, com.zhushen.space.entity.art.ArtVfx.MIND_QUAKE, 0xFFD9CEFF, 0, 18, t);
            ArtFx.soundAt(p, t.position(), com.zhushen.space.sound.ModSounds.artSfx(s.pool).impact().get(), 0.9f, 1.2f);
            hit(p, t, v - willSave(t), spec(PlayerHealthData.Severity.B, 0, 0, true, DamageKind.PURE_ENERGY));
        });
        return true;
    }

    /** 夜叉空行：自身 + 触及范围内准星所指玩家获得飞行 10 分钟（最多 决心 个目标）。背后展开风之羽翼 */
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
        }
        ArtFx.anim(p, "art_yaksha");
        ArtFx.castSfx(p, s.pool, 0.3f);
        later(p, 6, () -> {
            for (ServerPlayer t : ts) {
                if (!t.isAlive()) continue;
                com.zhushen.space.entity.art.ArtVfx.on(p, com.zhushen.space.entity.art.ArtVfx.YAKSHA, 0xFFE9D8FF, 0, 42, t);
                t.level().playSound(null, t.blockPosition(), SoundEvents.ENDER_DRAGON_FLAP, SoundSource.PLAYERS, 0.5f, 1.5f);
            }
            ArtFx.releaseSfx(p, s.pool, 0.3f);
        });
        return true;
    }

    /** 魔能爆：远程法术攻击，距离 = 关键属性 + 神秘学 米，威力 0，物理钝击（研发后力场）。掌前魔法阵射出奥术光枪 */
    static boolean magicBurst(ServerPlayer p, ArtSkill s) {
        double range = Math.max(4, spellCheck(p, s.pool));
        LivingEntity t = target(p, range);
        Vec3 end = t != null ? aim(t) : rayEnd(p, range);
        int check = spellCheck(p, s.pool);
        float v = t == null ? 0 : attackRoll(p, check, 0, defense(p, t, 0, 0, false), spellCap(p, s.pool, 0, 0), 0);
        boolean force = data(p).hasResearch(s, 0);
        if (v < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        ArtFx.anim(p, "art_magic_burst");
        ArtFx.castSfx(p, s.pool, 0.3f);
        later(p, 3, () -> {
            com.zhushen.space.entity.art.ArtVfx.beam(p, com.zhushen.space.entity.art.ArtVfx.ARCANE,
                    force ? 0xFF7C4DFF : 0xFFB388FF, force ? 1 : 0, 15, handPos(p), t != null ? aim(t) : end);
            ArtFx.releaseSfx(p, s.pool, 0.4f);
        });
        later(p, 7, () -> {
            if (t == null) { whiff(p); return; }
            if (!t.isAlive()) return;
            hit(p, t, v, spec(PlayerHealthData.Severity.L, 0, 0, true, force ? DamageKind.FORCE : DamageKind.BLUNT));
            hitSfx(p, s, t);
        });
        return true;
    }

    /** 五行道法：远程法术攻击，距离 = 风度 米，纯能量（关键词仅作表现）。掷出符箓，在目标处显化雷 / 风 / 水 / 火 / 土 */
    static boolean fiveElements(ServerPlayer p, ArtSkill s) {
        double range = Math.max(4, attr(p, AttributeType.CHARM));
        LivingEntity t = target(p, range);
        Vec3 end = t != null ? t.position().add(0, t.getBbHeight() * 0.5, 0) : rayEnd(p, range);
        float v = t == null ? 0 : attackRoll(p, spellCheck(p, s.pool), 0, defense(p, t, 0, 0, false), spellCap(p, s.pool, 0, 0), 0);
        int el = Math.max(0, Math.min(4, data(p).current[s.ordinal()]));
        int[] colors = {0xFFFFE45C, 0xFF9CF25A, 0xFF40C4FF, 0xFFFF7043, 0xFFC9A27E};
        if (v < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        ArtFx.anim(p, "art_five_elements");
        ArtFx.castSfx(p, s.pool, 0.3f);
        later(p, 4, () -> {
            com.zhushen.space.entity.art.ArtVfx.beam(p, com.zhushen.space.entity.art.ArtVfx.ELEMENT, colors[el], el, 24, handPos(p),
                    t != null ? t.position().add(0, t.getBbHeight() * 0.5, 0) : end);
            ArtFx.releaseSfx(p, s.pool, 0.35f);
        });
        later(p, 8, () -> {
            if (t != null && !t.isAlive()) return;
            if (t == null) whiff(p);
            else hit(p, t, v, spec(PlayerHealthData.Severity.L, 0, 0, true, DamageKind.PURE_ENERGY));
            var ev = switch (el) {
                case 0 -> SoundEvents.LIGHTNING_BOLT_IMPACT;
                case 1 -> SoundEvents.BREEZE_WIND_CHARGE_BURST.value();
                case 2 -> SoundEvents.GENERIC_SPLASH;
                case 3 -> SoundEvents.FIRECHARGE_USE;
                default -> SoundEvents.DRIPSTONE_BLOCK_BREAK;
            };
            ArtFx.soundAt(p, t != null ? t.position() : end, ev, 0.8f, 1.1f);
        });
        return true;
    }

    /** 无视我：10 分钟隐身，生物不锁定；互动即打破。「嘘」的手势后身形像信号故障一样碎散消失 */
    static boolean ignoreMe(ServerPlayer p, ArtSkill s) {
        if (!pay(p, s, s.cost)) return false;
        if (holdback(p, 1) < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        ArtFx.anim(p, "art_ignore_me");
        ArtFx.castSfx(p, s.pool, 0.25f);
        later(p, 8, () -> {
            com.zhushen.space.entity.art.ArtVfx.at(p, com.zhushen.space.entity.art.ArtVfx.VANISH, 0xFFE05AFF, 0, 22, p.position(), p.getViewVector(1));
            ArtFx.releaseSfx(p, s.pool, 0.2f);
            addBuff(p, s, 10 * 60 * 20);
            p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 10 * 60 * 20, 0, false, false, true));
            for (Mob m : p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(32))) {
                if (m.getTarget() == p) {
                    Set<UUID> seen = SEEN_THROUGH.get(p.getUUID());
                    if (seen == null || !seen.contains(m.getUUID())) m.setTarget(null);
                }
            }
        });
        return true;
    }

    /** 生物闪电：远程心灵接触攻击 20 米，【高速4】【破甲3】【破魔3】，闪电。指尖迸出分叉电弧 */
    static boolean bioLightning(ServerPlayer p, ArtSkill s) {
        LivingEntity t = target(p, 20);
        if (!pay(p, s, s.cost)) return false;
        float v = t == null ? 0 : attackRoll(p, mindValue(p), 0, defense(p, t, 4, 3, true), mindValue(p), 0);
        if (v < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        Vec3 end = t != null ? aim(t) : rayEnd(p, 20);
        ArtFx.anim(p, "art_bio_lightning");
        ArtFx.castSfx(p, s.pool, 0.3f);
        later(p, 3, () -> {
            if (t != null && !t.isAlive()) return;
            Vec3 to = t != null ? aim(t) : end;
            com.zhushen.space.entity.art.ArtVfx.beam(p, com.zhushen.space.entity.art.ArtVfx.BIO_BOLT, 0xFF9FE8FF, 0, 10, handPos(p), to);
            p.level().playSound(null, net.minecraft.core.BlockPos.containing(to), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 0.4f, 1.8f);
            if (t == null) { whiff(p); return; }
            hit(p, t, v, spec(PlayerHealthData.Severity.L, 3, 3, true, DamageKind.LIGHTNING));
        });
        return true;
    }

    /** 黄泉活力：附于下一次近战（10 秒内）：附加成功 +1；增幅每点妖力 +3DP（上限 传奇风度 次）。拳边缠绕黄泉鬼火 */
    static boolean netherVigor(ServerPlayer p, ArtSkill s) {
        // 增幅与基础能耗一同作为一次能耗支付
        Amplify.Plan plan = Amplify.plan(energy(p, s) - s.cost, legendary(p, AttributeType.CHARM), i -> 1,
                Amplify.mods(p, s), amplify(p));
        if (!pay(p, s, s.cost + plan.cost())) return false;
        int amp = plan.steps();
        VIGOR.put(p.getUUID(), amp);
        VIGOR_UNTIL.put(p.getUUID(), p.level().getGameTime() + 200);
        ArtFx.anim(p, "art_nether_vigor");
        ArtFx.castSfx(p, s.pool, 0.3f);
        endVigorFx(p);
        later(p, 6, () -> {
            if (!VIGOR.containsKey(p.getUUID())) return;
            VIGOR_FX.put(p.getUUID(), com.zhushen.space.entity.art.ArtVfx.on(p, com.zhushen.space.entity.art.ArtVfx.NETHER, 0xFF4DF2E0,
                    Math.min(6, amp), 194, p));
            p.level().playSound(null, p.blockPosition(), SoundEvents.SOUL_ESCAPE.value(), SoundSource.PLAYERS, 1f, 0.8f);
        });
        return true;
    }

    /** 风斩：白刃近战，目标措手不及，+传奇敏捷 DP，【击飞】 */


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

    /** 起死回生：触及，治疗点数 = 肉搏等级（冲击/严重 1 点，恶性 3 点）。天降金色光柱 */
    static boolean revive(ServerPlayer p, ArtSkill s) {
        LivingEntity t0 = p.isShiftKeyDown() ? p : target(p, p.entityInteractionRange());
        final LivingEntity t = t0 == null ? p : t0;
        if (!pay(p, s, s.cost)) return false;
        float pts = holdback(p, skill(p, SkillType.BRAWL));
        if (pts < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        ArtFx.anim(p, "art_revive");
        ArtFx.castSfx(p, s.pool, 0.35f);
        later(p, 7, () -> {
            if (!t.isAlive()) return;
            com.zhushen.space.entity.art.ArtVfx.on(p, com.zhushen.space.entity.art.ArtVfx.REVIVE, 0xFFFFE58A, t == p ? 0 : 1, 44, t);
            ArtFx.releaseSfx(p, s.pool, 0.4f);
            p.level().playSound(null, t.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.8f, 1.5f);
        });
        later(p, 13, () -> {
            if (!t.isAlive()) return;
            heal(t, (int) pts, true);
            p.level().playSound(null, t.blockPosition(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 0.35f, 1.6f);
        });
        return true;
    }

    /** 凤仙火之术：远程法术攻击 20 米，【高速4】，火焰；增幅每点查克拉 威力+2（上限 传奇感知 次）。口中连吐数枚小火球 */
    static boolean phoenixFire(ServerPlayer p, ArtSkill s) {
        LivingEntity t = target(p, 20);
        Vec3 end = t != null ? aim(t) : rayEnd(p, 20);
        Amplify.Plan plan = Amplify.plan(energy(p, s) - s.cost, legendary(p, AttributeType.PERCEPTION), i -> 1,
                Amplify.mods(p, s), amplify(p));
        if (!pay(p, s, s.cost + plan.cost())) return false; // 增幅与基础能耗一同作为一次能耗支付
        int power = 3 + PoolEffects.sageBoost(p, 2) + 2 * plan.steps();
        float v = t == null ? 0 : attackRoll(p, spellCheck(p, s.pool), 0, defense(p, t, 4, 0, false), spellCap(p, s.pool, power, 0), 0);
        if (v < 0) { deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        int balls = Math.min(9, 5 + plan.steps());
        ArtFx.anim(p, "art_phoenix_fire");
        ArtFx.castSfx(p, s.pool, 0.3f);
        later(p, 5, () -> {
            com.zhushen.space.entity.art.ArtVfx.beam(p, com.zhushen.space.entity.art.ArtVfx.PHOENIX, 0xFFFF8A3C, balls, 24, mouthPos(p),
                    t != null ? aim(t) : end);
            p.level().playSound(null, p.blockPosition(), SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1f, 1.3f);
        });
        later(p, 11, () -> {
            if (t == null) { whiff(p); return; }
            if (!t.isAlive()) return;
            hit(p, t, v, spec(PlayerHealthData.Severity.L, 0, 0, true, DamageKind.FIRE));
            hitSfx(p, s, t);
        });
        return true;
    }
    /** 豪火球之术：前方 20 米锥形，威力 4，火焰；每个目标反射豁免 −6DP（−18） */

}
