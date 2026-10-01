package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.*;
import com.zhushen.space.entity.art.ArtProjectile;
import com.zhushen.space.network.ChargeSkillPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;

/** Server clock only. No damage/energy is committed until a validated release. */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class ArtCharge {
    public static final int MAX_TICKS = 40;
    /** 豪火球最短结印时间 */
    public static final int MIN_FIRE_TICKS = 12;
    private record Charging(ServerPlayer player, int bar, int slot, ArtSkill skill, int start, int nonce,
                            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                            ArtProjectile seal) {}
    private static final Map<UUID, Charging> ACTIVE = new HashMap<>();
    private static final Map<UUID, Integer> LAST_START = new HashMap<>();
    public static boolean supports(ArtSkill s) {
        return s == ArtSkill.HADOKEN || s == ArtSkill.GREAT_FIREBALL || s == ArtSkill.EIGHT_FORMATION;
    }
    public static boolean charging(ServerPlayer p) { return ACTIVE.containsKey(p.getUUID()); }

    private static boolean valid(Charging c) {
        ServerPlayer p = c.player();
        return !p.isRemoved() && p.isAlive() && !p.isSpectator()
                && p.serverLevel().dimension().equals(c.dimension())
                && p.getData(ModAttachments.PLAYER_SKILLS).bar(c.bar())[c.slot()] == c.skill().ability.ordinal()
                && ArtManager.data(p).owns(c.skill()) && ArtManager.hasPool(p, c.skill())
                && !TaiChiManager.isPoolSealed(p)
                && StatusEffects.canCast(p, ArtManager.isSpell(c.skill()))
                && (!ArtManager.isSpell(c.skill()) || ArtManager.gesture(p));
    }
    public static void handle(ServerPlayer p, ChargeSkillPayload packet) {
        if (packet.action() == ChargeSkillPayload.CANCEL) {
            Charging c = ACTIVE.get(p.getUUID());
            if (c != null && c.nonce() == packet.nonce()) cancel(p);
            return;
        }
        if (packet.bar() < 0 || packet.bar() >= PlayerSkillData.BAR_COUNT || packet.slot() < 0 || packet.slot() >= 9) return;
        if (packet.action() == ChargeSkillPayload.START) {
            if (charging(p)) return;
            int now = p.getServer().getTickCount();
            if (now - LAST_START.getOrDefault(p.getUUID(), -100) < 6) return;
            LAST_START.put(p.getUUID(), now);
            int id = p.getData(ModAttachments.PLAYER_SKILLS).bar(packet.bar())[packet.slot()];
            if (id < 0 || id >= SkillAbility.COUNT) return;
            ArtSkill s = ArtSkill.of(SkillAbility.values()[id]);
            if (!supports(s) || !ArtManager.data(p).owns(s)) return;
            String err = ArtManager.prereq(p, s);
            if (err != null) { ArtManager.deny(p, err); return; }
            if (SkillManager.remainingCooldowns(p)[id] > 0) return;
            if (ArtManager.energy(p, s) < s.cost) { ArtManager.deny(p, "msg.zhushenspace.art.lack_energy"); return; }
            Charging c = new Charging(p, packet.bar(), packet.slot(), s, p.getServer().getTickCount(), packet.nonce(), p.serverLevel().dimension(), null);
            if (!valid(c)) return;
            ArtProjectile seal = s == ArtSkill.EIGHT_FORMATION ? ArtProjectile.seal(p, ArtManager.formationColor(p)) : null;
            ACTIVE.put(p.getUUID(), new Charging(p, c.bar(), c.slot(), s, c.start(), c.nonce(), c.dimension(), seal));
            ArtFx.anim(p, s == ArtSkill.HADOKEN ? "art_charge_wave" : s == ArtSkill.GREAT_FIREBALL ? "art_charge_fire" : "art_charge_seal");
            ArtFx.castSfx(p, s.pool, 0.35f);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,
                    new com.zhushen.space.network.ChargeStatePayload(packet.nonce(), true, s.ordinal()));
        } else if (packet.action() == ChargeSkillPayload.RELEASE) {
            Charging c = ACTIVE.get(p.getUUID());
            if (c == null || c.nonce() != packet.nonce() || c.bar() != packet.bar() || c.slot() != packet.slot()) return;
            int held = Math.min(MAX_TICKS, Math.max(0, p.getServer().getTickCount() - c.start()));
            boolean canRelease = valid(c) && SkillManager.remainingCooldowns(p)[c.skill().ability.ordinal()] == 0;
            // Fire seals require a minimum windup; quick taps cancel without spending energy.
            if (c.skill() == ArtSkill.GREAT_FIREBALL && held < MIN_FIRE_TICKS) {
                canRelease = false;
                ArtManager.deny(p, "msg.zhushenspace.art.charge_short");
            }
            cancel(p);
            if (!canRelease) return;
            SkillManager.releaseCharged(p, c.bar(), c.slot(), c.skill(), held);
        }
    }
    public static void cancel(ServerPlayer p) {
        Charging c = ACTIVE.remove(p.getUUID());
        if (c == null) return;
        if (c.seal() != null) c.seal().discard();
        if (!p.isRemoved()) net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,
                new com.zhushen.space.network.ChargeStatePayload(c.nonce(), false, c.skill().ordinal()));
        if (!p.isRemoved()) ArtFx.anim(p, "");
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post e) {
        for (Charging c : List.copyOf(ACTIVE.values())) {
            if (c.player().getServer() == e.getServer() && (!valid(c) || e.getServer().getTickCount() - c.start() > 200)) cancel(c.player());
        }
    }
    @SubscribeEvent public static void logout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) { cancel(p); LAST_START.remove(p.getUUID()); }
    }
    @SubscribeEvent public static void stop(net.neoforged.neoforge.event.server.ServerStoppedEvent e) {
        ACTIVE.clear(); LAST_START.clear();
    }
}
