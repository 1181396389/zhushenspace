package com.zhushen.space.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

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
 */
public class TVirusZombie extends Zombie {
    /** 濒死狂暴的生命阈值（25%） */
    private static final float RAGE_THRESHOLD = 0.25F;
    /** 尸群共鸣的激怒半径 */
    private static final double ENRAGE_RADIUS = 32.0;

    /** 是否已进入狂暴态（运行时字段，实体重建后重新判定即可） */
    private boolean raging = false;

    public TVirusZombie(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
        this.xpReward = 8;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Zombie.createAttributes()
                .add(Attributes.MAX_HEALTH, 40.0)
                .add(Attributes.ATTACK_DAMAGE, 6.0)
                .add(Attributes.MOVEMENT_SPEED, 0.27)
                .add(Attributes.ARMOR, 4.0)
                .add(Attributes.FOLLOW_RANGE, 40.0);
    }

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
