package com.zhushen.space.entity.dismember;

import com.zhushen.space.entity.TVirusZombie;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.entity.PartEntity;

/**
 * T 病毒丧尸的部位碰撞箱（NeoForge 多部件实体）。
 * <p>
 * 与末影龙的部件一样：不单独存在于世界中，由本体每 tick 按身体朝向重新定位；
 * 近战（准星拾取）、箭矢与 TACZ 子弹都会命中部件，伤害转交本体按部位结算。
 * 部位断掉后碰撞箱失效（不可拾取）。
 */
public class TVirusZombiePart extends PartEntity<TVirusZombie> {

    private final BodyPart part;
    private float scale = 1f;
    private EntityDimensions size;

    public TVirusZombiePart(TVirusZombie parent, BodyPart part) {
        super(parent);
        this.part = part;
        this.size = EntityDimensions.scalable(part.width, part.height);
        this.refreshDimensions();
    }

    public BodyPart bodyPart() {
        return part;
    }

    /** 跟随本体缩放（幼体 / scale 属性） */
    public void updateScale(float s) {
        if (Math.abs(s - scale) > 1.0e-4f) {
            scale = s;
            size = EntityDimensions.scalable(part.width * s, part.height * s);
            refreshDimensions();
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    public boolean isPickable() {
        return getParent().isPartHittable(part);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return !isInvulnerableTo(source) && getParent().hurtPart(this, source, amount);
    }

    /** 点燃部件（火焰附加 / TACZ 燃烧弹等）转交本体 */
    @Override
    public void setRemainingFireTicks(int ticks) {
        if (getParent() != null) getParent().setRemainingFireTicks(ticks);
    }

    @Override
    public boolean is(Entity entity) {
        return this == entity || getParent() == entity;
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return size;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public ItemStack getPickResult() {
        return getParent().getPickResult();
    }
}
