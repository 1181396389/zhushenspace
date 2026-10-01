package com.zhushen.space.entity.art;

import com.zhushen.space.entity.ModEntities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * 非弹体技艺的纯视觉特效实体（动漫风 v3）：不碰撞、不保存、不可选中，只负责把一次施法的演出同步给周围玩家。
 * <ul>
 *   <li>光束类（精神冲击 / 魔能爆 / 五行 / 生物闪电 / 凤仙火）：实体位于出手点，END = 终点相对出手点的偏移。</li>
 *   <li>附身类（治疗 / 息法 / 夜叉 / 防护 / 黄泉 / 起死回生 / 精神震荡）：FOLLOW = 跟随的实体 id，客户端按该实体的插值位置逐帧绘制。</li>
 *   <li>定点类（无视我的消散残影 / 基础掌法的掌印）：留在原地。</li>
 * </ul>
 * 伤害、治疗等结算全部在服务端 ArtManager 中按演出节奏延迟执行，本实体不参与任何规则。
 */
public class ArtVfx extends Entity {
    public static final int MIND_BEAM = 0, MIND_QUAKE = 1, ARCANE = 2, ELEMENT = 3, BIO_BOLT = 4, PHOENIX = 5,
            HEAL = 6, BREATH = 7, YAKSHA = 8, WARD = 9, VANISH = 10, NETHER = 11, PALM = 12, REVIVE = 13,
            THUNDER_BLADE = 14, THUNDER_MARK = 15, THUNDER_STRIKE = 16, LUMEN = 17, LIGHT_ORBS = 18, FROST_CLAW = 19;

    private static final EntityDataAccessor<Integer> KIND = SynchedEntityData.defineId(ArtVfx.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> COLOR = SynchedEntityData.defineId(ArtVfx.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> VARIANT = SynchedEntityData.defineId(ArtVfx.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> LIFE = SynchedEntityData.defineId(ArtVfx.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> OWNER_ID = SynchedEntityData.defineId(ArtVfx.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> FOLLOW_ID = SynchedEntityData.defineId(ArtVfx.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Vector3f> END = SynchedEntityData.defineId(ArtVfx.class, EntityDataSerializers.VECTOR3);
    /** 提前收尾（黄泉活力被下一次近战消耗）：客户端从该 tick 起淡出 */
    private static final EntityDataAccessor<Integer> FADE_AT = SynchedEntityData.defineId(ArtVfx.class, EntityDataSerializers.INT);

    /** 客户端：一次性的屏幕震颤是否已播放 */
    public boolean clientFxFired;
    private int lerpSteps;
    private double lx, ly, lz;

    public ArtVfx(EntityType<? extends ArtVfx> type, Level level) {
        super(type, level);
        setNoGravity(true);
        noPhysics = true;
    }

    @Override protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(KIND, MIND_BEAM); b.define(COLOR, 0xFFFFFFFF); b.define(VARIANT, 0); b.define(LIFE, 20);
        b.define(OWNER_ID, -1); b.define(FOLLOW_ID, -1); b.define(END, new Vector3f()); b.define(FADE_AT, -1);
    }

    public int kind() { return entityData.get(KIND); }
    public int color() { return entityData.get(COLOR); }
    public int variant() { return entityData.get(VARIANT); }
    public int life() { return entityData.get(LIFE); }
    public int ownerId() { return entityData.get(OWNER_ID); }
    public int followId() { return entityData.get(FOLLOW_ID); }
    public Vector3f end() { return entityData.get(END); }
    public int fadeAt() { return entityData.get(FADE_AT); }

    // ───────────── 服务端生成 ─────────────

    private static ArtVfx make(ServerPlayer p, int kind, int color, int variant, int life, Vec3 at, Vec3 dir) {
        ArtVfx e = new ArtVfx(ModEntities.ART_VFX.get(), p.level());
        e.entityData.set(KIND, kind); e.entityData.set(COLOR, color); e.entityData.set(VARIANT, variant);
        e.entityData.set(LIFE, life); e.entityData.set(OWNER_ID, p.getId());
        if (dir == null || dir.lengthSqr() < 1e-6) dir = p.getViewVector(1);
        dir = dir.normalize();
        e.moveTo(at.x, at.y, at.z, (float) Math.toDegrees(Math.atan2(-dir.x, dir.z)),
                (float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, dir.y)))));
        return e;
    }

    /** 光束：from → to */
    public static ArtVfx beam(ServerPlayer p, int kind, int color, int variant, int life, Vec3 from, Vec3 to) {
        ArtVfx e = make(p, kind, color, variant, life, from, to.subtract(from));
        Vec3 d = to.subtract(from);
        e.entityData.set(END, new Vector3f((float) d.x, (float) d.y, (float) d.z));
        p.level().addFreshEntity(e);
        return e;
    }

    /** 附身：跟随 target（脚底） */
    public static ArtVfx on(ServerPlayer p, int kind, int color, int variant, int life, Entity target) {
        ArtVfx e = make(p, kind, color, variant, life, target.position(), p.getViewVector(1));
        e.entityData.set(FOLLOW_ID, target.getId());
        if (target != p) {
            // 记录施法者 → 目标的偏移（治疗光流从施法者手中流向目标）
            Vec3 d = p.getEyePosition().add(0, -0.35, 0).subtract(target.position());
            e.entityData.set(END, new Vector3f((float) d.x, (float) d.y, (float) d.z));
        }
        p.level().addFreshEntity(e);
        return e;
    }

    /** 定点：留在 at，朝向 dir */
    public static ArtVfx at(ServerPlayer p, int kind, int color, int variant, int life, Vec3 at, Vec3 dir) {
        ArtVfx e = make(p, kind, color, variant, life, at, dir);
        p.level().addFreshEntity(e);
        return e;
    }

    /** 提前收尾：再播放 tail tick 的淡出后移除 */
    public void finish(int tail) {
        if (fadeAt() >= 0) return;
        entityData.set(FADE_AT, tickCount);
        entityData.set(LIFE, Math.min(life(), tickCount + tail));
    }

    // ───────────── tick ─────────────

    @Override public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps) {
        lx = x; ly = y; lz = z; lerpSteps = 2;
        setYRot(yaw); setXRot(pitch);
    }

    @Override public void tick() {
        super.tick();
        if (level().isClientSide) {
            if (lerpSteps > 0) {
                setPos(getX() + (lx - getX()) / lerpSteps, getY() + (ly - getY()) / lerpSteps, getZ() + (lz - getZ()) / lerpSteps);
                lerpSteps--;
            }
            return;
        }
        if (tickCount > life() || !level().hasChunkAt(blockPosition())) { discard(); return; }
        int f = followId();
        if (f >= 0) {
            Entity t = ((ServerLevel) level()).getEntity(f);
            if (t == null || t.isRemoved() || (t instanceof net.minecraft.world.entity.LivingEntity le && !le.isAlive())) { discard(); return; }
            setPos(t.position());
        }
    }

    @Override public boolean shouldBeSaved() { return false; }
    @Override public boolean isPickable() { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean isAttackable() { return false; }
    @Override public boolean hurt(net.minecraft.world.damagesource.DamageSource src, float amount) { return false; }
    @Override protected void readAdditionalSaveData(CompoundTag tag) {}
    @Override protected void addAdditionalSaveData(CompoundTag tag) {}
}
