package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.ArtSkill;
import com.zhushen.space.data.DamageKind;
import com.zhushen.space.data.PowerRank;
import com.zhushen.space.data.StatusType;
import com.zhushen.space.item.MahoragaWheelItem;
import com.zhushen.space.network.SyncAdaptPayload;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 魔虚罗之法阵：适应（B 级物品）。
 * <ul>
 *   <li>只能适应 B 级及以下的伤害 / 能力（{@link PowerRank#adaptableByB}）；BB 及以上无法适应。无等级的来源（原版生物、
 *       普通攻击、环境）总是可以适应。</li>
 *   <li>「现象」：技艺 = 该技艺；生物攻击 = 该种生物 · 伤害类型；玩家的普通伤害 = 玩家 · 伤害类型；
 *       环境 = 环境 · 伤害类型；不良状态 = 该状态的点数。</li>
 *   <li>每次承受现象累积进度，进度满则法阵转动一格（每轮至多转动一次），该现象的伤害 / 状态点数降低一档；
 *       至多转动 {@link #MAX_TURNS} 次（上限 {@code MAX_TURNS × PER_TURN} = 50%），永远不会完全免疫。</li>
 *   <li>B 级限制：同时只能记住 {@link #MAX_TRACKED} 种现象（遇到新现象时遗忘最久未遇到的那一种）。</li>
 *   <li>法阵视为物品：精神 / 毒素伤害无视物品带来的伤害降低，因此法阵无法降低它们的伤害（仍可适应它们造成的状态）。</li>
 *   <li>法阵取下 / 未穿戴完毕、死亡、长休后适应清空。</li>
 * </ul>
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class AdaptationManager {
    private AdaptationManager() {}

    public static final int MAX_TURNS = 4;
    public static final double PER_TURN = 0.125;
    public static final int MAX_TRACKED = 3;
    /** 每轮（3 秒）至多转动一次 */
    public static final int TURN_COOLDOWN = 60;

    private static final class Phen {
        final String key;
        final List<String> label;
        int turns;
        float progress;
        long lastSeen;

        Phen(String key, List<String> label) { this.key = key; this.label = label; }
    }

    private static final class State {
        final LinkedHashMap<String, Phen> phens = new LinkedHashMap<>();
        int wheel;
        long lastTurn = Long.MIN_VALUE / 2;
        long lastDeny;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();
    private static boolean inited;

    /** 现象：键、显示（翻译键列表）、等级、是否无视物品 */
    public record Phenom(String key, List<String> label, PowerRank rank, boolean itemIgnored) {}

    public static void init() {
        if (inited) return;
        inited = true;
        DamageRules.addPercentReduction(h -> {
            if (!(h.victim instanceof ServerPlayer p) || !wearing(p)) return 0;
            State s = STATES.get(p.getUUID());
            if (s == null) return 0;
            Phenom ph = phenomenon(h.source, h.kinds);
            if (ph == null || !PowerRank.adaptableByB(ph.rank)) return 0;
            Phen x = s.phens.get(ph.key);
            return x == null ? 0 : x.turns * PER_TURN;
        });
    }

    // ===== 查询 =====

    /** 是否佩戴着已生效（穿戴完毕）的魔虚罗法阵 */
    public static boolean wearing(ServerPlayer p) {
        return p.getItemBySlot(EquipmentSlot.HEAD).getItem() instanceof MahoragaWheelItem && GearManager.effective(p, EquipmentSlot.HEAD);
    }

    private static boolean excluded(DamageSource src) {
        if (src.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return true;
        if (src.getEntity() != null) return false;
        return src.is(DamageTypes.FALL) || src.is(DamageTypes.DROWN) || src.is(DamageTypes.STARVE) || src.is(DamageTypes.IN_WALL)
                || src.is(DamageTypes.CRAMMING) || src.is(DamageTypes.FLY_INTO_WALL) || src.is(DamageTypes.OUTSIDE_BORDER)
                || src.is(DamageTypes.DRY_OUT) || src.is(DamageTypes.GENERIC);
    }

    /** 由伤害判定现象（null = 不是可适应的现象，如坠落、溺水） */
    public static Phenom phenomenon(DamageSource src, Set<DamageKind> kinds) {
        if (excluded(src)) return null;
        DamageKind k = ArtDamage.primary(kinds);
        boolean itemIgnored = kinds.stream().anyMatch(DamageKind::ignoresItems);
        Entity a = src.getEntity();
        if (a != null && ArtDamage.kindOf(src) != null) {
            ArtSkill art = ArtContext.of(a);
            if (art != null) return new Phenom("art:" + art.name(), List.of(art.ability.nameKey()), PowerRank.ofBranchTier(art.branchTier), itemIgnored);
        }
        if (a instanceof Player) return new Phenom("player:" + k.key, List.of("adapt.zhushenspace.src.player", k.nameKey()), null, itemIgnored);
        if (a != null) {
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(a.getType()).toString();
            return new Phenom(type + ":" + k.key, List.of(a.getType().getDescriptionId(), k.nameKey()), PowerRank.ofEntity(a), itemIgnored);
        }
        return new Phenom("env:" + k.key, List.of("adapt.zhushenspace.src.env", k.nameKey()), null, itemIgnored);
    }

    private static PowerRank rankOfSource(Entity source) {
        if (source == null) return null;
        ArtSkill art = source instanceof Player ? ArtContext.of(source) : null;
        if (art != null) return PowerRank.ofBranchTier(art.branchTier);
        return PowerRank.ofEntity(source);
    }

    public static Component label(List<String> keys) {
        MutableComponent c = Component.empty();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) c.append(" · ");
            c.append(Component.translatable(keys.get(i)));
        }
        return c;
    }

    // ===== 累积 =====

    private static long now(ServerPlayer p) { return p.level().getGameTime(); }

    private static Phen track(ServerPlayer p, State s, String key, List<String> label) {
        Phen x = s.phens.get(key);
        if (x == null) {
            if (s.phens.size() >= MAX_TRACKED) {
                Phen old = null;
                for (Phen y : s.phens.values()) if (old == null || y.lastSeen < old.lastSeen) old = y;
                if (old != null) {
                    s.phens.remove(old.key);
                    p.displayClientMessage(Component.translatable("msg.zhushenspace.adapt.forget", label(old.label)), false);
                }
            }
            x = new Phen(key, label);
            s.phens.put(key, x);
        }
        x.lastSeen = now(p);
        return x;
    }

    private static void deny(ServerPlayer p, State s, String msg, Object... args) {
        long t = now(p);
        if (t - s.lastDeny < 60) return;
        s.lastDeny = t;
        p.displayClientMessage(Component.translatable(msg, args), true);
    }

    private static boolean tryTurn(ServerPlayer p, State s, Phen x) {
        if (x.progress < 1f || x.turns >= MAX_TURNS) return false;
        long t = now(p);
        if (t - s.lastTurn < TURN_COOLDOWN) return false;
        x.turns++;
        x.progress = x.turns >= MAX_TURNS ? 1f : 0f;
        s.lastTurn = t;
        s.wheel++;
        ServerLevel lvl = p.serverLevel();
        double y = p.getY() + p.getBbHeight() + 0.45;
        lvl.playSound(null, p.getX(), y, p.getZ(), com.zhushen.space.sound.ModSounds.MAHORAGA_TURN.get(), SoundSource.PLAYERS, 1.0f, 1.0f);
        lvl.sendParticles(ParticleTypes.END_ROD, p.getX(), y, p.getZ(), 18, 0.45, 0.04, 0.45, 0.015);
        p.displayClientMessage(Component.translatable(x.turns >= MAX_TURNS ? "msg.zhushenspace.adapt.max" : "msg.zhushenspace.adapt.turn",
                label(x.label), x.turns, MAX_TURNS), true);
        return true;
    }

    @SubscribeEvent
    public static void onDamagePost(LivingDamageEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !wearing(p) || e.getOriginalDamage() <= 0) return;
        DamageSource src = e.getSource();
        Phenom ph = phenomenon(src, DamageRules.kinds(src, p));
        if (ph == null) return;
        State s = STATES.computeIfAbsent(p.getUUID(), k -> new State());
        if (!PowerRank.adaptableByB(ph.rank)) {
            deny(p, s, "msg.zhushenspace.adapt.too_high", label(ph.label), ph.rank.label());
            return;
        }
        if (ph.itemIgnored) {
            deny(p, s, "msg.zhushenspace.adapt.item_ignored", label(ph.label));
            return;
        }
        Phen x = track(p, s, ph.key, ph.label);
        if (x.turns < MAX_TURNS) {
            float ref = Math.max(4f, p.getMaxHealth() * 0.3f);
            x.progress = Math.min(1f, x.progress + 0.2f + 0.8f * Math.min(1f, e.getOriginalDamage() / ref));
            tryTurn(p, s, x);
        }
        sync(p);
    }

    /** 不良状态点数（StatusManager.addKeyed 在豁免之后调用）：返回适应后的点数 */
    public static int adaptStatus(ServerPlayer p, StatusType t, int amount, Entity source) {
        if (amount <= 0 || !wearing(p)) return amount;
        State s = STATES.computeIfAbsent(p.getUUID(), k -> new State());
        PowerRank r = rankOfSource(source);
        List<String> label = List.of(t.nameKey());
        if (!PowerRank.adaptableByB(r)) {
            deny(p, s, "msg.zhushenspace.adapt.too_high", label(label), r.label());
            return amount;
        }
        Phen x = track(p, s, "status:" + t.key, label);
        double pct = x.turns * PER_TURN;
        int cut = (int) Math.floor(amount * pct);
        double frac = amount * pct - cut;
        if (frac > 0 && p.getRandom().nextDouble() < frac) cut++;
        if (x.turns < MAX_TURNS) {
            x.progress = Math.min(1f, x.progress + 0.25f);
            tryTurn(p, s, x);
        }
        sync(p);
        return Math.max(0, amount - cut);
    }

    // ===== 维护 =====

    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p.tickCount % 10 != 0) return;
        State s = STATES.get(p.getUUID());
        if (s == null) return;
        if (!wearing(p)) {
            if (!s.phens.isEmpty()) p.displayClientMessage(Component.translatable("msg.zhushenspace.adapt.reset"), true);
            STATES.remove(p.getUUID());
            sync(p);
            return;
        }
        boolean turned = false;
        for (Phen x : s.phens.values()) if (tryTurn(p, s, x)) { turned = true; break; }
        if (turned) sync(p);
    }

    /** 长休 / 死亡等：清空适应 */
    public static void reset(ServerPlayer p) {
        if (STATES.remove(p.getUUID()) != null) sync(p);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) reset(p);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        STATES.remove(e.getEntity().getUUID());
        ArtContext.forget(e.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking e) {
        if (e.getTarget() instanceof ServerPlayer target && e.getEntity() instanceof ServerPlayer viewer) {
            State s = STATES.get(target.getUUID());
            PacketDistributor.sendToPlayer(viewer, new SyncAdaptPayload(target.getId(), s == null ? 0 : s.wheel, List.of()));
        }
    }

    public static void sync(ServerPlayer p) {
        State s = STATES.get(p.getUUID());
        int wheel = s == null ? 0 : s.wheel;
        List<SyncAdaptPayload.Entry> list = new ArrayList<>();
        if (s != null) for (Phen x : s.phens.values()) list.add(new SyncAdaptPayload.Entry(x.label, x.turns, x.progress));
        PacketDistributor.sendToPlayer(p, new SyncAdaptPayload(p.getId(), wheel, list));
        PacketDistributor.sendToPlayersTrackingEntity(p, new SyncAdaptPayload(p.getId(), wheel, List.of()));
    }

    /** 调试 / 显示：某现象当前的降低比例 */
    public static double reductionFor(ServerPlayer p, String key) {
        State s = STATES.get(p.getUUID());
        Phen x = s == null ? null : s.phens.get(key);
        return x == null ? 0 : x.turns * PER_TURN;
    }

    static boolean isAdapting(LivingEntity e) { return STATES.containsKey(e.getUUID()); }
}
