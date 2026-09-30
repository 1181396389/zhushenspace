package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.LimbPart;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.PlayerHealthData;
import com.zhushen.space.data.PlayerLimbData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 快速医疗（资源效果框架；仅玩家）。
 * <ul>
 *   <li>每回合（{@link #ROUND_TICKS}，10 秒）开始时结算。回合时点全服统一，
 *       因此回合中途获得的快速医疗（例如给自己施展、已错过本回合结算时点）从下一回合开始生效。</li>
 *   <li>每个来源提供快速医疗 N：可恢复累计 N 点冲击 / 严重伤（B / L）；
 *       单个来源 ≥ 4 时，也可改为恢复 1 点恶性伤（A）。</li>
 *   <li>不可叠加但可共同生效：同一来源重复获得只取较高等级 / 较长时长；不同来源各自结算
 *       （快速医疗 3 + 快速医疗 2 → 每回合 5 点 B / L，但没有任何一项 ≥ 4，无法恢复恶性伤）。</li>
 *   <li>分配：各来源按等级从低到高依次恢复 B / L（先 B 后 L；昏迷时先 L 以便苏醒）；
 *       B / L 已无伤势时，等级 ≥ 4 的来源各恢复 1 点 A。</li>
 *   <li>断肢再生：任一来源 ≥ 4 时生效。所需时间（小时，现实标准）= 失去肢体数 × 100 ÷ 快速医疗点数合计，
 *       逐条再生（每条 100 ÷ 点数合计 小时）；时间按 MC 一天 = 24 小时换算（1 小时 = 1000 tick）。</li>
 * </ul>
 * 其他能力通过 {@link #grant} 给予临时来源，或 {@link #registerProvider} 注册常驻来源。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class QuickHealManager {

    /** 一回合：10 秒 */
    public static final int ROUND_TICKS = 200;
    /** 单个来源达到此点数可恢复恶性伤 / 再生断肢 */
    public static final int ADVANCED_LEVEL = 4;
    /** 断肢再生：每条失去的肢体折算的「小时 × 点数」 */
    public static final int REGROW_PER_LIMB = 100;
    private static final String KEY_REGROW = "ZsRegrowProgress";

    private record Source(int level, long expire) {
    }

    private static final Map<UUID, Map<String, Source>> SOURCES = new HashMap<>();
    private static final List<Function<ServerPlayer, Map<String, Integer>>> PROVIDERS = new ArrayList<>();

    private QuickHealManager() {
    }

    // ===== 来源管理 =====

    /** 注册常驻来源（专长 / 装备等），返回 来源 id → 点数 */
    public static void registerProvider(Function<ServerPlayer, Map<String, Integer>> provider) {
        PROVIDERS.add(provider);
    }

    /**
     * 给予一个快速医疗来源。同一来源不叠加：取较高等级，等级相同取较长时长。
     *
     * @param durationTicks 持续 tick；&lt;= 0 表示直到移除
     */
    public static void grant(ServerPlayer p, String source, int level, int durationTicks) {
        if (level <= 0) return;
        long expire = durationTicks > 0 ? now(p) + durationTicks : Long.MAX_VALUE;
        Map<String, Source> m = SOURCES.computeIfAbsent(p.getUUID(), k -> new HashMap<>());
        Source old = m.get(source);
        if (old != null && old.expire > now(p)) {
            if (old.level > level || (old.level == level && old.expire >= expire)) return;
        }
        m.put(source, new Source(level, expire));
    }

    public static void revoke(ServerPlayer p, String source) {
        Map<String, Source> m = SOURCES.get(p.getUUID());
        if (m != null) m.remove(source);
    }

    public static void clear(ServerPlayer p) {
        SOURCES.remove(p.getUUID());
    }

    private static long now(ServerPlayer p) {
        return p.getServer() != null ? p.getServer().overworld().getGameTime() : p.level().getGameTime();
    }

    /** 当前生效的各来源点数（来源 id → 点数） */
    public static Map<String, Integer> active(ServerPlayer p) {
        Map<String, Integer> out = new HashMap<>();
        Map<String, Source> m = SOURCES.get(p.getUUID());
        if (m != null) {
            long t = now(p);
            m.values().removeIf(s -> s.expire <= t);
            for (Map.Entry<String, Source> e : m.entrySet()) out.merge(e.getKey(), e.getValue().level, Math::max);
        }
        for (Function<ServerPlayer, Map<String, Integer>> prov : PROVIDERS) {
            Map<String, Integer> extra = prov.apply(p);
            if (extra != null) extra.forEach((k, v) -> { if (v != null && v > 0) out.merge(k, v, Math::max); });
        }
        return out;
    }

    // ===== 结算 =====

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        boolean round = tick % ROUND_TICKS == 0;
        boolean second = tick % 20 == 0;
        if (!round && !second) return;
        for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
            if (!p.isAlive()) continue;
            Map<String, Integer> act = active(p);
            if (act.isEmpty()) continue;
            if (round) round(p, act);
            if (second) regrow(p, act);
        }
    }

    private static void round(ServerPlayer p, Map<String, Integer> act) {
        PlayerHealthData hp = p.getData(ModAttachments.PLAYER_HEALTH);
        if (hp.total() <= 0) return;
        int maxHp = Math.round(p.getMaxHealth());
        boolean lFirst = hp.total() >= maxHp && hp.l() > 0; // 昏迷：先治严重伤以便苏醒
        List<Integer> levels = new ArrayList<>(act.values());
        levels.sort(Integer::compare);
        int b = 0, l = 0, a = 0;
        boolean bloodLoss = StatusEffects.bloodLoss(p); // 失血过多：B / L 如同 A 一样难治（3:1），A 无法治疗
        for (int lv : levels) {
            if (hp.b() + hp.l() > 0) {
                int rem = bloodLoss ? lv / 3 : lv;
                PlayerHealthData.Severity first = lFirst ? PlayerHealthData.Severity.L : PlayerHealthData.Severity.B;
                PlayerHealthData.Severity second = lFirst ? PlayerHealthData.Severity.B : PlayerHealthData.Severity.L;
                int t1 = hp.healSeverity(first, rem);
                rem -= t1;
                int t2 = hp.healSeverity(second, rem);
                if (first == PlayerHealthData.Severity.B) { b += t1; l += t2; } else { l += t1; b += t2; }
            } else if (!bloodLoss && lv >= ADVANCED_LEVEL && hp.a() > 0) {
                a += hp.healSeverity(PlayerHealthData.Severity.A, 1);
            }
        }
        int total = b + l + a;
        if (total <= 0) return;
        HealthManager.afterHeal(p, total);
        StringBuilder sb = new StringBuilder();
        if (b > 0) sb.append(" B-").append(b);
        if (l > 0) sb.append(" L-").append(l);
        if (a > 0) sb.append(" A-").append(a);
        p.displayClientMessage(Component.translatable("msg.zhushenspace.quickheal.round", sb.toString().trim()), true);
    }

    /** 断肢再生：每秒累计「点数合计 × 1 秒对应的小时数」，每满 100 再生一条（总耗时 = 失去肢体数 × 100 ÷ 点数） */
    private static void regrow(ServerPlayer p, Map<String, Integer> act) {
        int sum = 0, best = 0;
        for (int v : act.values()) {
            sum += v;
            best = Math.max(best, v);
        }
        PlayerLimbData limbs = LimbManager.data(p);
        LimbPart next = null;
        int lost = limbs.eyesLost(); // 眼睛是小部位：每只按 1 计
        for (LimbPart part : LimbPart.values()) {
            if (part.severable() && limbs.isSevered(part)) {
                lost++;
                if (next == null) next = part;
            }
        }
        var tag = p.getPersistentData();
        if (lost == 0 || best < ADVANCED_LEVEL || sum <= 0) {
            if (lost == 0) tag.remove(KEY_REGROW);
            return;
        }
        double progress = tag.getDouble(KEY_REGROW) + sum * 20.0 / RestManager.TICKS_PER_HOUR;
        double need = REGROW_PER_LIMB;
        if (progress >= need) {
            if (lost > 1) tag.putDouble(KEY_REGROW, progress - need);
            else tag.remove(KEY_REGROW);
            String name;
            if (next != null) {
                LimbManager.restore(p, next);
                name = next.nameKey();
            } else {
                int eye = limbs.eyeLost(PlayerLimbData.RIGHT_EYE) ? PlayerLimbData.RIGHT_EYE : PlayerLimbData.LEFT_EYE;
                LimbManager.restoreEye(p, eye);
                name = LimbManager.eyeKey(eye);
            }
            p.displayClientMessage(Component.translatable("msg.zhushenspace.quickheal.regrow",
                    Component.translatable(name)), false);
        } else {
            tag.putDouble(KEY_REGROW, progress);
        }
    }

    /** 再生进度 {已累计, 需要}（指令查看） */
    public static double[] regrowProgress(ServerPlayer p) {
        PlayerLimbData limbs = LimbManager.data(p);
        int lost = limbs.eyesLost();
        for (LimbPart part : LimbPart.values()) if (part.severable() && limbs.isSevered(part)) lost++;
        return new double[]{p.getPersistentData().getDouble(KEY_REGROW), lost == 0 ? 0 : REGROW_PER_LIMB};
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SOURCES.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        SOURCES.clear();
    }
}
