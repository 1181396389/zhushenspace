package com.zhushen.space.common;

import com.zhushen.space.network.HitFeedbackPayload;
import com.zhushen.space.network.PlayerAnimPayload;
import com.zhushen.space.sound.ModSounds;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3f;

/**
 * 太极拳动作与打击特效：
 * <ul>
 *   <li>动作：广播给自身与周围玩家（KosmX Player Animator，第一人称双臂同步挥动）</li>
 *   <li>打击：气劲轨迹（青白色尘雾从掌心射向目标）+ 命中点径向气浪环 + 分层音效 + 双方镜头震动</li>
 * </ul>
 */
public final class TaiChiFx {

    private TaiChiFx() {
    }

    /** 青碧内力 / 月白气劲 / 墨色阴劲 */
    public static final DustParticleOptions QI = new DustParticleOptions(new Vector3f(0.30f, 0.88f, 0.75f), 1.1f);
    public static final DustParticleOptions QI_WHITE = new DustParticleOptions(new Vector3f(0.92f, 0.97f, 1.0f), 0.9f);
    public static final DustParticleOptions QI_DARK = new DustParticleOptions(new Vector3f(0.15f, 0.15f, 0.2f), 1.2f);

    /** 播放动作（自身 + 周围可见玩家） */
    public static void anim(ServerPlayer player, String name) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new PlayerAnimPayload(player.getId(), name));
    }

    /** 镜头震动：攻击者 +（若为玩家）受击者 */
    public static void shake(ServerPlayer attacker, LivingEntity victim, float power, int ticks) {
        if (attacker != null) PacketDistributor.sendToPlayer(attacker, new HitFeedbackPayload(power, ticks));
        if (victim instanceof ServerPlayer vp && vp != attacker) {
            PacketDistributor.sendToPlayer(vp, new HitFeedbackPayload(power * 1.3f, ticks + 2));
        }
    }

    /** 气劲轨迹：从出掌点到目标的尘雾线 */
    public static void trail(ServerPlayer player, LivingEntity target, ParticleOptions p) {
        if (!(player.level() instanceof ServerLevel level)) return;
        Vec3 from = player.getEyePosition().add(0, -0.45, 0).add(player.getViewVector(1f).scale(0.5));
        Vec3 to = target.position().add(0, target.getBbHeight() * 0.55, 0);
        Vec3 d = to.subtract(from);
        int n = Math.max(4, (int) (d.length() * 5));
        for (int i = 0; i <= n; i++) {
            Vec3 q = from.add(d.scale(i / (double) n));
            level.sendParticles(p, q.x, q.y, q.z, 1, 0.02, 0.02, 0.02, 0);
        }
    }

    /** 径向气浪环：以命中点为中心，垂直于出招方向向外扩散 */
    public static void shockRing(ServerPlayer player, LivingEntity target, ParticleOptions p, double speed, int count) {
        if (!(player.level() instanceof ServerLevel level)) return;
        Vec3 c = target.position().add(0, target.getBbHeight() * 0.55, 0);
        Vec3 f = c.subtract(player.getEyePosition()).normalize();
        Vec3 up = Math.abs(f.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = f.cross(up).normalize();
        Vec3 v = f.cross(u).normalize();
        for (int i = 0; i < count; i++) {
            double a = Math.PI * 2 * i / count;
            Vec3 dir = u.scale(Math.cos(a)).add(v.scale(Math.sin(a)));
            // count = 0：xd/yd/zd 作为速度，粒子沿径向飞出
            level.sendParticles(p, c.x + dir.x * 0.2, c.y + dir.y * 0.2, c.z + dir.z * 0.2, 0,
                    dir.x, dir.y, dir.z, speed);
        }
    }

    /** 地面气圈（沉劲 / 待势） */
    public static void groundRing(ServerPlayer player, ParticleOptions p, double radius, int count) {
        if (!(player.level() instanceof ServerLevel level)) return;
        for (int i = 0; i < count; i++) {
            double a = Math.PI * 2 * i / count;
            level.sendParticles(p, player.getX() + Math.cos(a) * radius, player.getY() + 0.05,
                    player.getZ() + Math.sin(a) * radius, 1, 0, 0.01, 0, 0);
        }
    }

    private static void sound(LivingEntity at, SoundEvent s, float vol, float pitch) {
        at.level().playSound(null, at.getX(), at.getY(), at.getZ(), s, SoundSource.PLAYERS, vol, pitch);
    }

    /**
     * 招式命中：分层音效（出招风声 + 肉击 + 重击低频）+ 轨迹 + 气浪 + 震动，强度随伤害。
     *
     * @param heavy 0~1 招式力度（掤/靠等重招更高）
     */
    public static void impact(ServerPlayer player, LivingEntity target, float dealt, float heavy) {
        float k = Math.min(1f, dealt / 30f) * 0.6f + heavy * 0.4f;
        float pv = 0.9f + player.getRandom().nextFloat() * 0.2f;
        sound(target, ModSounds.TAI_CHI_MOVE.get(), 0.9f, pv);
        sound(target, SoundEvents.PLAYER_ATTACK_STRONG, 0.8f, 0.7f + (1 - k) * 0.3f);
        if (k > 0.55f) {
            sound(target, SoundEvents.PLAYER_ATTACK_KNOCKBACK, 0.9f, 0.6f);
            sound(target, SoundEvents.GENERIC_EXPLODE.value(), 0.25f + k * 0.2f, 1.6f);
        }
        trail(player, target, QI);
        shockRing(player, target, QI_WHITE, 0.12 + k * 0.18, 14 + (int) (k * 10));
        if (player.level() instanceof ServerLevel level) {
            double ty = target.getY() + target.getBbHeight() * 0.55;
            level.sendParticles(ParticleTypes.SWEEP_ATTACK, target.getX(), ty, target.getZ(), 1, 0, 0, 0, 0);
            if (k > 0.55f) {
                level.sendParticles(ParticleTypes.EXPLOSION, target.getX(), ty, target.getZ(), 1, 0, 0, 0, 0);
            }
            level.sendParticles(ParticleTypes.CRIT, target.getX(), ty, target.getZ(),
                    6 + (int) (k * 14), 0.25, 0.3, 0.25, 0.25 + k * 0.2);
        }
        shake(player, target, 0.35f + k * 0.6f, 5 + (int) (k * 4));
    }

    /** 待势 / 姿态启动：地面气圈 + 周身气流 */
    public static void stance(ServerPlayer player, float pitch) {
        groundRing(player, QI, 1.0, 20);
        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(QI_WHITE, player.getX(), player.getY() + 1.0, player.getZ(), 10, 0.4, 0.5, 0.4, 0);
        }
        sound(player, SoundEvents.BREEZE_IDLE_GROUND, 0.5f, pitch);
    }
}
