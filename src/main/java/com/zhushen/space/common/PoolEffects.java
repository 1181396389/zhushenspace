package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.ArtSkill;
import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.PlayerArtData;
import com.zhushen.space.data.PlayerEnergyData;
import com.zhushen.space.network.MagicSensePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 能量池基础用法与恢复：
 * - 能量加值（轮盘开关）：检定时自动花 1 点对应能量 +1DP（=3）；同类能量加值不叠加，取一个池。
 *   精神力：决心 / 沉着检定；魔力：心智系（智力 / 感知 / 决心）检定；妖力：生理系（力量 / 敏捷 / 耐力）与感知检定；
 *   灵能：任何检定。道力：道术施法检定额外 1 点 → +3DP。佛力：抵抗心灵影响的豁免 +2DP。
 * - 魔力感知 / 蛛行术 / 水面行走 / 灵感视觉 / 短休（冥想）/ 长休：均在动作轮盘中使用，不占技能栏（休息见 RestManager）。
 * - 仙术查克拉：静止每 60 秒 1D10，出 10 则 1 点查克拉转为仙术查克拉；仙人模式下施展忍术可消耗。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class PoolEffects {
    private PoolEffects() {}

    public static final String SAGE = "sage";
    /** 轮盘状态位（同步到客户端） */
    public static final int F_BOOST = 1, F_SPIDER = 2, F_WATER = 4, F_SENSE = 8, F_SIGHT = 16, F_REST = 32;

    private static final Map<UUID, Long> SENSE_UNTIL = new HashMap<>(), SIGHT_UNTIL = new HashMap<>(),
            SPIDER_NEXT = new HashMap<>(), WATER_NEXT = new HashMap<>(), REST_END = new HashMap<>(),
            REST_CD = new HashMap<>();
    private static final Map<UUID, Integer> STILL = new HashMap<>();
    private static final Map<UUID, Vec3> LAST_POS = new HashMap<>();

    static PlayerEnergyData energy(ServerPlayer p) { return p.getData(ModAttachments.PLAYER_ENERGY); }

    static boolean has(ServerPlayer p, String id) { return energy(p).getPool(id) != null; }

    static double cur(ServerPlayer p, String id) {
        var pool = energy(p).getPool(id);
        return pool == null ? 0 : pool.current;
    }

    static int attr(ServerPlayer p, AttributeType t) { return ArtManager.attr(p, t); }

    static int leg(ServerPlayer p, AttributeType t) { return Math.max(0, attr(p, t) - 4); }

    /** 两项属性检定（成功数） */
    static int check(ServerPlayer p, AttributeType a, AttributeType b) {
        return Math.max(0, Math.round((attr(p, a) + attr(p, b)) * DamageVariance.roll(p.getRandom())));
    }

    public static int flags(ServerPlayer p) {
        long now = p.level().getGameTime();
        int f = 0;
        if (ArtManager.data(p).boost) f |= F_BOOST;
        if (SPIDER_NEXT.containsKey(p.getUUID())) f |= F_SPIDER;
        if (WATER_NEXT.containsKey(p.getUUID())) f |= F_WATER;
        if (SENSE_UNTIL.getOrDefault(p.getUUID(), 0L) > now) f |= F_SENSE;
        if (SIGHT_UNTIL.getOrDefault(p.getUUID(), 0L) > now) f |= F_SIGHT;
        if (RestManager.isResting(p)) f |= F_REST;
        return f;
    }

    // ===== 能量加值 =====

    /**
     * 检定能量加值：返回加到判定上的点数（1DP = 3），并扣除 1 点能量。未开启 / 无适用池返回 0。
     * attrs = 本次检定涉及的属性。
     */
    public static int checkBonus(ServerPlayer p, AttributeType... attrs) {
        if (!ArtManager.data(p).boost) return 0;
        boolean mental = false, mind = false, body = false;
        for (AttributeType t : attrs) {
            switch (t) {
                case RESOLVE, COMPOSURE -> { mind = true; if (t == AttributeType.RESOLVE) mental = true; }
                case INTELLIGENCE -> mental = true;
                case PERCEPTION -> { mental = true; body = true; }
                case STRENGTH, AGILITY, ENDURANCE -> body = true;
                default -> {}
            }
        }
        String[] order = {mind ? "mind" : null, mental ? "magic" : null, body ? "yokai" : null, "psychic"};
        for (String id : order) {
            if (id != null && cur(p, id) >= 1 && EnergyManager.consume(p, id, 1)) return 3;
        }
        return 0;
    }

    /** 道力：道术施法检定额外消耗 1 点 → +3DP（=9）。开启能量加值时自动使用 */
    public static int daoBonus(ServerPlayer p) {
        if (!ArtManager.data(p).boost || cur(p, "dao") < 1) return 0;
        return EnergyManager.consume(p, "dao", 1) ? 9 : 0;
    }

    /** 佛力：抵抗心灵影响的豁免 / 对抗 +2DP（=6） */
    public static int buddhaSave(ServerPlayer p) {
        if (!ArtManager.data(p).boost || cur(p, "buddha") < 1) return 0;
        return EnergyManager.consume(p, "buddha", 1) ? 6 : 0;
    }

    // ===== 灵力（灵感疲劳：池当前值 = 上限 − 疲劳） =====

    /** 灵力启动上限 = 决心 + 沉着（+ 每点传奇决心 / 沉着 3） */
    public static int spiritLimit(ServerPlayer p) {
        return attr(p, AttributeType.RESOLVE) + attr(p, AttributeType.COMPOSURE)
                + 3 * (leg(p, AttributeType.RESOLVE) + leg(p, AttributeType.COMPOSURE));
    }

    /** 灵力启动检定：失败则消耗照付；累积 消耗/3 灵感疲劳。返回是否成功 */
    public static boolean spiritActivate(ServerPlayer p, int cost) {
        if (cur(p, "spirit") <= 0) { ArtManager.deny(p, "msg.zhushenspace.pool.spirit_tired"); return false; }
        if (cost > spiritLimit(p)) { ArtManager.deny(p, "msg.zhushenspace.pool.spirit_limit"); return false; }
        int roll = check(p, AttributeType.RESOLVE, AttributeType.COMPOSURE);
        if (cost > 0) EnergyManager.consume(p, "spirit", Math.min(cur(p, "spirit"), cost / 3.0));
        if (roll < cost) { ArtManager.deny(p, "msg.zhushenspace.pool.spirit_fail"); return false; }
        return true;
    }

    // ===== 仙术查克拉 =====

    /** 仙人模式（接口：进入方式待定，目前恒为 false） */
    public static boolean sageMode(ServerPlayer p) { return false; }

    /** 仙人模式下施展忍术：消耗 1 点仙术查克拉，威力 +X（D2 C4 B8 A16 S32） */
    public static int sageBoost(ServerPlayer p, int rankBonus) {
        if (!sageMode(p) || cur(p, SAGE) < 1) return 0;
        return EnergyManager.consume(p, SAGE, 1) ? rankBonus : 0;
    }

    // ===== 轮盘动作 =====

    public static void action(ServerPlayer p, int a) {
        UUID id = p.getUUID();
        long now = p.level().getGameTime();
        switch (a) {
            case 10 -> { PlayerArtData d = ArtManager.data(p); d.boost = !d.boost; }
            case 11 -> { // 魔力感知
                if (!has(p, "magic")) { ArtManager.deny(p, "msg.zhushenspace.art.no_pool"); break; }
                if (!EnergyManager.consume(p, "magic", 1)) { ArtManager.deny(p, "msg.zhushenspace.art.lack_energy"); break; }
                SENSE_UNTIL.put(id, now + 1200);
                p.level().playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1f, 1.2f);
                senseScan(p);
            }
            case 12, 13 -> { // 蛛行术 / 水面行走（再次点击关闭；持续期间每分钟自动续 1 点）
                Map<UUID, Long> m = a == 12 ? SPIDER_NEXT : WATER_NEXT;
                if (m.remove(id) != null) break;
                if (!has(p, "chakra")) { ArtManager.deny(p, "msg.zhushenspace.art.no_pool"); break; }
                if (!EnergyManager.consume(p, "chakra", 1)) { ArtManager.deny(p, "msg.zhushenspace.art.lack_energy"); break; }
                m.put(id, now + 1200);
            }
            case 14 -> { // 灵感视觉
                if (!has(p, "dao")) { ArtManager.deny(p, "msg.zhushenspace.art.no_pool"); break; }
                if (!EnergyManager.consume(p, "dao", 1)) { ArtManager.deny(p, "msg.zhushenspace.art.lack_energy"); break; }
                SIGHT_UNTIL.put(id, now + 1200);
                p.displayClientMessage(Component.translatable("msg.zhushenspace.pool.sight_on"), true);
            }
            case 15 -> RestManager.toggle(p, RestManager.Kind.SHORT); // 短休（冥想）
            case 16 -> RestManager.toggle(p, RestManager.Kind.LONG);  // 长休
            default -> {}
        }
        ArtManager.sync(p);
    }

    /** 灵感视觉中（可接触灵体；灵体系统接入时查询） */
    public static boolean spiritSight(ServerPlayer p) {
        return SIGHT_UNTIL.getOrDefault(p.getUUID(), 0L) > p.level().getGameTime();
    }

    // ===== 魔力感知 =====

    static boolean magical(LivingEntity e) {
        if (e instanceof ServerPlayer sp) {
            if (has(sp, "magic")) return true;
            for (ArtSkill s : ArtSkill.values()) if (ArtManager.buff(sp, s) && ArtManager.isSpell(s)) return true;
            return false;
        }
        EntityType<?> t = e.getType();
        return t == EntityType.WITCH || t == EntityType.EVOKER || t == EntityType.ILLUSIONER || t == EntityType.VEX
                || e.hasEffect(MobEffects.INVISIBILITY) || e.hasEffect(MobEffects.LEVITATION);
    }

    static void senseScan(ServerPlayer p) {
        double r = Math.max(1, attr(p, AttributeType.PERCEPTION));
        List<Integer> ids = new ArrayList<>();
        for (LivingEntity e : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(r),
                e -> e != p && e.distanceTo(p) <= r && magical(e))) ids.add(e.getId());
        long left = SENSE_UNTIL.getOrDefault(p.getUUID(), 0L) - p.level().getGameTime();
        PacketDistributor.sendToPlayer(p, new MagicSensePayload(ids.stream().mapToInt(Integer::intValue).toArray(),
                (int) Math.max(0, left)));
    }

    /** 施法时通知附近处于魔力感知中的玩家 */
    public static void onSpellCast(ServerPlayer caster, ArtSkill s) {
        long now = caster.level().getGameTime();
        for (ServerPlayer o : caster.serverLevel().players()) {
            if (o == caster || SENSE_UNTIL.getOrDefault(o.getUUID(), 0L) <= now) continue;
            if (o.distanceTo(caster) > Math.max(1, attr(o, AttributeType.PERCEPTION))) continue;
            o.displayClientMessage(Component.translatable("msg.zhushenspace.pool.sense_cast",
                    caster.getDisplayName(), Component.translatable(s.ability.nameKey())), false);
        }
    }

    // ===== 冥想（短休）：开始 / 打断 / 完成由 RestManager 统一管理 =====
    /** 子时（23~1 点）/ 午时（11~13 点）：天地气机交错 */
    static boolean qiHour(ServerPlayer p) {
        long t = Math.floorMod(p.level().getDayTime(), 24000L);
        return (t >= 17000 && t < 19000) || (t >= 5000 && t < 7000);
    }

    /** 短休：各能量池按各自的判定恢复，返回恢复摘要（由 RestManager 调用，调用方负责同步） */
    static List<String> shortRestPools(ServerPlayer p) {
        PlayerEnergyData d = energy(p);
        List<String> parts = new ArrayList<>();
        restore(p, d, "magic", check(p, AttributeType.INTELLIGENCE, AttributeType.PERCEPTION), parts);
        restore(p, d, "chakra", check(p, AttributeType.ENDURANCE, AttributeType.PERCEPTION), parts);
        restore(p, d, "dao", check(p, AttributeType.PERCEPTION, AttributeType.CHARM), parts);
        restore(p, d, "mind", check(p, AttributeType.RESOLVE, AttributeType.COMPOSURE), parts);
        restore(p, d, "buddha", check(p, AttributeType.RESOLVE, AttributeType.CHARM), parts);
        restore(p, d, "spirit", leg(p, AttributeType.RESOLVE) + leg(p, AttributeType.COMPOSURE), parts);
        restore(p, d, "yokai", qiHour(p) ? Integer.MAX_VALUE : check(p, AttributeType.ENDURANCE, AttributeType.CHARM), parts);
        return parts;
    }

    static void restore(ServerPlayer p, PlayerEnergyData d, String id, int amount, List<String> parts) {
        if (d.getPool(id) == null || amount <= 0) return;
        double got = d.restore(id, amount);
        if (got > 0) parts.add(Component.translatable("energy.zhushenspace." + id).getString() + "+" + (int) Math.round(got));
    }

    /** 长休（睡觉）：灵能 = 心灵检定成功数；妖力 = 耐力+风度检定；其余由 EnergyManager 回满 */
    public static boolean longRest(ServerPlayer p, String id) {
        PlayerEnergyData d = energy(p);
        if ("psychic".equals(id)) { d.restore(id, check(p, AttributeType.RESOLVE, AttributeType.COMPOSURE)); return true; }
        if ("yokai".equals(id)) { d.restore(id, check(p, AttributeType.ENDURANCE, AttributeType.CHARM)); return true; }
        return SAGE.equals(id); // 仙术查克拉不因休息恢复
    }

    // ===== 每刻 =====

    static boolean nearWall(ServerPlayer p) {
        BlockPos b = p.blockPosition();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!p.level().getBlockState(b.relative(d)).getCollisionShape(p.level(), b.relative(d)).isEmpty()) return true;
            if (!p.level().getBlockState(b.above().relative(d)).getCollisionShape(p.level(), b.above().relative(d)).isEmpty()) return true;
        }
        return false;
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post e) {
        for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) {
            UUID id = p.getUUID();
            long now = p.level().getGameTime();
            // 蛛行 / 水面行走：免坠落伤害；每分钟续费，查克拉不足则结束
            if (SPIDER_NEXT.containsKey(id) && nearWall(p)) p.fallDistance = 0;
            if (WATER_NEXT.containsKey(id) && p.level().getFluidState(p.blockPosition().below()).is(FluidTags.WATER)) p.fallDistance = 0;
            boolean changed = false;
            for (Map<UUID, Long> m : List.of(SPIDER_NEXT, WATER_NEXT)) {
                Long t = m.get(id);
                if (t != null && now >= t) {
                    if (EnergyManager.consume(p, "chakra", 1)) m.put(id, now + 1200);
                    else { m.remove(id); changed = true; }
                }
            }
            if (e.getServer().getTickCount() % 20 == 0) {
                if (SENSE_UNTIL.containsKey(id)) {
                    if (SENSE_UNTIL.get(id) > now) senseScan(p);
                    else { SENSE_UNTIL.remove(id); senseScan(p); changed = true; }
                }
                if (SIGHT_UNTIL.containsKey(id) && SIGHT_UNTIL.get(id) <= now) { SIGHT_UNTIL.remove(id); changed = true; }
                sageTick(p);
            }
            if (changed) ArtManager.sync(p);
        }
    }

    /** 静止 60 秒 → 1D10，出 10 则 1 点查克拉转为仙术查克拉 */
    static void sageTick(ServerPlayer p) {
        if (!has(p, "chakra")) return;
        Vec3 pos = p.position();
        Vec3 last = LAST_POS.put(p.getUUID(), pos);
        if (last == null || last.distanceToSqr(pos) > 0.0025) { STILL.put(p.getUUID(), 0); return; }
        int s = STILL.merge(p.getUUID(), 1, Integer::sum);
        if (s < 60) return;
        STILL.put(p.getUUID(), 0);
        if (p.getRandom().nextInt(10) != 9) return;
        PlayerEnergyData d = energy(p);
        var sage = d.getPool(SAGE);
        if (sage == null || sage.current >= sage.max || cur(p, "chakra") < 1) return;
        d.consume("chakra", 1);
        sage.current += 1;
        EnergyManager.syncLegendaryPools(p); // 仙术查克拉每 1 点，查克拉上限 −1
        p.displayClientMessage(Component.translatable("msg.zhushenspace.pool.sage_gain"), true);
        p.serverLevel().sendParticles(ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getY() + 1, p.getZ(), 12, 0.4, 0.5, 0.4, 0);
    }
}
