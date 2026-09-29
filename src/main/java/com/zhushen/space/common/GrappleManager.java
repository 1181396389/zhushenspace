package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.SkillType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.*;
import java.util.function.Function;

/**
 * 擒抱规则（实时化）。1 回合 = 3 秒；判定 = 确定性数值 × 20%~100% 浮动，体积差 / 附加成功在浮动后直接加。
 * <ul>
 *   <li>擒抱判定：力量 + 肉搏（软兵器：白刃）+ 可抓握武器伤害（天生武器；拳套不计，软兵器计入其伤害）</li>
 *   <li>逃脱：敏捷 + 运动。被擒抱方自动选择期望值更高的一种对抗</li>
 *   <li>胜者对败者造成差值的擒抱点数；对同一目标造成的点数上限 = 自身力量 + 敏捷</li>
 *   <li>擒抱中：外部攻击按体积比例随机命中组内成员（攻击者潜行 = 承受减值只打准星目标）、失去天生防御；
 *       不能攻击组外目标 / 使用弓、投掷物；人造武器攻击受 擒抱点数 + 武器体积 的减值</li>
 *   <li>移动：只有「领头者」能移动，速度 × 自身体积/总体积 − 擒抱点数（1 点 = 基础移速 10%），其余成员被拖行</li>
 *   <li>挣脱：潜行时使用擒抱，逐一对抗所有其他成员（均视为有异议）；传送立即脱离；受到击退可对抗挣脱</li>
 * </ul>
 * 原版怪物不参与；其他生物（如后续 T 病毒丧尸）通过 {@link #register} 提供数值。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class GrappleManager {
    private GrappleManager() {}

    public static final long ROUND_MS = 3000;
    public static final double TETHER = 1.8, BREAK_DIST = 8;

    public static final TagKey<Item> GAUNTLET = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "weapons/gauntlet"));
    public static final TagKey<Item> SOFT = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "weapons/soft"));

    /** 擒抱数值 */
    public record Stats(int str, int agi, int brawl, int blade, int athletics, int naturalWeapon, int volume) {}

    private static final Map<EntityType<?>, Function<LivingEntity, Stats>> PROVIDERS = new HashMap<>();

    /** 为非玩家生物注册擒抱数值（例如 T 病毒丧尸） */
    public static void register(EntityType<?> type, Function<LivingEntity, Stats> f) { PROVIDERS.put(type, f); }

    public static Stats stats(LivingEntity e) {
        if (e instanceof Player p) {
            int[] a = p.getData(ModAttachments.PLAYER_ATTRIBUTES).points();
            int[] fb = FeatEffects.attrBonus(p);
            var sk = p.getData(ModAttachments.PLAYER_SKILLS);
            return new Stats(a[AttributeType.STRENGTH.ordinal()] + fb[AttributeType.STRENGTH.ordinal()],
                    a[AttributeType.AGILITY.ordinal()] + fb[AttributeType.AGILITY.ordinal()],
                    sk.get(SkillType.BRAWL.ordinal()), sk.get(SkillType.BLADE.ordinal()), sk.get(SkillType.ATHLETICS.ordinal()),
                    1, FeatEffects.volume(p));
        }
        Function<LivingEntity, Stats> f = PROVIDERS.get(e.getType());
        return f == null ? null : f.apply(e);
    }

    public static boolean canGrapple(LivingEntity e) { return stats(e) != null; }

    // ===== 状态 =====

    static final class Group {
        final LinkedHashSet<UUID> members = new LinkedHashSet<>();
        UUID leader;
        long leaderUntil;
        long lastMoveMs;
        ServerLevel level;
    }

    private static final Map<UUID, Group> BY = new HashMap<>();
    /** 受到的擒抱点数 */
    private static final Map<UUID, Integer> POINTS = new HashMap<>();
    /** 「来源 → 目标」已造成的点数（上限 力量+敏捷） */
    private static final Map<String, Integer> DEALT = new HashMap<>();

    public static boolean grappled(Entity e) { return e != null && BY.containsKey(e.getUUID()); }

    public static int points(Entity e) { return POINTS.getOrDefault(e.getUUID(), 0); }

    /** 需要姿势成分的其他行动的减值（擒抱对抗除外） */
    public static int gesturePenalty(Entity e) { return grappled(e) ? points(e) : 0; }

    /** 需要专注时的专注判定难度（所受最高擒抱点数） */
    public static int focusDifficulty(Entity e) { return grappled(e) ? points(e) : 0; }

    public static boolean sameGroup(Entity a, Entity b) {
        Group g = BY.get(a.getUUID());
        return g != null && g == BY.get(b.getUUID());
    }

    private static List<LivingEntity> living(Group g) {
        List<LivingEntity> r = new ArrayList<>();
        for (UUID u : g.members) {
            Entity e = g.level.getEntity(u);
            if (e instanceof LivingEntity le && le.isAlive()) r.add(le);
        }
        return r;
    }

    // ===== 判定 =====

    private static int weaponBonus(LivingEntity e, Stats s) {
        ItemStack main = e.getMainHandItem();
        if (!main.isEmpty() && main.is(SOFT)) return (int) Math.round(e.getAttributeValue(Attributes.ATTACK_DAMAGE));
        // 手持拳套 / 其他物品时仍可用其他天生武器（牙、另一只手……）
        return s.naturalWeapon;
    }

    private static int grappleBase(LivingEntity e) {
        Stats s = stats(e);
        boolean soft = e.getMainHandItem().is(SOFT);
        return s.str + (soft ? s.blade : s.brawl) + weaponBonus(e, s);
    }

    private static int escapeBase(LivingEntity e) {
        Stats s = stats(e);
        return s.agi + s.athletics;
    }

    private static int sizeBonus(LivingEntity me, LivingEntity other) {
        return Math.max(0, stats(me).volume - stats(other).volume);
    }

    private static float roll(LivingEntity me, LivingEntity other, int base, boolean withWill) {
        float v = base * DamageVariance.roll(me.getRandom()) + sizeBonus(me, other);
        if (withWill && me instanceof ServerPlayer sp) v += WillpowerManager.contestStrike(sp, other);
        return v;
    }

    private static void addPoints(LivingEntity from, LivingEntity to, int amount) {
        if (amount <= 0) return;
        Stats s = stats(from);
        String key = from.getUUID() + ">" + to.getUUID();
        int dealt = DEALT.getOrDefault(key, 0);
        int add = Math.max(0, Math.min(amount, s.str + s.agi - dealt));
        DEALT.put(key, dealt + add);
        POINTS.merge(to.getUUID(), add, Integer::sum);
    }

    private static Group join(LivingEntity a, LivingEntity b) {
        Group ga = BY.get(a.getUUID()), gb = BY.get(b.getUUID());
        Group g = ga != null ? ga : gb != null ? gb : new Group();
        g.level = (ServerLevel) a.level();
        if (g.leader == null) g.leader = a.getUUID();
        if (gb != null && gb != g) for (UUID u : gb.members) { g.members.add(u); BY.put(u, g); }
        g.members.add(a.getUUID()); BY.put(a.getUUID(), g);
        g.members.add(b.getUUID()); BY.put(b.getUUID(), g);
        return g;
    }

    private static void leave(UUID u) {
        Group g = BY.remove(u);
        POINTS.remove(u);
        DEALT.keySet().removeIf(k -> k.startsWith(u + ">") || k.endsWith(">" + u));
        if (g == null) return;
        g.members.remove(u);
        if (Objects.equals(g.leader, u)) g.leader = g.members.isEmpty() ? null : g.members.iterator().next();
        if (g.level != null) {
            Entity e = g.level.getEntity(u);
            if (e instanceof LivingEntity le) clearMods(le);
        }
        if (g.members.size() < 2) for (UUID o : new ArrayList<>(g.members)) leave(o);
    }

    // ===== 主动：技能 =====

    /** 返回 true 表示动作已执行（进入冷却） */
    public static boolean activate(ServerPlayer p) {
        Group g = BY.get(p.getUUID());
        if (g != null && p.isShiftKeyDown()) return breakFree(p, g);
        LivingEntity t = target(p);
        if (t != null && g != null && sameGroup(p, t)) t = null; // 已互相擒抱不能再次发起
        if (t == null) {
            if (g != null) return takeLead(p, g);
            msg(p, "msg.zhushenspace.grapple.no_target");
            return false;
        }
        if (!canGrapple(t)) { msg(p, "msg.zhushenspace.grapple.invalid"); return false; }
        return attempt(p, t);
    }

    private static LivingEntity target(ServerPlayer p) {
        double reach = p.entityInteractionRange();
        Vec3 eye = p.getEyePosition(), look = p.getViewVector(1f), end = eye.add(look.scale(reach));
        AABB box = p.getBoundingBox().expandTowards(look.scale(reach)).inflate(1);
        EntityHitResult hit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(p, eye, end, box,
                e -> e instanceof LivingEntity && e != p && e.isAlive() && !e.isSpectator(), reach * reach);
        return hit != null && hit.getEntity() instanceof LivingEntity le ? le : null;
    }

    private static boolean attempt(ServerPlayer p, LivingEntity t) {
        // 天生武器接触攻击：举盾正面格挡视为未命中
        if (t.isBlocking() && t.getViewVector(1f).dot(p.position().subtract(t.position()).normalize()) > 0.3) {
            msg(p, "msg.zhushenspace.grapple.miss", t.getDisplayName());
            fx(p, t, false);
            return true;
        }
        boolean escape = escapeBase(t) > grappleBase(t);
        float mine = roll(p, t, grappleBase(p), true);
        float theirs = roll(t, p, escape ? escapeBase(t) : grappleBase(t), true);
        int margin = Math.round(mine - theirs);
        if (escape && margin <= 0) {
            msg(p, "msg.zhushenspace.grapple.escaped", t.getDisplayName(), fmt(mine), fmt(theirs));
            if (t instanceof ServerPlayer tp) msg(tp, "msg.zhushenspace.grapple.you_escaped", p.getDisplayName());
            fx(p, t, false);
            return true;
        }
        join(p, t);
        if (margin > 0) addPoints(p, t, margin);
        else addPoints(t, p, -margin);
        msg(p, margin > 0 ? "msg.zhushenspace.grapple.win" : "msg.zhushenspace.grapple.lose",
                t.getDisplayName(), fmt(mine), fmt(theirs), points(margin > 0 ? t : p));
        if (t instanceof ServerPlayer tp) msg(tp, margin > 0 ? "msg.zhushenspace.grapple.grabbed" : "msg.zhushenspace.grapple.reversed",
                p.getDisplayName(), fmt(theirs), fmt(mine), points(margin > 0 ? t : p));
        fx(p, t, true);
        return true;
    }

    private static boolean breakFree(ServerPlayer p, Group g) {
        List<LivingEntity> others = living(g);
        others.removeIf(e -> e == p);
        for (LivingEntity o : others) {
            float mine = roll(p, o, grappleBase(p), true), theirs = roll(o, p, grappleBase(o), true);
            if (mine <= theirs) {
                msg(p, "msg.zhushenspace.grapple.break_fail", o.getDisplayName(), fmt(mine), fmt(theirs));
                return true;
            }
        }
        leave(p.getUUID());
        msg(p, "msg.zhushenspace.grapple.break_ok");
        for (LivingEntity o : others) if (o instanceof ServerPlayer op) msg(op, "msg.zhushenspace.grapple.other_broke", p.getDisplayName());
        return true;
    }

    private static boolean takeLead(ServerPlayer p, Group g) {
        long now = System.currentTimeMillis();
        if (Objects.equals(g.leader, p.getUUID()) && now < g.leaderUntil) return false;
        if (now - g.lastMoveMs < ROUND_MS) { msg(p, "msg.zhushenspace.grapple.moved_this_round"); return false; }
        List<LivingEntity> others = living(g);
        others.removeIf(e -> e == p);
        for (LivingEntity o : others) {
            float mine = roll(p, o, grappleBase(p), true), theirs = roll(o, p, grappleBase(o), true);
            if (mine <= theirs) { msg(p, "msg.zhushenspace.grapple.move_fail", o.getDisplayName()); return true; }
        }
        g.leader = p.getUUID();
        g.leaderUntil = now + ROUND_MS;
        g.lastMoveMs = now;
        msg(p, "msg.zhushenspace.grapple.move_ok");
        return true;
    }

    // ===== 每刻维护 =====

    private static final ResourceLocation SPEED = ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "grapple_speed");
    private static final ResourceLocation NO_DEF = ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "grapple_no_natural_def");
    private static final ResourceLocation NO_JUMP = ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "grapple_no_jump");

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        for (Group g : new HashSet<>(BY.values())) {
            if (g.level == null) continue;
            for (UUID u : new ArrayList<>(g.members)) {
                Entity en = g.level.getEntity(u);
                if (!(en instanceof LivingEntity le) || !le.isAlive()) leave(u);
            }
            if (g.members.size() < 2) continue;
            List<LivingEntity> ms = living(g);
            Entity leadE = g.leader == null ? null : g.level.getEntity(g.leader);
            if (!(leadE instanceof LivingEntity lead)) continue;
            int totalVol = 0;
            for (LivingEntity m : ms) totalVol += stats(m).volume;
            for (LivingEntity m : ms) {
                if (m.distanceTo(lead) > BREAK_DIST) { leave(m.getUUID()); continue; }
                boolean isLead = m == lead;
                double mult = isLead ? (double) stats(m).volume / totalVol - 0.1 * points(m) : 0;
                setMod(m, Attributes.MOVEMENT_SPEED, SPEED, Math.max(0, mult) - 1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
                setMod(m, Attributes.JUMP_STRENGTH, NO_JUMP, isLead ? 0 : -1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
                setMod(m, Attributes.ARMOR, NO_DEF, -naturalDefense(m), AttributeModifier.Operation.ADD_VALUE);
                if (!isLead) {
                    // 拖行：超出绳距时拉向领头者（坠落时仅当领头者有稳固支撑才能拉住）
                    Vec3 d = lead.position().subtract(m.position());
                    if (d.length() > TETHER && (lead.onGround() || lead.isInWater() || !m.onGround() == !lead.onGround())) {
                        Vec3 v = d.normalize().scale(Math.min(0.6, (d.length() - TETHER) * 0.4));
                        m.setDeltaMovement(v.x, Math.max(m.getDeltaMovement().y, v.y), v.z);
                        m.hurtMarked = true;
                        m.fallDistance = Math.min(m.fallDistance, lead.fallDistance);
                    }
                }
            }
            if (g.level.getGameTime() % 10 == 0) {
                for (LivingEntity m : ms) g.level.sendParticles(ParticleTypes.CRIT, m.getX(), m.getY() + m.getBbHeight() * 0.6, m.getZ(), 1, 0.2, 0.2, 0.2, 0);
            }
        }
    }

    /** 天生防御：敏捷护甲 + 巨大身材天生防御 */
    private static double naturalDefense(LivingEntity m) {
        AttributeInstance a = m.getAttribute(Attributes.ARMOR);
        if (a == null) return 0;
        double v = 0;
        for (String id : new String[]{"agility_armor", "feat_giant_natural"}) {
            AttributeModifier mod = a.getModifier(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, id));
            if (mod != null) v += mod.amount();
        }
        return v;
    }

    private static void setMod(LivingEntity e, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr,
                               ResourceLocation id, double amount, AttributeModifier.Operation op) {
        AttributeInstance a = e.getAttribute(attr);
        if (a == null) return;
        AttributeModifier cur = a.getModifier(id);
        if (cur != null && cur.amount() == amount) return;
        a.removeModifier(id);
        if (amount != 0) a.addTransientModifier(new AttributeModifier(id, amount, op));
    }

    private static void clearMods(LivingEntity e) {
        for (var pair : List.of(Map.entry(Attributes.MOVEMENT_SPEED, SPEED), Map.entry(Attributes.JUMP_STRENGTH, NO_JUMP), Map.entry(Attributes.ARMOR, NO_DEF))) {
            AttributeInstance a = e.getAttribute(pair.getKey());
            if (a != null) a.removeModifier(pair.getValue());
        }
    }

    // ===== 事件 =====

    private static boolean redirecting;

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onDamage(LivingIncomingDamageEvent e) {
        if (redirecting) return;
        LivingEntity victim = e.getEntity();
        DamageSource src = e.getSource();
        Entity atk = src.getEntity();
        if (!(atk instanceof LivingEntity attacker)) return;
        // 擒抱中的攻击者
        if (grappled(attacker)) {
            if (!sameGroup(attacker, victim)) { e.setCanceled(true); return; } // 不能对组外目标做出肢体动作
            ItemStack main = attacker.getMainHandItem();
            if (!main.isEmpty() && src.getDirectEntity() == attacker) {
                int pen = points(attacker) + weaponVolume(main);
                e.setAmount(Math.max(0, e.getAmount() - pen));
            }
            return;
        }
        // 外部攻击命中擒抱中的目标：按体积随机分配
        Group g = BY.get(victim.getUUID());
        if (g == null) return;
        List<LivingEntity> ms = living(g);
        if (attacker instanceof Player ap && ap.isShiftKeyDown()) {
            // 主动承受减值：忽略其他成员（减值 = 其体积 + 其擒抱点数）
            int pen = 0;
            for (LivingEntity m : ms) if (m != victim) pen += stats(m).volume + points(m);
            e.setAmount(Math.max(0, e.getAmount() - pen));
            return;
        }
        int total = 0;
        for (LivingEntity m : ms) total += stats(m).volume;
        if (total <= 0) return;
        int r = victim.getRandom().nextInt(total);
        LivingEntity hit = victim;
        for (LivingEntity m : ms) { r -= stats(m).volume; if (r < 0) { hit = m; break; } }
        if (hit != victim) {
            float amt = e.getAmount();
            e.setCanceled(true);
            redirecting = true;
            try {
                hit.invulnerableTime = 0;
                hit.hurt(src, amt);
            } finally {
                redirecting = false;
            }
            if (attacker instanceof ServerPlayer sp) msg(sp, "msg.zhushenspace.grapple.redirect", hit.getDisplayName());
        }
    }

    /** 武器体积调整（后续武器系统提供；目前为 0） */
    public static int weaponVolume(ItemStack stack) { return 0; }

    @SubscribeEvent
    public static void onUseItem(PlayerInteractEvent.RightClickItem e) {
        if (!grappled(e.getEntity())) return;
        ItemStack st = e.getItemStack();
        var cat = CombatFormula.classify(st);
        if (cat == com.zhushen.space.data.WeaponCategory.BOW || cat == com.zhushen.space.data.WeaponCategory.THROWN
                || cat.group == com.zhushen.space.data.WeaponCategory.Group.GUN) {
            e.setCanceled(true);
            if (e.getEntity() instanceof ServerPlayer sp) msg(sp, "msg.zhushenspace.grapple.no_gesture");
        }
    }

    @SubscribeEvent
    public static void onKnockback(LivingKnockBackEvent e) {
        LivingEntity m = e.getEntity();
        Group g = BY.get(m.getUUID());
        if (g == null || m.level().isClientSide) return;
        int dist = Math.max(1, Math.round(e.getStrength() * 4));
        for (LivingEntity o : living(g)) {
            if (o == m) continue;
            float hold = roll(o, m, grappleBase(o), false);
            float esc = roll(m, o, escapeBase(m), false) + dist;
            if (hold > esc && hold > dist) { e.setCanceled(true); return; }
        }
        leave(m.getUUID());
        if (m instanceof ServerPlayer sp) msg(sp, "msg.zhushenspace.grapple.knocked_free");
    }

    @SubscribeEvent
    public static void onTeleport(EntityTeleportEvent e) {
        if (!(e instanceof EntityTeleportEvent.EnderPearl) && !(e instanceof EntityTeleportEvent.ChorusFruit)
                && !(e instanceof EntityTeleportEvent.EnderEntity) && !(e instanceof EntityTeleportEvent.TeleportCommand)) return;
        if (grappled(e.getEntity())) leave(e.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) { if (grappled(e.getEntity())) leave(e.getEntity().getUUID()); }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) { if (grappled(e.getEntity())) leave(e.getEntity().getUUID()); }

    @SubscribeEvent
    public static void onDim(PlayerEvent.PlayerChangedDimensionEvent e) { if (grappled(e.getEntity())) leave(e.getEntity().getUUID()); }

    // ===== 表现 =====

    private static String fmt(float v) { return String.valueOf(Math.round(v)); }

    private static void msg(ServerPlayer p, String key, Object... args) {
        p.displayClientMessage(Component.translatable(key, args), true);
    }

    private static void fx(LivingEntity a, LivingEntity b, boolean success) {
        if (!(a.level() instanceof ServerLevel lv)) return;
        Vec3 mid = a.position().add(b.position()).scale(0.5).add(0, 1, 0);
        lv.sendParticles(success ? ParticleTypes.SWEEP_ATTACK : ParticleTypes.POOF, mid.x, mid.y, mid.z, success ? 2 : 6, 0.3, 0.2, 0.3, 0);
        lv.playSound(null, b.blockPosition(), success ? SoundEvents.PLAYER_ATTACK_STRONG : SoundEvents.PLAYER_ATTACK_NODAMAGE, SoundSource.PLAYERS, 0.8f, success ? 0.7f : 1.2f);
    }
}
