package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.SkillType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 特殊能力检定（实时化：确定性数值 × 20%~100% 浮动，再截断到上限）。
 * <ul>
 *   <li>心灵检定：决心 + 沉着 ± 调整；攻击时再 − 目标防御。成功数上限 = 伤害上限 = 决心 + 沉着</li>
 *   <li>回声检定：风度 + 所选表达子技能 ± 调整（攻击同理）。上限 = 风度 + 表达技能；
 *       手持【回声】词条武器（标签 zhushenspace:weapons/echo）时，武器伤害同时计入数值与上限，且只有【回声】能提供武器伤害</li>
 * </ul>
 * 只用于特殊能力的施展；技能的基础应用不走这里。
 * 「获得相当于关键属性的加值」类效果：用 {@link #keyAttribute} 取两项关键属性之一（取高）。
 */
public final class CheckRules {
    private CheckRules() {}

    public static final TagKey<Item> ECHO = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "weapons/echo"));

    /** 表达子技能（目前共用「表达」技能等级，后续可按子技能细分） */
    public enum Expression { SING, PERFORM_MUSIC, DANCE, ACTING, SPEECH;
        public String nameKey() { return "expression.zhushenspace." + name().toLowerCase(java.util.Locale.ROOT); } }

    public enum Kind { MIND, ECHO }

    /** 检定结果：value = 截断后的成功数（攻击时即伤害），cap = 上限，raw = 截断前 */
    public record Result(float value, int cap, float raw) {
        public boolean success(float difficulty) { return value >= difficulty; }
    }

    private static int attr(ServerPlayer p, AttributeType t) {
        int[] a = p.getData(ModAttachments.PLAYER_ATTRIBUTES).points();
        return a[t.ordinal()] + FeatEffects.attrBonus(p)[t.ordinal()];
    }

    private static int skill(ServerPlayer p, SkillType t) { return p.getData(ModAttachments.PLAYER_SKILLS).get(t.ordinal()); }

    /** 表达子技能等级 */
    public static int expression(ServerPlayer p, Expression sub) { return skill(p, SkillType.EXPRESSION); }

    /** 【回声】武器伤害（非回声武器为 0） */
    public static int echoWeapon(ServerPlayer p) {
        ItemStack st = p.getMainHandItem();
        if (st.isEmpty() || !st.is(ECHO)) return 0;
        return (int) Math.round(Math.max(0, p.getAttributeValue(Attributes.ATTACK_DAMAGE) - 1));
    }

    /** 关键属性（「获得相当于关键属性的加值」时选其一，取高） */
    public static int keyAttribute(ServerPlayer p, Kind k) {
        return k == Kind.MIND ? Math.max(attr(p, AttributeType.RESOLVE), attr(p, AttributeType.COMPOSURE)) : attr(p, AttributeType.CHARM);
    }

    public static int cap(ServerPlayer p, Kind k, Expression sub) {
        return k == Kind.MIND ? attr(p, AttributeType.RESOLVE) + attr(p, AttributeType.COMPOSURE)
                : attr(p, AttributeType.CHARM) + expression(p, sub) + echoWeapon(p);
    }

    /** 非攻击检定 */
    public static Result check(ServerPlayer p, Kind k, Expression sub, int mod) {
        int cap = cap(p, k, sub);
        mod += boost(p, k);
        float raw = (cap + mod) * DamageVariance.roll(p.getRandom());
        return new Result(Math.max(0, Math.min(cap, raw)), cap, raw);
    }

    /** 能量加值（心灵：决心 / 沉着；回声：风度） */
    private static int boost(ServerPlayer p, Kind k) {
        return k == Kind.MIND ? PoolEffects.checkBonus(p, AttributeType.RESOLVE, AttributeType.COMPOSURE)
                : PoolEffects.checkBonus(p, AttributeType.CHARM);
    }

    public static Result mind(ServerPlayer p, int mod) { return check(p, Kind.MIND, null, mod); }

    public static Result echo(ServerPlayer p, Expression sub, int mod) { return check(p, Kind.ECHO, sub, mod); }

    /** 攻击检定：− 目标防御，伤害上限 = 检定上限 */
    public static Result attack(ServerPlayer p, Kind k, Expression sub, LivingEntity target, int mod) {
        int cap = cap(p, k, sub);
        mod += boost(p, k);
        float def = CombatFormula.defense(target, p.damageSources().playerAttack(p));
        float raw = (cap + mod - def) * DamageVariance.roll(p.getRandom());
        return new Result(Math.max(0, Math.min(cap, raw)), cap, raw);
    }

    /** 以检定结果对目标造成伤害（类型 / 等级由能力指定）。已自行扣除防御，不再重复计算护甲 */
    public static boolean strike(ServerPlayer p, Kind k, Expression sub, LivingEntity target, int mod, DamageRules.Spec spec) {
        Result r = attack(p, k, sub, target, mod);
        if (r.value() <= 0) return false;
        DamageSource src = p.damageSources().indirectMagic(p, p);
        return DamageRules.deal(target, src, r.value(), spec);
    }
}
