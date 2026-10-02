package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.LimbPart;
import com.zhushen.space.data.MeleeWeapon;
import com.zhushen.space.data.MeleeWeapon.Trait;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.StatusType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 基础冷兵器的关键词与特殊属性（攻击判定本身在 {@link CombatFormula}）：
 * <ul>
 *   <li>【双手】单手持用（另一只手拿着东西或失去一条手臂）：攻击检定失去一半自然成功数。</li>
 *   <li>【威猛】近战攻击格挡中的目标：+ 目标格挡防御的一半（器械加值），上限 = 本次攻击主技能等级的一半。</li>
 *   <li>【眩晕】最终伤害超过目标耐力：造成 武器伤害 点晕眩点数（不可叠加），目标强韧豁免。
 *       非玩家生物没有不良状态系统：未豁免的点数改为每点 1 轮的减速与虚弱。</li>
 *   <li>破甲：依次削减目标的盾牌 / 盔甲 / 天生防御（DamageRules 再用剩余量击破硬度）。</li>
 *   <li>重武器：−6 器械减值，命中（浮动后伤害 &gt; 0）后 +2 附加成功（计入伤害上限）。</li>
 *   <li>轻型武器：近战白刃攻击时力量 / 敏捷取较高者。</li>
 *   <li>钝击武器 / 伤势等级 / 伤害类型：按模板记录给 DamageRules（冲击武器可切换为冲击伤害）。</li>
 * </ul>
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class WeaponRules {
    private WeaponRules() {}

    /** 重武器：器械减值 / 附加成功 */
    public static final int HEAVY_PENALTY = 6, HEAVY_BONUS = 2;

    /** 双手持用：双臂健在且另一只手空着 */
    public static boolean twoHanded(ServerPlayer p) {
        var limbs = p.getData(ModAttachments.PLAYER_LIMBS);
        if (limbs.isSevered(LimbPart.RIGHT_ARM) || limbs.isSevered(LimbPart.LEFT_ARM)) return false;
        return p.getOffhandItem().isEmpty();
    }

    private static final Map<UUID, Long> ONE_HAND_HINT = new HashMap<>();

    /** 单手持用双手武器的提示（每 5 秒至多一次） */
    static void oneHandHint(ServerPlayer p) {
        long now = p.level().getGameTime();
        Long last = ONE_HAND_HINT.get(p.getUUID());
        if (last != null && now - last < 100) return;
        ONE_HAND_HINT.put(p.getUUID(), now);
        p.displayClientMessage(Component.translatable("msg.zhushenspace.weapon.one_handed"), true);
    }

    /** 破甲：对目标防御的削减量（以盾牌 + 盔甲 + 天生防御为上限；非玩家生物为护甲值） */
    public static int pierce(LivingEntity v, DamageSource src, Entity attacker, int x) {
        if (x <= 0) return 0;
        if (v instanceof ServerPlayer sp) {
            Defense.Parts d = Defense.parts(sp, src, attacker, false);
            return Math.min(x, Math.max(0, d.shield() + d.armor() + d.natural()));
        }
        return Math.min(x, Math.max(0, v.getArmorValue()));
    }

    /** 威猛：目标正在格挡时的器械加值 */
    public static int mighty(LivingEntity v, DamageSource src, ServerPlayer attacker, int mainSkill) {
        if (!(v instanceof ServerPlayer sp)) return 0;
        int parry = Defense.parts(sp, src, attacker, false).parry();
        if (parry <= 0) return 0;
        return Math.max(0, Math.min(parry / 2, mainSkill / 2));
    }

    /** 记录本次攻击的伤势等级 / 伤害类型 / 破甲（由 DamageRules 在 LOWEST 结算时读取） */
    public static void note(LivingEntity v, DamageSource src, MeleeWeapon w, ItemStack stack) {
        DamageRules.noteMelee(v, src, w.severityFor(stack), EnumSet.of(w.kindFor(stack)), w.armorPierce);
    }

    /** 目标耐力（玩家 = 耐力属性；其他生物按生命上限估算） */
    static int endurance(LivingEntity v) {
        if (v instanceof ServerPlayer sp) {
            int i = AttributeType.ENDURANCE.ordinal();
            return CombatFormula.attr(sp, AttributeType.ENDURANCE) + FeatEffects.attrBonus(sp)[i];
        }
        return Math.max(1, Math.round(v.getMaxHealth() / 10f));
    }

    // ===== 剧痛（鞭子） =====

    /** 最终伤害超过目标耐力：造成武器伤害点剧痛（强韧豁免，不可叠加）；非玩家生物改为虚弱 + 缓慢 */
    private static void agony(ServerPlayer p, LivingEntity v, MeleeWeapon w, float dmg) {
        int end = endurance(v);
        if (dmg <= end) return;
        int pts = w.damage;
        int got;
        if (v instanceof ServerPlayer tp) {
            pts -= Defense.roll(tp, Defense.fort(tp)); // 强韧豁免
            if (pts <= 0) return;
            got = StatusManager.add(tp, StatusType.PAIN, pts, false, p, StatusManager.Source.MALICIOUS, 0, false, null);
        } else {
            pts -= Math.round(end * DamageVariance.roll(v.getRandom()));
            if (pts <= 0) return;
            int ticks = 60 * pts;
            v.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 0, false, true), p);
            v.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, 0, false, true), p);
            got = pts;
        }
        if (got <= 0) return;
        p.displayClientMessage(Component.translatable("msg.zhushenspace.weapon.agony", v.getDisplayName(), got), true);
        if (v.level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.DAMAGE_INDICATOR, v.getX(), v.getEyeY(), v.getZ(), 6, 0.3, 0.2, 0.3, 0.1);
            sl.playSound(null, v.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.5f, 1.9f);
        }
    }

    // ===== 【眩晕】 =====

    @SubscribeEvent
    public static void onDamagePost(LivingDamageEvent.Post e) {
        DamageSource src = e.getSource();
        if (!(src.getEntity() instanceof ServerPlayer p) || src.getDirectEntity() != p) return;
        LivingEntity v = e.getEntity();
        if (v == p || !v.isAlive()) return;
        MeleeWeapon w = MeleeWeapon.melee(p.getMainHandItem());
        if (w == null) return;
        if (w.has(Trait.AGONY)) agony(p, v, w, e.getNewDamage());
        if (!w.has(Trait.STUN)) return;
        int end = endurance(v);
        if (e.getNewDamage() <= end) return;
        int pts = w.damage;
        int got;
        if (v instanceof ServerPlayer tp) {
            pts -= Defense.roll(tp, Defense.fort(tp)); // 强韧豁免
            if (pts <= 0) return;
            got = StatusManager.add(tp, StatusType.DAZE, pts, false, p, StatusManager.Source.MALICIOUS, 0, false, null); // 不可叠加（按来源计）
        } else {
            pts -= Math.round(end * DamageVariance.roll(v.getRandom()));
            if (pts <= 0) return;
            int ticks = 60 * pts; // 每点 1 轮（3 秒）
            v.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, 1, false, true), p);
            v.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 0, false, true), p);
            got = pts;
        }
        if (got <= 0) return;
        p.displayClientMessage(Component.translatable("msg.zhushenspace.weapon.stun", v.getDisplayName(), got), true);
        if (v.level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.CRIT, v.getX(), v.getEyeY() + 0.3, v.getZ(), 10, 0.3, 0.1, 0.3, 0.05);
            sl.playSound(null, v.blockPosition(), SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.25f, 1.8f);
        }
    }
}
