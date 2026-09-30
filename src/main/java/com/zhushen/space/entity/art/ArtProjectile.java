package com.zhushen.space.entity.art;

import com.zhushen.space.common.*;
import com.zhushen.space.entity.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import java.util.*;

/** Actual server-side flight/collision. Short lived; never saves, loads chunks or destroys terrain. */
public class ArtProjectile extends Entity {
    public static final int WIND = 0, WAVE = 1, SEAL = 2, LASER = 3, FIREBALL = 4, BURST = 5, SPIRIT = 6;
    private static final EntityDataAccessor<Integer> KIND = SynchedEntityData.defineId(ArtProjectile.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> COLOR = SynchedEntityData.defineId(ArtProjectile.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> SIZE = SynchedEntityData.defineId(ArtProjectile.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DISTANCE = SynchedEntityData.defineId(ArtProjectile.class, EntityDataSerializers.FLOAT);
    private UUID owner;
    private ArtBallistics.Shot shot;
    private double traveled;
    private int lifetime = 100;
    private boolean consumed;
    private int lerpSteps;
    private double lx, ly, lz;
    public ArtProjectile(EntityType<? extends ArtProjectile> type, Level level) {
        super(type, level); setNoGravity(true); noPhysics = true;
    }
    @Override protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(KIND, WAVE); b.define(COLOR, 0xFFA5F8E1); b.define(SIZE, 1f); b.define(DISTANCE, 0f);
    }
    public int kind() { return entityData.get(KIND); }
    public int color() { return entityData.get(COLOR); }
    public float size() { return entityData.get(SIZE); }
    public float flightScale() { return growth(entityData.get(DISTANCE)); }
    private float growth(double d) { return kind() == FIREBALL ? 0.12f + 0.88f * (float)Math.min(1, d / 3) : 1; }
    private static ArtProjectile make(ServerPlayer p, int kind, int color, float size, Vec3 origin, Vec3 direction) {
        ArtProjectile e = new ArtProjectile(ModEntities.ART_PROJECTILE.get(), p.level());
        e.owner = p.getUUID(); e.entityData.set(KIND, kind); e.entityData.set(COLOR, color); e.entityData.set(SIZE, size);
        e.moveTo(origin.x, origin.y, origin.z,
                (float) Math.toDegrees(Math.atan2(-direction.x, direction.z)),
                (float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, direction.normalize().y)))));
        return e;
    }
    public static ArtProjectile launch(ServerPlayer p, int kind, int color, float size, Vec3 origin,
                                       Vec3 direction, double speed, ArtBallistics.Shot shot) {
        ArtProjectile e = make(p, kind, color, size, origin, direction);
        e.shot = shot; e.setDeltaMovement(direction.normalize().scale(speed));
        p.level().addFreshEntity(e); return e;
    }
    public static ArtProjectile seal(ServerPlayer p, int color) {
        Vec3 dir = p.getViewVector(1);
        ArtProjectile e = make(p, SEAL, color, 1.25f, p.getEyePosition().add(dir.scale(1.2)), dir);
        e.lifetime = 220; p.level().addFreshEntity(e); return e;
    }
    public void releaseSeal(ArtBallistics.Shot shot) { this.shot = shot; this.lifetime = tickCount + 14; }
    public static void burst(ServerPlayer p, Vec3 at) {
        ArtProjectile e = make(p, BURST, 0xFFFFFFFF, 10f, at, new Vec3(0, 0, 1));
        e.lifetime = 14; p.level().addFreshEntity(e);
    }
    @Override public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps) {
        lx = x; ly = y; lz = z; lerpSteps = 1;
        setYRot(yaw); setXRot(pitch);
    }
    @Override public void tick() {
        super.tick();
        if (level().isClientSide) {
            if (lerpSteps > 0) { setPos(getX() + (lx-getX())/lerpSteps, getY() + (ly-getY())/lerpSteps, getZ() + (lz-getZ())/lerpSteps); lerpSteps--; }
            return;
        }
        ServerLevel level = (ServerLevel) level();
        ServerPlayer p = owner == null ? null : level.getServer().getPlayerList().getPlayer(owner);
        if (tickCount > lifetime || p == null || !p.isAlive() || p.serverLevel() != level || !level.hasChunkAt(blockPosition())) { discard(); return; }
        if (kind() == BURST) return;
        if (kind() == SEAL) {
            if (shot == null) {
                if (!ArtCharge.charging(p)) { discard(); return; }
                Vec3 dir = p.getViewVector(1);
                setPos(p.getEyePosition().add(dir.scale(1.2)));
                setYRot(p.getYRot()); setXRot(p.getXRot());
            } else {
                int remaining = lifetime - tickCount;
                if (remaining == 12 || remaining == 8 || remaining == 4) {
                    Vec3 dir = getViewVector(1);
                    // Central, parallel pulses: all use the same per-cast hit ledger.
                    launch(p, LASER, color(), 0.3f, position(), dir, 2.5, shot);
                }
            }
            return;
        }
        if (shot == null || consumed) { discard(); return; }
        Vec3 start = position(), motion = getDeltaMovement();
        double remaining = Math.max(0, shot.range() - traveled);
        Vec3 end = start.add(motion.length() > remaining ? motion.normalize().scale(remaining) : motion);
        if (!level.hasChunkAt(BlockPos.containing(end))) { discard(); return; }
        double radius = kind() == FIREBALL ? 0.65 * size() : kind() == WIND || kind() == SPIRIT ? 0.65 : kind() == WAVE ? 0.4 : 0.12;
        // Swept volume: center + shell rays, then select the earliest collision.
        double blockFraction = 1;
        boolean blocked = false;
        Vec3 impactNormal = Vec3.ZERO;
        Vec3 delta = end.subtract(start);
        double distance = delta.length();
        // Tapered sweep: growth itself can collide; never ignore near-wall contacts.
        double startRadius = radius * growth(traveled), endRadius = radius * growth(traveled + distance);
        Vec3[] offsets = {Vec3.ZERO, new Vec3(1,0,0), new Vec3(-1,0,0),
                new Vec3(0,1,0),new Vec3(0,-1,0),new Vec3(0,0,1),new Vec3(0,0,-1)};
        for (Vec3 offset : offsets) {
            HitResult hit = level.clip(new ClipContext(start.add(offset.scale(startRadius)), end.add(offset.scale(endRadius)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
            if (hit.getType() != HitResult.Type.MISS) {
                if (offset != Vec3.ZERO && ((BlockHitResult) hit).isInside()) continue;
                Vec3 shellStart = start.add(offset.scale(startRadius));
                double shellLength = shellStart.distanceTo(end.add(offset.scale(endRadius)));
                double f = shellLength < 1e-7 ? 0 : shellStart.distanceTo(hit.getLocation()) / shellLength;
                if (f <= blockFraction) {
                    blockFraction = f; blocked = true;
                    impactNormal = Vec3.atLowerCornerOf(((BlockHitResult) hit).getDirection().getNormal());
                }
            }
        }
        Vec3 stop = start.add(delta.scale(blockFraction));
        LivingEntity target = null;
        double closest = start.distanceToSqr(stop);
        for (LivingEntity t : level.getEntitiesOfClass(LivingEntity.class, new AABB(start, stop).inflate(endRadius + 1),
                t -> t != p && t.isAlive() && !t.isSpectator() && t.isAttackable() && !t.isAlliedTo(p) && !t.isPassengerOfSameVehicle(p))) {
            AABB bounds = t.getBoundingBox().inflate(endRadius);
            Optional<Vec3> contact = bounds.contains(start) ? Optional.of(start) : bounds.clip(start, stop);
            if (contact.isPresent() && start.distanceToSqr(contact.get()) <= closest
                    && AreaShape.effectLine(level, contact.get(), t, this)) {
                closest = start.distanceToSqr(contact.get()); stop = contact.get(); target = t;
            }
        }
        if (target != null || blocked) {
            consumed = true; setPos(stop);
            // Mark consumed before damage callbacks. One collision per projectile.
            Vec3 impactOrigin = target == null ? stop.add(impactNormal.scale(0.1)) : stop;
            // Avoid starting explosion sight rays inside a wall/corner collision shape.
            if (!level.noCollision(this, new AABB(impactOrigin, impactOrigin).inflate(0.01))) impactOrigin = start;
            ArtBallistics.impact(this, p, shot, target, impactOrigin);
            discard(); return;
        }
        traveled += distance; entityData.set(DISTANCE, (float)traveled); setPos(end);
        if (traveled >= shot.range()) discard();
    }
    @Override public boolean shouldBeSaved() { return false; }
    @Override public boolean isPickable() { return false; }
    @Override public boolean isPushable() { return false; }
    @Override protected void readAdditionalSaveData(CompoundTag tag) {}
    @Override protected void addAdditionalSaveData(CompoundTag tag) {}
}
