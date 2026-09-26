package com.zhushen.space.common;

import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.PlayerAttributeData;
import com.zhushen.space.data.PlayerEnergyData;
import com.zhushen.space.data.SkillAbility;
import com.zhushen.space.network.SyncEnergyPayload;
import com.zhushen.space.network.UseEnergyAbilityPayload;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 能量池服务端管理。能量池没有通用回复速率——恢复方式由各加成来源分别提供。
 *
 * 主能量池：玩家基础容量最大的能量池（通常是内力池），不生成任何额外的池。
 * 决心与沉着的传奇加成直接扩充主能量池容量：
 * - 决心满 5 点：每点传奇点数 +3 上限（容量加成）
 * - 沉着满 5 点：每点传奇点数 +3 上限（与决心叠加），并解锁"原地静立或潜行时每秒 +1"
 * 仅当玩家没有任何真实池时，才以 main 池作为传奇加成的载体。
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

    /** 主能量池 id：决心/沉着传奇加成的唯一载体 */
    public static final String POOL_MAIN = "main";
    /** 内力池 id：东方通用能量池（耐力+感知） */
    public static final String POOL_NEILI = "neili";
    /** 传奇加成：每点传奇点数提供的主能量池上限 */
    public static final double CAPACITY_PER_LEGENDARY = 3.0;
    /** 沉着加成：静立/潜行时的恢复间隔（tick） */
    private static final int COMPOSURE_INTERVAL_TICKS = 20;
    /** 沉着加成：判定"原地静立"的水平速度平方阈值 */
    private static final double CALM_SPEED_SQR = 1.0E-4;
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
     * - 传奇加成（决心/沉着满 5 点时，每点传奇点数各 +3，可叠加）直接扩充玩家主能量池
     *   ——主能量池即基础容量最大的池（通常是内力池），不生成任何额外的池。
     * - 仅当玩家没有任何真实池时，才以 main 池作为传奇加成的载体（基础容量 0）。
     * - 内力池基础容量 = 耐力 + 感知（已获得时）。
     * 同时清除旧版本的独立池数据。
     */
    public static void syncLegendaryPools(ServerPlayer player) {
        PlayerAttributeData attrs = player.getData(ModAttachments.PLAYER_ATTRIBUTES);
        int[] p = attrs.points();
        int leg = AttributeType.legendaryCount(p);

        double bonus = 0;
        if (leg > 0) {
            if (p[AttributeType.RESOLVE.ordinal()] >= AttributeType.MAX_POINTS) {
                bonus += leg * CAPACITY_PER_LEGENDARY;
            }
            if (p[AttributeType.COMPOSURE.ordinal()] >= AttributeType.MAX_POINTS) {
                bonus += leg * CAPACITY_PER_LEGENDARY;
            }
        }

        PlayerEnergyData data = player.getData(ModAttachments.PLAYER_ENERGY);
        data.removePool(LEGACY_RESOLVE);
        data.removePool(LEGACY_COMPOSURE);

        // 内力池：基础容量随耐力+感知重算（未获得内力池则无影响）
        if (data.getPool(POOL_NEILI) != null) {
            data.setBaseCapacity(POOL_NEILI,
                    p[AttributeType.ENDURANCE.ordinal()] + p[AttributeType.PERCEPTION.ordinal()]);
        }

        boolean hasRealPool = data.pools().keySet().stream().anyMatch(id -> !POOL_MAIN.equals(id));
        if (hasRealPool) {
            // 有真实池（如内力池）：传奇加成并入最大的池，独立 main 池不再存在
            data.removePool(POOL_MAIN);
        } else if (bonus > 0) {
            // 无任何真实池：main 作为传奇加成的唯一载体（新建即满，基础容量 0）
            data.grantPool(POOL_MAIN, bonus);
            data.setBaseCapacity(POOL_MAIN, 0);
        } else {
            data.removePool(POOL_MAIN);
        }

        // 主能量池 = 基础容量最大的池：传奇加成直接扩充其上限
        data.applyMainPoolBonus(bonus);
        sync(player);
    }

    /** 沉着加成是否生效（沉着满 5 点且有传奇点数） */
    private static boolean composureActive(ServerPlayer player) {
        PlayerAttributeData attrs = player.getData(ModAttachments.PLAYER_ATTRIBUTES);
        return attrs.get(AttributeType.COMPOSURE.ordinal()) >= AttributeType.MAX_POINTS
                && attrs.legendaryPoints() > 0;
    }

    /** ===== 内力：自动获得的技能 ===== */

    /** 能量池附带技能入口（K/H 快捷键触发，与战斗预设栏共用冷却状态） */
    public static void useEnergyAbility(ServerPlayer player, int ability) {
        switch (ability) {
            case UseEnergyAbilityPayload.ABILITY_BREATH ->
                    SkillManager.useNeiliAbility(player, SkillAbility.NEILI_BREATH);
            case UseEnergyAbilityPayload.ABILITY_MEDITATE ->
                    SkillManager.useNeiliAbility(player, SkillAbility.NEILI_MEDITATE);
        }
    }

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
        player.displayClientMessage(Component.translatable("msg.zhushenspace.meditate.start"), true);
        // 特效：钟磬共鸣 + 符文环汇聚
        player.level().playSound(null, player.blockPosition(),
                ModSounds.NEILI_MEDITATE_START.get(), SoundSource.PLAYERS, 1.0f, 1.0f);
        enchantRing(player);
        return true;
    }

    /** 打坐完成：内力回满 */
    private static void finishMeditation(ServerPlayer player) {
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
        if (event.getSource().getDirectEntity() != attacker) return; // 仅近战直击，弹射物除外
        PlayerEnergyData data = attacker.getData(ModAttachments.PLAYER_ENERGY);
        if (!data.breathEnabled() || data.getPool(POOL_NEILI) == null) return;
        if (TaiChiManager.isPoolSealed(attacker)) return; // 能量池被封印
        if (data.consume(POOL_NEILI, 1.0)) {
            event.setNewDamage(event.getNewDamage() + (float) BREATH_BONUS_DAMAGE);
            sync(attacker);
        }
    }

    /** 沉着加成恢复方式：原地静立或潜行，每秒 +1 恢复主能量池；同时推进打坐完成判定 */
    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        long tick = event.getServer().getTickCount();
        boolean calmPhase = tick % COMPOSURE_INTERVAL_TICKS == 0;
        List<ServerPlayer> changed = null;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            PlayerEnergyData data = player.getData(ModAttachments.PLAYER_ENERGY);
            if (data.isEmpty()) continue;
            boolean didSync = false;
            if (calmPhase && player.isAlive()
                    && composureActive(player) && isCalm(player)) {
                var main = data.mainPool();
                didSync = main != null && data.restore(main.getKey(), 1.0) > 0;
            }
            if (didSync) {
                if (changed == null) changed = new ArrayList<>();
                changed.add(player);
            }
        }
        if (changed != null) {
            for (ServerPlayer player : changed) {
                sync(player);
            }
        }

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

    /** 睡觉回满内力：全员入睡跳过夜晚时触发 */
    @SubscribeEvent
    public static void onSleepFinished(SleepFinishedTimeEvent event) {
        for (var entity : event.getLevel().players()) {
            if (!(entity instanceof ServerPlayer player)) continue;
            PlayerEnergyData data = player.getData(ModAttachments.PLAYER_ENERGY);
            if (data.getPool(POOL_NEILI) != null && data.restore(POOL_NEILI, Double.MAX_VALUE) > 0) {
                player.displayClientMessage(Component.translatable("msg.zhushenspace.neili.sleep"), true);
                // 特效：晨光磬音 + 符文环
                player.level().playSound(null, player.blockPosition(),
                        ModSounds.NEILI_MEDITATE_DONE.get(), SoundSource.PLAYERS, 0.8f, 1.2f);
                enchantRing(player);
                sync(player);
            }
        }
    }

    /** 是否处于"静立"状态：潜行，或站在地面且几乎无水平移动 */
    private static boolean isCalm(ServerPlayer player) {
        if (player.isShiftKeyDown()) return true;
        return player.onGround()
                && player.getDeltaMovement().horizontalDistanceSqr() < CALM_SPEED_SQR;
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
            // 内力池移除后按属性重算：决心/沉着满级的玩家恢复独立主池
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

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
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
