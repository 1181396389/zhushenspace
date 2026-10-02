package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.ArtSkill;
import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.DamageKind;
import com.zhushen.space.data.ModComponents;
import com.zhushen.space.data.PlayerHealthData;
import com.zhushen.space.data.WeaponCategory;
import com.zhushen.space.entity.art.ArtVfx;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 魔法·专业法术：轰雷剑（塑能）/ 光亮术（咒法）/ 照明术（咒法）/ 冻寒骨爪（死灵）。
 * <p>
 * 「目标：一个目标」只表示能影响多少个对象：没有对准目标时照常施放（消耗、冷却、演出）并打空；
 * 标记【锁定】的技艺才必须锁定（见 {@link ArtSkill#lockOn()}）。1 轮 = 3 秒。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class MagicSpells {
    private MagicSpells() {}

    /** 1 轮 = 3 秒 */
    static final int ROUND = 60;
    static final int TEN_MIN = 10 * 60 * 20;

    /** 灵体生物（数据包可扩充）：处于照明术光球范围内时失去灵体特性带来的隐形 */
    public static final TagKey<EntityType<?>> SPIRIT =
            TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "spirit"));

    // =====================================================================
    // 轰雷剑：近战施法攻击，以手中白刃武器的伤害代替威力；命中不造成伤害，而是标记目标并记录伤害，
    // 目标在接下来一轮内主动移动（含传送）即受到等量雷电严重伤害
    // =====================================================================

    private static final class ThunderMark {
        UUID caster;
        LivingEntity target;
        float amount;
        long until;
        double x, y, z;
        int grace;
        ArtVfx fx;
    }

    private static final Map<UUID, ThunderMark> MARKS = new HashMap<>();

    static boolean thunderSword(ServerPlayer p, ArtSkill s) {
        LivingEntity t = ArtManager.target(p, p.entityInteractionRange());
        if (!ArtManager.pay(p, s, s.cost)) return false;
        ItemStack held = p.getMainHandItem();
        WeaponCategory cat = CombatFormula.classify(held);
        boolean blade = !held.isEmpty() && cat.group == WeaponCategory.Group.BLADE && cat != WeaponCategory.HIDDEN_WEAPON;
        // 手中白刃武器：武器伤害代替威力值，并继承武器的力量前提 / 专业减值与附魔效果
        float weapon = blade ? ArtManager.heldWeapon(p) : 0f;
        int pen = 0;
        if (blade) {
            int deficit = CombatFormula.strengthDeficit(p, held);
            if (deficit > WeaponCategory.MAX_DEFICIT) {
                ArtManager.deny(p, "message.zhushenspace.weapon.too_heavy");
                return true;
            }
            pen = deficit * WeaponCategory.REQ_PENALTY + CombatFormula.professionPenalty(p, cat);
        }
        float v = t == null ? 0 : ArtManager.attackRoll(p, ArtManager.spellCheck(p, s.pool), -pen,
                ArtManager.defense(p, t, 0, 0, false), ArtManager.spellCap(p, s.pool, 0, weapon), 0);
        if (v < 0) { ArtManager.deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        ArtFx.anim(p, "art_thunder_sword");
        ArtFx.castSfx(p, s.pool, 0.3f);
        ArtVfx.on(p, ArtVfx.THUNDER_BLADE, 0xFF8FE8FF, blade ? 1 : 0, 16, p);
        ArtFx.soundAt(p, p.position(), SoundEvents.TRIDENT_THUNDER.value(), 0.25f, 1.9f);
        ArtManager.later(p, 6, () -> {
            ArtFx.releaseSfx(p, s.pool, 0.35f);
            ArtFx.soundAt(p, p.position(), SoundEvents.PLAYER_ATTACK_SWEEP, 0.8f, 1.3f);
            if (t == null) { ArtManager.whiff(p); return; }
            if (!t.isAlive()) return;
            if (v <= 0) { p.displayClientMessage(Component.translatable("msg.zhushenspace.art.miss"), true); return; }
            if (blade && p.level() instanceof ServerLevel sl) {
                // 继承武器效果（火焰附加等攻击后附魔）；本次攻击不造成伤害
                EnchantmentHelper.doPostAttackEffects(sl, t, p.damageSources().playerAttack(p));
            }
            mark(p, t, v);
        });
        return true;
    }

    private static void mark(ServerPlayer p, LivingEntity t, float amount) {
        ThunderMark old = MARKS.remove(t.getUUID());
        if (old != null && old.fx != null) old.fx.finish(4);
        ThunderMark m = new ThunderMark();
        m.caster = p.getUUID();
        m.target = t;
        m.amount = amount;
        m.until = t.level().getGameTime() + ROUND;
        m.x = t.getX(); m.y = t.getY(); m.z = t.getZ();
        m.grace = 2;
        m.fx = ArtVfx.on(p, ArtVfx.THUNDER_MARK, 0xFF8FE8FF, 0, ROUND + 8, t);
        MARKS.put(t.getUUID(), m);
        ArtFx.soundAt(p, t.position(), SoundEvents.LIGHTNING_BOLT_IMPACT, 0.5f, 1.8f);
        p.displayClientMessage(Component.translatable("msg.zhushenspace.art.thunder_marked", t.getDisplayName(), Math.round(amount)), true);
        if (t instanceof ServerPlayer tp) tp.displayClientMessage(Component.translatable("msg.zhushenspace.art.thunder_warn"), true);
    }

    /** 主动移动判定：水平位移或向上位移；受击击退（hurtTime）/ 乘骑 / 下落不算 */
    private static boolean movedActively(ThunderMark m) {
        LivingEntity t = m.target;
        double dx = t.getX() - m.x, dy = t.getY() - m.y, dz = t.getZ() - m.z;
        m.x = t.getX(); m.y = t.getY(); m.z = t.getZ();
        if (m.grace > 0) { m.grace--; return false; }
        if (t.hurtTime > 0 || t.isPassenger()) return false;
        double horiz = dx * dx + dz * dz;
        return horiz > 0.03 * 0.03 * 4 || dy > 0.06;
    }

    private static void strike(MinecraftServer server, ThunderMark m) {
        LivingEntity t = m.target;
        if (m.fx != null) m.fx.finish(3);
        ServerPlayer caster = server.getPlayerList().getPlayer(m.caster);
        if (caster == null || !t.isAlive()) return;
        if (caster.level() == t.level()) {
            ArtVfx.at(caster, ArtVfx.THUNDER_STRIKE, 0xFF8FE8FF, 0, 20, t.position(), new Vec3(0, 1, 0));
        }
        t.level().playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 0.8f, 1.6f);
        t.level().playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 1f, 1.1f);
        ArtManager.hit(caster, t, m.amount, ArtManager.spec(PlayerHealthData.Severity.L, 0, 0, false, DamageKind.LIGHTNING)
                .withTraits(DamageRules.Trait.MAGIC)); // 法术：【魔法】（穿透 DR/魔法）
    }

    /** 传送立即引爆（末影珍珠的摔落伤害会让位移判定忽略这一下，所以单独处理） */
    @SubscribeEvent
    public static void onPearl(EntityTeleportEvent.EnderPearl e) { teleported(e.getEntity()); }

    @SubscribeEvent
    public static void onChorus(EntityTeleportEvent.ChorusFruit e) { teleported(e.getEntity()); }

    @SubscribeEvent
    public static void onEnderman(EntityTeleportEvent.EnderEntity e) { teleported(e.getEntity()); }

    private static void teleported(Entity e) {
        if (e == null || e.level().isClientSide) return;
        ThunderMark m = MARKS.remove(e.getUUID());
        if (m != null && e.getServer() != null) strike(e.getServer(), m);
    }

    // =====================================================================
    // 光亮术：目标物品照亮周围半径 20 米（10 分钟）。物品写入组件 light_until，光照由客户端动态光源绘制
    // =====================================================================

    static boolean light(ServerPlayer p, ArtSkill s) {
        ItemStack stack = null;
        Entity holder = p;
        if (!p.getMainHandItem().isEmpty()) stack = p.getMainHandItem();
        else if (!p.getOffhandItem().isEmpty()) stack = p.getOffhandItem();
        else {
            Entity e = pick(p, p.entityInteractionRange());
            if (e instanceof ItemEntity ie) { stack = ie.getItem(); holder = ie; }
            else if (e instanceof Player o) {
                // 被持有的物品：持有者必须自愿（潜行表示自愿）
                if (!o.isShiftKeyDown()) { ArtManager.deny(p, "msg.zhushenspace.art.light_unwilling"); return false; }
                if (!o.getMainHandItem().isEmpty()) stack = o.getMainHandItem();
                else if (!o.getOffhandItem().isEmpty()) stack = o.getOffhandItem();
                holder = o;
            }
        }
        if (stack == null || stack.isEmpty()) { ArtManager.deny(p, "msg.zhushenspace.art.light_no_item"); return false; }
        if (!ArtManager.pay(p, s, s.cost)) return false;
        if (ArtManager.holdback(p, 1) < 0) { ArtManager.deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        ArtFx.anim(p, "art_light");
        ArtFx.castSfx(p, s.pool, 0.25f);
        final ItemStack fs = stack;
        final Entity fh = holder;
        ArtManager.later(p, 5, () -> {
            if (fh.isRemoved()) return;
            long until = fh.level().getGameTime() + TEN_MIN;
            if (fh instanceof ItemEntity ie) {
                ItemStack c = ie.getItem().copy();
                c.set(ModComponents.LIGHT_UNTIL.get(), until);
                ie.setItem(c);
            } else {
                fs.set(ModComponents.LIGHT_UNTIL.get(), until);
            }
            if (fh.level() == p.level()) ArtVfx.on(p, ArtVfx.LUMEN, 0xFFFFF0B8, fh instanceof ItemEntity ? 1 : 0, 28, fh);
            ArtFx.releaseSfx(p, s.pool, 0.25f);
            fh.level().playSound(null, fh.getX(), fh.getY(), fh.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1f, 1.5f);
            fh.level().playSound(null, fh.getX(), fh.getY(), fh.getZ(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 0.35f, 1.9f);
        });
        return true;
    }

    /** 准星所指的掉落物 / 玩家 */
    private static Entity pick(ServerPlayer p, double range) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getViewVector(1f);
        Vec3 end = eye.add(look.scale(range));
        HitResult block = p.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (block.getType() != HitResult.Type.MISS) end = block.getLocation();
        AABB box = p.getBoundingBox().expandTowards(look.scale(range)).inflate(1.5);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(p.level(), p, eye, end, box,
                e -> (e instanceof ItemEntity || e instanceof Player) && e != p && !e.isSpectator(), 0.3f);
        return hit != null ? hit.getEntity() : null;
    }

    /** 物品上的光亮术是否生效 */
    public static boolean lit(ItemStack stack, long gameTime) {
        Long until = stack.get(ModComponents.LIGHT_UNTIL.get());
        return until != null && until > gameTime;
    }

    // =====================================================================
    // 照明术：身前四个浮空光球（10 分钟），以光球为中心半径 10 米变为明亮环境；灵体失去隐形
    // =====================================================================

    private static final Map<UUID, ArtVfx> ORBS = new HashMap<>();
    /** 被照明术显形的灵体（供灵体系统查询） */
    private static final Map<UUID, Long> REVEALED = new HashMap<>();

    static boolean illumination(ServerPlayer p, ArtSkill s) {
        if (!ArtManager.pay(p, s, s.cost)) return false;
        if (ArtManager.holdback(p, 1) < 0) { ArtManager.deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        ArtFx.anim(p, "art_illumination");
        ArtFx.castSfx(p, s.pool, 0.3f);
        ArtManager.later(p, 6, () -> {
            ArtManager.addBuff(p, s, TEN_MIN);
            spawnOrbs(p, TEN_MIN);
            ArtFx.releaseSfx(p, s.pool, 0.3f);
            ArtFx.soundAt(p, p.position(), SoundEvents.BEACON_ACTIVATE, 0.5f, 1.8f);
            ArtFx.soundAt(p, p.position(), SoundEvents.AMETHYST_CLUSTER_PLACE, 0.8f, 1.6f);
        });
        return true;
    }

    private static void spawnOrbs(ServerPlayer p, int life) {
        ArtVfx old = ORBS.remove(p.getUUID());
        if (old != null && !old.isRemoved()) old.discard();
        ORBS.put(p.getUUID(), ArtVfx.on(p, ArtVfx.LIGHT_ORBS, 0xFFFFF2B0, 0, life, p));
    }

    /** 光球群中心（与客户端绘制位置一致：头顶前方） */
    public static Vec3 orbCenter(Entity p) {
        double yaw = Math.toRadians(p instanceof LivingEntity le ? le.yBodyRot : p.getYRot());
        return p.position().add(-Math.sin(yaw) * 0.7, p.getBbHeight() + 0.25, Math.cos(yaw) * 0.7);
    }

    /** 灵体是否正被照明术显形 */
    public static boolean spiritRevealed(Entity e) {
        Long t = REVEALED.get(e.getUUID());
        return t != null && t > e.level().getGameTime();
    }

    /** 解除魔法类能力：熄灭照明术光球 */
    public static void dispelIllumination(ServerPlayer p) {
        ArtVfx fx = ORBS.remove(p.getUUID());
        if (fx != null && !fx.isRemoved()) fx.finish(10);
        ArtManager.addBuff(p, ArtSkill.ILLUMINATION, 0);
    }

    // =====================================================================
    // 冻寒骨爪：远程施法攻击（射程 = 智力 米），寒冰严重伤害；
    // 研发「镇亡」：命中不死生物后其攻击检定 −6（亵渎），持续到你的下一轮开始；研发「凋寒」：寒冰 + 亵渎混合
    // =====================================================================

    private static final Map<UUID, Long> CURSE = new HashMap<>();

    static boolean frostClaw(ServerPlayer p, ArtSkill s) {
        double range = Math.max(1, ArtManager.attr(p, AttributeType.INTELLIGENCE));
        LivingEntity t = ArtManager.target(p, range);
        Vec3 end = t != null ? ArtManager.aim(t) : ArtManager.rayEnd(p, range);
        float v = t == null ? 0 : ArtManager.attackRoll(p, ArtManager.spellCheck(p, s.pool), 0,
                ArtManager.defense(p, t, 0, 0, false), ArtManager.spellCap(p, s.pool, 0, 0), 0);
        if (v < 0) { ArtManager.deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        boolean bane = ArtManager.data(p).hasResearch(s, 0), blight = ArtManager.data(p).hasResearch(s, 1);
        ArtFx.anim(p, "art_frost_claw");
        ArtFx.castSfx(p, s.pool, 0.3f);
        ArtManager.later(p, 4, () -> {
            ArtVfx.beam(p, ArtVfx.FROST_CLAW, blight ? 0xFFB9A4FF : 0xFF9EE8FF, blight ? 1 : 0, 18, ArtManager.handPos(p),
                    t != null && t.isAlive() ? ArtManager.aim(t) : end);
            ArtFx.releaseSfx(p, s.pool, 0.3f);
            ArtFx.soundAt(p, p.position(), SoundEvents.SKELETON_AMBIENT, 0.5f, 0.6f);
        });
        ArtManager.later(p, 8, () -> {
            if (t == null) { ArtManager.whiff(p); return; }
            if (!t.isAlive()) return;
            DamageRules.Spec spec = blight
                    ? ArtManager.spec(PlayerHealthData.Severity.L, 0, 0, true, DamageKind.COLD, DamageKind.UNHOLY)
                    : ArtManager.spec(PlayerHealthData.Severity.L, 0, 0, true, DamageKind.COLD);
            spec = spec.withTraits(DamageRules.Trait.MAGIC); // 法术：【魔法】
            ArtManager.hit(p, t, v, spec);
            ArtFx.soundAt(p, t.position(), SoundEvents.PLAYER_HURT_FREEZE, 0.9f, 1.0f);
            ArtFx.soundAt(p, t.position(), SoundEvents.GLASS_BREAK, 0.5f, 1.6f);
            if (v > 0 && bane && t.getType().is(EntityTypeTags.UNDEAD)) {
                CURSE.put(t.getUUID(), t.level().getGameTime() + ROUND);
                ArtFx.soundAt(p, t.position(), SoundEvents.SOUL_ESCAPE.value(), 0.8f, 0.7f);
                p.displayClientMessage(Component.translatable("msg.zhushenspace.art.undead_bane", t.getDisplayName()), true);
            }
        });
        return true;
    }

    /** 冻寒骨爪「镇亡」：攻击检定减值（正数 = 扣除） */
    public static int attackCurse(Entity attacker) {
        if (attacker == null) return 0;
        Long t = CURSE.get(attacker.getUUID());
        return t != null && t > attacker.level().getGameTime() ? 6 : 0;
    }

    // =====================================================================
    // tick
    // =====================================================================

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post e) {
        MinecraftServer server = e.getServer();
        // 轰雷剑标记：每 tick 检查主动移动
        if (!MARKS.isEmpty()) {
            Iterator<Map.Entry<UUID, ThunderMark>> it = MARKS.entrySet().iterator();
            java.util.List<ThunderMark> fire = new java.util.ArrayList<>();
            while (it.hasNext()) {
                ThunderMark m = it.next().getValue();
                LivingEntity t = m.target;
                if (t.isRemoved() || !t.isAlive()) { it.remove(); if (m.fx != null) m.fx.finish(3); continue; }
                if (movedActively(m)) { it.remove(); fire.add(m); continue; }
                if (t.level().getGameTime() > m.until) { it.remove(); if (m.fx != null) m.fx.finish(6); }
            }
            for (ThunderMark m : fire) strike(server, m);
        }
        int tick = server.getTickCount();
        if (tick % 10 != 0) return;
        // 照明术：光球保持（换维度 / 重登后重新生成）、灵体显形、到期熄灭
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            ArtVfx fx = ORBS.get(p.getUUID());
            if (ArtManager.buff(p, ArtSkill.ILLUMINATION)) {
                if (fx == null || fx.isRemoved() || fx.level() != p.level() || fx.followId() != p.getId()) {
                    long left = until(p, ArtSkill.ILLUMINATION) - p.level().getGameTime();
                    if (left > 20) spawnOrbs(p, (int) left);
                }
                Vec3 c = orbCenter(p);
                for (LivingEntity le : p.level().getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(10.5),
                        x -> x.getType().is(SPIRIT) && x.position().distanceToSqr(c) <= 10.5 * 10.5)) {
                    le.removeEffect(MobEffects.INVISIBILITY);
                    le.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30, 0, false, false, false));
                    REVEALED.put(le.getUUID(), le.level().getGameTime() + 20);
                }
            } else if (fx != null) {
                ORBS.remove(p.getUUID());
                if (!fx.isRemoved()) fx.finish(10);
            }
        }
        if (tick % 200 == 0) {
            long now = server.overworld().getGameTime();
            CURSE.values().removeIf(v -> v < now);
            REVEALED.values().removeIf(v -> v < now);
            // 光亮术到期：清掉玩家物品上的组件（让物品重新可堆叠）
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                long gt = p.level().getGameTime();
                var inv = p.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack st = inv.getItem(i);
                    Long u = st.isEmpty() ? null : st.get(ModComponents.LIGHT_UNTIL.get());
                    if (u != null && u <= gt) st.remove(ModComponents.LIGHT_UNTIL.get());
                }
            }
        }
    }

    private static long until(ServerPlayer p, ArtSkill s) { return ArtManager.buffUntil(p, s); }
}
