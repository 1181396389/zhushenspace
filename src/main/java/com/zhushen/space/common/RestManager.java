package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.PlayerHealthData;
import com.zhushen.space.network.MeditatePosePayload;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.SleepFinishedTimeEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 自然恢复：短休 / 长休（仅玩家；时间一律按「现实标准」换算，MC 中一天固定 = 24000 tick = 24 小时）。
 * <ul>
 *   <li><b>短休</b>（动作轮盘「短休」）：原地静坐 1 分钟，不限次数；移动、受伤、攻击或再次点击会打断。
 *       完成时恢复全部冲击伤（B），各能量池按各自的判定恢复（见 {@link PoolEffects#shortRestPools}）。</li>
 *   <li><b>长休</b>（动作轮盘「长休」，或睡床）：静坐 8 小时（现实标准，= 8000 tick），每 24 小时只能进行一次；
 *       睡床跳过夜晚时立即完成。完成时恢复全部冲击伤与严重伤（B / L）、1 点恶性伤（A），
 *       各能量池按各自的长休判定恢复（见 {@link EnergyManager#longRestPools}），意志力回满。
 *       24 小时内已长休过时，睡床只算一次短休。</li>
 *   <li>饱和度 / 饱食度不再回复生命值（见 {@link HealthManager#onHeal}），伤势需要休息、快速医疗或治疗能力处理。</li>
 * </ul>
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class RestManager {

    /** 现实标准：1 小时 = 1000 tick（MC 一天 24000 tick = 24 小时） */
    public static final int TICKS_PER_HOUR = 1000;
    /** 一天（24 小时） */
    public static final long DAY_TICKS = 24L * TICKS_PER_HOUR;
    /** 短休时长：1 分钟（真实时间） */
    public static final int SHORT_TICKS = 1200;
    /** 长休时长：8 小时（现实标准） */
    public static final int LONG_TICKS = 8 * TICKS_PER_HOUR;
    /** 长休时恢复的恶性伤点数 */
    public static final int LONG_REST_A_HEAL = 1;
    /** 静坐时允许的最大水平位移（超出视为移动，打断休息） */
    private static final double MOVE_TOLERANCE_SQR = 0.6 * 0.6;
    /** 静坐禁步用的缓慢等级（与打坐一致） */
    private static final int LOCK_AMPLIFIER = 9;

    public enum Kind { SHORT, LONG }

    private static final class Rest {
        Kind kind;
        long end;
        int duration;
        Vec3 pos;
        /** 睡床（不禁步、不播放静坐姿态，醒来即中止） */
        boolean bed;
    }

    private static final Map<UUID, Rest> RESTS = new HashMap<>();

    private RestManager() {
    }

    // ===== 查询 =====

    public static boolean isResting(ServerPlayer p) {
        return RESTS.containsKey(p.getUUID());
    }

    /** 休息计时用的单调 tick（不受睡觉跳夜 / 时间规则影响） */
    private static long now(ServerPlayer p) {
        return p.getServer() != null ? p.getServer().overworld().getGameTime() : p.level().getGameTime();
    }

    /** 世界时钟（主世界 dayTime，含睡觉跳过的夜晚）：长休的「24 小时一次」按它计算 */
    private static long worldClock(ServerPlayer p) {
        return p.getServer() != null ? p.getServer().overworld().getDayTime() : p.level().getDayTime();
    }

    /** 睡床长休的宽限：每晚入睡时刻略有早晚，差 1 小时以内仍算满 24 小时 */
    private static final long BED_GRACE = TICKS_PER_HOUR;

    private static boolean canLongRest(ServerPlayer p, boolean bed) {
        return longRestReadyIn(p) <= (bed ? BED_GRACE : 0);
    }

    /** 距离下一次可以长休还有多少 tick（0 = 现在就可以） */
    public static long longRestReadyIn(ServerPlayer p) {
        long last = p.getData(ModAttachments.LAST_LONG_REST);
        if (last == Long.MIN_VALUE) return 0;
        long t = worldClock(p);
        if (last > t) return 0; // 世界时间回退（换存档等）：视为可用
        return Math.max(0, last + DAY_TICKS - t);
    }

    /** 重置长休冷却（指令） */
    public static void resetLongRest(ServerPlayer p) {
        p.setData(ModAttachments.LAST_LONG_REST, Long.MIN_VALUE);
    }

    // ===== 开始 / 中止 =====

    /** 动作轮盘：开始 / 取消短休或长休 */
    public static void toggle(ServerPlayer p, Kind kind) {
        Rest cur = RESTS.get(p.getUUID());
        if (cur != null) {
            cancel(p, "msg.zhushenspace.rest.cancel");
            return;
        }
        if (StatusManager.incapacitated(p)) return;
        if (!p.onGround() || p.isPassenger()) {
            p.displayClientMessage(Component.translatable("msg.zhushenspace.rest.not_ground"), true);
            return;
        }
        if (kind == Kind.LONG) {
            long wait = longRestReadyIn(p);
            if (wait > 0) {
                p.displayClientMessage(Component.translatable("msg.zhushenspace.rest.long_used", clock(wait)), true);
                return;
            }
        }
        start(p, kind, false);
    }

    private static void start(ServerPlayer p, Kind kind, boolean bed) {
        Rest r = new Rest();
        r.kind = kind;
        r.duration = kind == Kind.LONG ? LONG_TICKS : SHORT_TICKS;
        r.end = now(p) + r.duration;
        r.pos = p.position();
        r.bed = bed;
        RESTS.put(p.getUUID(), r);
        if (!bed) {
            p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, r.duration, LOCK_AMPLIFIER, false, false));
            sendPose(p, r.duration);
            p.displayClientMessage(Component.translatable(kind == Kind.LONG
                    ? "msg.zhushenspace.rest.long_start" : "msg.zhushenspace.rest.short_start"), true);
        }
        ArtManager.sync(p);
    }

    /** 中止休息（不给予任何恢复） */
    public static void cancel(ServerPlayer p, String messageKey) {
        Rest r = RESTS.remove(p.getUUID());
        if (r == null) return;
        if (!r.bed) {
            unlock(p);
            sendPose(p, 0);
        }
        if (messageKey != null) p.displayClientMessage(Component.translatable(messageKey), true);
        ArtManager.sync(p);
    }

    /** 撤去静坐禁步（只撤本系统施加的那一档缓慢） */
    private static void unlock(ServerPlayer p) {
        MobEffectInstance e = p.getEffect(MobEffects.MOVEMENT_SLOWDOWN);
        if (e != null && e.getAmplifier() == LOCK_AMPLIFIER) p.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
    }

    private static void sendPose(ServerPlayer p, int ticks) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(p, new MeditatePosePayload(p.getId(), ticks));
    }

    // ===== 完成 =====

    private static void complete(ServerPlayer p, Kind kind, boolean bed) {
        Rest r = RESTS.remove(p.getUUID());
        if (r != null && !r.bed) {
            unlock(p);
            sendPose(p, 0);
        }
        if (!p.isAlive()) return;
        if (kind == Kind.LONG && !canLongRest(p, bed)) kind = Kind.SHORT; // 24 小时内已长休：降为短休

        PlayerHealthData hp = p.getData(ModAttachments.PLAYER_HEALTH);
        List<String> parts = new ArrayList<>();
        int healed;
        if (kind == Kind.SHORT) {
            int b = hp.healSeverity(PlayerHealthData.Severity.B, Integer.MAX_VALUE);
            healed = b;
            if (b > 0) parts.add("B-" + b);
            parts.addAll(PoolEffects.shortRestPools(p));
        } else {
            int b = hp.healSeverity(PlayerHealthData.Severity.B, Integer.MAX_VALUE);
            int l = hp.healSeverity(PlayerHealthData.Severity.L, Integer.MAX_VALUE);
            int a = hp.healSeverity(PlayerHealthData.Severity.A, LONG_REST_A_HEAL);
            healed = b + l + a;
            if (b > 0) parts.add("B-" + b);
            if (l > 0) parts.add("L-" + l);
            if (a > 0) parts.add("A-" + a);
            parts.addAll(EnergyManager.longRestPools(p));
            if (WillpowerManager.refill(p)) {
                parts.add(Component.translatable("energy.zhushenspace.willpower").getString() + "+");
            }
            p.setData(ModAttachments.LAST_LONG_REST, worldClock(p));
        }
        // 体力回满；长休精力回满
        StatusManager.data(p).stamina = SurvivalManager.maxStamina(p);
        StatusManager.data(p).exhausted = false;
        if (kind == Kind.LONG) SurvivalManager.restoreSleep(p, SurvivalManager.MAX);
        else StatusManager.sync(p);
        // 伤势移除后归位生命值并同步（长休顺带回满未断部位的部位血量）
        HealthManager.afterHeal(p, kind == Kind.LONG ? 10000 : healed);
        EnergyManager.sync(p);
        ArtManager.sync(p);

        String key = kind == Kind.LONG ? "msg.zhushenspace.rest.long_done" : "msg.zhushenspace.rest.short_done";
        String detail = parts.isEmpty() ? Component.translatable("msg.zhushenspace.rest.nothing").getString()
                : String.join(" ", parts);
        p.displayClientMessage(Component.translatable(key, detail), false);
        p.level().playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS,
                0.8f, kind == Kind.LONG ? 0.8f : 1.2f);
        p.serverLevel().sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1, p.getZ(), 20, 0.4, 0.6, 0.4, 0.05);
    }

    /** tick → 「H:MM」（现实标准小时）+ 真实分秒 */
    private static String clock(long ticks) {
        long hours = ticks / TICKS_PER_HOUR;
        long minutes = (ticks % TICKS_PER_HOUR) * 60 / TICKS_PER_HOUR;
        long realSec = (ticks + 19) / 20;
        return String.format("%d:%02d（%d:%02d）", hours, minutes, realSec / 60, realSec % 60);
    }

    // ===== 事件 =====

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        long t = server.overworld().getGameTime();
        // 睡床自动进入休息（24 小时内可长休则为长休，否则为短休）
        if (server.getTickCount() % 20 == 0) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.isSleeping() && !RESTS.containsKey(p.getUUID())) {
                    start(p, canLongRest(p, true) ? Kind.LONG : Kind.SHORT, true);
                }
            }
        }
        if (RESTS.isEmpty()) return;
        Iterator<Map.Entry<UUID, Rest>> it = RESTS.entrySet().iterator();
        List<ServerPlayer> done = new ArrayList<>();
        List<ServerPlayer> moved = new ArrayList<>();
        List<ServerPlayer> woke = new ArrayList<>();
        while (it.hasNext()) {
            Map.Entry<UUID, Rest> e = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) {
                it.remove();
                continue;
            }
            Rest r = e.getValue();
            if (r.bed) {
                if (!p.isSleeping()) woke.add(p);
                else if (t >= r.end) done.add(p);
                continue;
            }
            Vec3 pos = p.position();
            double dx = pos.x - r.pos.x, dz = pos.z - r.pos.z;
            if (dx * dx + dz * dz > MOVE_TOLERANCE_SQR) moved.add(p);
            else if (t >= r.end) done.add(p);
        }
        for (ServerPlayer p : moved) cancel(p, "msg.zhushenspace.rest.moved");
        for (ServerPlayer p : woke) cancel(p, null);
        for (ServerPlayer p : done) {
            Rest r = RESTS.get(p.getUUID());
            if (r != null) complete(p, r.kind, r.bed);
        }
    }

    /** 睡床跳过夜晚：正在睡觉的玩家立即完成休息 */
    @SubscribeEvent
    public static void onSleepFinished(SleepFinishedTimeEvent event) {
        for (Player entity : event.getLevel().players()) {
            if (!(entity instanceof ServerPlayer p) || !p.isSleeping()) continue;
            complete(p, canLongRest(p, true) ? Kind.LONG : Kind.SHORT, true);
        }
    }

    /** 受伤打断（睡床时原版本身也会惊醒） */
    @SubscribeEvent
    public static void onDamage(LivingDamageEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer p && event.getNewDamage() > 0 && isResting(p)) {
            cancel(p, "msg.zhushenspace.rest.hurt");
        }
    }

    /** 攻击打断 */
    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && isResting(p)) {
            cancel(p, "msg.zhushenspace.rest.cancel");
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) RESTS.remove(p.getUUID());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        RESTS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) cancel(p, null);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        RESTS.clear();
    }
}
