package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.ArtSkill;
import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.DamageKind;
import com.zhushen.space.data.FeatType;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.PlayerArtData;
import com.zhushen.space.data.PlayerHealthData;
import com.zhushen.space.data.SkillType;
import com.zhushen.space.data.WeaponCategory;
import com.zhushen.space.entity.art.ArtVfx;
import com.zhushen.space.network.TkStatePayload;
import com.zhushen.space.network.TkStrikePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 念动力（特异本质，念动力天赋专长）。
 * <ul>
 *   <li>池 = 决心 + 沉着；每天恢复 3 倍总值，平均到每小时（每小时超过 6 点时平均到每 10 分钟）。1 小时 = 1000 tick。</li>
 *   <li>有效力量 = 传奇决心 × 2，有效敏捷 = 传奇沉着 × 2（传奇 = 属性 − 4）；作为属性参与检定时同样可能因高属性获得传奇值。</li>
 *   <li>有效范围 = (传奇决心 + 传奇沉着，至少 1) × 5 米。</li>
 *   <li>能量加值：运动 / 肉搏 / 白刃检定花 1 点，加值 = 有效力量（见 {@link PoolEffects#skillBonus}）。</li>
 *   <li>念动力场（轮盘开关）：被攻击时花 1 点，防御 + 有效敏捷；攻击附带的【破魔X】使其 −X。</li>
 *   <li>念动力攻击：1 点 · 有效范围 · 一个目标 · 远程心灵攻击（心灵检定），物理严重伤害，无需姿势。</li>
 *   <li>念动力操控：1 点 · 持续 决心 轮（1 轮 = 3 秒）：隔空操作门 / 拉杆 / 容器；隔空取物（跟随准星）；
 *       悬浮武器（数量 ≤ 有效敏捷，以有效力量攻击）；推开 / 托起生物（有效力量对抗）。</li>
 * </ul>
 * 加值类型：来自念动力的加值只计算一次（能量加值与其他能量池的加值不叠加）。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class Telekinesis {
    private Telekinesis() {}

    public static final String POOL = "telekinesis";
    public static final int COLOR = 0xFFFF9A3C;
    /** 1 轮 = 3 秒 */
    static final int ROUND = 60;
    /** 悬浮武器飞行时间（tick） */
    static final int FLIGHT = 5;
    /** 每柄悬浮武器两次出击的间隔（tick） */
    static final int WEAPON_CD = 20;
    /** 原版方块交互距离（远于此距离只能操作方块本身，不能破坏 / 放置） */
    static final double VANILLA_REACH = 5.0;

    private static final ResourceLocation REACH = ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "tk_reach");
    private static final ResourceLocation CARRY = ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "tk_carry");

    // ===== 数值 =====

    static int leg(int v) { return Math.max(0, v - 4); }

    static int attr(ServerPlayer p, AttributeType t) { return ArtManager.attr(p, t); }

    /** 有效力量 = 传奇决心 × 2 */
    public static int str(ServerPlayer p) { return 2 * leg(attr(p, AttributeType.RESOLVE)); }

    /** 有效敏捷 = 传奇沉着 × 2 */
    public static int agi(ServerPlayer p) { return 2 * leg(attr(p, AttributeType.COMPOSURE)); }

    /** 有效力量作为属性参与检定（含其自身的传奇值） */
    public static int strCheck(ServerPlayer p) { int v = str(p); return v + leg(v); }

    /** 有效范围（米） */
    public static double range(ServerPlayer p) {
        return Math.max(1, leg(attr(p, AttributeType.RESOLVE)) + leg(attr(p, AttributeType.COMPOSURE))) * 5.0;
    }

    /** 可用念动力能量加值的技能：运动 / 肉搏 / 白刃 */
    public static boolean applies(SkillType s) { return s == SkillType.ATHLETICS || s == SkillType.BRAWL || s == SkillType.BLADE; }

    static boolean hasPool(ServerPlayer p) { return p.getData(ModAttachments.PLAYER_ENERGY).getPool(POOL) != null; }

    static double cur(ServerPlayer p) {
        var pool = p.getData(ModAttachments.PLAYER_ENERGY).getPool(POOL);
        return pool == null ? 0 : pool.current;
    }

    // ===== 天生技艺 =====

    /** 拥有念动力池 = 习得念动力攻击 / 念动力操控；失去池则一并失去 */
    public static void syncInnate(ServerPlayer p) {
        PlayerArtData d = ArtManager.data(p);
        boolean has = hasPool(p);
        long before = d.owned;
        for (ArtSkill s : ArtSkill.values()) {
            if (!s.innate()) continue;
            long bit = 1L << s.ordinal();
            if (has) d.owned |= bit; else d.owned &= ~bit;
        }
        if (!has) {
            if (d.tkField) d.tkField = false;
            end(p, false);
        }
        if (d.owned != before) ArtManager.sync(p);
    }

    // ===== 恢复 =====

    private static void tickRecovery(ServerPlayer p) {
        var data = p.getData(ModAttachments.PLAYER_ENERGY);
        var pool = data.getPool(POOL);
        if (pool == null || pool.current >= pool.max || TaiChiManager.isPoolSealed(p)) return;
        double perHour = pool.max * 3 / 24.0;
        boolean fine = perHour > 6;
        int interval = fine ? 167 : 1000; // 10 分钟 ≈ 167 tick / 1 小时 = 1000 tick
        if (p.tickCount <= 0 || p.tickCount % interval != 0) return;
        data.restore(POOL, fine ? perHour / 6 : perHour);
        EnergyManager.sync(p);
    }

    // ===== 念动力场 =====

    /** 被攻击时：开启念动力场且有能量时花 1 点，返回力场防御（有效敏捷 − 破魔） */
    public static int forceField(ServerPlayer p, int breakMagic) {
        if (!ArtManager.data(p).tkField) return 0;
        int v = agi(p);
        if (v <= 0 || cur(p) < 1 || !EnergyManager.consume(p, POOL, 1)) return 0;
        if (p.level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.ENCHANTED_HIT, p.getX(), p.getY() + 1.0, p.getZ(), 10, 0.45, 0.6, 0.45, 0.05);
        }
        p.level().playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_HIT, SoundSource.PLAYERS, 0.8f, 0.6f);
        return Math.max(0, v - breakMagic);
    }

    // ===== 念动力攻击 =====

    static boolean attack(ServerPlayer p, ArtSkill s) {
        double r = range(p);
        LivingEntity t = ArtManager.target(p, r);
        int check = ArtManager.mindValue(p);
        int cap = check + str(p);
        float v = t == null ? 0 : ArtManager.attackRoll(p, check, 0, ArtManager.defense(p, t, 0, 0, false), cap, 0);
        if (v < 0) { ArtManager.deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        if (!ArtManager.pay(p, s, s.cost)) return false;
        PlayerHealthData.Severity sev = FeatEffects.has(p, FeatType.TELEKINESIS_TALENT)
                ? PlayerHealthData.Severity.L : PlayerHealthData.Severity.B;
        Vec3 end = t != null ? ArtManager.aim(t) : ArtManager.rayEnd(p, r);
        ArtFx.anim(p, "art_tk_attack");
        ArtFx.castSfx(p, s.pool, 0.3f);
        ArtManager.later(p, 3, () -> {
            if (t != null && t.isAlive()) ArtVfx.on(p, ArtVfx.TK_CRUSH, COLOR, 0, 18, t);
            else ArtVfx.at(p, ArtVfx.TK_CRUSH, COLOR, 1, 18, end, p.getViewVector(1));
            ArtFx.releaseSfx(p, s.pool, 0.4f);
        });
        ArtManager.later(p, 8, () -> {
            if (t == null) { ArtManager.whiff(p); return; }
            if (!t.isAlive()) return;
            ArtManager.hit(p, t, v, ArtManager.spec(sev, 0, 0, true, DamageKind.BLUNT));
            ArtManager.hitSfx(p, s, t);
            p.level().playSound(null, t.blockPosition(), SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.35f, 1.7f);
            Vec3 k = t.position().subtract(p.position());
            if (k.lengthSqr() > 1e-4) {
                k = k.normalize().scale(0.25);
                t.push(k.x, 0.12, k.z);
                t.hurtMarked = true;
            }
        });
        return true;
    }

    // ===== 念动力操控 =====

    static final class State {
        long until;
        int[] slots = new int[0];
        long[] ready = new long[0];
        int itemId = -1;
        double holdDist;
        boolean carrying;
        int liftId = -1;
        /** 托起后的抬升量（0 → LIFT_UP，逐 tick 缓升） */
        double liftUp;
        ArtVfx blades, grip;
        long lastSync;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    public static boolean active(ServerPlayer p) {
        State st = STATES.get(p.getUUID());
        return st != null && p.level().getGameTime() < st.until;
    }

    static boolean manipulate(ServerPlayer p, ArtSkill s) {
        if (!ArtManager.pay(p, s, s.cost)) return false;
        int rounds = Math.max(1, attr(p, AttributeType.RESOLVE));
        int ticks = rounds * ROUND;
        State st = STATES.computeIfAbsent(p.getUUID(), k -> new State());
        st.until = p.level().getGameTime() + ticks;
        pickWeapons(p, st);
        applyReach(p);
        ArtFx.anim(p, "art_tk_manip");
        ArtFx.castSfx(p, s.pool, 0.3f);
        ArtManager.later(p, 5, () -> {
            ArtVfx.on(p, ArtVfx.TK_AURA, COLOR, 0, 36, p);
            ArtFx.releaseSfx(p, s.pool, 0.3f);
            p.level().playSound(null, p.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.5f, 1.6f);
        });
        refreshBlades(p, st, ticks);
        p.displayClientMessage(Component.translatable("msg.zhushenspace.tk.manip_on", rounds * 3, st.slots.length,
                String.format("%.0f", range(p))), true);
        sync(p, st);
        return true;
    }

    private static void refreshBlades(ServerPlayer p, State st, int life) {
        if (st.blades != null && !st.blades.isRemoved()) st.blades.discard();
        st.blades = st.slots.length > 0 ? ArtVfx.on(p, ArtVfx.TK_BLADES, COLOR, st.slots.length, life + 10, p) : null;
    }

    /** 悬浮武器：快捷栏中（主手所选格除外）的近战武器，数量 ≤ 有效敏捷 */
    private static void pickWeapons(ServerPlayer p, State st) {
        int n = agi(p);
        List<Integer> l = new ArrayList<>();
        Inventory inv = p.getInventory();
        for (int i = 0; i < 9 && l.size() < n; i++) {
            if (i == inv.selected) continue;
            if (isWeapon(inv.getItem(i))) l.add(i);
        }
        st.slots = l.stream().mapToInt(Integer::intValue).toArray();
        st.ready = new long[st.slots.length];
    }

    static boolean isWeapon(ItemStack st) {
        if (st.isEmpty() || com.zhushen.space.compat.TaczCompat.gunCategory(st) != null) return false;
        if (st.getItem() instanceof SwordItem || st.getItem() instanceof AxeItem
                || st.getItem() instanceof TridentItem || st.getItem() instanceof MaceItem) return true;
        com.zhushen.space.data.MeleeWeapon mw = com.zhushen.space.data.MeleeWeapon.of(st);
        if (mw != null) return mw.category.group == WeaponCategory.Group.BLADE && !mw.hidden(); // 基础冷兵器（拳套、暗器除外）
        WeaponCategory c = CombatFormula.classify(st);
        return c == WeaponCategory.LONGSWORD || c == WeaponCategory.GREATSWORD || c == WeaponCategory.RAPIER || c == WeaponCategory.FAN;
    }

    /** 武器伤害 = 1（天生）+ 主手攻击伤害修饰 */
    static float weaponDamage(ItemStack st) {
        double[] sum = {1};
        st.forEachModifier(EquipmentSlot.MAINHAND, (attr, mod) -> {
            if (attr.value() == Attributes.ATTACK_DAMAGE.value() && mod.operation() == AttributeModifier.Operation.ADD_VALUE)
                sum[0] += mod.amount();
        });
        return (float) Math.max(1, sum[0]);
    }

    static DamageKind weaponKind(ItemStack st) {
        if (st.getItem() instanceof TridentItem) return DamageKind.PIERCE;
        if (st.getItem() instanceof MaceItem) return DamageKind.BLUNT;
        com.zhushen.space.data.MeleeWeapon mw = com.zhushen.space.data.MeleeWeapon.of(st);
        if (mw != null) return mw.kindFor(st);
        WeaponCategory c = CombatFormula.classify(st);
        if (c == WeaponCategory.RAPIER) return DamageKind.PIERCE;
        return DamageKind.SLASH;
    }

    private static void applyReach(ServerPlayer p) {
        AttributeInstance inst = p.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
        if (inst == null) return;
        inst.removeModifier(REACH);
        double add = Math.max(0, range(p) - inst.getBaseValue());
        if (add > 0) inst.addTransientModifier(new AttributeModifier(REACH, add, AttributeModifier.Operation.ADD_VALUE));
    }

    private static void removeReach(ServerPlayer p) {
        AttributeInstance inst = p.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
        if (inst != null) inst.removeModifier(REACH);
    }

    private static void setCarry(ServerPlayer p, boolean on) {
        AttributeInstance inst = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (inst == null) return;
        boolean has = inst.getModifier(CARRY) != null;
        if (on == has) return;
        if (on) inst.addTransientModifier(new AttributeModifier(CARRY, -0.5, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        else inst.removeModifier(CARRY);
    }

    /** 操控结束：放下物品与生物，悬浮武器归位 */
    static void end(ServerPlayer p, boolean msg) {
        State st = STATES.remove(p.getUUID());
        if (st == null) return;
        releaseItem(p, st);
        releaseLift(p, st);
        if (st.blades != null && !st.blades.isRemoved()) st.blades.finish(8);
        removeReach(p);
        setCarry(p, false);
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(p, new TkStatePayload(p.getId(), 0, List.of(), -1));
        if (msg) p.displayClientMessage(Component.translatable("msg.zhushenspace.tk.manip_end"), true);
    }

    private static void sync(ServerPlayer p, State st) {
        long now = p.level().getGameTime();
        st.lastSync = now;
        List<ItemStack> ws = new ArrayList<>();
        for (int s : st.slots) ws.add(p.getInventory().getItem(s).copy());
        int held = st.itemId >= 0 ? st.itemId : st.liftId;
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(p,
                new TkStatePayload(p.getId(), (int) Math.max(1, st.until - now), ws, held));
    }

    // ===== 操作（客户端发来） =====

    /** 托起的生物悬在准星点上方的高度 */
    static final double LIFT_UP = 1.2;
    /** 滚轮一格推远 / 拉近的距离 */
    static final double DIST_STEP = 0.75;

    public static void handle(ServerPlayer p, int action, boolean sneak) {
        if (!active(p) || StatusEffects.incapacitated(p)) return;
        State st = STATES.get(p.getUUID());
        boolean holding = st.itemId >= 0 || st.liftId >= 0;
        switch (action) {
            case 0 -> use(p, st, sneak);
            case 1 -> { if (holding) fling(p, st); else strike(p, st); } // 托着东西时左键 = 扔出去
            case 2, 3 -> {                                                  // 滚轮：推远 / 拉近
                if (!holding) return;
                st.holdDist = Mth.clamp(st.holdDist + (action == 2 ? DIST_STEP : -DIST_STEP), 1.5, range(p));
            }
            default -> {}
        }
    }

    private static void use(ServerPlayer p, State st, boolean sneak) {
        if (st.itemId >= 0) { releaseItem(p, st); sync(p, st); return; }
        if (st.liftId >= 0) { releaseLift(p, st); sync(p, st); return; }
        double r = range(p);
        Entity e = pick(p, r);
        if (e instanceof ItemEntity it) {
            st.itemId = it.getId();
            st.holdDist = Math.max(1.5, p.getEyePosition().distanceTo(it.position()));
            it.setNoGravity(true);
            it.setPickUpDelay(10);
            st.grip = ArtVfx.on(p, ArtVfx.TK_GRIP, COLOR, 0, 20 * 60 * 10, it);
            ArtFx.anim(p, "art_tk_grab");
            p.level().playSound(null, it.blockPosition(), SoundEvents.AMETHYST_CLUSTER_PLACE, SoundSource.PLAYERS, 0.6f, 1.4f);
            sync(p, st);
            return;
        }
        if (e instanceof LivingEntity le) {
            if (!contest(p, le)) {
                ArtFx.anim(p, "art_tk_push");
                p.displayClientMessage(Component.translatable("msg.zhushenspace.tk.contest_fail", le.getDisplayName()), true);
                ArtVfx.on(p, ArtVfx.TK_GRIP, COLOR, 2, 10, le);
                return;
            }
            if (sneak) {
                st.liftId = le.getId();
                st.liftUp = 0;
                st.holdDist = Mth.clamp(p.getEyePosition().distanceTo(le.position().add(0, le.getBbHeight() * 0.5, 0)), 1.5, range(p));
                st.grip = ArtVfx.on(p, ArtVfx.TK_GRIP, COLOR, 0, 20 * 60 * 10, le);
                ArtFx.anim(p, "art_tk_lift");
                p.level().playSound(null, le.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 0.6f, 1.8f);
                sync(p, st);
            } else {
                Vec3 d = le.position().subtract(p.position()).multiply(1, 0, 1);
                if (d.lengthSqr() < 1e-4) d = p.getViewVector(1).multiply(1, 0, 1);
                d = d.normalize();
                double k = 0.7 + 0.15 * strCheck(p);
                le.push(d.x * k, 0.3, d.z * k);
                le.hurtMarked = true;
                ArtFx.anim(p, "art_tk_push");
                ArtVfx.on(p, ArtVfx.TK_GRIP, COLOR, 1, 12, le);
                p.level().playSound(null, le.blockPosition(), SoundEvents.WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 0.6f, 1.2f);
            }
            return;
        }
        p.displayClientMessage(Component.translatable("msg.zhushenspace.tk.nothing"), true);
    }

    /** 准星处范围内的掉落物或生物（需要视线） */
    private static Entity pick(ServerPlayer p, double r) {
        Vec3 eye = p.getEyePosition(), look = p.getViewVector(1f), end = eye.add(look.scale(r));
        HitResult b = p.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (b.getType() != HitResult.Type.MISS) end = b.getLocation();
        AABB box = p.getBoundingBox().expandTowards(look.scale(r)).inflate(1.0);
        EntityHitResult h = ProjectileUtil.getEntityHitResult(p.level(), p, eye, end, box,
                e -> e != p && !e.isSpectator() && e.isAlive() && (e instanceof ItemEntity || e instanceof LivingEntity), 0.45f);
        return h == null ? null : h.getEntity();
    }

    /** 有效力量对抗：念动力 (有效力量 + 其传奇) 对 目标力量，各自 20%~100% 浮动 */
    private static boolean contest(ServerPlayer p, LivingEntity t) {
        float mine = strCheck(p) * DamageVariance.roll(p.getRandom());
        float theirs = targetStr(t) * DamageVariance.roll(t.getRandom());
        return mine > theirs;
    }

    private static int targetStr(LivingEntity t) {
        if (t instanceof ServerPlayer sp) {
            int s = StatusManager.attr(sp, AttributeType.STRENGTH);
            return s + leg(s);
        }
        GrappleManager.Stats st = GrappleManager.stats(t);
        if (st != null) return st.str();
        return Math.max(1, Math.round(t.getBbWidth() * t.getBbHeight() * 1.5f + t.getMaxHealth() / 10f));
    }

    private static void releaseItem(ServerPlayer p, State st) {
        if (st.itemId < 0) return;
        Entity e = p.serverLevel().getEntity(st.itemId);
        st.itemId = -1;
        st.carrying = false;
        setCarry(p, false);
        if (st.grip != null && !st.grip.isRemoved()) st.grip.finish(6);
        st.grip = null;
        if (e instanceof ItemEntity it) {
            it.setNoGravity(false);
            it.setPickUpDelay(10);
            it.setDeltaMovement(it.getDeltaMovement().scale(0.5));
            it.hasImpulse = true;
        }
    }

    private static void releaseLift(ServerPlayer p, State st) {
        if (st.liftId < 0) return;
        st.liftId = -1;
        st.carrying = false;
        setCarry(p, false);
        if (st.grip != null && !st.grip.isRemoved()) st.grip.finish(6);
        st.grip = null;
    }

    /** 准星处的目标点（被方块挡住时停在方块前） */
    private static Vec3 holdPoint(ServerPlayer p, State st, double r) {
        Vec3 eye = p.getEyePosition(), look = p.getViewVector(1f);
        Vec3 want = eye.add(look.scale(Math.min(st.holdDist, r)));
        HitResult hr = p.level().clip(new ClipContext(eye, want, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (hr.getType() != HitResult.Type.MISS) want = hr.getLocation().subtract(look.scale(0.3));
        return want;
    }

    /** 移动被托物消耗移动力：速度不超过自己的步行速度，移动期间自身移速减半 */
    private static Vec3 steer(ServerPlayer p, State st, Vec3 d) {
        double max = Math.max(0.05, p.getAttributeBaseValue(Attributes.MOVEMENT_SPEED) * 2.16);
        double len = d.length();
        boolean moving = len > 0.08;
        if (moving != st.carrying) { st.carrying = moving; setCarry(p, moving); }
        return len > max ? d.scale(max / len) : d.scale(0.6);
    }

    // ===== 投掷 =====

    /** 被扔出去的东西：撞墙 / 撞到生物时结算撞击伤害 */
    private static final class Thrown {
        final ServerPlayer owner;
        final int id;
        final boolean item;
        int ticks;
        double lastSpeed;
        Thrown(ServerPlayer owner, int id, boolean item) { this.owner = owner; this.id = id; this.item = item; }
    }

    private static final List<Thrown> THROWN = new ArrayList<>();

    /** 投掷速度（格 / tick）：随有效力量提高 */
    static double throwSpeed(ServerPlayer p) { return Math.min(2.2, 0.6 + 0.14 * strCheck(p)); }

    private static void fling(ServerPlayer p, State st) {
        Vec3 look = p.getViewVector(1f);
        double v = throwSpeed(p);
        Entity e = null;
        if (st.itemId >= 0) {
            e = p.serverLevel().getEntity(st.itemId);
            releaseItem(p, st);
            if (e instanceof ItemEntity it) {
                it.setDeltaMovement(look.scale(v).add(0, 0.08, 0));
                it.hasImpulse = true;
                it.setPickUpDelay(20);
            }
        } else if (st.liftId >= 0) {
            e = p.serverLevel().getEntity(st.liftId);
            releaseLift(p, st);
            if (e instanceof LivingEntity le) {
                le.setDeltaMovement(look.scale(v * 0.85).add(0, 0.15, 0));
                le.hurtMarked = true;
            }
        }
        sync(p, st);
        if (e == null || !e.isAlive()) return;
        THROWN.add(new Thrown(p, e.getId(), e instanceof ItemEntity));
        ArtFx.anim(p, "art_tk_throw");
        ArtVfx.on(p, ArtVfx.TK_GRIP, COLOR, 1, 12, e);
        p.level().playSound(null, e.blockPosition(), SoundEvents.WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 0.7f, 0.9f);
        p.level().playSound(null, p.blockPosition(), SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 0.6f, 0.7f);
    }

    private static void tickThrown() {
        if (THROWN.isEmpty()) return;
        THROWN.removeIf(t -> {
            ServerPlayer o = t.owner;
            if (o.isRemoved() || !o.isAlive() || ++t.ticks > 60) return true;
            Entity e = o.serverLevel().getEntity(t.id);
            if (e == null || !e.isAlive()) return true;
            double speed = e.getDeltaMovement().length();
            if (t.ticks > 2 && e.onGround() && speed < 0.2) return true;
            boolean done = t.item ? tickThrownItem(o, (ItemEntity) e, speed) : e instanceof LivingEntity le && tickThrownMob(o, le, t);
            t.lastSpeed = speed;
            return done;
        });
    }

    /** 扔出的物品撞到生物：运动（投掷）检定，判定 = 念动力力量检定值 + 运动 */
    private static boolean tickThrownItem(ServerPlayer o, ItemEntity it, double speed) {
        if (speed < 0.45) return false;
        for (Entity h : o.level().getEntities(it, it.getBoundingBox().inflate(0.3), en -> en instanceof LivingEntity && en != o && en.isAlive() && !en.isSpectator())) {
            LivingEntity t = (LivingEntity) h;
            int check = strCheck(o) + ArtManager.skill(o, SkillType.ATHLETICS);
            float v = ArtManager.attackRoll(o, check, (int) Math.round(speed * 2), ArtManager.defense(o, t, 0, 0, false), check + 3, 0);
            if (v > 0) ArtManager.hit(o, t, v, ArtManager.spec(PlayerHealthData.Severity.B, 0, 0, true, DamageKind.BLUNT));
            else ArtManager.whiff(o);
            it.setDeltaMovement(it.getDeltaMovement().scale(-0.15));
            it.hasImpulse = true;
            o.level().playSound(null, t.blockPosition(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 0.7f, 1.2f);
            return true;
        }
        return false;
    }

    /** 扔出的生物：撞墙或撞上别的生物时双方受到撞击伤害（钝击 B，不可防御），落地后的坠落伤害照常结算 */
    private static boolean tickThrownMob(ServerPlayer o, LivingEntity le, Thrown t) {
        if (t.lastSpeed < 0.35) return false;
        LivingEntity other = null;
        for (Entity h : o.level().getEntities(le, le.getBoundingBox().inflate(0.15), en -> en instanceof LivingEntity && en != o && en.isAlive() && !en.isSpectator())) {
            other = (LivingEntity) h;
            break;
        }
        if (!le.horizontalCollision && other == null) return false;
        float amt = Math.max(1f, (strCheck(o) + 2) * DamageVariance.roll(o.getRandom()) + (float) (t.lastSpeed * 4));
        var spec = ArtManager.spec(PlayerHealthData.Severity.B, 0, 0, true, DamageKind.BLUNT);
        ArtManager.hit(o, le, amt, spec);
        if (other != null) ArtManager.hit(o, other, amt * 0.75f, spec);
        if (o.level() instanceof ServerLevel sl) sl.sendParticles(ParticleTypes.EXPLOSION, le.getX(), le.getY() + le.getBbHeight() * 0.5, le.getZ(), 1, 0, 0, 0, 0);
        o.level().playSound(null, le.blockPosition(), SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.4f, 0.8f);
        TaiChiFx.shake(o, le, 0.6f, 6);
        return true;
    }

    private static void tickItem(ServerPlayer p, State st) {
        Entity e = p.serverLevel().getEntity(st.itemId);
        double r = range(p);
        if (!(e instanceof ItemEntity it) || !it.isAlive() || it.distanceTo(p) > r + 1.5 || !p.hasLineOfSight(it)) {
            boolean lost = e instanceof ItemEntity;
            releaseItem(p, st);
            if (lost) p.displayClientMessage(Component.translatable("msg.zhushenspace.tk.dropped"), true);
            sync(p, st);
            return;
        }
        if (p.isShiftKeyDown()) st.holdDist = Math.max(1.5, st.holdDist - 0.15); // 潜行：拉向自己
        Vec3 want = holdPoint(p, st, r);
        it.setNoGravity(true);
        it.setPickUpDelay(10);
        it.setDeltaMovement(steer(p, st, want.subtract(it.position().add(0, 0.15, 0))));
        it.hasImpulse = true;
        it.fallDistance = 0;
    }

    private static void tickLift(ServerPlayer p, State st) {
        Entity e = p.serverLevel().getEntity(st.liftId);
        double r = range(p);
        if (!(e instanceof LivingEntity le) || !le.isAlive() || le.distanceTo(p) > r + 1.5 || !p.hasLineOfSight(le)) {
            releaseLift(p, st);
            sync(p, st);
            return;
        }
        // 和掉落物一样跟随准星：滚轮推远 / 拉近（潜行也可拉近），悬在准星点上方
        if (p.isShiftKeyDown()) st.holdDist = Math.max(1.5, st.holdDist - 0.15);
        st.liftUp = Math.min(LIFT_UP, st.liftUp + 0.12);
        Vec3 want = holdPoint(p, st, r).add(0, st.liftUp - le.getBbHeight() * 0.5, 0);
        Vec3 d = steer(p, st, want.subtract(le.position()));
        le.setDeltaMovement(d.x, Mth.clamp(d.y, -0.3, 0.35) + 0.04, d.z); // +0.04：抵消一部分重力，悬停不下坠
        le.fallDistance = 0; // 被放下时从托举高度开始计算坠落
        le.hurtMarked = true;
    }

    // ===== 悬浮武器出击 =====

    private static void strike(ServerPlayer p, State st) {
        if (st.slots.length == 0) return;
        LivingEntity t = ArtManager.target(p, range(p));
        if (t == null) return;
        long now = p.level().getGameTime();
        int launched = 0;
        for (int i = 0; i < st.slots.length; i++) {
            if (st.ready[i] > now) continue;
            ItemStack w = p.getInventory().getItem(st.slots[i]);
            if (!isWeapon(w)) continue;
            int idx = i, delay = launched * 3;
            st.ready[i] = now + delay + FLIGHT + WEAPON_CD;
            ItemStack copy = w.copy();
            ArtManager.later(p, delay, () -> launch(p, idx, t, copy));
            launched++;
        }
        if (launched > 0) ArtFx.anim(p, "art_tk_command");
    }

    private static void launch(ServerPlayer p, int idx, LivingEntity t, ItemStack w) {
        if (!t.isAlive() || !active(p)) return;
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(p, new TkStrikePayload(p.getId(), idx, t.getId(), FLIGHT));
        p.level().playSound(null, p.blockPosition(), SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 0.6f, 1.5f);
        ArtManager.later(p, FLIGHT, () -> {
            if (!t.isAlive()) return;
            // 以念动力的有效力量进行攻击：有效力量 + 武器技能 + 武器伤害 − 防御 − 力量前提不足减值（念动力加值只计算一次，不再叠加能量加值）
            WeaponCategory cat = CombatFormula.classify(w);
            SkillType sk = cat.skill == SkillType.FIREARMS || cat.skill == SkillType.ATHLETICS ? SkillType.BLADE : cat.skill;
            float wd = weaponDamage(w);
            int pen = Math.max(0, CombatFormula.spec(w).strReq() - str(p)) * WeaponCategory.REQ_PENALTY
                    + CombatFormula.professionPenalty(p, cat); // 专业：按悬浮武器自身的分类
            com.zhushen.space.data.MeleeWeapon mw = com.zhushen.space.data.MeleeWeapon.of(w);
            if (mw != null && mw.has(com.zhushen.space.data.MeleeWeapon.Trait.HEAVY_WEAPON)) pen += WeaponRules.HEAVY_PENALTY;
            int check = strCheck(p) + ArtManager.skill(p, sk);
            int cap = Math.round(wd * 1.5f) + check;
            float def = ArtManager.defense(p, t, 0, 0, false);
            float v = ArtManager.attackRoll(p, check, Math.round(wd) - pen, def, cap, 0);
            if (v < 0) return;
            ArtManager.hit(p, t, v, ArtManager.spec(mw != null ? mw.severityFor(w) : PlayerHealthData.Severity.B,
                    mw != null ? mw.armorPierce : 0, 0, true, weaponKind(w)));
            p.level().playSound(null, t.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.7f, 1.3f);
        });
    }

    // ===== tick / 事件 =====

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        tickThrown();
        for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) {
            tickRecovery(p);
            State st = STATES.get(p.getUUID());
            if (st == null) continue;
            long now = p.level().getGameTime();
            if (now >= st.until || !p.isAlive() || !hasPool(p)) { end(p, true); continue; }
            if (now % 10 == 0) {
                // 悬浮武器：所选格变化或武器被取走时重新挑选
                Inventory inv = p.getInventory();
                boolean bad = false;
                for (int s : st.slots) if (s == inv.selected || !isWeapon(inv.getItem(s))) bad = true;
                int before = st.slots.length;
                if (bad || (st.slots.length < agi(p) && now % 40 == 0)) {
                    int[] old = st.slots;
                    pickWeapons(p, st);
                    if (bad || st.slots.length != before || !java.util.Arrays.equals(old, st.slots)) {
                        refreshBlades(p, st, (int) (st.until - now));
                        sync(p, st);
                    }
                }
            }
            if (st.itemId >= 0) tickItem(p, st);
            if (st.liftId >= 0) tickLift(p, st);
            if (now - st.lastSync >= 40) sync(p, st);
        }
    }

    private static boolean tooFar(ServerPlayer p, BlockPos pos) {
        return p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > VANILLA_REACH;
    }

    /** 远程操作只能操作方块本身：超出原版距离时不能破坏方块 */
    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (e.getPlayer() instanceof ServerPlayer p && STATES.containsKey(p.getUUID()) && tooFar(p, e.getPos())) e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock e) {
        if (e.getEntity() instanceof ServerPlayer p && STATES.containsKey(p.getUUID()) && tooFar(p, e.getPos())) e.setCanceled(true);
    }

    /** 远处的方块：只能使用方块（门 / 拉杆 / 按钮 / 容器），不能对其使用手中物品（放置方块等） */
    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock e) {
        if (e.getEntity() instanceof ServerPlayer p && STATES.containsKey(p.getUUID()) && tooFar(p, e.getPos())) {
            e.setUseItem(TriState.FALSE);
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) end(p, false);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) end(p, false);
    }

    @SubscribeEvent
    public static void onDim(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) end(p, false);
    }

    /** 新进入视野的玩家：补发操控状态（悬浮武器渲染） */
    @SubscribeEvent
    public static void onTrack(PlayerEvent.StartTracking e) {
        if (!(e.getTarget() instanceof ServerPlayer owner) || !(e.getEntity() instanceof ServerPlayer viewer)) return;
        State st = STATES.get(owner.getUUID());
        if (st == null) return;
        List<ItemStack> ws = new ArrayList<>();
        for (int s : st.slots) ws.add(owner.getInventory().getItem(s).copy());
        long now = owner.level().getGameTime();
        PacketDistributor.sendToPlayer(viewer, new TkStatePayload(owner.getId(), (int) Math.max(1, st.until - now), ws,
                st.itemId >= 0 ? st.itemId : st.liftId));
    }
}
