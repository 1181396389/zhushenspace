package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.*;
import com.zhushen.space.entity.art.ArtProjectile;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;

/**
 * Release-time stat snapshot, collision-time target defense. No instant target damage.
 * 出手与动作对齐：施法时立即扣费、播放动作，弹体在动作的“出招帧”才真正发出（几 tick 的延迟，瞄准取发出时的视线）。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class ArtBallistics {
    private ArtBallistics() {}

    /** 各技艺动作里“刀光 / 掌风 / 火球离手”那一帧距施法的 tick 数（与 art.json 的出招关键帧一致） */
    private static final int SPIRIT_DELAY = 4, WIND_DELAY = 5, HADOKEN_DELAY = 2, FIREBALL_DELAY = 3;

    private record Pending(ServerPlayer player, int due, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim, Runnable run) {}
    private static final List<Pending> PENDING = new ArrayList<>();

    static void later(ServerPlayer p, int ticks, Runnable run) {
        if (ticks <= 0) { run.run(); return; }
        PENDING.add(new Pending(p, p.getServer().getTickCount() + ticks, p.level().dimension(), run));
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post e) {
        if (PENDING.isEmpty()) return;
        int now = e.getServer().getTickCount();
        List<Pending> due = new ArrayList<>();
        PENDING.removeIf(q -> {
            if (q.player().getServer() != e.getServer() || q.due() > now) return false;
            due.add(q); return true;
        });
        for (Pending q : due) {
            ServerPlayer p = q.player();
            // 出招前死亡 / 下线 / 换维度：这一招作废（费用已在施法时结算，与原先即时发出的规则一致）
            if (p.isRemoved() || !p.isAlive() || p.level().dimension() != q.dim()) continue;
            q.run().run();
        }
    }

    @SubscribeEvent public static void stop(net.neoforged.neoforge.event.server.ServerStoppedEvent e) { PENDING.clear(); }
    public record Shot(ArtSkill skill, int check, int cap, int bonus, float roll, float restraint,
                       float charge, int element, double range, Set<UUID> hit) {}

    public static boolean cast(ServerPlayer p, ArtSkill s, int castBoost, int chargeTicks) {
        if (!ArtManager.pay(p, s, s.cost)) return false;
        float restraint = ArtManager.holdback(p, 100) / 100f;
        if (restraint < 0) { ArtManager.deny(p, "msg.zhushenspace.art.holdback_fail"); return true; }
        int check, cap, bonus = 0, kind, color;
        double range, speed;
        float size = 1;
        String animation;
        int element = Math.max(0, Math.min(4, ArtManager.data(p).current[s.ordinal()]));
        float charge = ArtCharge.supports(s) ? 1f + Math.max(0, Math.min(ArtCharge.MAX_TICKS, chargeTicks)) / (float) ArtCharge.MAX_TICKS : 1;
        switch (s) {
            case HADOKEN -> {
                check = ArtManager.attr(p, AttributeType.STRENGTH) + ArtManager.skill(p, SkillType.BRAWL) + 1;
                cap = check; kind = ArtProjectile.WAVE; color = 0xFFB4F4FF; range = 50; speed = 1.25;
                size = 0.75f + 0.5f * (charge - 1); animation = "art_hadoken";
            }
            case WIND_SLASH -> {
                check = ArtManager.attr(p, AttributeType.STRENGTH) + ArtManager.skill(p, SkillType.BLADE) + Math.round(ArtManager.heldWeapon(p));
                cap = Integer.MAX_VALUE; bonus = 3 * ArtManager.legendary(p, AttributeType.AGILITY);
                kind = ArtProjectile.WIND; color = 0xFFAFF4DA; range = 16; speed = 1.1; animation = "art_wind_slash";
            }
            case SPIRIT_SLASH -> {
                check = ArtManager.mindValue(p) + Math.round(ArtManager.heldWeapon(p)); cap = check;
                kind = ArtProjectile.SPIRIT; color = 0xFFD1F8FF; range = Math.max(2, ArtManager.attr(p, AttributeType.RESOLVE));
                speed = 1.1; animation = "art_spirit_slash";
            }
            case EIGHT_FORMATION -> {
                check = ArtManager.spellCheck(p, s.pool); cap = ArtManager.spellCap(p, s.pool, 3, 0);
                kind = ArtProjectile.SEAL; color = ArtManager.formationColor(p); range = 20; speed = 2.5; animation = "art_formation";
            }
            case GREAT_FIREBALL -> {
                check = ArtManager.spellCheck(p, s.pool); cap = ArtManager.spellCap(p, s.pool, 4 + PoolEffects.sageBoost(p, 4), 0);
                kind = ArtProjectile.FIREBALL; color = 0xFFFFFFFF; range = 40; speed = 0.85;
                size = 1.3f + (charge - 1) * 0.65f; animation = "art_fireball";
            }
            default -> { return false; }
        }
        Shot shot = new Shot(s, check + castBoost, cap, bonus, ArtManager.roll(p), restraint, charge, element, range, new HashSet<>());
        shot.hit().add(p.getUUID());
        ArtFx.anim(p, animation);
        if (kind == ArtProjectile.SEAL) {
            ArtFx.releaseSfx(p, s.pool, 0.45f);
            // 阵留在施法者面前（与蓄力时同一位置），三道激光从阵心射出；被墙挡住时阵贴在墙前
            ArtProjectile seal = ArtProjectile.seal(p, color);
            seal.releaseSeal(shot);
            return true;
        }
        final int fKind = kind, fColor = color;
        final float fSize = size;
        final double fSpeed = speed;
        int delay = switch (s) {
            case SPIRIT_SLASH -> SPIRIT_DELAY;
            case WIND_SLASH -> WIND_DELAY;
            case HADOKEN -> HADOKEN_DELAY;
            default -> FIREBALL_DELAY;
        };
        if (kind == ArtProjectile.SPIRIT || kind == ArtProjectile.WIND) {
            // 挥刀残光：比刀光离手早一帧，贴着施法者身前划过（size 编码方向：1 = 横斩，2 = 斜劈）
            float variant = kind == ArtProjectile.SPIRIT ? 1f : 2f;
            later(p, delay - 1, () -> {
                Vec3 v = p.getViewVector(1);
                ArtProjectile.fx(p, ArtProjectile.SWING, fColor, variant, p.getEyePosition().add(v.scale(0.95)).add(0, -0.22, 0), v, 7);
            });
        }
        later(p, delay, () -> fire(p, s, fKind, fColor, fSize, fSpeed, shot));
        return true;
    }

    private static void fire(ServerPlayer p, ArtSkill s, int kind, int color, float size, double speed, Shot shot) {
        ArtFx.releaseSfx(p, s.pool, 0.45f);
        Vec3 dir = p.getViewVector(1);
        // Start at eye/hand, not past nearby walls. Swept-volume collision handles the first tick.
        Vec3 origin = p.getEyePosition().add(0, kind == ArtProjectile.FIREBALL ? -0.12 : -0.3, 0);
        var mouthPath = p.level().clip(new net.minecraft.world.level.ClipContext(p.getEyePosition(), origin,
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
        if (mouthPath.getType() != net.minecraft.world.phys.HitResult.Type.MISS)
            origin = mouthPath.getLocation().add(Vec3.atLowerCornerOf(mouthPath.getDirection().getNormal()).scale(0.05));
        ArtProjectile.launch(p, kind, color, size, origin, dir, speed, shot);
        if (kind == ArtProjectile.WAVE || kind == ArtProjectile.FIREBALL) {
            // 出手冲击环
            int ring = kind == ArtProjectile.WAVE ? 0xFF7FD8FF : 0xFFFF9A3C;
            ArtProjectile.fx(p, ArtProjectile.RING, ring, kind == ArtProjectile.WAVE ? size : size * 0.8f, origin.add(dir.scale(0.7)), dir, 9);
        }
    }

    /** 两发敌对弹幕对撞的火花 */
    public static void clashFx(ServerPlayer p, Vec3 at) {
        ArtProjectile.fx(p, ArtProjectile.HIT_SEAL, 0xFFFFFFFF, 1f, at, p.getViewVector(1), 10);
        ArtFx.soundAt(p, at, net.minecraft.sounds.SoundEvents.ANVIL_LAND, 0.35f, 1.8f);
    }

    /** 弹幕血量归零被击碎 */
    public static void shatterFx(ArtProjectile e, ServerPlayer p) {
        Vec3 at = e.position(), dir = e.getLookAngle();
        switch (e.kind()) {
            case ArtProjectile.SPIRIT -> ArtProjectile.fx(p, ArtProjectile.HIT_SLASH, 0xFF8FE3FF, 1f, at, dir, 11);
            case ArtProjectile.WIND -> ArtProjectile.fx(p, ArtProjectile.HIT_WIND, 0xFF5BE3A8, 1f, at, dir, 17);
            case ArtProjectile.WAVE -> ArtProjectile.fx(p, ArtProjectile.HIT_SEAL, 0xFF5FC8FF, 1f, at, dir, 10);
            case ArtProjectile.FIREBALL -> ArtProjectile.fx(p, ArtProjectile.HIT_SEAL, 0xFFFF8A2A, 1f, at, dir, 10);
            default -> ArtProjectile.fx(p, ArtProjectile.HIT_SEAL, e.color(), 1f, at, dir, 10);
        }
        ArtFx.soundAt(p, at, net.minecraft.sounds.SoundEvents.GLASS_BREAK, 0.5f, 1.3f);
    }

    /** 命中 / 击中方块时的视觉特效 */
    private static void hitFx(ArtProjectile projectile, ServerPlayer p, Shot shot, Vec3 point) {
        Vec3 dir = projectile.getLookAngle();
        switch (shot.skill()) {
            case SPIRIT_SLASH -> ArtProjectile.fx(p, ArtProjectile.HIT_SLASH, 0xFF8FE3FF, 1f, point, dir, 11);
            case WIND_SLASH -> ArtProjectile.fx(p, ArtProjectile.HIT_WIND, 0xFF5BE3A8, 1f, point, dir, 17);
            case HADOKEN -> ArtProjectile.fx(p, ArtProjectile.HIT_WAVE, 0xFF5FC8FF, projectile.size(), point, dir, 13);
            case EIGHT_FORMATION -> ArtProjectile.fx(p, ArtProjectile.HIT_SEAL, projectile.color(), 1f, point, dir, 10);
            default -> {}
        }
    }

    public static void impact(ArtProjectile projectile, ServerPlayer p, Shot shot, LivingEntity target, Vec3 point) {
        if (shot.skill() != ArtSkill.GREAT_FIREBALL) hitFx(projectile, p, shot, point);
        if (shot.skill() == ArtSkill.GREAT_FIREBALL) {
            ArtProjectile.burst(p, point);
            ArtFx.soundAt(p, point, com.zhushen.space.sound.ModSounds.artSfx(shot.skill().pool).impact().get(), 1.0f, 0.9f);
            // Exactly radius 10 (diameter 20), fixed even at full charge. No block destruction.
            for (LivingEntity t : p.serverLevel().getEntitiesOfClass(LivingEntity.class, new AABB(point, point).inflate(10),
                    t -> t != p && t.isAlive() && !t.isSpectator() && t.isAttackable() && !t.isAlliedTo(p))) {
                if (t.getBoundingBox().getCenter().distanceToSqr(point) <= 100
                        && AreaShape.effectLine(p.serverLevel(), point, t, projectile)) damage(projectile, p, shot, t);
            }
        } else if (target != null) damage(projectile, p, shot, target);
        else ArtFx.soundAt(p, point, com.zhushen.space.sound.ModSounds.artSfx(shot.skill().pool).impact().get(), 0.45f, 1.1f); // 打在方块上
    }

    private static void damage(ArtProjectile projectile, ServerPlayer p, Shot shot, LivingEntity t) {
        if (shot.hit().contains(t.getUUID()) || t instanceof net.minecraft.world.entity.player.Player victim && !p.canHarmPlayer(victim)) return;
        shot.hit().add(t.getUUID());
        float value;
        if (shot.skill() == ArtSkill.GREAT_FIREBALL) {
            value = Math.min(shot.cap(), Math.max(0, shot.check() * shot.roll())) * shot.charge();
            value -= Math.max(0, ArtManager.reflexSave(t, p, true) - 18);
        } else {
            int speed = shot.skill() == ArtSkill.HADOKEN || shot.skill() == ArtSkill.EIGHT_FORMATION ? 8 : 0;
            float defense = ArtManager.defense(p, t, speed, 0, false);
            if (shot.skill() == ArtSkill.WIND_SLASH) defense = Math.max(0, defense - (float) DamageRules.naturalDefense(t));
            value = Math.min(shot.cap(), Math.max(0, (shot.check() - defense) * shot.roll() + shot.bonus())) * shot.charge();
        }
        // 弹幕被削弱（受击 / 对撞）后剩余血量越少，命中伤害越低
        value = Math.max(0, value) * shot.restraint() * Math.max(0f, Math.min(1f, projectile.hpFraction()));
        if (value <= 0) { p.displayClientMessage(net.minecraft.network.chat.Component.translatable("msg.zhushenspace.art.miss"), true); return; }
        DamageKind kind = switch (shot.skill()) {
            case WIND_SLASH -> DamageKind.SLASH;
            case HADOKEN -> DamageKind.BLUNT;
            case GREAT_FIREBALL -> DamageKind.FIRE;
            case EIGHT_FORMATION -> new DamageKind[]{DamageKind.FIRE, DamageKind.COLD, DamageKind.LIGHTNING, DamageKind.ACID, DamageKind.PURE_ENERGY}[shot.element()];
            default -> DamageKind.PURE_ENERGY;
        };
        PlayerHealthData.Severity sev = shot.skill() == ArtSkill.GREAT_FIREBALL || shot.skill() == ArtSkill.EIGHT_FORMATION
                ? PlayerHealthData.Severity.L : PlayerHealthData.Severity.B;
        if (DamageRules.deal(t, ArtDamage.source(projectile, p, kind), value, ArtManager.spec(sev, 0, 0, true, kind))) {
            if (shot.skill() == ArtSkill.WIND_SLASH) ArtManager.knockUp(p, t, value);
            if (shot.skill() == ArtSkill.GREAT_FIREBALL) t.igniteForSeconds(3);
            // One restrained hit sound; no generic rings, sparks or forced camera shake.
            ArtFx.soundAt(p, t.position(), com.zhushen.space.sound.ModSounds.artSfx(shot.skill().pool).hit().get(), 0.65f, 1f);
        }
    }
}
