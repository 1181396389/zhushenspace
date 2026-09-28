package com.zhushen.space.entity.dismember;

import com.zhushen.space.entity.ModEntities;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 断肢：从 T 病毒丧尸或玩家身上脱落的头 / 手臂 / 腿（玩家断肢带 owner，客户端按其皮肤渲染）。
 * 纯表现实体——受重力下落、在空中翻滚、落地后横躺，新鲜时滴血；20 秒后消失，不存盘、不可交互。
 */
public class SeveredLimb extends Entity {

    private static final EntityDataAccessor<Byte> DATA_PART =
            SynchedEntityData.defineId(SeveredLimb.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Float> DATA_SCALE =
            SynchedEntityData.defineId(SeveredLimb.class, EntityDataSerializers.FLOAT);
    /** 玩家断肢：原主人 UUID（渲染其皮肤）；丧尸断肢为空 */
    private static final EntityDataAccessor<java.util.Optional<java.util.UUID>> DATA_OWNER =
            SynchedEntityData.defineId(SeveredLimb.class, EntityDataSerializers.OPTIONAL_UUID);

    /** 存在时长（tick） */
    private static final int LIFETIME = 400;
    /** 新鲜滴血时长（tick） */
    private static final int BLEED_TICKS = 100;

    // 客户端翻滚角（绕自身横轴，度）
    private float spin, spinO;
    private float spinSpeed;

    public SeveredLimb(EntityType<? extends SeveredLimb> type, Level level) {
        super(type, level);
    }

    /** 在指定位置生成一截断肢，并给予初速度 */
    public static void spawn(Level level, BodyPart part, float scale, Vec3 pos, Vec3 velocity, float yaw) {
        spawn(level, part, scale, pos, velocity, yaw, null);
    }

    /** 生成玩家断肢（owner 非空时客户端使用该玩家的皮肤与粗细手臂模型渲染） */
    public static void spawn(Level level, BodyPart part, float scale, Vec3 pos, Vec3 velocity, float yaw,
                             java.util.UUID owner) {
        SeveredLimb limb = new SeveredLimb(ModEntities.SEVERED_LIMB.get(), level);
        limb.entityData.set(DATA_OWNER, java.util.Optional.ofNullable(owner));
        limb.entityData.set(DATA_PART, (byte) part.ordinal());
        limb.entityData.set(DATA_SCALE, scale);
        limb.moveTo(pos.x, pos.y, pos.z, yaw, 0f);
        limb.setDeltaMovement(velocity);
        level.addFreshEntity(limb);
    }

    public BodyPart part() {
        return BodyPart.byId(entityData.get(DATA_PART));
    }

    public java.util.UUID owner() {
        return entityData.get(DATA_OWNER).orElse(null);
    }

    public float scale() {
        return entityData.get(DATA_SCALE);
    }

    /** 渲染用翻滚角（插值） */
    public float spin(float partialTick) {
        return Mth.lerp(partialTick, spinO, spin);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_PART, (byte) 0);
        builder.define(DATA_SCALE, 1f);
        builder.define(DATA_OWNER, java.util.Optional.empty());
    }

    @Override
    public void tick() {
        super.tick();
        // 简单物理：重力 + 空气阻力 + 落地摩擦
        Vec3 v = getDeltaMovement().add(0, -0.04, 0);
        setDeltaMovement(v);
        move(MoverType.SELF, v);
        float friction = onGround() ? 0.55f : 0.98f;
        setDeltaMovement(getDeltaMovement().multiply(friction, 0.98, friction));

        if (level().isClientSide) {
            spinO = spin;
            if (tickCount == 1) spinSpeed = 18f + random.nextFloat() * 14f;
            if (!onGround()) {
                spin += spinSpeed;
            } else {
                // 落地：平滑转到最近的横躺角度（90° + 180°k）
                float target = Math.round((spin - 90f) / 180f) * 180f + 90f;
                spin += (target - spin) * 0.35f;
            }
            // 新鲜断肢滴血
            if (tickCount < BLEED_TICKS && random.nextInt(3) == 0) {
                level().addParticle(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState()),
                        getX() + (random.nextDouble() - 0.5) * 0.2, getY() + 0.1, getZ() + (random.nextDouble() - 0.5) * 0.2,
                        0, 0, 0);
            }
        } else if (tickCount > LIFETIME) {
            discard();
        }
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    /** 不参与原版伤害（断肢只是表现） */
    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        return false;
    }
}
