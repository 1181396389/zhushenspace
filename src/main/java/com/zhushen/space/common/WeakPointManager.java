package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.sound.ModSounds;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 感知属性·弱点勘破：
 * 攻击无弱点的目标时，按每点感知 5% 的概率在目标身体表面（面向攻击者的一侧）
 * 显现一个黄色弱点光点（呼吸式闪烁粒子，持续 5 秒）。
 * 光点锚定在实体身体坐标系中，随生物移动与转向实时跟随；
 * 只有命中光点（投射物取投射物位置、近战取视线射线交点，半径 0.45 格）才触发
 * 1.5 倍弱点伤害，并伴随音效与粒子反馈；光点命中后消耗。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public class WeakPointManager {

    /** 每点感知提供的弱点光点出现概率 */
    public static final float SPAWN_CHANCE_PER_POINT = 0.05f;
    /** 命中判定半径（格） */
    public static final float HIT_RADIUS = 0.45f;
    /** 光点持续时长（tick） */
    private static final int DURATION_TICKS = 100;
    /** 光点粒子发射间隔（tick） */
    private static final int EMIT_INTERVAL = 2;

    private static final Vector3f YELLOW = new Vector3f(1.0f, 0.85f, 0.1f);

    private static final Map<UUID, WeakPoint> ACTIVE = new HashMap<>();
    /** 弱点再生成冷却：目标 UUID → 冷却结束 tick（目前仅枪械命中消耗弱点时写入） */
    private static final Map<UUID, Integer> COOLDOWN = new HashMap<>();
    private static int serverTick;

    /**
     * 弱点光点：localOffset 为实体身体坐标系偏移（x=身体右侧，y=相对脚底高度，z=身体前方），
     * 随实体位置与朝向实时换算世界坐标，实现跟随生物移动。
     */
    public record WeakPoint(ServerLevel level, Vec3 localOffset, long expireTick) {
    }

    /** 获取目标当前弱点光点（过期自动清除） */
    public static WeakPoint get(LivingEntity target) {
        WeakPoint wp = ACTIVE.get(target.getUUID());
        if (wp == null) return null;
        if (serverTick >= wp.expireTick()) {
            ACTIVE.remove(target.getUUID());
            return null;
        }
        return wp;
    }

    /** 光点当前的世界坐标（随实体位置与身体朝向实时变化） */
    public static Vec3 getWorldPos(LivingEntity entity, WeakPoint wp) {
        return entity.position().add(rotateToWorld(wp.localOffset(), entity));
    }

    /**
     * 判定本次攻击是否命中弱点光点：
     * 投射物取投射物位置（即命中瞬间的碰撞点）；
     * 近战取视线射线到光点的最近距离（不依赖 pick 的方块/实体遮挡，玩家瞄准光点即触发）。
     */
    public static boolean isHit(ServerPlayer player, DamageSource source, Vec3 wpPos) {
        if (source.getDirectEntity() instanceof Projectile projectile) {
            return projectile.position().distanceToSqr(wpPos) <= HIT_RADIUS * HIT_RADIUS;
        }
        double reach = player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE) + 1.0;
        Vec3 eye = player.getEyePosition();
        Vec3 dir = player.getViewVector(1.0F);
        Vec3 toDot = wpPos.subtract(eye);
        double t = toDot.dot(dir);
        if (t <= 0 || t > reach) return false;
        Vec3 closest = eye.add(dir.scale(t));
        return closest.distanceToSqr(wpPos) <= HIT_RADIUS * HIT_RADIUS;
    }

    /** 尝试在目标身上生成弱点光点（每点感知 5% 概率） */
    public static void trySpawn(ServerLevel level, LivingEntity target, ServerPlayer attacker, int perception) {
        if (perception <= 0 || ACTIVE.containsKey(target.getUUID())) return;
        if (attacker.getRandom().nextFloat() >= perception * SPAWN_CHANCE_PER_POINT) return;

        WeakPoint wp = new WeakPoint(level, randomLocalOffset(target, attacker), serverTick + DURATION_TICKS);
        ACTIVE.put(target.getUUID(), wp);
        // 出现时的一簇提示粒子，让玩家注意到光点
        Vec3 pos = getWorldPos(target, wp);
        level.sendParticles(new DustParticleOptions(YELLOW, 1.2f),
                pos.x, pos.y, pos.z, 8, 0.1, 0.1, 0.1, 0.02);
    }

    /** 目标进入弱点再生成冷却 */
    public static void startCooldown(LivingEntity target, int ticks) {
        COOLDOWN.put(target.getUUID(), serverTick + ticks);
    }

    /** 目标是否处于弱点再生成冷却中 */
    public static boolean onCooldown(LivingEntity target) {
        Integer until = COOLDOWN.get(target.getUUID());
        if (until == null) return false;
        if (serverTick >= until) {
            COOLDOWN.remove(target.getUUID());
            return false;
        }
        return true;
    }

    /** 命中弱点：消耗光点并播放音效与视觉反馈 */
    public static void consume(ServerLevel level, LivingEntity target) {
        WeakPoint wp = ACTIVE.remove(target.getUUID());
        if (wp == null) return;
        Vec3 pos = getWorldPos(target, wp);

        level.playSound(null, pos.x, pos.y, pos.z,
                ModSounds.WEAK_POINT_HIT.get(), SoundSource.PLAYERS, 1.0f, 1.0f);
        // 黄色爆发 + 闪耀 + 暴击星尘
        level.sendParticles(new DustParticleOptions(YELLOW, 1.6f),
                pos.x, pos.y, pos.z, 20, 0.25, 0.25, 0.25, 0.12);
        level.sendParticles(ParticleTypes.END_ROD,
                pos.x, pos.y, pos.z, 10, 0.2, 0.2, 0.2, 0.15);
        level.sendParticles(ParticleTypes.CRIT,
                pos.x, pos.y, pos.z, 12, 0.3, 0.3, 0.3, 0.2);
    }

    /**
     * 在实体身体坐标系表面随机取一点：面向攻击者的一侧（前后按攻击者方位确定），
     * 随机高度（15%~85% 身高）与横向位置，外扩 0.02 格避免粒子嵌入身体。
     */
    private static Vec3 randomLocalOffset(LivingEntity target, ServerPlayer attacker) {
        AABB box = target.getBoundingBox();
        RandomSource rnd = target.getRandom();
        double halfWidth = target.getBbWidth() / 2.0;
        double y = (0.15 + 0.7 * rnd.nextDouble()) * (box.maxY - box.minY);
        double lateral = (rnd.nextDouble() * 2 - 1) * halfWidth;

        // 攻击者位于身体前方还是后方（世界向量 → 身体坐标系）
        Vec3 toAttacker = attacker.getEyePosition().subtract(target.position());
        float yawRad = target.yBodyRot * ((float) Math.PI / 180F);
        double localForward = -toAttacker.x * Mth.sin(yawRad) + toAttacker.z * Mth.cos(yawRad);
        double forward = (localForward >= 0 ? 1 : -1) * (halfWidth + 0.02);
        return new Vec3(lateral, y, forward);
    }

    /** 身体坐标偏移 → 世界坐标偏移（yaw=0 时身体前方为 +Z） */
    private static Vec3 rotateToWorld(Vec3 local, LivingEntity entity) {
        float yawRad = entity.yBodyRot * ((float) Math.PI / 180F);
        double wx = local.x * Mth.cos(yawRad) - local.z * Mth.sin(yawRad);
        double wz = local.x * Mth.sin(yawRad) + local.z * Mth.cos(yawRad);
        return new Vec3(wx, local.y, wz);
    }

    // ===== 服务端 tick：光点粒子呼吸闪烁 + 过期/实体消失清理 =====

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        serverTick++;
        if (!COOLDOWN.isEmpty() && serverTick % 200 == 0) {
            COOLDOWN.values().removeIf(until -> serverTick >= until);
        }
        if (ACTIVE.isEmpty()) return;
        boolean emit = serverTick % EMIT_INTERVAL == 0;
        Iterator<Map.Entry<UUID, WeakPoint>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, WeakPoint> entry = it.next();
            WeakPoint wp = entry.getValue();
            if (serverTick >= wp.expireTick()) {
                it.remove();
                continue;
            }
            Entity entity = wp.level().getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity living) || living.isRemoved() || !living.isAlive()) {
                it.remove();
                continue;
            }
            if (emit) {
                // 每次发射都按实体实时位置与朝向换算 → 光点跟随生物移动与转向
                Vec3 pos = getWorldPos(living, wp);
                float size = 0.45f + 0.15f * (float) Math.sin(serverTick * 0.35);
                wp.level().sendParticles(new DustParticleOptions(YELLOW, size),
                        pos.x, pos.y, pos.z, 2, 0.02, 0.02, 0.02, 0.0);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ACTIVE.clear();
        COOLDOWN.clear();
        serverTick = 0;
    }
}
