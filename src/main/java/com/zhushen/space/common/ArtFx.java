package com.zhushen.space.common;

import com.zhushen.space.data.PlayerHealthData;
import com.zhushen.space.network.FxEventPayload;
import com.zhushen.space.sound.ModSounds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 技艺特效总线（服务端）。
 * <p>
 * 设计原则：<b>不使用粒子</b>。所有表现由三部分组成：
 * <ol>
 *   <li><b>动作</b> —— KosmX 玩家动作（assets/zhushenspace/player_animations/art.json），第一人称双臂同步</li>
 *   <li><b>几何特效</b> —— {@link FxEventPayload} 广播给周围玩家，客户端用即时几何体（光束 / 刀光 / 阵纹 / 锥形冲击 / 电弧）绘制</li>
 *   <li><b>分层音效</b> —— 起手 / 释放 / 命中 三段，音量音高随技能池与威力变化</li>
 * </ol>
 * 能量池配色与音色统一在这里定义，保证 9 个流派一眼可辨（形状 + 颜色 + 声音三重区分）。
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = com.zhushen.space.ZhuShenSpace.MODID)
public final class ArtFx {
    private record Pending(net.minecraft.server.MinecraftServer server, ServerPlayer player,
                           net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                           int due, Runnable action) {}
    private static final java.util.List<Pending> PENDING = new java.util.ArrayList<>();

    @net.neoforged.bus.api.SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        var server = event.getServer();
        var ready = new java.util.ArrayList<Pending>();
        PENDING.removeIf(task -> {
            if (task.server() != server) return false;
            if (task.player().isRemoved() || !task.player().isAlive()
                    || task.player().serverLevel().dimension() != task.dimension()) return true;
            if (server.getTickCount() >= task.due()) { ready.add(task); return true; }
            return false;
        });
        for (var task : ready) task.action().run();
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        PENDING.removeIf(task -> task.server() == event.getServer());
    }


    private ArtFx() {
    }

    /** 一次广播的默认半径（格） */
    private static final double RANGE = 96.0;

    // ===== 能量池配色（ARGB）=====
    public static final int C_SPIRIT = 0xFF9FE8FF;  // 灵力：青白
    public static final int C_MIND = 0xFFC77DFF;    // 精神力：紫
    public static final int C_PSYCHIC = 0xFF76FF9E; // 灵能：生体绿
    public static final int C_YOKAI = 0xFFFF7043;   // 妖力：赤橙
    public static final int C_BUDDHA = 0xFFFFD54F;  // 佛法：金
    public static final int C_MAGIC = 0xFF7C4DFF;   // 魔法：紫罗兰
    public static final int C_DAO = 0xFF59F0E6;     // 道术：青碧
    public static final int C_NEILI = 0xFFFFF3C4;   // 内力：月白
    public static final int C_CHAKRA = 0xFFFF8A3D;  // 查克拉：橙

    /** 能量池主色 */
    public static int color(FeatEffects.Pool pool) {
        if (pool == null) return C_NEILI;
        return switch (pool) {
            case SPIRIT -> C_SPIRIT;
            case MIND -> C_MIND;
            case PSYCHIC -> C_PSYCHIC;
            case YOKAI -> C_YOKAI;
            case BUDDHA -> C_BUDDHA;
            case MAGIC -> C_MAGIC;
            case DAO -> C_DAO;
            case NEILI -> C_NEILI;
            case CHAKRA -> C_CHAKRA;
        };
    }

    // ===== 动作 =====

    /** 播放技艺动作（自身 + 周围可见玩家；动作缺失时客户端静默跳过） */
    public static void anim(ServerPlayer p, String name) {
        TaiChiFx.anim(p, name);
    }

    // ===== 几何特效广播 =====

    /** 广播一条几何特效给周围玩家（含自己） */
    public static void send(ServerPlayer p, int kind, Vec3 from, Vec3 to, int color, float power, int life) {
        send(p, kind, from, to, color, power, life, (int) (Math.random() * 100000));
    }

    public static void send(ServerPlayer p, int kind, Vec3 from, Vec3 to, int color, float power, int life, int seed) {
        FxEventPayload payload = new FxEventPayload(kind,
                from.x, from.y, from.z, to.x, to.y, to.z, color, power, life, seed);
        PacketDistributor.sendToPlayersNear((ServerLevel) p.level(), null,
                from.x, from.y, from.z, RANGE, payload);
    }

    /** 施法者的手部（用于几何特效起点）：眼位下移 + 沿视线前伸 */
    public static Vec3 hand(ServerPlayer p) {
        return p.getEyePosition().add(0, -0.45, 0).add(p.getViewVector(1f).scale(0.6));
    }

    // ===== 复合表现（起手 / 飞行 / 命中）=====

    /**
     * 蓄力：施法者身前的能量球逐步聚拢。
     * 形状由客户端插值——power 控制半径，life 控制聚拢时长。
     */
    public static void charge(ServerPlayer p, int color, float power, int life) {
        Vec3 h = hand(p);
        Vec3 dir = p.getViewVector(1f);
        send(p, FxEventPayload.ORB, h, h.add(dir.scale(1.2)), color, power, life);
    }

    /**
     * 弹体飞行：从起点到终点的光束，位置由客户端按 life 插值推进（= 真正「飞过去」）。
     * 仅推进视觉弹体；战斗判定仍由调用方即时完成。
     */
    public static void projectile(ServerPlayer p, Vec3 to, int color, float width, int travelTicks) {
        Vec3 from = hand(p);
        send(p, FxEventPayload.BEAM, from, to, color, width, Math.max(2, travelTicks));
    }

    /** 直线光柱（按坐标两端点）：治疗、灌顶、佛光、气柱等 */
    public static void beamAt(ServerPlayer p, Vec3 from, Vec3 to, int color, float width, int life) {
        send(p, FxEventPayload.BEAM, from, to, color, width, life);
    }

    /** 命中（按坐标）：短促的几何爆点 + 冲击环，不涉及实体生命周期 */
    public static void impactAt(ServerPlayer p, Vec3 c, int color, float power) {
        send(p, FxEventPayload.IMPACT, c, p.getEyePosition(), color, power, 8);
        shake(p, null, 0.3f + power * 0.35f, 5);
    }

    /** 命中（按实体）：几何爆点 + 镜头反馈 */
    public static void hit(ServerPlayer p, LivingEntity t, int color, float power) {
        impactAt(p, center(t), color, power);
        shake(p, t, 0.3f + power * 0.4f, 6);
    }

    /** 实体胸口高度（特效中心） */
    public static Vec3 center(LivingEntity t) {
        return t.position().add(0, t.getBbHeight() * 0.55, 0);
    }

    /** 仅调度表现，不延迟战斗判定；在换维度、死亡、退出后取消。 */
    public static void delay(ServerPlayer p, int ticks, Runnable r) {
        var server = p.getServer();
        if (server == null || p.isRemoved()) return;
        if (ticks <= 0) { r.run(); return; }
        if (PENDING.size() >= 2048) return;
        PENDING.add(new Pending(server, p, p.serverLevel().dimension(), server.getTickCount() + ticks, r));
    }

    /** 地面阵纹：以 from 为顶点、从 from 指向 to 的扇面（含环形穴纹） */
    public static void formation(ServerPlayer p, Vec3 center, Vec3 facing, int color, float radius) {
        send(p, FxEventPayload.FORMATION, center, facing, color, radius, 40);
    }

    /** 锥形冲击：以 from 为顶点指向 to 的扇形冲击波（豪火球等范围技） */
    public static void cone(ServerPlayer p, Vec3 apex, Vec3 facing, int color, float length) {
        send(p, FxEventPayload.CONE, apex, facing, color, length, 16);
    }

    /** 弧形刀光（按坐标）：中心 + 朝向 */
    public static void slashAt(ServerPlayer p, Vec3 c, Vec3 facing, int color, float radius) {
        send(p, FxEventPayload.SLASH, c, facing, color, radius, 9);
    }

    /** 弧形刀光：在命中点沿施法者方向展开的月牙刃面（斩击类） */
    public static void slash(ServerPlayer p, LivingEntity t, int color, float radius) {
        Vec3 c = center(t);
        slashAt(p, c, c.subtract(p.getEyePosition()), color, radius);
    }

    /** 折线电弧：从起点到终点的分叉闪电（生体闪电等） */
    public static void arc(ServerPlayer p, Vec3 to, int color, float power) {
        send(p, FxEventPayload.ARC, p.getEyePosition(), to, color, power, 6);
    }

    // ===== 音效 =====

    /** 分层播放：同一位置按音量/音高叠两层，得到比单次播放更厚的声音 */
    public static void soundAt(ServerPlayer p, Vec3 at, SoundEvent e, float vol, float pitch) {
        p.level().playSound(null, at.x, at.y, at.z, e, SoundSource.PLAYERS, vol, pitch);
    }

    /** 起手音（聚气 / 结印） */
    public static void castSfx(ServerPlayer p, FeatEffects.Pool pool, float power) {
        ModSounds.ArtSfx sfx = ModSounds.artSfx(pool);
        soundAt(p, p.position(), sfx.cast().get(), 0.7f + power * 0.3f, 1.0f);
    }

    /** 释放音（飞行 / 出招） */
    public static void releaseSfx(ServerPlayer p, FeatEffects.Pool pool, float power) {
        ModSounds.ArtSfx sfx = ModSounds.artSfx(pool);
        soundAt(p, p.position().add(p.getViewVector(1f).scale(1.5)), sfx.release().get(),
                0.8f + power * 0.4f, 0.95f + p.getRandom().nextFloat() * 0.12f);
    }

    /** 命中音（按坐标，按伤害严重度加重） */
    public static void hitSfxAt(ServerPlayer p, Vec3 at, FeatEffects.Pool pool, PlayerHealthData.Severity sev) {
        ModSounds.ArtSfx sfx = ModSounds.artSfx(pool);
        float heavy = sev == PlayerHealthData.Severity.A ? 1.0f : sev == PlayerHealthData.Severity.B ? 0.7f : 0.45f;
        soundAt(p, at, sfx.hit().get(), 0.75f + heavy * 0.5f, 1.0f - heavy * 0.12f);
        // 重击再加一层低频，制造「重量」
        if (heavy > 0.6f) {
            soundAt(p, at, sfx.impact().get(), 0.5f + heavy * 0.4f, 0.85f);
        }
        shake(p, null, 0.35f + heavy * 0.55f, 5 + (int) (heavy * 4));
    }

    /** 命中音（按实体） */
    public static void hitSfx(ServerPlayer p, LivingEntity t, FeatEffects.Pool pool, PlayerHealthData.Severity sev) {
        hitSfxAt(p, t.position(), pool, sev);
        shake(p, t, 0.35f + (sev == PlayerHealthData.Severity.A ? 1.0f : 0.6f), 6);
    }

    /** 镜头反馈：攻击者 + 受击玩家 */
    public static void shake(ServerPlayer attacker, LivingEntity victim, float power, int ticks) {
        TaiChiFx.shake(attacker, victim, power, ticks);
    }

    /** 镜头反馈（仅攻击者） */
    public static void shakeSelf(ServerPlayer p, float power, int ticks) {
        TaiChiFx.shake(p, null, power, ticks);
    }
}
