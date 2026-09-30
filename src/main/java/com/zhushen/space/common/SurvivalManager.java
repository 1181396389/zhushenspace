package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.PlayerConditionData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.SleepFinishedTimeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 生存需求（硬核）：把玩家当作人类——需要喝水、吃饭、喘气、睡觉。时间按「现实标准」（1 小时 = 1000 tick）。
 * <ul>
 *   <li><b>水分</b>（0~100）：约 20 小时喝空；冲刺、炎热环境（沙漠 / 下界等）消耗更快。
 *       偏低时体力恢复减半；很低时缓慢、乏力；归零后持续受到脱水伤害，很快致死。
 *       补水：潜行 + 空手右键水面（生水）、水瓶（生水）、烧开的水（熔炉 / 营火加热水瓶）、牛奶、药水、汤与多汁的食物。
 *       生水有一定概率闹肚子。</li>
 *   <li><b>食物</b>：沿用原版饱食度，但饱和度不再回血；饿到零时无论难度都会一直掉血（不会停在半颗心）。</li>
 *   <li><b>体力</b>（上限随耐力提高）：冲刺、跳跃、攻击、挖掘、游泳、攀爬消耗；停下来才会恢复（站着不动最快，休息更快）。
 *       耗尽即「力竭」：恢复到三成之前不能冲刺、不能攻击、挖掘变慢、走路也变慢。</li>
 *   <li><b>精力</b>（睡眠，0~100）：约 40 小时耗空。睡床跳过夜晚 / 长休回满，短休与静坐休息时缓慢恢复。
 *       困倦时挖掘变慢、体力恢复减半；极度困倦时会打瞌睡（短暂失明）并产生幻听；归零时当场昏睡倒地，直到恢复一些精力。</li>
 * </ul>
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class SurvivalManager {

    private SurvivalManager() {
    }

    public static final float MAX = 100f;
    /** 每 tick 基础消耗：水分约 20 小时喝空，精力约 40 小时耗空 */
    private static final float THIRST_PER_TICK = MAX / 20000f, SLEEP_PER_TICK = MAX / 40000f;
    /** 睡觉 / 昏睡时每 tick 恢复的精力 */
    private static final float SLEEP_REGEN = 0.05f, REST_SLEEP_REGEN = 0.01f;
    /** 体力消耗 */
    private static final float SPRINT_COST = 0.15f, SWIM_COST = 0.08f, CLIMB_COST = 0.1f, JUMP_COST = 2.5f,
            ATTACK_COST = 3f, MINE_COST = 1f;
    /** 力竭解除所需的体力比例 */
    private static final float EXHAUST_RECOVER = 0.3f;
    /** 生水闹肚子的概率 */
    private static final float RAW_WATER_SICK = 0.2f;

    public static final ResourceKey<DamageType> DEHYDRATION = ResourceKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "dehydration"));

    /** 上一次消耗体力的时刻（停下来一秒后才开始恢复） */
    private static final Map<UUID, Long> LAST_EXERT = new HashMap<>();
    private static final Map<UUID, Long> DRINK_CD = new HashMap<>();

    static PlayerConditionData data(Player p) {
        return StatusManager.data(p);
    }

    public static float maxStamina(ServerPlayer p) {
        int end = CombatFormula.attr(p, AttributeType.ENDURANCE) + FeatEffects.attrBonus(p)[AttributeType.ENDURANCE.ordinal()];
        return 60f + 10f * end;
    }

    private static boolean exempt(Player p) {
        return p.isCreative() || p.isSpectator();
    }

    // ===== 体力 =====

    /** 消耗体力（不足时降为 0 并进入力竭） */
    public static void exert(ServerPlayer p, float cost) {
        if (exempt(p) || cost <= 0) return;
        PlayerConditionData d = data(p);
        if (d.stamina < 0) d.stamina = maxStamina(p);
        d.stamina = Math.max(0, d.stamina - cost);
        LAST_EXERT.put(p.getUUID(), p.level().getGameTime());
        if (d.stamina <= 0 && !d.exhausted) {
            d.exhausted = true;
            p.setSprinting(false);
            p.displayClientMessage(Component.translatable("msg.zhushenspace.survival.exhausted"), true);
            StatusManager.sync(p);
        }
    }

    public static boolean exhausted(Player p) {
        return data(p).exhausted;
    }

    @SubscribeEvent
    public static void onJump(LivingEvent.LivingJumpEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) exert(p, JUMP_COST);
    }

    /** 力竭时不能攻击；攻击消耗体力 */
    @SubscribeEvent
    public static void onAttack(AttackEntityEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || exempt(p)) return;
        if (data(p).exhausted || StatusManager.incapacitated(p)) {
            e.setCanceled(true);
            p.displayClientMessage(Component.translatable("msg.zhushenspace.survival.too_tired"), true);
            return;
        }
        exert(p, ATTACK_COST);
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (e.getPlayer() instanceof ServerPlayer p) exert(p, MINE_COST);
    }

    /** 力竭 / 困倦 / 缺水：挖掘变慢（两端都会计算，客户端取同步数据） */
    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || exempt(p)) return; // 客户端见 ClientCondition
        PlayerConditionData d = data(p);
        float mul = mineFactor(d.exhausted, d.sleep, d.thirst);
        if (mul < 1f) e.setNewSpeed(e.getNewSpeed() * mul);
    }

    /** 挖掘速度倍率（两端共用） */
    public static float mineFactor(boolean exhausted, float sleep, float thirst) {
        float mul = 1f;
        if (exhausted) mul *= 0.5f;
        if (sleep < 25) mul *= 0.7f;
        if (thirst < 10) mul *= 0.7f;
        return mul;
    }

    // ===== 喝水 =====

    /** 潜行 + 空手右键水面：喝生水（客户端发起，服务端重新校验视线上的水） */
    public static void drinkFromSource(ServerPlayer p) {
        if (!p.getMainHandItem().isEmpty() || LimbManager.handSevered(p, InteractionHand.MAIN_HAND)
                && LimbManager.handSevered(p, InteractionHand.OFF_HAND)) return;
        long now = p.level().getGameTime();
        Long cd = DRINK_CD.get(p.getUUID());
        if (cd != null && cd > now) return;
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getViewVector(1f).scale(3.5));
        BlockHitResult hit = p.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, p));
        if (hit.getType() != HitResult.Type.BLOCK || !p.level().getFluidState(hit.getBlockPos()).is(FluidTags.WATER)) return;
        DRINK_CD.put(p.getUUID(), now + 10);
        p.swing(InteractionHand.MAIN_HAND, true);
        p.level().playSound(null, p.blockPosition(), SoundEvents.GENERIC_DRINK, SoundSource.PLAYERS, 0.6f, 1f);
        drink(p, 12f, true);
    }

    /** 补充水分；raw = 生水（有概率闹肚子） */
    public static void drink(ServerPlayer p, float amount, boolean raw) {
        PlayerConditionData d = data(p);
        d.thirst = Math.max(0, Math.min(MAX, d.thirst + amount));
        if (raw && p.getRandom().nextFloat() < RAW_WATER_SICK) {
            p.addEffect(new MobEffectInstance(MobEffects.HUNGER, 600, 0));
            p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 200, 0));
            d.thirst = Math.max(0, d.thirst - 10f);
            p.displayClientMessage(Component.translatable("msg.zhushenspace.survival.sick_water"), false);
        }
        StatusManager.sync(p);
    }

    /** 喝完 / 吃完物品：按物品补充水分 */
    @SubscribeEvent
    public static void onUseFinish(LivingEntityUseItemEvent.Finish e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        ItemStack st = e.getItem();
        float water = 0;
        boolean raw = false;
        if (st.is(Items.POTION)) {
            PotionContents pc = st.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
            if (pc.is(Potions.WATER)) {
                CustomData cd = st.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
                raw = !cd.copyTag().getBoolean("zs_boiled");
                water = 30;
            } else water = 15;
        } else if (st.is(Items.MILK_BUCKET)) water = 25;
        else if (st.is(Items.HONEY_BOTTLE)) water = 8;
        else if (st.is(Items.MUSHROOM_STEW) || st.is(Items.BEETROOT_SOUP) || st.is(Items.RABBIT_STEW) || st.is(Items.SUSPICIOUS_STEW)) water = 20;
        else if (st.is(Items.MELON_SLICE)) water = 10;
        else if (st.is(Items.APPLE) || st.is(Items.GOLDEN_APPLE) || st.is(Items.ENCHANTED_GOLDEN_APPLE)) water = 5;
        else if (st.is(Items.SWEET_BERRIES) || st.is(Items.GLOW_BERRIES) || st.is(Items.CHORUS_FRUIT)) water = 3;
        else if (st.is(Items.CARROT) || st.is(Items.BEETROOT)) water = 2;
        else if (st.is(Items.DRIED_KELP)) water = -3;
        else if (st.is(Items.ROTTEN_FLESH)) water = -5;
        if (water != 0) drink(p, water, raw);
    }

    // ===== 睡眠 =====

    /** 睡床跳过夜晚：精力回满 */
    @SubscribeEvent
    public static void onSleepFinished(SleepFinishedTimeEvent e) {
        for (Player pl : e.getLevel().players()) {
            if (pl instanceof ServerPlayer p && p.isSleeping()) restoreSleep(p, MAX);
        }
    }

    /** 恢复精力（长休回满 / 短休少量） */
    public static void restoreSleep(ServerPlayer p, float amount) {
        PlayerConditionData d = data(p);
        d.sleep = Math.min(MAX, d.sleep + amount);
        StatusManager.sync(p);
    }

    // ===== 每 tick =====

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !p.isAlive()) return;
        PlayerConditionData d = data(p);
        float max = maxStamina(p);
        if (d.stamina < 0 || d.stamina > max) d.stamina = max;
        if (exempt(p)) {
            if (p.tickCount % 10 == 0) StatusManager.sync(p);
            return;
        }
        long now = p.level().getGameTime();
        boolean resting = RestManager.isResting(p);
        boolean sleeping = p.isSleeping() || d.collapsed;

        // --- 水分 ---
        float thirstCost = THIRST_PER_TICK;
        if (p.isSprinting()) thirstCost += THIRST_PER_TICK * 2;
        if (hot(p)) thirstCost += THIRST_PER_TICK;
        if (sleeping) thirstCost *= 0.5f;
        d.thirst = Math.max(0, d.thirst - thirstCost);

        // --- 精力 ---
        if (sleeping) d.sleep = Math.min(MAX, d.sleep + SLEEP_REGEN);
        else if (resting) d.sleep = Math.min(MAX, d.sleep + REST_SLEEP_REGEN);
        else d.sleep = Math.max(0, d.sleep - SLEEP_PER_TICK);

        // --- 体力 ---
        boolean moving = p.getDeltaMovement().horizontalDistanceSqr() > 0.0009;
        float cost = 0;
        if (p.isSprinting()) cost += SPRINT_COST;
        if (p.isInWater() && moving) cost += SWIM_COST;
        if (p.onClimbable() && p.getDeltaMovement().y > 0.05) cost += CLIMB_COST;
        if (cost > 0) exert(p, cost);
        else if (now - LAST_EXERT.getOrDefault(p.getUUID(), 0L) > 20) {
            float regen = resting || sleeping ? 1.0f : moving ? 0.2f : 0.35f;
            if (d.thirst < 25) regen *= 0.5f;
            if (p.getFoodData().getFoodLevel() < 6) regen *= 0.5f;
            if (p.getFoodData().getFoodLevel() <= 0) regen = 0;
            if (d.sleep < 25) regen *= 0.5f;
            d.stamina = Math.min(max, d.stamina + regen);
        }
        if (d.exhausted) {
            p.setSprinting(false);
            if (d.stamina >= max * EXHAUST_RECOVER) {
                d.exhausted = false;
                p.displayClientMessage(Component.translatable("msg.zhushenspace.survival.recovered"), true);
            }
        }

        if (p.tickCount % 20 == 0) effects(p, d);
        if (p.tickCount % 10 == 0) StatusManager.sync(p);
    }

    private static boolean hot(ServerPlayer p) {
        if (p.level().dimensionType().ultraWarm()) return true;
        return p.level().getBiome(p.blockPosition()).value().getBaseTemperature() > 1.0f
                && p.level().isDay() && p.level().canSeeSky(p.blockPosition());
    }

    /** 每秒：各项需求的后果 */
    private static void effects(ServerPlayer p, PlayerConditionData d) {
        long t = p.level().getGameTime();
        // 缺水
        if (d.thirst < 10) {
            p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 0, true, false));
            p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 40, 0, true, false));
        }
        if (d.thirst <= 0 && t % 100 < 20) {
            p.invulnerableTime = 0;
            p.hurt(new DamageSource(p.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(DEHYDRATION)), 2f);
            p.displayClientMessage(Component.translatable("msg.zhushenspace.survival.dehydrated"), true);
        }
        // 饥饿：饿到零时无论难度都会一直掉血（原版困难难度自己会扣，这里补上其余难度）
        Difficulty diff = p.level().getDifficulty();
        if (p.getFoodData().getFoodLevel() <= 0 && diff != Difficulty.PEACEFUL && diff != Difficulty.HARD && t % 80 < 20) {
            boolean vanilla = p.getHealth() > 10f || (p.getHealth() > 1f && diff == Difficulty.NORMAL);
            if (!vanilla) {
                p.invulnerableTime = 0;
                p.hurt(p.damageSources().starve(), 1f);
            }
        }
        // 困倦
        if (d.collapsed) {
            p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0, true, false));
            if (d.sleep >= 20f) {
                d.collapsed = false;
                p.displayClientMessage(Component.translatable("msg.zhushenspace.survival.wake"), false);
            }
        } else if (d.sleep <= 0) {
            d.collapsed = true;
            StatusManager.setProne(p, true, null);
            RestManager.cancel(p, null);
            p.displayClientMessage(Component.translatable("msg.zhushenspace.survival.collapse"), false);
        } else if (d.sleep < 10) {
            p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 0, true, false));
            p.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 40, 0, true, false));
            // 打瞌睡：约每分钟一次短暂失明
            if (!p.isSleeping() && p.getRandom().nextInt(60) == 0) {
                p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 50, 0, true, false));
                p.displayClientMessage(Component.translatable("msg.zhushenspace.survival.microsleep"), true);
            }
        } else if (d.sleep < 25) {
            p.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 40, 0, true, false));
        }
    }

    // ===== 生命周期 =====

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            data(p).stamina = maxStamina(p);
            StatusManager.sync(p);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        LAST_EXERT.remove(e.getEntity().getUUID());
        DRINK_CD.remove(e.getEntity().getUUID());
    }
}
