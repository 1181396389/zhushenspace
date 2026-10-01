package com.zhushen.space.common;

import com.mojang.brigadier.CommandDispatcher;
import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.*;
import com.zhushen.space.network.TrialActionPayload;
import com.zhushen.space.network.TrialStatePayload;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.BossEvent;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.util.INBTSerializable;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;
import java.util.function.Supplier;

/**
 * 新手试炼（教程副本）：每名玩家一个独立实例（zhushenspace:trial 维度），10 个房间依次教学：
 * <ol start="0">
 *   <li>苏醒：打开主神面板</li>
 *   <li>试用角色：从 4 张预设角色卡中选择一张（不消耗 XP）</li>
 *   <li>移动与地形：穿过蛛网与灵魂沙、攀上高墙、跳过深沟、登上终点平台</li>
 *   <li>战斗模式：进入战斗模式、攻击训练假人、使用一个技能</li>
 *   <li>防御：开启全力防御，挡住木桩傀儡的攻击（傀儡伤害很低、不致死）</li>
 *   <li>伤势与状态：爬起来、扑灭火焰、止血</li>
 *   <li>能量池与技艺：施放技艺并命中假人</li>
 *   <li>综合战：击败普通敌人，再击败小头目（T 病毒丧尸）；关前为检查点，失败后从这里重来</li>
 *   <li>休息与恢复：喝水、完成一次短休</li>
 *   <li>结算：走上传送台，查看统计并回到主神空间开始正式建卡</li>
 * </ol>
 * 安全保证：进入时备份背包 / 属性 / 技能 / 建卡 / 能量 / 技艺 / 伤势 / 状态 / 货币 / 药水效果 / 饱食度 / 游戏模式，
 * 离开（完成、退出、掉线后在别处登录、被传送出维度）时整体还原；试炼内倒下 = 回到本关入口并完全恢复，没有任何损失。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class TrialManager {
    private TrialManager() {}

    public static final ResourceKey<Level> TRIAL_DIMENSION =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "trial"));

    public static final int S_AWAKE = 0, S_PICK = 1, S_TERRAIN = 2, S_COMBAT = 3, S_DEFENSE = 4, S_WOUNDS = 5,
            S_ARTS = 6, S_BATTLE = 7, S_REST = 8, S_FINISH = 9;
    /** 每关目标数 */
    public static final int[] GOALS = {1, 1, 3, 3, 2, 3, 2, 2, 2, 1};
    /** 战斗关：需要命中假人的次数 */
    public static final int HITS = 3;
    /** 防御关：全力防御状态下需要承受的傀儡攻击次数 */
    public static final int BLOCKS = 3;
    /** 综合战：普通敌人数量 */
    public static final int MINIONS = 3;
    /** 试炼中的短休时长（tick）：正式短休需要 1 分钟，试炼里缩短为 15 秒 */
    public static final int REST_TICKS = 300;
    /** 技艺关：施放技艺后多少 tick 内命中假人算作「技艺命中」 */
    private static final int ART_WINDOW = 100;
    /** 傀儡：出手间隔 / 攻击距离 / 伤害 */
    private static final int PUPPET_INTERVAL = 40;
    private static final double PUPPET_REACH = 3.6;
    private static final float PUPPET_DAMAGE = 2f;

    private static final Map<UUID, Integer> SLOTS = new HashMap<>();
    private static final Map<UUID, UUID> DUMMIES = new HashMap<>(), PUPPETS = new HashMap<>();
    private static final Map<UUID, Long> ART_AT = new HashMap<>(), PUPPET_NEXT = new HashMap<>();
    private static final Map<UUID, Integer> PICK_NONCE = new HashMap<>(), FINISH_NONCE = new HashMap<>();
    private static final Map<UUID, Battle> BATTLES = new HashMap<>();
    private static final Set<UUID> ON_PAD = new HashSet<>();
    /** 正在由本类执行的传送（离开试炼），维度切换监听不再重复还原 */
    private static final Set<UUID> EXITING = new HashSet<>();

    /** 综合战进行状态（内存，掉线 / 重启后重新开战） */
    private static final class Battle {
        final List<UUID> minions = new ArrayList<>();
        final Set<UUID> counted = new HashSet<>();
        UUID boss;
        boolean bossSpawned, bossDead;
        ServerBossEvent bar;
    }

    // ===================== 备份的附件 =====================

    private record Att<T extends INBTSerializable<CompoundTag>>(String key, Supplier<AttachmentType<T>> type, Supplier<T> fresh) {
        CompoundTag save(ServerPlayer p) { return p.getData(type.get()).serializeNBT(p.registryAccess()); }

        void load(ServerPlayer p, CompoundTag t) {
            T v = fresh.get();
            v.deserializeNBT(p.registryAccess(), t);
            p.setData(type.get(), v);
        }
    }

    private static final List<Att<?>> ATTS = List.of(
            new Att<PlayerAttributeData>("attributes", ModAttachments.PLAYER_ATTRIBUTES, PlayerAttributeData::new),
            new Att<PlayerSkillData>("skills", ModAttachments.PLAYER_SKILLS, PlayerSkillData::new),
            new Att<PlayerBuildData>("build", ModAttachments.PLAYER_BUILD, PlayerBuildData::new),
            new Att<PlayerEnergyData>("energy", ModAttachments.PLAYER_ENERGY, PlayerEnergyData::new),
            new Att<PlayerCurrencyData>("currency", ModAttachments.PLAYER_CURRENCY, PlayerCurrencyData::new),
            new Att<PlayerSchoolData>("schools", ModAttachments.PLAYER_SCHOOLS, PlayerSchoolData::new),
            new Att<PlayerHealthData>("health", ModAttachments.PLAYER_HEALTH, PlayerHealthData::new),
            new Att<PlayerLimbData>("limbs", ModAttachments.PLAYER_LIMBS, PlayerLimbData::new),
            new Att<PlayerArtData>("arts", ModAttachments.PLAYER_ARTS, PlayerArtData::new),
            new Att<PlayerConditionData>("condition", ModAttachments.PLAYER_CONDITION, PlayerConditionData::new));

    // ===================== 对外接口 =====================

    public static TrialData data(Player p) { return p.getData(ModAttachments.TRIAL); }

    /** 是否正在进行试炼 */
    public static boolean inTrial(Player p) { return data(p).active(); }

    /** 正在进行试炼且身处试炼维度 */
    public static boolean inTrialWorld(Player p) { return inTrial(p) && p.level().dimension() == TRIAL_DIMENSION; }

    private static ServerLevel level(ServerPlayer p) { return p.server.getLevel(TRIAL_DIMENSION); }

    private static long now(ServerPlayer p) { return p.server.overworld().getGameTime(); }

    public static void handle(ServerPlayer p, int action, int value) {
        TrialData d = data(p);
        switch (action) {
            case TrialActionPayload.ENTER -> start(p);
            case TrialActionPayload.SKIP -> skip(p);
            case TrialActionPayload.NOTE_PANEL -> { if (d.active() && d.stage == S_AWAKE) complete(p, 0); }
            case TrialActionPayload.PICK -> pick(p, value);
            case TrialActionPayload.NOTE_COMBAT -> { if (d.active() && d.stage == S_COMBAT) complete(p, 0); }
            case TrialActionPayload.QUIT -> quit(p);
            default -> { }
        }
    }

    /** 战斗预设栏使用技能 / 技艺成功（SkillManager 调用） */
    public static void onSkillUsed(ServerPlayer p, SkillAbility a) {
        TrialData d = data(p);
        if (!d.active() || p.level().dimension() != TRIAL_DIMENSION) return;
        d.skills++;
        if (d.stage == S_COMBAT) complete(p, 2);
        else if (d.stage == S_ARTS && a.isArtAbility()) {
            ART_AT.put(p.getUUID(), p.level().getGameTime());
            complete(p, 0);
        }
    }

    /** 喝到水（SurvivalManager 调用）：休息关目标 */
    public static void onDrink(ServerPlayer p) {
        TrialData d = data(p);
        if (d.active() && p.level().dimension() == TRIAL_DIMENSION && d.stage == S_REST) complete(p, 0);
    }

    /** 休息完成（RestManager 调用）：休息关目标 */
    public static void onRest(ServerPlayer p) {
        TrialData d = data(p);
        if (d.active() && p.level().dimension() == TRIAL_DIMENSION && d.stage == S_REST) complete(p, 1);
    }

    // ===================== 进入 / 退出 =====================

    public static void start(ServerPlayer p) {
        TrialData d = data(p);
        if (d.active()) { p.displayClientMessage(Component.translatable("msg.zhushenspace.trial.already"), true); return; }
        if (!p.getData(ModAttachments.PLAYER_ATTRIBUTES).envelopeUsed()) {
            p.displayClientMessage(Component.translatable("msg.zhushenspace.trial.need_envelope"), true);
            return;
        }
        ServerLevel tl = level(p);
        if (tl == null) { p.displayClientMessage(Component.translatable("msg.zhushenspace.trial.missing"), true); return; }
        if (p.isPassenger()) p.stopRiding();
        Telekinesis.end(p, false);
        RestManager.cancel(p, null);
        d.prevStatus = d.status == TrialData.ACTIVE ? TrialData.NONE : d.status;
        d.returnPos = GlobalPos.of(p.level().dimension(), p.blockPosition());
        d.returnYaw = p.getYRot();
        d.backup = backup(p);
        d.status = TrialData.ACTIVE;
        d.ver = TrialData.VERSION;
        d.slot = allocSlot(p, -1);
        begin(p, d, tl);
        p.setGameMode(GameType.ADVENTURE);
        p.displayClientMessage(Component.translatable("msg.zhushenspace.trial.welcome"), false);
    }

    /** 从第 0 关开始（进入 / 重开共用）：重建场景、清空背包、完全恢复、传送到入口 */
    private static void begin(ServerPlayer p, TrialData d, ServerLevel tl) {
        d.stage = S_AWAKE;
        d.done = 0;
        d.count = 0;
        d.doors = 0;
        d.template = -1;
        d.deaths = 0;
        d.hits = 0;
        d.skills = 0;
        d.kills = 0;
        d.woundsApplied = false;
        d.startTick = now(p);
        d.gen++;
        forget(p);
        p.getInventory().clearContent();
        TrialArena.build(tl, d.slot, d.gen);
        teleport(p, tl, TrialArena.checkpoint(d.slot, S_AWAKE));
        fullHeal(p);
        sync(p);
        tl.playSound(null, p.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1f, 0.8f);
    }

    /** 清除内存中的实例状态（假人 / 傀儡 / 综合战 / 光台） */
    private static void forget(ServerPlayer p) {
        UUID id = p.getUUID();
        DUMMIES.remove(id);
        PUPPETS.remove(id);
        PUPPET_NEXT.remove(id);
        ART_AT.remove(id);
        ON_PAD.remove(id);
        Battle b = BATTLES.remove(id);
        if (b != null && b.bar != null) b.bar.removeAllPlayers();
    }

    /** 重开：试炼中 → 回到第 0 关（存档备份保持不变）；不在试炼中 → 进入 */
    public static void restart(ServerPlayer p) {
        TrialData d = data(p);
        if (!d.active()) { start(p); return; }
        ServerLevel tl = level(p);
        if (tl == null) return;
        Telekinesis.end(p, false);
        RestManager.cancel(p, null);
        if (d.backup != null) loadAtts(p, d.backup.getCompound("Att"));
        resyncAll(p);
        begin(p, d, tl);
        p.displayClientMessage(Component.translatable("msg.zhushenspace.trial.restarted"), false);
    }

    public static void skip(ServerPlayer p) {
        TrialData d = data(p);
        if (d.active()) { quit(p); return; }
        if (d.status == TrialData.NONE) d.status = TrialData.SKIPPED;
        sync(p);
        p.displayClientMessage(Component.translatable("msg.zhushenspace.trial.skipped"), false);
    }

    public static void quit(ServerPlayer p) {
        if (!data(p).active()) { p.displayClientMessage(Component.translatable("msg.zhushenspace.trial.not_in"), true); return; }
        finish(p, false, true);
        p.displayClientMessage(Component.translatable("msg.zhushenspace.trial.quit"), false);
    }

    /**
     * 结束试炼：还原存档 → 记录 → 传送（完成 → 主神空间大厅；中途退出 → 进入前的位置）。
     * teleport = false：玩家已不在试炼维度（被其他方式传走 / 登录时不在场景内），只还原不传送。
     */
    private static void finish(ServerPlayer p, boolean completed, boolean teleport) {
        TrialData d = data(p);
        if (!d.active()) return;
        long ticks = Math.max(0, now(p) - d.startTick);
        Telekinesis.end(p, false);
        RestManager.cancel(p, null);
        // 先改状态再传送：维度切换监听看到非试炼中，不会重复还原
        if (completed) {
            d.status = TrialData.DONE;
            d.completions++;
            d.lastTicks = ticks;
            d.lastDeaths = d.deaths;
            d.lastHits = d.hits;
            d.lastSkills = d.skills;
            d.lastKills = d.kills;
            d.lastBest = d.bestTicks <= 0 || ticks < d.bestTicks;
            if (d.lastBest) d.bestTicks = ticks;
            FINISH_NONCE.merge(p.getUUID(), 1, Integer::sum);
        } else {
            d.status = d.prevStatus == TrialData.DONE ? TrialData.DONE : TrialData.SKIPPED;
        }
        ServerLevel tl = level(p);
        if (tl != null && d.slot >= 0 && p.level() == tl) TrialArena.clearEntities(tl, d.slot, -1, true);
        CompoundTag bk = d.backup;
        d.backup = null;
        SLOTS.remove(p.getUUID());
        forget(p);
        d.slot = -1;
        if (teleport) {
            EXITING.add(p.getUUID());
            try {
                if (completed) sendToHall(p, d);
                else sendBack(p, d);
            } finally {
                EXITING.remove(p.getUUID());
            }
        }
        if (bk != null) restore(p, bk);
        sync(p);
        if (completed) {
            p.displayClientMessage(Component.translatable("msg.zhushenspace.trial.complete",
                    formatTime(ticks), d.deaths, d.hits, d.skills, d.kills), false);
            p.displayClientMessage(Component.translatable("msg.zhushenspace.trial.next"), false);
            p.level().playSound(null, p.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.9f, 1f);
        }
    }

    private static void sendToHall(ServerPlayer p, TrialData d) {
        ServerLevel hall = p.server.getLevel(HallManager.HALL_DIMENSION);
        if (hall == null) { sendBack(p, d); return; }
        // 从主世界进入试炼：把进入前的位置记为大厅返回点，之后可从大厅回去
        if (d.returnPos != null && d.returnPos.dimension() != HallManager.HALL_DIMENSION)
            p.getData(ModAttachments.HALL_RETURN).set(d.returnPos);
        com.zhushen.space.entity.HallBuilder.ensureBuilt(hall);
        BlockPos sp = HallManager.HALL_SPAWN;
        p.teleportTo(hall, sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5, 180f, 0f);
        p.setDeltaMovement(0, 0, 0);
        p.fallDistance = 0;
    }

    private static void sendBack(ServerPlayer p, TrialData d) {
        ServerLevel target = d.returnPos == null ? null : p.server.getLevel(d.returnPos.dimension());
        BlockPos pos;
        if (target == null) {
            target = p.server.overworld();
            pos = target.getSharedSpawnPos();
        } else pos = d.returnPos.pos();
        p.teleportTo(target, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, d.returnYaw, 0f);
        p.setDeltaMovement(0, 0, 0);
        p.fallDistance = 0;
    }

    private static void teleport(ServerPlayer p, ServerLevel l, double[] c) {
        p.teleportTo(l, c[0], c[1], c[2], 0f, 0f);
        p.setDeltaMovement(0, 0, 0);
        p.fallDistance = 0;
    }

    /** 分配实例槽位：避开其他在线试炼玩家正在使用的槽位 */
    private static int allocSlot(ServerPlayer p, int prefer) {
        Set<Integer> used = new HashSet<>();
        for (Map.Entry<UUID, Integer> e : SLOTS.entrySet()) if (!e.getKey().equals(p.getUUID())) used.add(e.getValue());
        int s = prefer >= 0 && !used.contains(prefer) ? prefer : 0;
        if (prefer < 0 || used.contains(prefer)) while (used.contains(s)) s++;
        SLOTS.put(p.getUUID(), s);
        return s;
    }

    // ===================== 备份 / 还原 =====================

    private static CompoundTag backup(ServerPlayer p) {
        CompoundTag t = new CompoundTag();
        t.put("Inv", p.getInventory().save(new ListTag()));
        ListTag fx = new ListTag();
        for (MobEffectInstance e : p.getActiveEffects()) fx.add(e.save());
        t.put("Effects", fx);
        CompoundTag food = new CompoundTag();
        p.getFoodData().addAdditionalSaveData(food);
        t.put("Food", food);
        t.putFloat("Health", p.getHealth());
        t.putInt("Air", p.getAirSupply());
        t.putInt("Mode", p.gameMode.getGameModeForPlayer().getId());
        CompoundTag att = new CompoundTag();
        for (Att<?> a : ATTS) att.put(a.key(), a.save(p));
        t.put("Att", att);
        return t;
    }

    private static void loadAtts(ServerPlayer p, CompoundTag att) {
        StatusManager.setProne(p, false, null);
        for (Att<?> a : ATTS) if (att.contains(a.key())) a.load(p, att.getCompound(a.key()));
    }

    private static void restore(ServerPlayer p, CompoundTag t) {
        loadAtts(p, t.getCompound("Att"));
        p.getInventory().clearContent();
        p.getInventory().load(t.getList("Inv", Tag.TAG_COMPOUND));
        p.removeAllEffects();
        ListTag fx = t.getList("Effects", Tag.TAG_COMPOUND);
        for (int i = 0; i < fx.size(); i++) {
            MobEffectInstance e = MobEffectInstance.load(fx.getCompound(i));
            if (e != null) p.addEffect(e);
        }
        p.getFoodData().readAdditionalSaveData(t.getCompound("Food"));
        p.clearFire();
        p.setAirSupply(t.contains("Air") ? t.getInt("Air") : p.getMaxAirSupply());
        p.setGameMode(GameType.byId(t.getInt("Mode")));
        resyncAll(p);
        p.setHealth(Math.max(1f, Math.min(p.getMaxHealth(), t.getFloat("Health"))));
        HealthManager.sync(p);
        p.inventoryMenu.broadcastChanges();
    }

    /** 属性 / 能量池 / 技能 / 技艺 / 伤势 / 状态全部重算并同步到客户端 */
    private static void resyncAll(ServerPlayer p) {
        AttributeServer.applyAndSync(p);
        ArtManager.sync(p);
        EnergyManager.sync(p);
        LimbManager.applyPenalties(p);
        LimbManager.sync(p);
        StatusManager.sync(p);
        HealthManager.sync(p);
    }

    /** 完全恢复：伤势、肢体、不良状态、倒地、火焰、药水效果、饱食度全部清除 */
    private static void fullHeal(ServerPlayer p) {
        StatusManager.setProne(p, false, null);
        p.setData(ModAttachments.PLAYER_CONDITION, new PlayerConditionData());
        p.getData(ModAttachments.PLAYER_HEALTH).reset();
        LimbManager.data(p).reset();
        p.clearFire();
        p.removeAllEffects();
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(5f);
        p.setAirSupply(p.getMaxAirSupply());
        p.setHealth(p.getMaxHealth());
        HealthManager.afterHeal(p, 0);
        LimbManager.applyPenalties(p);
        LimbManager.sync(p);
        StatusManager.sync(p);
        HealthManager.sync(p);
    }

    /** 能量池补满（技艺关 / 综合战开始时） */
    private static void refillPools(ServerPlayer p) {
        for (PlayerEnergyData.Pool pool : p.getData(ModAttachments.PLAYER_ENERGY).pools().values()) pool.current = pool.max;
        EnergyManager.sync(p);
    }

    // ===================== 试用角色 =====================

    private static void pick(ServerPlayer p, int idx) {
        TrialData d = data(p);
        if (!d.active() || d.stage != S_PICK || idx < 0 || idx >= TrialTemplate.VALUES.length) return;
        TrialTemplate t = TrialTemplate.VALUES[idx];
        Telekinesis.end(p, false);
        // 先回到进入前的干净存档（避免上一张角色卡 / 真实角色的专长与技艺残留），再套用角色卡
        if (d.backup != null) loadAtts(p, d.backup.getCompound("Att"));
        applyTemplate(p, t);
        d.template = idx;
        p.getInventory().clearContent();
        for (ItemStack st : t.kit()) p.getInventory().add(st);
        p.inventoryMenu.broadcastChanges();
        fullHeal(p);
        p.displayClientMessage(Component.translatable("msg.zhushenspace.trial.picked",
                Component.translatable(t.nameKey())), false);
        complete(p, 0);
    }

    private static void applyTemplate(ServerPlayer p, TrialTemplate t) {
        PlayerBuildData b = p.getData(ModAttachments.PLAYER_BUILD);
        b.version = PlayerBuildData.VERSION;
        b.created = true;
        b.totalXp = BuildRules.TOTAL_XP;
        b.artXp = 0;
        b.attrXp = t.attrXp();
        b.featMask = t.featMask();
        b.si1Skill = -1;
        b.si3Skills = new int[]{-1, -1};
        b.giftedSkillXp = 0;
        b.pendingItems = 0;
        b.pendingExchange = false;
        p.getData(ModAttachments.PLAYER_ATTRIBUTES).setPoints(t.attrs.clone());
        PlayerSkillData sd = p.getData(ModAttachments.PLAYER_SKILLS);
        int[] sk = t.skills();
        sd.setPoints(sk);
        sd.clearProfessions();
        sd.setBar(0, t.bar(sk));
        int[] empty = new int[9];
        Arrays.fill(empty, -1);
        sd.setBar(1, empty);
        PlayerArtData art = new PlayerArtData();
        for (ArtSkill a : t.arts) {
            if (a.innate()) continue;
            art.owned |= 1L << a.ordinal();
            if (a.mode == ArtSkill.Mode.CYCLE) art.optionBits[a.ordinal()] = (1 << a.options.length) - 1;
        }
        p.setData(ModAttachments.PLAYER_ARTS, art);
        p.setData(ModAttachments.PLAYER_ENERGY, new PlayerEnergyData());
        AttributeApplier.apply(p);
        EnergyManager.syncLegendaryPools(p); // 按角色卡专长生成满额能量池，念动力自动习得天生技艺
        BuildServer.syncAll(p);
        ArtManager.sync(p);
        EnergyManager.sync(p);
        HealthManager.sync(p);
    }

    // ===================== 关卡推进 =====================

    private static void complete(ServerPlayer p, int bit) {
        TrialData d = data(p);
        if ((d.done & (1 << bit)) != 0) return;
        d.done |= 1 << bit;
        p.level().playSound(null, p.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.8f, 1.5f);
        p.displayClientMessage(Component.translatable("msg.zhushenspace.trial.goal_done",
                Component.translatable("trial.zhushenspace.goal." + d.stage + "." + bit)), true);
        sync(p);
    }

    private static boolean allDone(TrialData d) {
        return d.done == (1 << GOALS[Math.max(0, Math.min(GOALS.length - 1, d.stage))]) - 1;
    }

    private static void enterStage(ServerPlayer p, ServerLevel l, int s) {
        TrialData d = data(p);
        int prev = d.stage;
        d.stage = s;
        d.done = 0;
        d.count = 0;
        // 离开上一关：清掉该关的教学实体
        if (prev == S_COMBAT || prev == S_ARTS) discardDummy(p, l);
        if (prev == S_DEFENSE) discardPuppet(p, l);
        if (prev == S_BATTLE) endBattle(p, l, true);
        switch (s) {
            case S_PICK -> openPick(p);
            case S_COMBAT, S_ARTS -> ensureDummy(p, l, d);
            case S_DEFENSE -> {
                ensurePuppet(p, l, d);
                PUPPET_NEXT.put(p.getUUID(), l.getGameTime() + 60);
            }
            case S_WOUNDS -> d.woundsApplied = false;
            case S_BATTLE -> startBattle(p, l, d);
            case S_REST -> {
                // 休息关：口渴一些，喝水的效果一目了然
                PlayerConditionData c = StatusManager.data(p);
                c.thirst = Math.min(c.thirst, 55f);
                StatusManager.sync(p);
            }
            default -> { }
        }
        if (s == S_ARTS || s == S_BATTLE) refillPools(p);
        l.playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1f, 1.2f);
        sync(p);
    }

    private static void openPick(ServerPlayer p) {
        PICK_NONCE.merge(p.getUUID(), 1, Integer::sum);
        sync(p);
    }

    private static void ensureDummy(ServerPlayer p, ServerLevel l, TrialData d) {
        UUID id = DUMMIES.get(p.getUUID());
        Entity e = id == null ? null : l.getEntity(id);
        if (e instanceof LivingEntity le && le.isAlive() && TrialArena.roomAt(le.getZ()) == d.stage) {
            if (le.getHealth() < le.getMaxHealth()) le.setHealth(le.getMaxHealth());
            return;
        }
        if (e != null) e.discard();
        // 重连 / 重启后内存里没有记录：先清掉场景里遗留的假人，避免重复
        for (Entity old : l.getEntities((Entity) null, TrialArena.box(d.slot), en -> en.getTags().contains(TrialArena.DUMMY_TAG)))
            old.discard();
        UUID nid = TrialArena.spawnDummy(l, d.slot, d.stage, d.gen);
        if (nid != null) DUMMIES.put(p.getUUID(), nid);
    }

    private static void discardDummy(ServerPlayer p, ServerLevel l) {
        UUID id = DUMMIES.remove(p.getUUID());
        Entity e = id == null ? null : l.getEntity(id);
        if (e != null) e.discard();
    }

    // ----- 防御关：木桩傀儡 -----

    private static LivingEntity ensurePuppet(ServerPlayer p, ServerLevel l, TrialData d) {
        UUID id = PUPPETS.get(p.getUUID());
        Entity e = id == null ? null : l.getEntity(id);
        if (e instanceof LivingEntity le && le.isAlive()) {
            if (le.getHealth() < le.getMaxHealth()) le.setHealth(le.getMaxHealth());
            return le;
        }
        if (e != null) e.discard();
        for (Entity old : l.getEntities((Entity) null, TrialArena.box(d.slot), en -> en.getTags().contains(TrialArena.PUPPET_TAG)))
            old.discard();
        UUID nid = TrialArena.spawnPuppet(l, d.slot, d.gen);
        if (nid == null) return null;
        PUPPETS.put(p.getUUID(), nid);
        return l.getEntity(nid) instanceof LivingEntity le ? le : null;
    }

    private static void discardPuppet(ServerPlayer p, ServerLevel l) {
        UUID id = PUPPETS.remove(p.getUUID());
        PUPPET_NEXT.remove(p.getUUID());
        Entity e = id == null ? null : l.getEntity(id);
        if (e != null) e.discard();
    }

    /** 傀儡出手：面向玩家，靠近时每两秒挥一次木剑；伤害很低，生命过半前会先帮你补回来（不致死） */
    private static void tickPuppet(ServerPlayer p, ServerLevel l, TrialData d) {
        LivingEntity pup = ensurePuppet(p, l, d);
        if (pup == null) return;
        double dx = p.getX() - pup.getX(), dz = p.getZ() - pup.getZ();
        float yaw = (float) (Math.atan2(dz, dx) * 180 / Math.PI) - 90f;
        pup.setYRot(yaw);
        pup.setYHeadRot(yaw);
        pup.yBodyRot = yaw;
        if (Defense.fullActive(p)) complete(p, 0);
        long gt = l.getGameTime();
        Long next = PUPPET_NEXT.get(p.getUUID());
        if (next != null && gt < next) return;
        if (p.distanceTo(pup) > PUPPET_REACH || p.isCreative()) return;
        PUPPET_NEXT.put(p.getUUID(), gt + PUPPET_INTERVAL);
        if (p.getHealth() < p.getMaxHealth() * 0.5f || HealthManager.isUnconscious(p)) fullHeal(p);
        boolean guarded = Defense.fullActive(p);
        pup.swing(InteractionHand.MAIN_HAND, true);
        l.playSound(null, pup.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 0.7f, 0.8f);
        p.hurt(p.damageSources().mobAttack(pup), PUPPET_DAMAGE);
        if (guarded && (d.done & 2) == 0) {
            l.sendParticles(ParticleTypes.CRIT, p.getX(), p.getY() + 1.1, p.getZ(), 10, 0.3, 0.4, 0.3, 0.1);
            l.playSound(null, p.blockPosition(), SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 0.9f, 1.1f);
            d.count = Math.min(BLOCKS, d.count + 1);
            if (d.count >= BLOCKS) complete(p, 1);
            else sync(p);
        } else if (!guarded && (d.done & 2) == 0) {
            p.displayClientMessage(Component.translatable("msg.zhushenspace.trial.unguarded"), true);
        }
    }

    // ----- 综合战 -----

    private static void startBattle(ServerPlayer p, ServerLevel l, TrialData d) {
        endBattle(p, l, true);
        for (Entity old : l.getEntities((Entity) null, TrialArena.roomBox(d.slot, S_BATTLE), en -> en.getTags().contains(TrialArena.MOB_TAG)))
            old.discard();
        d.count = 0;
        d.done = 0;
        if (l.getDifficulty() == Difficulty.PEACEFUL) {
            // 和平难度下怪物无法存在：直接视为通过
            p.displayClientMessage(Component.translatable("msg.zhushenspace.trial.peaceful"), false);
            complete(p, 0);
            complete(p, 1);
            return;
        }
        Battle b = new Battle();
        for (int i = 0; i < MINIONS; i++) {
            UUID id = TrialArena.spawnMinion(l, d.slot, d.gen, i);
            if (id != null) b.minions.add(id);
        }
        BATTLES.put(p.getUUID(), b);
        p.displayClientMessage(Component.translatable("msg.zhushenspace.trial.battle_start"), false);
        l.playSound(null, p.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 0.6f, 1.4f);
    }

    /** 结束 / 重置综合战：移除首领血条；discard = 同时清掉还活着的敌人 */
    private static void endBattle(ServerPlayer p, ServerLevel l, boolean discard) {
        Battle b = BATTLES.remove(p.getUUID());
        if (b == null) return;
        if (b.bar != null) b.bar.removeAllPlayers();
        if (!discard) return;
        for (UUID id : b.minions) { Entity e = l.getEntity(id); if (e != null) e.discard(); }
        if (b.boss != null) { Entity e = l.getEntity(b.boss); if (e != null) e.discard(); }
    }

    private static void tickBattle(ServerPlayer p, ServerLevel l, TrialData d) {
        if (allDone(d)) return;
        Battle b = BATTLES.get(p.getUUID());
        if (b == null) { startBattle(p, l, d); return; } // 重连 / 重启后重新开战
        int dead = 0;
        for (UUID id : b.minions) {
            Entity e = l.getEntity(id);
            if (e == null || !e.isAlive()) {
                dead++;
                if (b.counted.add(id)) d.kills++;
            }
        }
        if (dead != d.count) {
            d.count = dead;
            sync(p);
        }
        if (dead >= b.minions.size()) complete(p, 0);
        if ((d.done & 1) != 0 && !b.bossSpawned) {
            b.bossSpawned = true;
            b.boss = TrialArena.spawnBoss(l, d.slot, d.gen);
            b.bar = new ServerBossEvent(Component.translatable("entity.zhushenspace.trial_boss"),
                    BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
            b.bar.addPlayer(p);
            p.displayClientMessage(Component.translatable("msg.zhushenspace.trial.boss"), false);
        }
        if (b.bossSpawned && !b.bossDead) {
            Entity e = b.boss == null ? null : l.getEntity(b.boss);
            if (e instanceof LivingEntity le && le.isAlive()) {
                if (b.bar != null) b.bar.setProgress(Math.max(0f, Math.min(1f, le.getHealth() / le.getMaxHealth())));
            } else {
                b.bossDead = true;
                d.kills++;
                if (b.bar != null) b.bar.removeAllPlayers();
                complete(p, 1);
                l.playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 1.2f);
            }
        }
    }

    /** 伤势关：踏入房间后触发「陷阱」——倒地、着火、流血，并留下一点冲击伤 */
    private static void applyWounds(ServerPlayer p, ServerLevel l) {
        TrialData d = data(p);
        d.woundsApplied = true;
        StatusManager.setProne(p, true, null);
        StatusManager.add(p, StatusType.BURN, 2, false, null, StatusManager.Source.NATURAL, 0);
        StatusManager.add(p, StatusType.BLEED, 2, false, null, StatusManager.Source.NATURAL, 0);
        HealthManager.addWound(p, PlayerHealthData.Severity.B, 3);
        l.sendParticles(ParticleTypes.EXPLOSION, p.getX(), p.getY() + 0.5, p.getZ(), 1, 0, 0, 0, 0);
        l.playSound(null, p.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 0.6f, 1.3f);
        p.displayClientMessage(Component.translatable("msg.zhushenspace.trial.trap"), false);
        sync(p);
    }

    /** 倒下（死亡 / 昏迷 / 掉出场景）：回到本关入口并完全恢复；综合战从头再来 */
    private static void defeated(ServerPlayer p) {
        TrialData d = data(p);
        d.deaths++;
        RestManager.cancel(p, null);
        if (d.stage == S_WOUNDS && !allDone(d)) d.woundsApplied = false;
        ServerLevel l = level(p);
        if (l != null) teleport(p, l, TrialArena.checkpoint(d.slot, d.stage));
        fullHeal(p);
        p.displayClientMessage(Component.translatable("msg.zhushenspace.trial.defeated"), false);
        p.level().playSound(null, p.blockPosition(), SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1f, 1.2f);
        if (l != null && d.stage == S_BATTLE && !allDone(d)) {
            startBattle(p, l, d);
            refillPools(p);
        }
        sync(p);
    }

    // ===================== 事件 =====================

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p.tickCount % 5 != 0) return;
        TrialData d = data(p);
        if (!d.active() || p.level().dimension() != TRIAL_DIMENSION || d.slot < 0) return;
        ServerLevel l = (ServerLevel) p.level();
        int bx = TrialArena.baseX(d.slot);
        // 掉出 / 越出场景
        if (p.getY() < TrialArena.FLOOR - 5 || Math.abs(p.getX() - bx - 0.5) > TrialArena.HALF + 3
                || p.getZ() < -3 || p.getZ() > TrialArena.ROOMS * TrialArena.LEN + 2) {
            teleport(p, l, TrialArena.checkpoint(d.slot, d.stage));
            return;
        }
        if (HealthManager.isUnconscious(p)) { defeated(p); return; }
        long gt = l.getGameTime();
        double rz = p.getZ() - TrialArena.roomZ(d.stage);
        switch (d.stage) {
            case S_PICK -> {
                boolean on = TrialArena.onPickPad(d.slot, p.getX(), p.getZ());
                if (on && ON_PAD.add(p.getUUID())) openPick(p); // 站上光台：重新打开选择界面（可以换卡）
                else if (!on) ON_PAD.remove(p.getUUID());
            }
            case S_TERRAIN -> {
                if (rz >= TrialArena.T_SAND1 + 1.5) complete(p, 0);
                if (rz >= TrialArena.T_WALL1 + 1.2 && p.getY() < TrialArena.FLOOR + TrialArena.T_WALL_H) complete(p, 1);
                if (rz >= TrialArena.T_PLAT0 && p.getY() >= TrialArena.FLOOR + 1.9 && p.onGround()) {
                    complete(p, 0);
                    complete(p, 1);
                    complete(p, 2);
                }
            }
            case S_COMBAT, S_ARTS -> ensureDummy(p, l, d);
            case S_DEFENSE -> tickPuppet(p, l, d);
            case S_WOUNDS -> {
                if (!d.woundsApplied) {
                    if (rz >= 2.5) applyWounds(p, l);
                } else {
                    if (!StatusManager.prone(p)) complete(p, 0);
                    if (!StatusManager.data(p).any(StatusType.BURN)) complete(p, 1);
                    if (!StatusManager.data(p).any(StatusType.BLEED)) complete(p, 2);
                }
            }
            case S_BATTLE -> tickBattle(p, l, d);
            case S_FINISH -> {
                if (gt % 10 == 0) l.sendParticles(ParticleTypes.END_ROD, bx + 0.5, TrialArena.FLOOR + 1.2,
                        TrialArena.roomZ(S_FINISH) + 10.5, 4, 0.6, 0.4, 0.6, 0.02);
                if (TrialArena.onExitPad(d.slot, p.getX(), p.getZ())) { finish(p, true, true); return; }
            }
            default -> { }
        }
        // 目标全部完成 → 开门
        if (d.stage < TrialArena.ROOMS - 1 && allDone(d) && (d.doors & (1 << d.stage)) == 0) {
            d.doors |= 1 << d.stage;
            TrialArena.openDoor(l, d.slot, d.stage);
            p.displayClientMessage(Component.translatable("msg.zhushenspace.trial.door"), true);
            sync(p);
        }
        // 穿过已开启的门 → 下一关
        if (d.stage < TrialArena.ROOMS - 1 && (d.doors & (1 << d.stage)) != 0
                && TrialArena.roomAt(p.getZ()) > d.stage && p.getZ() >= TrialArena.roomZ(d.stage + 1) + 0.5) {
            enterStage(p, l, d.stage + 1);
        }
        if (gt % 100 == 0) TrialArena.clearEntities(l, d.slot, d.gen, false);
    }

    /** 训练假人受击：计数 / 技艺命中；受伤后立即回满。玩家受伤：统计受伤次数 */
    @SubscribeEvent
    public static void onDamagePost(LivingDamageEvent.Post e) {
        LivingEntity t = e.getEntity();
        if (t instanceof ServerPlayer victim) {
            TrialData vd = data(victim);
            if (vd.active() && victim.level().dimension() == TRIAL_DIMENSION && e.getNewDamage() > 0) vd.hits++;
            return;
        }
        if (TrialArena.invulnerable(t)) t.setHealth(t.getMaxHealth());
        if (!t.getTags().contains(TrialArena.DUMMY_TAG)) return;
        if (!(e.getSource().getEntity() instanceof ServerPlayer p)) return;
        TrialData d = data(p);
        if (!d.active() || !t.getUUID().equals(DUMMIES.get(p.getUUID()))) return;
        if (d.stage == S_COMBAT && (d.done & 2) == 0) {
            d.count = Math.min(HITS, d.count + 1);
            if (d.count >= HITS) complete(p, 1);
            else sync(p);
        } else if (d.stage == S_ARTS) {
            Long at = ART_AT.get(p.getUUID());
            if (at != null && p.level().getGameTime() - at <= ART_WINDOW) complete(p, 1);
        }
    }

    /** 试炼中不会真正死亡：取消死亡 → 回到本关入口；训练假人 / 傀儡也不会死 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDeath(LivingDeathEvent e) {
        LivingEntity t = e.getEntity();
        if (TrialArena.invulnerable(t)) {
            e.setCanceled(true);
            t.setHealth(t.getMaxHealth());
            return;
        }
        if (t instanceof ServerPlayer p && data(p).active() && p.level().dimension() == TRIAL_DIMENSION) {
            e.setCanceled(true);
            p.setHealth(p.getMaxHealth());
            defeated(p);
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (e.getPlayer() instanceof ServerPlayer p && p.level().dimension() == TRIAL_DIMENSION && !p.isCreative())
            e.setCanceled(true);
    }

    /** 试炼维度的床只是摆设（此维度床会爆炸）：提示改用动作轮盘休息 */
    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        Player pl = e.getEntity();
        if (pl.level().dimension() != TRIAL_DIMENSION) return;
        if (!(pl.level().getBlockState(e.getPos()).getBlock() instanceof BedBlock)) return;
        e.setCanceled(true);
        if (pl instanceof ServerPlayer sp && e.getHand() == InteractionHand.MAIN_HAND)
            sp.displayClientMessage(Component.translatable("msg.zhushenspace.trial.bed"), true);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        TrialData d = data(p);
        boolean there = p.level().dimension() == TRIAL_DIMENSION;
        if (d.active()) {
            if (!there || d.slot < 0) {
                finish(p, false, there);
            } else if (d.ver < TrialData.VERSION) {
                // 试炼内容已更新（关卡编号变化）：还原存档并请玩家重新进入
                finish(p, false, true);
                p.displayClientMessage(Component.translatable("msg.zhushenspace.trial.updated"), false);
            } else {
                int old = d.slot;
                d.slot = allocSlot(p, old);
                if (d.slot != old) {
                    // 槽位被占用：在新位置重建并回到本关入口（已开的门保持开启）
                    ServerLevel l = (ServerLevel) p.level();
                    d.gen++;
                    TrialArena.build(l, d.slot, d.gen);
                    for (int i = 0; i < TrialArena.ROOMS - 1; i++) if ((d.doors & (1 << i)) != 0) TrialArena.openDoor(l, d.slot, i);
                    teleport(p, l, TrialArena.checkpoint(d.slot, d.stage));
                }
            }
        } else if (there && !p.hasPermissions(2)) {
            ServerLevel ow = p.server.overworld();
            BlockPos sp = ow.getSharedSpawnPos();
            p.teleportTo(ow, sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5, 0f, 0f);
        }
        sync(p);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        SLOTS.remove(p.getUUID());
        forget(p);
    }

    /** 被其他方式带离试炼维度（指令传送等）：立即还原存档，结束本次试炼 */
    @SubscribeEvent
    public static void onChangedDim(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || EXITING.contains(p.getUUID())) return;
        if (e.getFrom() == TRIAL_DIMENSION && data(p).active()) {
            finish(p, false, false);
            p.displayClientMessage(Component.translatable("msg.zhushenspace.trial.quit"), false);
        }
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent e) {
        SLOTS.clear();
        DUMMIES.clear();
        PUPPETS.clear();
        PUPPET_NEXT.clear();
        ART_AT.clear();
        ON_PAD.clear();
        EXITING.clear();
        for (Battle b : BATTLES.values()) if (b.bar != null) b.bar.removeAllPlayers();
        BATTLES.clear();
    }

    // ===================== 指令 =====================

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        CommandDispatcher<CommandSourceStack> d = e.getDispatcher();
        d.register(Commands.literal("zs").then(Commands.literal("tutorial")
                .then(Commands.literal("start").executes(c -> { start(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("restart").executes(c -> { restart(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("skip").executes(c -> { skip(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("quit").executes(c -> { quit(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("info").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    TrialData t = data(p);
                    p.displayClientMessage(Component.translatable("msg.zhushenspace.trial.info",
                            Component.translatable("trial.zhushenspace.status." + t.status), t.completions,
                            t.bestTicks > 0 ? formatTime(t.bestTicks) : "-"), false);
                    return 1;
                }))));
    }

    // ===================== 同步 =====================

    public static void sync(ServerPlayer p) {
        TrialData d = data(p);
        UUID id = p.getUUID();
        boolean done = d.status == TrialData.DONE;
        PacketDistributor.sendToPlayer(p, new TrialStatePayload(d.status, d.stage, d.done, d.count, d.template,
                d.active() ? d.deaths : d.lastDeaths,
                PICK_NONCE.getOrDefault(id, 0), FINISH_NONCE.getOrDefault(id, 0), d.lastTicks, d.completions,
                d.active() ? d.hits : d.lastHits, d.active() ? d.skills : d.lastSkills, d.active() ? d.kills : d.lastKills,
                done ? d.bestTicks : 0, done && d.lastBest));
    }

    static String formatTime(long ticks) {
        long s = ticks / 20;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
