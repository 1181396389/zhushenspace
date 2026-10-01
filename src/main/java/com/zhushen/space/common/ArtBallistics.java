package com.zhushen.space.common;

import com.zhushen.space.data.*;
import com.zhushen.space.entity.art.ArtProjectile;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Release-time stat snapshot, collision-time target defense. No instant target damage. */
public final class ArtBallistics {
    private ArtBallistics() {}
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
        ArtFx.releaseSfx(p, s.pool, 0.45f);
        Vec3 dir = p.getViewVector(1);
        // Start at eye/hand, not past nearby walls. Swept-volume collision handles the first tick.
        Vec3 origin = p.getEyePosition().add(0, kind == ArtProjectile.FIREBALL ? -0.12 : -0.3, 0);
        var mouthPath = p.level().clip(new net.minecraft.world.level.ClipContext(p.getEyePosition(), origin,
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
        if (mouthPath.getType() != net.minecraft.world.phys.HitResult.Type.MISS)
            origin = mouthPath.getLocation().add(Vec3.atLowerCornerOf(mouthPath.getDirection().getNormal()).scale(0.05));
        if (kind == ArtProjectile.SEAL) {
            // 阵留在施法者面前（与蓄力时同一位置），三道激光从阵心射出；被墙挡住时阵贴在墙前
            ArtProjectile seal = ArtProjectile.seal(p, color);
            seal.releaseSeal(shot);
        } else ArtProjectile.launch(p, kind, color, size, origin, dir, speed, shot);
        return true;
    }

    public static void impact(ArtProjectile projectile, ServerPlayer p, Shot shot, LivingEntity target, Vec3 point) {
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
        value = Math.max(0, value) * shot.restraint();
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
        if (DamageRules.deal(t, p.damageSources().indirectMagic(projectile, p), value, ArtManager.spec(sev, 0, 0, true, kind))) {
            if (shot.skill() == ArtSkill.WIND_SLASH) ArtManager.knockUp(p, t, value);
            if (shot.skill() == ArtSkill.GREAT_FIREBALL) t.igniteForSeconds(3);
            // One restrained hit sound; no generic rings, sparks or forced camera shake.
            ArtFx.soundAt(p, t.position(), com.zhushen.space.sound.ModSounds.artSfx(shot.skill().pool).hit().get(), 0.65f, 1f);
        }
    }
}
