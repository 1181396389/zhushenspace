package com.zhushen.space.entity;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.common.ModGameRules;
import com.zhushen.space.entity.dismember.BodyPart;
import com.zhushen.space.entity.dismember.SeveredLimb;
import com.zhushen.space.entity.dismember.TVirusZombiePart;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.PartEntity;
import org.joml.Vector3f;

import java.util.List;

/**
 * T病毒丧尸——「生化危机」片（D级）的基础怪，第一个副本怪物。
 * 在原版僵尸的外观与 AI 行为上的强化版：
 * <ul>
 *   <li>属性强化：40 生命（×2）/ 6 攻击（×2）/ 0.27 移速 / 4 点护甲 / 40 格索敌</li>
 *   <li>T病毒变异体：不畏阳光（白天不会自燃）</li>
 *   <li>感染爪击：命中附加中毒（T病毒侵蚀），对玩家额外附加饥饿</li>
 *   <li>濒死狂暴：生命值低于 25% 时进入暴走——移速 II + 力量 I（红眼纹理 + 怒气粒子）</li>
 *   <li>尸群共鸣：任何同类死亡时，32 格内的同类被激怒 5 秒（尸潮机制雏形）</li>
 * </ul>
 * 伤害结算遵循现有 B/L/A 规则：丧尸攻击统一记为冲击（B）伤势。
 * <p>
 * <b>测试功能：部位肢解</b>（游戏规则 {@code zsDismemberment}，默认开启）
 * <ul>
 *   <li>头、躯干、双臂、双腿各有独立碰撞箱（{@link TVirusZombiePart}），本体不再直接可被拾取</li>
 *   <li>部位血量按最大生命值比例分配（见 {@link BodyPart}），躯干即本体生命值；
 *       命中四肢本体只承受一半伤害，部位承受全额</li>
 *   <li>部位血量归零：肢体脱落（{@link SeveredLimb}）+ 喷血，断口持续流血；头部脱落直接死亡</li>
 *   <li>断腿：移速大幅下降，双腿全断只能拖着上半身爬行（碰撞箱变矮）；
 *       断臂：攻击力下降，双臂全断只能撕咬；断掉的手臂会丢下手持物品</li>
 * </ul>
 */
public class TVirusZombie extends Zombie {
    /** 濒死狂暴的生命阈值（25%） */
    private static final float RAGE_THRESHOLD = 0.25F;
    /** 尸群共鸣的激怒半径 */
    private static final double ENRAGE_RADIUS = 32.0;

    // ===== 部位肢解（测试功能）=====

    /** 断一条腿的移速惩罚 / 双腿全断（爬行）的移速惩罚（乘算） */
    private static final double ONE_LEG_SPEED_PENALTY = -0.45;
    private static final double NO_LEG_SPEED_PENALTY = -0.75;
    /** 每断一条手臂的攻击力惩罚（乘算）；双臂全断 = 只能撕咬 */
    private static final double ARM_ATTACK_PENALTY = -0.35;
    private static final ResourceLocation SPEED_MOD_ID =
            ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "dismember_speed");
    private static final ResourceLocation ATTACK_MOD_ID =
            ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "dismember_attack");
    /** 断口大量喷血的时长（tick），之后转为缓慢滴血 */
    private static final int FRESH_BLEED_TICKS = 100;

    private static final EntityDataAccessor<Boolean> DATA_PARTS_ON =
            SynchedEntityData.defineId(TVirusZombie.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Byte> DATA_SEVERED =
            SynchedEntityData.defineId(TVirusZombie.class, EntityDataSerializers.BYTE);
    /** 各部位剩余血量比例（0~1），躯干不使用（直接取本体生命值） */
    private static final EntityDataAccessor<Float>[] DATA_PART_HP = createPartHpAccessors();

    @SuppressWarnings("unchecked")
    private static EntityDataAccessor<Float>[] createPartHpAccessors() {
        EntityDataAccessor<Float>[] arr = new EntityDataAccessor[BodyPart.values().length];
        for (BodyPart p : BodyPart.values()) {
            if (p.hasOwnPool()) arr[p.ordinal()] = SynchedEntityData.defineId(TVirusZombie.class, EntityDataSerializers.FLOAT);
        }
        return arr;
    }

    private static final DustParticleOptions BLOOD_MIST = new DustParticleOptions(new Vector3f(0.55f, 0.02f, 0.03f), 1.3f);
    private static final BlockParticleOption BLOOD_DROP =
            new BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState());

    /** 部位碰撞箱 */
    private final TVirusZombiePart[] parts;
    /** 本次 hurt 正在结算的部位（由部件转交时设置） */
    private BodyPart pendingPart;
    /** 各部位断开时刻（tickCount；不存盘，读档后视为旧伤缓慢滴血） */
    private final int[] severedAt = new int[BodyPart.values().length];

    /** 是否已进入狂暴态（运行时字段，实体重建后重新判定即可） */
    private boolean raging = false;

    public TVirusZombie(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
        this.xpReward = 8;
        BodyPart[] all = BodyPart.values();
        this.parts = new TVirusZombiePart[all.length];
        for (BodyPart p : all) parts[p.ordinal()] = new TVirusZombiePart(this, p);
        // 与末影龙相同：为部件预留连续的实体 id（客户端按本体 id + 序号还原部件 id）
        this.setId(ENTITY_COUNTER.getAndAdd(parts.length + 1) + 1);
        for (int i = 0; i < severedAt.length; i++) severedAt[i] = -FRESH_BLEED_TICKS;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Zombie.createAttributes()
                .add(Attributes.MAX_HEALTH, 40.0)
                .add(Attributes.ATTACK_DAMAGE, 6.0)
                .add(Attributes.MOVEMENT_SPEED, 0.27)
                .add(Attributes.ARMOR, 4.0)
                .add(Attributes.FOLLOW_RANGE, 40.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_PARTS_ON, true);
        builder.define(DATA_SEVERED, (byte) 0);
        for (EntityDataAccessor<Float> a : DATA_PART_HP) {
            if (a != null) builder.define(a, 1f);
        }
    }

    // ===== 多部件实体 =====

    @Override
    public void setId(int id) {
        super.setId(id);
        if (parts != null) {
            for (int i = 0; i < parts.length; i++) parts[i].setId(id + i + 1);
        }
    }

    @Override
    public boolean isMultipartEntity() {
        return true;
    }

    @Override
    public PartEntity<?>[] getParts() {
        return parts;
    }

    /** 部位肢解是否生效（服务端按游戏规则同步到客户端，客户端准星拾取也依赖它） */
    public boolean partsActive() {
        return entityData.get(DATA_PARTS_ON);
    }

    public int severedMask() {
        return entityData.get(DATA_SEVERED) & 0xFF;
    }

    public boolean isSevered(BodyPart part) {
        return (severedMask() & part.bit()) != 0;
    }

    /** 双腿全断：上半身下沉爬行 */
    public boolean legless() {
        return isSevered(BodyPart.RIGHT_LEG) && isSevered(BodyPart.LEFT_LEG);
    }

    /** 部位剩余血量比例（躯干 = 本体生命值比例） */
    public float partHealthRatio(BodyPart part) {
        if (!part.hasOwnPool()) return getMaxHealth() > 0 ? Mth.clamp(getHealth() / getMaxHealth(), 0f, 1f) : 0f;
        return isSevered(part) ? 0f : entityData.get(DATA_PART_HP[part.ordinal()]);
    }

    /** 任一部位受过伤（决定是否显示部位血条） */
    public boolean anyPartDamaged() {
        if (getHealth() < getMaxHealth() || severedMask() != 0) return true;
        for (BodyPart p : BodyPart.values()) {
            if (p.hasOwnPool() && entityData.get(DATA_PART_HP[p.ordinal()]) < 1f) return true;
        }
        return false;
    }

    /** 部件是否可被命中 */
    public boolean isPartHittable(BodyPart part) {
        return partsActive() && isAlive() && !isSevered(part);
    }

    /** 开启部位肢解后，本体不再直接可被拾取（只命中各部件） */
    @Override
    public boolean isPickable() {
        return !partsActive() && super.isPickable();
    }

    /** 部件受击 → 按部位结算 */
    public boolean hurtPart(TVirusZombiePart part, DamageSource source, float amount) {
        if (level().isClientSide) return false;
        BodyPart bp = part.bodyPart();
        if (!partsActive() || !bp.severable()) return hurt(source, amount);
        if (isSevered(bp)) return false;
        BodyPart prev = pendingPart;
        pendingPart = bp;
        try {
            return hurt(source, amount);
        } finally {
            pendingPart = prev;
        }
    }

    /**
     * 实际扣血：命中四肢时本体只承受一定比例（护甲等照常结算），
     * 部位血量按「本体实际扣除量 ÷ 比例」承受全额。
     */
    @Override
    protected void actuallyHurt(DamageSource source, float amount) {
        BodyPart bp = pendingPart;
        if (bp == null || !partsActive()) {
            super.actuallyHurt(source, amount);
            return;
        }
        pendingPart = null; // 防止嵌套伤害误记到同一部位
        if (!bp.hasOwnPool()) {
            // 头部：全额结算本体生命值；若这一击致死则断头（仅演出，不再有「头部血量耗尽即死」）
            super.actuallyHurt(source, amount);
            if (bp == BodyPart.HEAD && isDeadOrDying() && !isSevered(bp)) sever(bp, source);
            return;
        }
        float before = getHealth() + getAbsorptionAmount();
        // NeoForge 的 actuallyHurt 以伤害容器中的数值为准（忽略参数），因此直接缩放容器里的伤害
        if (bp.mainDamageFactor != 1f && !damageContainers.isEmpty()) {
            var container = damageContainers.peek();
            container.setNewDamage(container.getNewDamage() * bp.mainDamageFactor);
        }
        super.actuallyHurt(source, amount * bp.mainDamageFactor);
        float dealt = before - (getHealth() + getAbsorptionAmount());
        if (dealt > 0f && !isSevered(bp)) {
            damagePart(bp, dealt / bp.mainDamageFactor, source);
        }
    }

    private void damagePart(BodyPart bp, float damage, DamageSource source) {
        float max = bp.hpFraction * getMaxHealth();
        if (max <= 0f) return;
        EntityDataAccessor<Float> acc = DATA_PART_HP[bp.ordinal()];
        float ratio = entityData.get(acc) - damage / max;
        if (ratio <= 0f) {
            entityData.set(acc, 0f);
            sever(bp, source);
        } else {
            entityData.set(acc, ratio);
        }
    }

    /** 部位脱落 */
    private void sever(BodyPart bp, DamageSource source) {
        entityData.set(DATA_SEVERED, (byte) (severedMask() | bp.bit()));
        severedAt[bp.ordinal()] = tickCount;
        TVirusZombiePart part = parts[bp.ordinal()];
        Vec3 center = part.getBoundingBox().getCenter();

        // 断肢飞出：沿部位偏离身体的方向 + 受击方向 + 向上
        Vec3 away = center.subtract(position().x, center.y, position().z);
        away = away.lengthSqr() > 1.0e-4 ? away.normalize() : Vec3.ZERO;
        Entity src = source.getDirectEntity() != null ? source.getDirectEntity() : source.getEntity();
        Vec3 push = src != null ? center.subtract(src.position()).multiply(1, 0, 1) : Vec3.ZERO;
        push = push.lengthSqr() > 1.0e-4 ? push.normalize().scale(0.18) : Vec3.ZERO;
        double up = bp == BodyPart.HEAD ? 0.38 : bp.isLeg() ? 0.12 : 0.22;
        Vec3 vel = away.scale(0.16).add(push).add(
                (random.nextDouble() - 0.5) * 0.08, up + random.nextDouble() * 0.08, (random.nextDouble() - 0.5) * 0.08);
        SeveredLimb.spawn(level(), bp, getScale(), new Vec3(center.x, part.getY(), center.z), vel, yBodyRot);

        // 喷血 + 撕裂声
        if (level() instanceof ServerLevel server) {
            server.sendParticles(BLOOD_MIST, center.x, center.y, center.z, 28, 0.18, 0.18, 0.18, 0.06);
            server.sendParticles(BLOOD_DROP, center.x, center.y, center.z, 36, 0.15, 0.15, 0.15, 0.25);
        }
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 1.0f, 0.6f);
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, SoundSource.HOSTILE, 0.35f, 1.6f);

        switch (bp) {
            case HEAD -> {
                // 仅在头部命中致死时触发（见 actuallyHurt）；保险起见确保死亡
                setHealth(0f);
            }
            case RIGHT_ARM -> dropHeld(EquipmentSlot.MAINHAND);
            case LEFT_ARM -> dropHeld(EquipmentSlot.OFFHAND);
            default -> {
            }
        }
        if (bp.isLeg()) refreshDimensions();
        updateDismemberModifiers();
    }

    private void dropHeld(EquipmentSlot slot) {
        ItemStack held = getItemBySlot(slot);
        if (!held.isEmpty()) {
            spawnAtLocation(held.copy());
            setItemSlot(slot, ItemStack.EMPTY);
        }
    }

    /** 按断肢情况刷新移速 / 攻击力惩罚 */
    private void updateDismemberModifiers() {
        int legs = (isSevered(BodyPart.RIGHT_LEG) ? 1 : 0) + (isSevered(BodyPart.LEFT_LEG) ? 1 : 0);
        int arms = (isSevered(BodyPart.RIGHT_ARM) ? 1 : 0) + (isSevered(BodyPart.LEFT_ARM) ? 1 : 0);
        applyModifier(Attributes.MOVEMENT_SPEED, SPEED_MOD_ID,
                legs == 0 ? 0 : legs == 1 ? ONE_LEG_SPEED_PENALTY : NO_LEG_SPEED_PENALTY);
        applyModifier(Attributes.ATTACK_DAMAGE, ATTACK_MOD_ID, arms * ARM_ATTACK_PENALTY);
    }

    private void applyModifier(net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr,
                               ResourceLocation id, double amount) {
        AttributeInstance inst = getAttribute(attr);
        if (inst == null) return;
        inst.removeModifier(id);
        if (amount != 0) {
            inst.addTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    /** 双腿全断：碰撞箱变矮（上半身贴地） */
    @Override
    public EntityDimensions getDefaultDimensions(Pose pose) {
        EntityDimensions base = super.getDefaultDimensions(pose);
        if (parts != null && legless()) {
            float h = base.height();
            return base.scale(1f, Math.max(0.3f, (h - BodyPart.LEG_DROP * (h / 1.95f)) / h));
        }
        return base;
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_SEVERED.equals(key)) refreshDimensions();
    }

    @Override
    public void tick() {
        super.tick();
        updateParts();
        if (!level().isClientSide) {
            if (tickCount % 10 == 0 || tickCount <= 1) {
                boolean on = ModGameRules.DISMEMBERMENT == null
                        || level().getGameRules().getBoolean(ModGameRules.DISMEMBERMENT);
                if (on != partsActive()) entityData.set(DATA_PARTS_ON, on);
            }
            if (severedMask() != 0 && level() instanceof ServerLevel server) bleed(server);
        }
    }

    /** 按身体朝向与缩放重新定位各部件碰撞箱 */
    private void updateParts() {
        float s = getScale();
        float yaw = yBodyRot * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw);   // 面朝方向
        double rx = -Mth.cos(yaw), rz = -Mth.sin(yaw);  // 右侧方向
        double drop = legless() ? BodyPart.LEG_DROP : 0;
        for (TVirusZombiePart part : parts) {
            BodyPart bp = part.bodyPart();
            part.updateScale(s);
            part.setOldPosAndRot();
            double lat = bp.lateral * s, fwd = bp.forward * s;
            double y = (bp.isLeg() ? bp.bottom : bp.bottom - drop) * s;
            part.setPos(getX() + rx * lat + fx * fwd, getY() + y, getZ() + rz * lat + fz * fwd);
        }
    }

    /** 断口流血：刚断时大量喷溅，之后缓慢滴落 */
    private void bleed(ServerLevel server) {
        for (TVirusZombiePart part : parts) {
            BodyPart bp = part.bodyPart();
            if (!isSevered(bp) || (bp == BodyPart.HEAD && !isAlive() && deathTime > 15)) continue;
            boolean fresh = tickCount - severedAt[bp.ordinal()] < FRESH_BLEED_TICKS;
            int interval = fresh ? 2 : 12;
            if ((tickCount + bp.ordinal()) % interval != 0) continue;
            var box = part.getBoundingBox();
            double y = box.minY + (box.maxY - box.minY) * bp.stumpHeightRatio();
            Vec3 c = box.getCenter();
            server.sendParticles(BLOOD_DROP, c.x, y, c.z, fresh ? 3 : 1, 0.05, 0.02, 0.05, fresh ? 0.12 : 0.02);
            if (fresh) server.sendParticles(BLOOD_MIST, c.x, y, c.z, 2, 0.06, 0.04, 0.06, 0.01);
        }
    }

    // ===== 存档 =====

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putByte("ZsSevered", (byte) severedMask());
        CompoundTag hp = new CompoundTag();
        for (BodyPart p : BodyPart.values()) {
            if (p.hasOwnPool()) hp.putFloat(p.name(), entityData.get(DATA_PART_HP[p.ordinal()]));
        }
        tag.put("ZsPartHp", hp);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(DATA_SEVERED, tag.getByte("ZsSevered"));
        CompoundTag hp = tag.getCompound("ZsPartHp");
        for (BodyPart p : BodyPart.values()) {
            if (p.hasOwnPool() && hp.contains(p.name())) {
                entityData.set(DATA_PART_HP[p.ordinal()], Mth.clamp(hp.getFloat(p.name()), 0f, 1f));
            }
        }
        refreshDimensions();
        updateDismemberModifiers();
    }

    // ===== 原有行为 =====

    /** T病毒变异体不畏阳光 */
    @Override
    public boolean isSunSensitive() {
        return false;
    }

    /** 感染爪击：T病毒侵蚀 */
    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hurt = super.doHurtTarget(target);
        if (hurt && target instanceof LivingEntity living) {
            living.addEffect(new MobEffectInstance(MobEffects.POISON, 40, 0), this);
            if (target instanceof Player) {
                living.addEffect(new MobEffectInstance(MobEffects.HUNGER, 120, 0), this);
            }
        }
        return hurt;
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!level().isClientSide && isAlive() && getHealth() <= getMaxHealth() * RAGE_THRESHOLD) {
            if (!raging) {
                raging = true;
                // 进入狂暴：怒气粒子 + 捶门吼叫
                if (level() instanceof ServerLevel server) {
                    server.sendParticles(ParticleTypes.ANGRY_VILLAGER, getX(), getEyeY(), getZ(), 6, 0.4, 0.4, 0.4, 0.0);
                }
                playSound(SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, 1.0F, 1.2F);
            }
            // 狂暴期间静默续期：移速 II + 力量 I
            addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 60, 1, true, false), this);
            addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 60, 0, true, false), this);
        }
    }

    /** 尸群共鸣：死亡时激怒周围同类 */
    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!level().isClientSide) {
            List<TVirusZombie> kin = level().getEntitiesOfClass(TVirusZombie.class,
                    getBoundingBox().inflate(ENRAGE_RADIUS), z -> z != this && z.isAlive());
            for (TVirusZombie z : kin) {
                z.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 100, 0, false, true), this);
                z.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 100, 0, false, true), this);
            }
        }
    }
}
