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
    /** 纯视觉特效：蓄力光团 / 口中火苗（跟随施法者）、出手冲击环、命中特效、挥砍残光 */
    public static final int CHARGE_ORB = 7, CHARGE_FIRE = 8, RING = 9, HIT_SLASH = 10, HIT_WIND = 11, HIT_WAVE = 12,
            HIT_SEAL = 13, SWING = 14;
    /** 跟随方式：0 不跟随；1 施法者视线前方（八卦阵）；2 右腰侧（波动拳蓄力）；3 口前（豪火球蓄力） */
    public static final int ANCHOR_NONE = 0, ANCHOR_EYE = 1, ANCHOR_HIP = 2, ANCHOR_MOUTH = 3;
    private static final EntityDataAccessor<Integer> KIND = SynchedEntityData.defineId(ArtProjectile.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> COLOR = SynchedEntityData.defineId(ArtProjectile.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> SIZE = SynchedEntityData.defineId(ArtProjectile.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DISTANCE = SynchedEntityData.defineId(ArtProjectile.class, EntityDataSerializers.FLOAT);
    /** 施法者实体 id 与"蓄力中跟随施法者视线"标记：客户端据此逐帧把八卦阵钉在施法者面前，转头时不再拖影 / 抖动 */
    private static final EntityDataAccessor<Integer> OWNER_ID = SynchedEntityData.defineId(ArtProjectile.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ANCHOR = SynchedEntityData.defineId(ArtProjectile.class, EntityDataSerializers.INT);
    /** 客户端：本实体触发的屏幕闪光 / 震屏是否已播放（每个实体只播一次） */
    public boolean clientFxFired;
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
        b.define(KIND, WAVE); b.define(COLOR, 0xFFA5F8E1); b.define(SIZE, 1f); b.define(DISTANCE, 0f); b.define(OWNER_ID, -1); b.define(ANCHOR, ANCHOR_NONE);
    }
    public int kind() { return entityData.get(KIND); }
    public int color() { return entityData.get(COLOR); }
    public float size() { return entityData.get(SIZE); }
    public int ownerId() { return entityData.get(OWNER_ID); }
    public int anchor() { return entityData.get(ANCHOR); }
    public boolean anchored() { return anchor() != ANCHOR_NONE; }
    public static boolean isFx(int kind) {
        return kind == BURST || kind == RING || kind == HIT_SLASH || kind == HIT_WIND || kind == HIT_WAVE || kind == HIT_SEAL || kind == SWING;
    }
    /**
     * 跟随施法者的特效位置（服务端与客户端共用；客户端每帧按插值后的姿态调用，转身不拖影）。
     * firstPerson：本地玩家第一人称时改放到视野内可见的位置。
     */
    public static Vec3 anchorPos(Entity o, int mode, float partial, boolean firstPerson) {
        Vec3 eye = o.getEyePosition(partial), view = o.getViewVector(partial);
        switch (mode) {
            case ANCHOR_EYE -> { return eye.add(view.scale(firstPerson ? 2.2 : SEAL_DISTANCE)); }
            case ANCHOR_HIP -> {
                if (firstPerson) {
                    double y = Math.toRadians(o.getViewYRot(partial));
                    Vec3 right = new Vec3(-Math.cos(y), 0, -Math.sin(y));
                    return eye.add(view.scale(0.85)).add(right.scale(0.36)).add(0, -0.4, 0);
                }
                float body = o instanceof LivingEntity le ? net.minecraft.util.Mth.rotLerp(partial, le.yBodyRotO, le.yBodyRot) : o.getViewYRot(partial);
                double y = Math.toRadians(body);
                Vec3 fwd = new Vec3(-Math.sin(y), 0, Math.cos(y)), right = new Vec3(-Math.cos(y), 0, -Math.sin(y));
                double hipY = o.isCrouching() ? 0.6 : 0.8;
                return o.getPosition(partial).add(0, hipY, 0).add(fwd.scale(0.08)).add(right.scale(0.46));
            }
            case ANCHOR_MOUTH -> {
                return firstPerson ? eye.add(view.scale(0.9)).add(0, -0.32, 0) : eye.add(view.scale(0.42)).add(0, -0.16, 0);
            }
            default -> { return o.getPosition(partial); }
        }
    }
    /** 八卦阵离施法者眼睛的距离 */
    public static final double SEAL_DISTANCE = 1.2;
    public float flightScale() { return growth(entityData.get(DISTANCE)); }
    private float growth(double d) { return kind() == FIREBALL ? 0.12f + 0.88f * (float)Math.min(1, d / 3) : 1; }
    private static ArtProjectile make(ServerPlayer p, int kind, int color, float size, Vec3 origin, Vec3 direction) {
        ArtProjectile e = new ArtProjectile(ModEntities.ART_PROJECTILE.get(), p.level());
        e.owner = p.getUUID(); e.entityData.set(OWNER_ID, p.getId()); e.entityData.set(KIND, kind); e.entityData.set(COLOR, color); e.entityData.set(SIZE, size);
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
        ArtProjectile e = make(p, SEAL, color, 1.25f, sealPos(p, dir), dir);
        e.entityData.set(ANCHOR, ANCHOR_EYE);
        e.lifetime = 220; p.level().addFreshEntity(e); return e;
    }
    /** 蓄力视觉（跟随施法者，蓄力结束即消失） */
    public static ArtProjectile charge(ServerPlayer p, int kind, int color, int anchor) {
        Vec3 dir = p.getViewVector(1);
        ArtProjectile e = make(p, kind, color, 1f, anchorPos(p, anchor, 1f, false), dir);
        e.entityData.set(ANCHOR, anchor);
        e.lifetime = 220; p.level().addFreshEntity(e); return e;
    }
    /** 一次性视觉特效（出手环、命中、挥砍残光、爆炸） */
    public static ArtProjectile fx(ServerPlayer p, int kind, int color, float size, Vec3 at, Vec3 dir, int life) {
        ArtProjectile e = make(p, kind, color, size, at, dir.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : dir);
        e.lifetime = life; p.level().addFreshEntity(e); return e;
    }
    /** 施法者面前的阵位；被墙挡住时贴在墙面前，激光永远不会从墙后发出 */
    public static Vec3 sealPos(ServerPlayer p, Vec3 dir) {
        Vec3 eye = p.getEyePosition(), want = eye.add(dir.scale(SEAL_DISTANCE));
        HitResult hit = p.level().clip(new ClipContext(eye, want, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (hit.getType() == HitResult.Type.MISS) return want;
        return eye.add(dir.scale(Math.max(0.15, eye.distanceTo(hit.getLocation()) - 0.1)));
    }
    public void releaseSeal(ArtBallistics.Shot shot) { this.shot = shot; this.lifetime = tickCount + 14; entityData.set(ANCHOR, ANCHOR_NONE); }
    public static void burst(ServerPlayer p, Vec3 at) {
        fx(p, BURST, 0xFFFFFFFF, 10f, at, new Vec3(0, 0, 1), 26);
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
        if (isFx(kind())) return;
        if (anchored()) {
            // 蓄力视觉：跟随施法者；蓄力结束（释放 / 取消）即消失
            if (!ArtCharge.charging(p)) { discard(); return; }
            Vec3 dir = p.getViewVector(1);
            setPos(anchor() == ANCHOR_EYE ? sealPos(p, dir) : anchorPos(p, anchor(), 1f, false));
            setYRot(p.getYRot()); setXRot(p.getXRot());
            return;
        }
        if (kind() == CHARGE_ORB || kind() == CHARGE_FIRE) { discard(); return; }
        if (kind() == SEAL) {
            if (shot == null) { discard(); return; }
            {
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
