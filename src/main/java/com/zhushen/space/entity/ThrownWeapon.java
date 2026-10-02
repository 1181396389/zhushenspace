package com.zhushen.space.entity;

import com.zhushen.space.data.MeleeWeapon;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * 投出的冷兵器（匕首等【轻投掷武器】）。命中伤害由 CombatFormula 按投掷公式结算
 * （关键属性 + 运动 + 武器伤害 − 防御 − 距离减值；伤势等级 / 伤害类型取武器模板）。
 * 命中或落地后可由投掷者捡回；无主的可被任何人捡起。
 */
public class ThrownWeapon extends AbstractArrow {

    private static final EntityDataAccessor<ItemStack> ITEM =
            SynchedEntityData.defineId(ThrownWeapon.class, EntityDataSerializers.ITEM_STACK);
    /** 落地后保留的时间（tick）：5 分钟 */
    private static final int KEEP_TICKS = 6000;

    private boolean dealtDamage;
    private int groundLife;

    public ThrownWeapon(EntityType<? extends ThrownWeapon> type, Level level) {
        super(type, level);
    }

    public ThrownWeapon(Level level, LivingEntity owner, ItemStack stack) {
        super(ModEntities.THROWN_WEAPON.get(), owner, level, stack, null);
        this.entityData.set(ITEM, stack.copyWithCount(1));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(ITEM, ItemStack.EMPTY);
    }

    /** 渲染用物品（客户端同步） */
    public ItemStack displayItem() { return this.entityData.get(ITEM); }

    /** 武器模板（服务端：取实际携带的物品） */
    @Nullable
    public MeleeWeapon weapon() { return MeleeWeapon.of(getPickupItemStackOrigin()); }

    @Override
    public ItemStack getWeaponItem() { return getPickupItemStackOrigin(); }

    public boolean inGround() { return this.inGround; }

    @Override
    public void tick() {
        if (this.inGroundTime > 4) this.dealtDamage = true;
        super.tick();
    }

    @Nullable
    @Override
    protected EntityHitResult findHitEntity(Vec3 from, Vec3 to) {
        return this.dealtDamage ? null : super.findHitEntity(from, to);
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        Entity target = hit.getEntity();
        Entity owner = getOwner();
        DamageSource src = damageSources().thrown(this, owner == null ? this : owner);
        MeleeWeapon w = weapon();
        float amount = w != null ? w.damage : 1f;
        this.dealtDamage = true;
        if (target.hurt(src, amount) && target instanceof LivingEntity le) {
            doKnockback(le, src);
            doPostHurtEffects(le);
        }
        setDeltaMovement(getDeltaMovement().multiply(-0.01, -0.1, -0.01));
        playSound(SoundEvents.TRIDENT_HIT, 0.7f, 1.6f);
    }

    @Override
    protected boolean tryPickup(Player player) {
        return super.tryPickup(player) || this.isNoPhysics() && this.ownedBy(player) && player.getInventory().add(this.getPickupItem());
    }

    @Override
    public void playerTouch(Player player) {
        if (this.ownedBy(player) || this.getOwner() == null) super.playerTouch(player);
    }

    @Override
    protected ItemStack getDefaultPickupItem() {
        return new ItemStack(com.zhushen.space.ZhuShenSpace.weaponItem(MeleeWeapon.DAGGER));
    }

    @Override
    protected SoundEvent getDefaultHitGroundSoundEvent() {
        return SoundEvents.TRIDENT_HIT_GROUND;
    }

    @Override
    protected void tickDespawn() {
        if (this.pickup != Pickup.ALLOWED) {
            super.tickDespawn();
            return;
        }
        if (++groundLife >= KEEP_TICKS) discard();
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.dealtDamage = tag.getBoolean("DealtDamage");
        this.groundLife = tag.getInt("GroundLife");
        this.entityData.set(ITEM, getPickupItemStackOrigin().copyWithCount(1));
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("DealtDamage", this.dealtDamage);
        tag.putInt("GroundLife", this.groundLife);
    }
}
