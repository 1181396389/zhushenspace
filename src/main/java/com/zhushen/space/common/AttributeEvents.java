package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.PlayerAttributeData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 属性系统事件处理。
 *
 * - 登录/重生/换维度时重新应用属性修改器并同步数据（瞬态修改器不随 NBT 保存）
 * - 操作属性传奇加成：每点传奇点数 +1% 暴击率，暴击伤害 1.5 倍
 * - 感知属性：弱点勘破（每点感知 +5% 出现概率），目标身上显现黄色弱点光点，
 *   只有命中光点才触发 1.5 倍额外伤害（可与跳劈暴击叠加），伴随音效与粒子反馈（见 WeakPointManager）
 * - 耐力属性：每 5 点使受到的负面效果持续时间降低 15%
 * - 耐力传奇加成：饱食度消耗降低（耐力满 5 后激活，每传奇点消耗 = 1/(1+0.5×点数)）
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public class AttributeEvents {

    /** 每点传奇点数提供的暴击率 */
    private static final float CRIT_CHANCE_PER_LEGENDARY = 0.01f;
    /** 暴击伤害倍率 */
    private static final float CRIT_DAMAGE_MULTIPLIER = 1.5f;

    /** 弱点勘破伤害倍率（命中目标身上的黄色弱点光点时触发，见 WeakPointManager） */
    private static final float WEAK_POINT_DAMAGE_MULTIPLIER = 1.5f;
    /** 每 5 点耐力提供的负面效果时长减免 */
    private static final float EFFECT_DURATION_REDUCTION_PER_5 = 0.15f;
    /** 耐力传奇加成：每点传奇点数使饱食度消耗的分母增加（消耗 = 1/(1+0.5×点数)，1 点 = 1/1.5） */
    private static final float FOOD_DIVISOR_PER_LEGENDARY = 0.5f;

    /** 防止替换负面效果时递归触发 MobEffectEvent.Added */
    private static final ThreadLocal<Boolean> RESCALING = ThreadLocal.withInitial(() -> false);

    /** 饱食度消耗追踪：玩家 UUID → 上一 tick 处理后的消耗累积值 */
    private static final Map<UUID, Float> LAST_EXHAUSTION = new HashMap<>();

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AttributeServer.applyAndSync(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AttributeServer.applyAndSync(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AttributeServer.applyAndSync(player);
        }
    }

    /** 操作属性传奇暴击 + 感知属性弱点勘破（玩家造成伤害时判定） */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        DamageSource source = event.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;

        var data = player.getData(ModAttachments.PLAYER_ATTRIBUTES);

        // TACZ 枪械伤害走单独的平衡规则（降档倍率 + 每发一次判定 + 总倍率封顶）
        if (GunDamage.isGun(source)) {
            onGunDamage(event, player, data);
            return;
        }

        // 操作：传奇暴击（仅当操作属性自身满 5 点时激活，按传奇点数叠加）
        int operation = data.get(AttributeType.OPERATION.ordinal());
        int leg = data.legendaryPoints();
        if (operation >= AttributeType.MAX_POINTS && leg > 0
                && player.getRandom().nextFloat() < leg * CRIT_CHANCE_PER_LEGENDARY) {
            event.setAmount(event.getAmount() * CRIT_DAMAGE_MULTIPLIER);
        }

        // 感知：弱点勘破（黄色光点瞄准机制，见 WeakPointManager）
        int perception = data.get(AttributeType.PERCEPTION.ordinal());
        if (perception > 0 && player.level() instanceof ServerLevel level) {
            var weakPoint = WeakPointManager.get(event.getEntity());
            if (weakPoint != null) {
                // 已有光点：命中光点（光点随目标实时移动）才触发额外伤害并消耗
                Vec3 wpPos = WeakPointManager.getWorldPos(event.getEntity(), weakPoint);
                if (WeakPointManager.isHit(player, source, wpPos)) {
                    event.setAmount(event.getAmount() * WEAK_POINT_DAMAGE_MULTIPLIER);
                    WeakPointManager.consume(level, event.getEntity());
                }
            } else {
                // 无光点：按每点感知 5% 概率生成弱点光点
                WeakPointManager.trySpawn(level, event.getEntity(), player, perception);
            }
        }
    }

    /**
     * 枪械伤害的暴击 / 弱点结算（见 {@link GunDamage}）：
     * - 暴击、弱点倍率降档为枪械专用值
     * - TACZ 每发子弹的「普通 + 穿甲」两次结算只判定一次，第二次复用同一倍率（不再额外掷弱点生成）
     * - 弱点被枪械消耗后，目标进入弱点冷却，期间枪械命中不会再生成弱点
     * - 技能乘区 × 暴击 × 弱点 合计不超过总上限
     */
    private static void onGunDamage(LivingIncomingDamageEvent event, ServerPlayer player, PlayerAttributeData data) {
        var target = event.getEntity();
        DamageSource source = event.getSource();
        GunDamage.Hit hit = GunDamage.current(player, target, source);

        float extra;
        if (hit != null && hit.rolled) {
            extra = hit.extra;
        } else {
            extra = 1f;
            int operation = data.get(AttributeType.OPERATION.ordinal());
            int leg = data.legendaryPoints();
            if (operation >= AttributeType.MAX_POINTS && leg > 0
                    && player.getRandom().nextFloat() < leg * CRIT_CHANCE_PER_LEGENDARY) {
                extra *= GunDamage.CRIT_MULTIPLIER;
            }
            int perception = data.get(AttributeType.PERCEPTION.ordinal());
            if (perception > 0 && player.level() instanceof ServerLevel level) {
                var weakPoint = WeakPointManager.get(target);
                if (weakPoint != null) {
                    Vec3 wpPos = WeakPointManager.getWorldPos(target, weakPoint);
                    if (WeakPointManager.isHit(player, source, wpPos)) {
                        extra *= GunDamage.WEAK_POINT_MULTIPLIER;
                        WeakPointManager.consume(level, target);
                        WeakPointManager.startCooldown(target, GunDamage.WEAK_POINT_COOLDOWN_TICKS);
                    }
                } else if (!WeakPointManager.onCooldown(target)) {
                    WeakPointManager.trySpawn(level, target, player, perception);
                }
            }
            // 总倍率上限（随枪械技能点数上涨）：技能乘区已在 TACZ Pre 事件中应用，这里只截断暴击 × 弱点部分
            float skill = hit != null ? Math.max(1f, hit.skillFactor) : 1f;
            float cap = hit != null ? hit.cap : DamageCap.gunCap(player);
            extra = Math.max(1f, Math.min(extra, cap / skill));
            if (hit != null) {
                hit.rolled = true;
                hit.extra = extra;
            }
        }
        if (extra != 1f) event.setAmount(event.getAmount() * extra);
    }

    /** 耐力：每 5 点使受到的负面效果持续时间降低 15% */
    @SubscribeEvent
    public static void onMobEffectAdded(MobEffectEvent.Added event) {
        if (RESCALING.get()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;

        MobEffectInstance instance = event.getEffectInstance();
        if (instance.getEffect().value().getCategory() != MobEffectCategory.HARMFUL) return;
        if (instance.isInfiniteDuration()) return;

        int endurance = player.getData(ModAttachments.PLAYER_ATTRIBUTES)
                .get(AttributeType.ENDURANCE.ordinal());
        int tiers = endurance / 5;
        if (tiers <= 0) return;

        int reduced = Math.max(1, (int) Math.floor(
                instance.getDuration() * (1.0f - EFFECT_DURATION_REDUCTION_PER_5 * tiers)));
        if (reduced >= instance.getDuration()) return;

        // 移除刚添加的效果并以缩短后的时长重新添加（RESCALING 防递归）
        RESCALING.set(true);
        try {
            player.removeEffect(instance.getEffect());
            player.addEffect(new MobEffectInstance(instance.getEffect(), reduced,
                    instance.getAmplifier(), instance.isAmbient(), instance.isVisible(),
                    instance.showIcon()), event.getEffectSource());
        } finally {
            RESCALING.set(false);
        }
    }

    /**
     * 耐力传奇加成：饱食度消耗降低。
     * NeoForge 无消耗累积事件，这里在服务端 tick 末尾追踪每个玩家本 tick 新增的消耗量，
     * 将新增部分按倍率（1/(1+0.5×传奇点数)）缩写回 FoodData。
     */
    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            float multiplier = foodExhaustionMultiplier(
                    player.getData(ModAttachments.PLAYER_ATTRIBUTES));
            FoodData food = player.getFoodData();
            float current = food.getExhaustionLevel();
            float last = LAST_EXHAUSTION.getOrDefault(player.getUUID(), current);

            if (multiplier < 1.0f && current > last) {
                // 仅缩放本 tick 新增的消耗量（负 delta 来自死亡重置等，直接跳过）
                float scaled = last + (current - last) * multiplier;
                food.setExhaustion(scaled);
                current = scaled;
            }
            LAST_EXHAUSTION.put(player.getUUID(), current);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_EXHAUSTION.remove(event.getEntity().getUUID());
        GunDamage.forget(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        LAST_EXHAUSTION.clear();
        GunDamage.clear();
    }

    /** 饱食度消耗倍率：耐力满 5 后激活，每传奇点分母 +0.5（1 点 = 1/1.5，2 点 = 1/2……） */
    private static float foodExhaustionMultiplier(PlayerAttributeData data) {
        if (data.get(AttributeType.ENDURANCE.ordinal()) < AttributeType.MAX_POINTS) return 1.0f;
        int leg = data.legendaryPoints();
        return leg <= 0 ? 1.0f : 1.0f / (1.0f + FOOD_DIVISOR_PER_LEGENDARY * leg);
    }
}
