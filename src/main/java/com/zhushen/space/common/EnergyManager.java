package com.zhushen.space.common;

import com.zhushen.space.network.MeditatePosePayload;
import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.PlayerAttributeData;
import com.zhushen.space.data.PlayerEnergyData;
import com.zhushen.space.data.SkillAbility;
import com.zhushen.space.network.SyncEnergyPayload;
import com.zhushen.space.sound.ModSounds;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.SleepFinishedTimeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 能量池服务端管理。能量池没有通用回复速率——恢复方式由各加成来源分别提供。
 *
 * 主能量池：玩家基础容量最大的能量池（通常是内力池），不生成任何额外的池。
 * 沉着的传奇加成直接扩充主能量池容量：
 * - 沉着满 5 点：每点传奇点数 +3 上限
 * 决心不再扩充任何能量池，改为决定意志力池（{@link WillpowerManager}）：上限 = 决心加点，满级后每点传奇 +3。
 * 上限每次由属性重新推导（基础容量 + 加成），不会重复累加；玩家没有任何能量池时加成不生效、也不会凭空生成池。
 *
 * 内力（neili）：东方通用能量池，容量 = 耐力 + 感知。获得时自动附带两个技能：
 * - 内力吐息（自动档）：开启后近战攻击（含普攻）消耗 1 点内力，+6 伤害
 * - 打坐（冷却 5 分钟）：禁步静坐 5 秒后回满内力；睡觉时内力也会自行回满
 *
 * 池容量随属性加点动态重算（{@link #syncLegendaryPools}）。
 * 其他 id 的能量池可通过 /zhushen energy 指令发放（作为额外池，无自动恢复方式）。
 */
@EventBusSubscriber(modid = com.zhushen.space.ZhuShenSpace.MODID)
public class EnergyManager {

    /** 旧版本的独立主池 id（现已不再生成，重算时清除） */
    public static final String POOL_MAIN = "main";
    /** 内力池 id：东方通用能量池（耐力+感知） */
    public static final String POOL_NEILI = "neili";
    /** 传奇加成：每点传奇点数为主能量池提供的上限（决心 / 沉着满级时各自生效，可叠加；每次重算推导，不会重复累加） */
    public static final double CAPACITY_PER_LEGENDARY = 3.0;
    /** 内力吐息：每次近战攻击的伤害加成 */
    private static final double BREATH_BONUS_DAMAGE = 6.0;
    /** 打坐禁步时长（tick）：5 秒 */
    private static final int MEDITATION_CHANNEL_TICKS = 100;
    /** 旧版本的独立池 id（迁移时清除） */
    private static final String LEGACY_RESOLVE = "resolve";
    private static final String LEGACY_COMPOSURE = "composure";

    /** 打坐进行中：玩家 UUID → 打坐结束时的服务器 tick */
    private static final Map<UUID, Long> MEDITATION_CHANNEL_END = new HashMap<>();

    /** 通用色板（未知的池 id 按哈希取色） */
    private static final int[] PALETTE = {
            0xFF7BC94D, 0xFFC94D7B, 0xFF9B59E0, 0xFF4DE0C0, 0xFF5980E0
    };

    /** ===== 传奇加成接入 ===== */

    /**
     * 按当前属性重算能量池容量（属性提交与登录/重生/换维度时调用）：
     * - 传奇加成（决心 / 沉着满 5 点时，每点传奇点数各 +3，二者可叠加）直接扩充玩家主能量池上限
     *   ——主能量池即基础容量最大的池（通常是内力池），不生成任何额外的池。
     * - 上限 = 基础容量 + 加成，每次都从属性重新推导而非累加，因此反复提交属性 / 重登 /
     *   摘戴饰品都不会重复获得 +3；玩家没有任何能量池时加成不生效、也不会凭空出现一个池。
     * - 内力池基础容量 = 耐力 + 感知（已获得时）。
     * 同时清除旧版本的独立池数据。
     */
    public static void syncLegendaryPools(ServerPlayer player) {
        PlayerAttributeData attrs = player.getData(ModAttachments.PLAYER_ATTRIBUTES);
        int[] p = attrs.points();
        int leg = AttributeType.legendaryCount(p);

        double bonus = 0;
        if (leg > 0) {
            if (p[AttributeType.COMPOSURE.ordinal()] >= AttributeType.MAX_POINTS) {
                bonus += leg * CAPACITY_PER_LEGENDARY;
            }
        }

        PlayerEnergyData data = player.getData(ModAttachments.PLAYER_ENERGY);
        data.removePool(LEGACY_RESOLVE);
        data.removePool(LEGACY_COMPOSURE);
        // 旧版本曾以 main 池作为无真实池时的加成载体：现在没有能量池就不显示、不加成，一律清除
        data.removePool(POOL_MAIN);

        // 内力池：基础容量随耐力+感知重算（未获得内力池则无影响）
        if (data.getPool(POOL_NEILI) != null) {
            data.setBaseCapacity(POOL_NEILI,
                    p[AttributeType.ENDURANCE.ordinal()] + p[AttributeType.PERCEPTION.ordinal()]);
        }

        // 专长能量池（灵力 / 精神力 / 妖力 / 佛力 / 魔力 / 道力 / 灵能 / 内力 / 查克拉）
        syncFeatPools(player, data, p);

        // 主能量池 = 基础容量最大的池：传奇加成直接扩充其上限（无池时 applyMainPoolBonus 无事可做）
        data.applyMainPoolBonus(bonus);
        // 意志力池：上限 = 决心加点（+ 决心满级时每点传奇 +3），独立于主能量池
        WillpowerManager.recalc(player, data);
        sync(player);
    }

    /** 专长能量池：拥有专长则按属性重算基础容量；失去专长则移除（内力池可能由太极饰品发放，不移除） */
    private static void syncFeatPools(ServerPlayer player, PlayerEnergyData data, int[] p) {
        for (FeatEffects.Pool pool : FeatEffects.Pool.values()) {
            boolean has = FeatEffects.has(player, pool.feat);
            if (has) {
                double cap = pool.capacity(p);
                if (POOL_NEILI.equals(pool.id)) cap = Math.max(cap, p[AttributeType.ENDURANCE.ordinal()] + p[AttributeType.PERCEPTION.ordinal()]);
                int legRC = Math.max(0, p[AttributeType.RESOLVE.ordinal()] - 4) + Math.max(0, p[AttributeType.COMPOSURE.ordinal()] - 4);
                if (pool == FeatEffects.Pool.SPIRIT) cap += 3 * legRC;   // 灵力：每点传奇决心 / 沉着 +3
                if (pool == FeatEffects.Pool.MIND) cap += 5 * legRC;     // 精神力：每点传奇决心 / 沉着 +5
                if (pool == FeatEffects.Pool.CHAKRA) {
                    // 仙术查克拉：上限永久 = 查克拉基础上限，分开计算；每 1 点仙术查克拉使查克拉上限 −1
                    boolean freshSage = data.getPool(PoolEffects.SAGE) == null;
                    data.setBaseCapacity(PoolEffects.SAGE, cap);
                    var sage = data.getPool(PoolEffects.SAGE);
                    if (freshSage) sage.current = 0;
                    sage.max = cap;
                    sage.current = Math.min(sage.current, cap);
                    cap = Math.max(0, cap - sage.current);
                }
                boolean fresh = data.getPool(pool.id) == null;
                data.setBaseCapacity(pool.id, cap);
                if (fresh) data.getPool(pool.id).current = cap;
            } else if (!POOL_NEILI.equals(pool.id) && data.getPool(pool.id) != null) {
                data.removePool(pool.id);
                if (pool == FeatEffects.Pool.CHAKRA) data.removePool(PoolEffects.SAGE);
            }
        }
    }

    /** ===== 内力：自动获得的技能 ===== */

    /** 内力吐息：自动档开关（仅拥有内力池时可用，状态持久化）。返回是否切换成功 */
    public static boolean toggleBreath(ServerPlayer player) {
        PlayerEnergyData data = player.getData(ModAttachments.PLAYER_ENERGY);
        if (data.getPool(POOL_NEILI) == null) {
            player.displayClientMessage(Component.translatable("msg.zhushenspace.neili.none"), true);
            return false;
        }
        if (TaiChiManager.isPoolSealed(player)) {
            player.displayClientMessage(Component.translatable("msg.zhushenspace.neili.sealed"), true);
            return false;
        }
        boolean on = !data.breathEnabled();
        data.setBreathEnabled(on);
        player.displayClientMessage(Component.translatable(
                on ? "msg.zhushenspace.breath.on" : "msg.zhushenspace.breath.off"), true);
        // 特效：开启时磬音 + 内力上升，关闭时低磬音 + 消散
        player.level().playSound(null, player.blockPosition(),
                ModSounds.NEILI_CHIME.get(), SoundSource.PLAYERS, 0.9f, on ? 1.0f : 0.6f);
        if (on && player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.END_ROD,
                    player.getX(), player.getY() + 1.0, player.getZ(),
                    12, 0.3, 0.1, 0.3, 0.03);
        }
        sync(player);
        return true;
    }

    /**
     * 打坐：禁步 5 秒后回满内力（冷却由 SkillManager 冷却系统统一管理）。
     * 返回是否真正开始打坐（内力已满或打坐进行中返回 false，不消耗冷却）。
     */
    public static boolean tryMeditate(ServerPlayer player) {
        PlayerEnergyData.Pool pool = player.getData(ModAttachments.PLAYER_ENERGY).getPool(POOL_NEILI);
        if (pool == null) {
            player.displayClientMessage(Component.translatable("msg.zhushenspace.neili.none"), true);
            return false;
        }
        if (MEDITATION_CHANNEL_END.containsKey(player.getUUID())) return false;
        if (pool.current >= pool.max) {
            player.displayClientMessage(Component.translatable("msg.zhushenspace.neili.full"), true);
            return false;
        }

        MEDITATION_CHANNEL_END.put(player.getUUID(),
                (long) player.getServer().getTickCount() + MEDITATION_CHANNEL_TICKS);
        // 禁步：重度缓慢使玩家在打坐期间无法移动（效果时长与打坐时长一致，到期自散）
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,
                MEDITATION_CHANNEL_TICKS, 9, false, true));
        sendPose(player, MEDITATION_CHANNEL_TICKS);
        player.displayClientMessage(Component.translatable("msg.zhushenspace.meditate.start"), true);
        // 特效：钟磬共鸣 + 符文环汇聚
        player.level().playSound(null, player.blockPosition(),
                ModSounds.NEILI_MEDITATE_START.get(), SoundSource.PLAYERS, 1.0f, 1.0f);
        enchantRing(player);
        return true;
    }

    /** 广播打坐姿态（自身 + 周围可见玩家）：ticks>0 盘坐调息，0 收功起身 */
    private static void sendPose(ServerPlayer player, int ticks) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new MeditatePosePayload(player.getId(), ticks));
    }

    /** 中断打坐（死亡 / 登出 / 重生）：移除计时并通知客户端起身 */
    private static void interruptMeditation(net.minecraft.world.entity.player.Player player) {
        if (MEDITATION_CHANNEL_END.remove(player.getUUID()) != null && player instanceof ServerPlayer sp) {
            sendPose(sp, 0);
        }
    }

    /** 打坐完成：内力回满 */
    private static void finishMeditation(ServerPlayer player) {
        sendPose(player, 0);
        if (!player.isAlive()) return; // 打坐期间死亡：打坐中断
        PlayerEnergyData data = player.getData(ModAttachments.PLAYER_ENERGY);
        if (data.getPool(POOL_NEILI) == null) return;
        if (data.restore(POOL_NEILI, Double.MAX_VALUE) > 0) {
            player.displayClientMessage(Component.translatable("msg.zhushenspace.meditate.done"), true);
            // 特效：磬音上扬 + 内力粒子迸发
            player.level().playSound(null, player.blockPosition(),
                    ModSounds.NEILI_MEDITATE_DONE.get(), SoundSource.PLAYERS, 1.0f, 1.0f);
            if (player.level() instanceof ServerLevel level) {
                level.sendParticles(ParticleTypes.END_ROD,
                        player.getX(), player.getY() + 1.0, player.getZ(),
                        24, 0.4, 0.6, 0.4, 0.08);
            }
            sync(player);
        }
    }

    /** 以玩家为中心的符文环（打坐 / 听劲类气场的公共底纹） */
    private static void enchantRing(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return;
        for (int i = 0; i < 12; i++) {
            double a = Math.PI * 2 * i / 12;
            level.sendParticles(ParticleTypes.ENCHANT,
                    player.getX() + Math.cos(a) * 0.8, player.getY() + 1.1,
                    player.getZ() + Math.sin(a) * 0.8, 2, 0, 0.08, 0, 0.04);
        }
    }

    /** ===== 恢复方式 ===== */

    /** 内力吐息：开启时近战直击（含普攻）消耗 1 点内力，+6 伤害 */
    @SubscribeEvent
    public static void onDamagePre(LivingDamageEvent.Pre event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer attacker)) return;
        if (attacker == event.getEntity()) return;
        if (WillpowerManager.isBonusStrike(attacker)) return; // 意志加持追加伤害：固定 9 点
        if (event.getSource().getDirectEntity() != attacker) return; // 仅近战直击，弹射物除外
        if (GunDamage.isGun(event.getSource())) return; // TACZ 伪装近战的子弹不算近战
        PlayerEnergyData data = attacker.getData(ModAttachments.PLAYER_ENERGY);
        if (!data.breathEnabled() || data.getPool(POOL_NEILI) == null) return;
        if (TaiChiManager.isPoolSealed(attacker)) return; // 能量池被封印
        if (data.consume(POOL_NEILI, 1.0)) {
            event.setNewDamage(event.getNewDamage() + (float) BREATH_BONUS_DAMAGE);
            sync(attacker);
        }
    }

    /** 打坐计时推进（沉着传奇的静立恢复已移除） */
    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        long tick = event.getServer().getTickCount();

        // 打坐计时：期间每 0.5 秒符文环绕体，结束时回满内力
        if (!MEDITATION_CHANNEL_END.isEmpty()) {
            boolean ambientTick = tick % 10 == 0;
            Iterator<Map.Entry<UUID, Long>> it = MEDITATION_CHANNEL_END.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Long> entry = it.next();
                if (tick >= entry.getValue()) {
                    it.remove();
                    ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
                    if (player != null) {
                        finishMeditation(player);
                    }
                } else if (ambientTick) {
                    ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
                    if (player != null) {
                        enchantRing(player);
                    }
                }
            }
        }
    }

    /**
     * 长休：各能量池按各自的长休判定恢复（灵能 / 妖力走检定，仙术查克拉不恢复，其余回满），内力回满。
     * 由 {@link RestManager} 在长休完成时调用（每 24 小时一次），调用方负责同步；返回恢复摘要。
     */
    public static java.util.List<String> longRestPools(ServerPlayer player) {
        PlayerEnergyData data = player.getData(ModAttachments.PLAYER_ENERGY);
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (FeatEffects.Pool fp : FeatEffects.Pool.values()) {
            if (POOL_NEILI.equals(fp.id) || data.getPool(fp.id) == null) continue;
            double before = data.getPool(fp.id).current;
            if (!PoolEffects.longRest(player, fp.id)) data.restore(fp.id, Double.MAX_VALUE);
            var pool = data.getPool(fp.id);
            double got = pool == null ? 0 : pool.current - before;
            if (got > 0) parts.add(Component.translatable("energy.zhushenspace." + fp.id).getString()
                    + "+" + (int) Math.round(got));
        }
        if (data.getPool(POOL_NEILI) != null && data.restore(POOL_NEILI, Double.MAX_VALUE) > 0) {
            parts.add(Component.translatable("energy.zhushenspace." + POOL_NEILI).getString() + "+");
        }
        return parts;
    }

    /** ===== 数据维护接口（指令 / 未来技能消耗接入） ===== */

    /** 从能量池消耗能量，成功后同步客户端（被封印时不可消耗） */
    public static boolean consume(ServerPlayer player, String id, double amount) {
        if (TaiChiManager.isPoolSealed(player)) return false;
        PlayerEnergyData data = player.getData(ModAttachments.PLAYER_ENERGY);
        if (!data.consume(id, amount)) return false;
        sync(player);
        return true;
    }

    /** 发放（或调整上限）能量池：不存在则创建并填满 */
    public static void grantPool(ServerPlayer player, String id, double max) {
        player.getData(ModAttachments.PLAYER_ENERGY).grantPool(id, max);
        // 内力池容量由属性决定，发放后立即按属性重算
        if (POOL_NEILI.equals(id)) {
            syncLegendaryPools(player);
        } else {
            sync(player);
        }
    }

    public static void removePool(ServerPlayer player, String id) {
        PlayerEnergyData data = player.getData(ModAttachments.PLAYER_ENERGY);
        data.removePool(id);
        if (POOL_NEILI.equals(id)) {
            data.setBreathEnabled(false);
            // 内力池移除后按属性重算（清理传奇加成等派生数据）
            syncLegendaryPools(player);
        } else {
            sync(player);
        }
    }

    public static void setAmount(ServerPlayer player, String id, double amount) {
        PlayerEnergyData.Pool pool = player.getData(ModAttachments.PLAYER_ENERGY).getPool(id);
        if (pool == null) return;
        pool.current = Math.max(0, Math.min(pool.max, amount));
        sync(player);
    }

    /** 服务端固定颜色（与客户端展示一致）；已知池使用主题色，未知池按 id 哈希取色 */
    public static int colorOf(String id) {
        return switch (id) {
            case POOL_MAIN -> 0xFFE0B84D;         // 主能量池：鎏金（传奇能量）
            case POOL_NEILI -> 0xFF4DE0C0;        // 内力：青碧（东方之气）
            case WillpowerManager.POOL_ID -> 0xFFE0704D; // 意志力：赤焰
            case "spirit" -> 0xFF9FE8FF;   // 灵力：幽蓝
            case "mind" -> 0xFFB48CFF;     // 精神力：紫
            case "yokai" -> 0xFFFF7AB8;    // 妖力：妖粉
            case "buddha" -> 0xFFFFD35A;   // 佛力：金
            case "magic" -> 0xFF5A7BFF;    // 魔力：靛蓝
            case "dao" -> 0xFF7FE0A0;      // 道力：青绿
            case "psychic" -> 0xFFE05AFF;  // 灵能：品红
            case "chakra" -> 0xFF4DA6FF;   // 查克拉：蓝
            default -> PALETTE[Math.floorMod(id.hashCode(), PALETTE.length)];
        };
    }

    /** 同步玩家全部能量池到客户端（含内力吐息开关状态） */
    public static void sync(ServerPlayer player) {
        PlayerEnergyData data = player.getData(ModAttachments.PLAYER_ENERGY);
        int count = data.pools().size();
        String[] ids = new String[count];
        double[] currents = new double[count];
        double[] maxes = new double[count];
        int[] colors = new int[count];
        int i = 0;
        for (var entry : data.pools().entrySet()) {
            ids[i] = entry.getKey();
            currents[i] = entry.getValue().current;
            maxes[i] = entry.getValue().max;
            colors[i] = colorOf(entry.getKey());
            i++;
        }
        PacketDistributor.sendToPlayer(player,
                new SyncEnergyPayload(ids, currents, maxes, colors, data.breathEnabled()));
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncLegendaryPools(player);
        }
    }

    /** 打坐期间死亡：立即中断并起身（不等重生） */
    @SubscribeEvent
    public static void onPlayerDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) interruptMeditation(player);
    }

    /** 登出 / 死亡重生时中断打坐（否则打坐计时仍会在重生后或重新登录后回满内力） */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        interruptMeditation(event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        interruptMeditation(event.getEntity());
        if (event.getEntity() instanceof ServerPlayer player) {
            syncLegendaryPools(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncLegendaryPools(player);
        }
    }
}
