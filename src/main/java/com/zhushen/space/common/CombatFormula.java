package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.compat.TaczCompat;
import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.LimbPart;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.PlayerSkillData;
import com.zhushen.space.data.SkillType;
import com.zhushen.space.data.WeaponCategory;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.ArrowLooseEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 攻击判定公式（替换原版伤害加成；随后仍走 20%~100% 浮动、暴击 / 弱点等调整）。
 * <pre>
 * 枪械：  属性（敏捷，炮为智力）+ 枪械 + 武器伤害 − 目标防御 ± 调整          （在 TACZ Pre 事件中结算，见 TaczGunEvents）
 * 白刃：  力量 + 白刃 + 武器伤害 − 目标防御 ± 调整
 * 肉搏：  力量 + 肉搏 + 天生武器伤害 − 目标防御 ± 调整
 * 投掷：  敏捷 + 运动 + 武器伤害 − 目标防御 − 距离减值 ± 调整；上限 = 武器伤害 + 运动 + 力量；距离上限 = 射程单位 × 力量
 * 弓箭：  敏捷 + 运动 + 武器伤害 − 目标防御 − 距离减值 ± 调整；上限 = 武器伤害 × 2 + 运动 + 弓的力量要求；距离上限 = 8 × 射程单位
 * </pre>
 * 目标防御 = 护甲值（玩家只计覆盖命中部位的盔甲）；公式攻击不再重复计算原版护甲减伤。
 * <p>
 * 器械减值：力量前提每差 1 点 −6（弓为 −2 且距离上限少 1 个射程单位），差超过 3 点无法使用；
 * 需要专业的分类（白刃 / 枪械的细分）没有对应专业 −9。
 * 距离减值：每超过 1 个射程单位 −6；力量不足时额外叠加（投掷每单位再 −6；弓为 2、6、12、20…× 差值）。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class CombatFormula {
    private CombatFormula() {}

    // ===== 物品分类（数据包标签，可自行增删） =====

    private static TagKey<Item> tag(String path) {
        return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "weapons/" + path));
    }

    public static final TagKey<Item> LONGSWORD = tag("longsword");
    public static final TagKey<Item> GREATSWORD = tag("greatsword");
    public static final TagKey<Item> RAPIER = tag("rapier");
    public static final TagKey<Item> FAN = tag("fan");
    public static final TagKey<Item> BOW = tag("bow");
    public static final TagKey<Item> THROWN = tag("thrown");

    /** 武器参数：力量前提、射程单位（格）、投掷伤害（投掷武器用；弓箭伤害取箭的伤害） */
    public record Spec(int strReq, float range) {
        static final Spec NONE = new Spec(0, 0);
    }

    public static Spec spec(ItemStack stack) {
        if (stack.is(Items.BOW)) return new Spec(2, 8);
        if (stack.is(Items.CROSSBOW)) return new Spec(3, 10);
        if (stack.is(Items.TRIDENT)) return new Spec(2, 4);
        if (stack.is(BOW)) return new Spec(2, 8);
        if (stack.is(THROWN)) return new Spec(1, 4);
        return Spec.NONE;
    }

    /** 手持物品的分类（空手 = 天生武器；TACZ 枪按枪械类型；其余按标签，未分类 = 普通人造物品） */
    public static WeaponCategory classify(ItemStack stack) {
        if (stack.isEmpty()) return WeaponCategory.NATURAL;
        WeaponCategory gun = TaczCompat.gunCategory(stack);
        if (gun != null) return gun;
        if (stack.is(BOW)) return WeaponCategory.BOW;
        if (stack.is(THROWN)) return WeaponCategory.THROWN;
        if (stack.is(GREATSWORD)) return WeaponCategory.GREATSWORD;
        if (stack.is(RAPIER)) return WeaponCategory.RAPIER;
        if (stack.is(FAN)) return WeaponCategory.FAN;
        if (stack.is(LONGSWORD)) return WeaponCategory.LONGSWORD;
        return WeaponCategory.GENERIC;
    }

    // ===== 数值 =====

    public static int attr(ServerPlayer p, AttributeType t) {
        return p.getData(ModAttachments.PLAYER_ATTRIBUTES).points()[t.ordinal()];
    }

    public static int skill(ServerPlayer p, SkillType t) {
        return p.getData(ModAttachments.PLAYER_SKILLS).get(t.ordinal());
    }

    /** 没有专业惩罚（不需要专业的分类为 0） */
    public static int professionPenalty(ServerPlayer p, WeaponCategory c) {
        WeaponCategory.ProfGroup g = c.profGroup();
        if (g == null) return 0;
        PlayerSkillData d = p.getData(ModAttachments.PLAYER_SKILLS);
        return d.hasProfession(c) ? 0 : WeaponCategory.NO_PROFESSION_PENALTY;
    }

    public static int strengthDeficit(ServerPlayer p, ItemStack stack) {
        return Math.max(0, spec(stack).strReq() - attr(p, AttributeType.STRENGTH));
    }

    /** 目标防御：护甲值（玩家按命中部位） */
    public static float defense(LivingEntity victim, DamageSource src) {
        float d = victim instanceof ServerPlayer sp ? (float) LimbManager.defenseFor(sp, src) : victim.getArmorValue();
        // 倒地（远程 +3 / 近战 −6）与轻度不良状态（−4）
        d += StatusManager.defenseMod(victim, src);
        // 措手不及 / 擒抱中（面对组外攻击）：失去天生防御（闪避、格挡加值待接入）
        if (DamageRules.isFlatFooted(victim, src.getEntity())) {
            d -= (float) DamageRules.naturalDefense(victim);
            DamageRules.consumeFlat(victim, src.getEntity());
        }
        return Math.max(0f, d);
    }

    private static final ResourceLocation STR_ATTACK =
            ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "strength_attack_damage");

    /**
     * 近战武器伤害：原版本次伤害中除去力量属性加成的部分（保留攻击冷却、原版暴击、附魔等比例）。
     * 空手时即天生武器伤害（拳头基础 1）。
     */
    public static float weaponDamage(ServerPlayer p, float vanillaAmount) {
        double total = p.getAttributeValue(Attributes.ATTACK_DAMAGE);
        AttributeInstance inst = p.getAttribute(Attributes.ATTACK_DAMAGE);
        double str = 0;
        if (inst != null && inst.getModifier(STR_ATTACK) != null) str = inst.getModifier(STR_ATTACK).amount();
        if (total <= 0) return Math.max(0f, vanillaAmount);
        double ratio = vanillaAmount / total;
        return (float) Math.max(0, (total - str) * ratio);
    }

    /** 距离减值：每超过 1 个射程单位 −6 */
    private static int rangeExcess(double dist, float range) {
        if (range <= 0 || dist <= range) return 0;
        return (int) Math.ceil((dist - range) / range);
    }

    private static void deny(ServerPlayer p, String key) {
        p.displayClientMessage(Component.translatable(key), true);
    }

    // ===== 结算 =====

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onIncoming(LivingIncomingDamageEvent event) {
        DamageSource src = event.getSource();
        if (!(src.getEntity() instanceof ServerPlayer p)) return;
        LivingEntity victim = event.getEntity();
        if (p == victim || event.getAmount() <= 0f) return;
        if (WillpowerManager.isBonusStrike(p)) return;
        if (DamageRules.hasPending(victim)) return; // 模组能力伤害（心灵 / 回声检定等）自行结算
        if (GunDamage.isGun(src)) {
            // 枪械公式已在 TACZ Pre 事件中结算（含目标防御）；这里只取消原版护甲的重复减伤
            zeroArmor(event);
            return;
        }
        Entity direct = src.getDirectEntity();
        float def = defense(victim, src);
        float base;
        if (direct == p) {
            ItemStack stack = p.getMainHandItem();
            WeaponCategory cat = classify(stack);
            // 枪托 / 弓身 / 投掷武器拿在手里砸人：视为普通人造物品的白刃攻击
            if (cat.group == WeaponCategory.Group.GUN || cat.group == WeaponCategory.Group.BOW
                    || cat.group == WeaponCategory.Group.THROWN) cat = WeaponCategory.GENERIC;
            int deficit = strengthDeficit(p, stack);
            if (deficit > WeaponCategory.MAX_DEFICIT) {
                deny(p, "message.zhushenspace.weapon.too_heavy");
                event.setCanceled(true);
                return;
            }
            int pen = deficit * WeaponCategory.REQ_PENALTY + professionPenalty(p, cat);
            float wd = weaponDamage(p, event.getAmount());
            base = attr(p, AttributeType.STRENGTH) + skill(p, cat.skill) + wd - def - pen
                    + PoolEffects.checkBonus(p, AttributeType.STRENGTH) - StatusManager.attackPenalty(p, false, victim);
            base = Math.max(0f, base) * StatusEffects.successFactor(p); // 肌肉痉挛：失去一半自然成功数
            base = Math.max(0f, base);
            event.setAmount(base);
            DamageCap.setMeleeBase(p, victim, base);
        } else if (direct instanceof ThrownTrident trident) {
            ItemStack weapon = trident.getWeaponItem();
            if (weapon == null || weapon.isEmpty()) weapon = new ItemStack(Items.TRIDENT);
            Spec sp = spec(weapon);
            int deficit = strengthDeficit(p, weapon);
            int str = attr(p, AttributeType.STRENGTH);
            double dist = p.distanceTo(victim);
            if (deficit > WeaponCategory.MAX_DEFICIT || dist > sp.range() * str) {
                event.setAmount(0f); // 超出投掷距离上限：无法进行有意义的攻击
                zeroArmor(event);
                return;
            }
            int n = rangeExcess(dist, sp.range());
            int distPen = n * WeaponCategory.RANGE_PENALTY + (deficit > 0 ? n * WeaponCategory.RANGE_PENALTY : 0);
            float wd = event.getAmount();
            int ath = skill(p, SkillType.ATHLETICS);
            base = attr(p, AttributeType.AGILITY) + ath + wd - def - distPen - deficit * WeaponCategory.REQ_PENALTY
                    + PoolEffects.checkBonus(p, AttributeType.AGILITY) - StatusManager.attackPenalty(p, true, victim);
            base = Math.max(0f, Math.min(base, wd + ath + str)) * StatusEffects.successFactor(p);
            event.setAmount(base);
        } else if (direct instanceof AbstractArrow arrow) {
            ItemStack weapon = arrow.getWeaponItem();
            if (weapon == null || weapon.isEmpty()) weapon = p.getMainHandItem();
            Spec sp = spec(weapon);
            float range = sp.range() > 0 ? sp.range() : 8;
            int deficit = strengthDeficit(p, weapon);
            double dist = p.distanceTo(victim);
            if (deficit > WeaponCategory.MAX_DEFICIT || dist > (8 - deficit) * range) {
                event.setAmount(0f);
                zeroArmor(event);
                return;
            }
            int n = rangeExcess(dist, range);
            int distPen = n * WeaponCategory.RANGE_PENALTY + deficit * n * (n + 1);
            float wd = event.getAmount();
            int ath = skill(p, SkillType.ATHLETICS);
            base = attr(p, AttributeType.AGILITY) + ath + wd - def - distPen - deficit * 2
                    + PoolEffects.checkBonus(p, AttributeType.AGILITY) - StatusManager.attackPenalty(p, true, victim);
            base = Math.max(0f, Math.min(base, wd * 2 + ath + sp.strReq())) * StatusEffects.successFactor(p);
            event.setAmount(base);
        } else {
            return; // 其他弹射物（雪球等）保持原样
        }
        zeroArmor(event);
    }

    /**
     * 打在技艺弹幕（非生物实体）上的伤害：沿用本公式的攻击值部分，目标防御视为 0，不计距离减值；
     * 结果同样走 20%~100% 浮动。其他来源（枪械等）保持原值。
     */
    public static float objectHit(ServerPlayer p, DamageSource src, float amount) {
        Entity direct = src.getDirectEntity();
        float base;
        if (direct == p) {
            ItemStack stack = p.getMainHandItem();
            WeaponCategory cat = classify(stack);
            if (cat.group == WeaponCategory.Group.GUN || cat.group == WeaponCategory.Group.BOW
                    || cat.group == WeaponCategory.Group.THROWN) cat = WeaponCategory.GENERIC;
            int deficit = strengthDeficit(p, stack);
            if (deficit > WeaponCategory.MAX_DEFICIT) return 0f;
            int pen = deficit * WeaponCategory.REQ_PENALTY + professionPenalty(p, cat);
            base = attr(p, AttributeType.STRENGTH) + skill(p, cat.skill) + weaponDamage(p, amount) - pen
                    + PoolEffects.checkBonus(p, AttributeType.STRENGTH);
        } else if (direct instanceof AbstractArrow || direct instanceof ThrownTrident) {
            base = attr(p, AttributeType.AGILITY) + skill(p, SkillType.ATHLETICS) + amount
                    + PoolEffects.checkBonus(p, AttributeType.AGILITY);
        } else {
            return amount;
        }
        base = Math.max(0f, base) * StatusEffects.successFactor(p);
        return base * DamageVariance.roll(p.getRandom());
    }

    private static void zeroArmor(LivingIncomingDamageEvent event) {
        event.addReductionModifier(DamageContainer.Reduction.ARMOR, (container, reduction) -> 0f);
    }

    // ===== 使用限制 =====

    /** 弓 / 弩：必须双手、力量差超过 3 点拉不开；投掷武器力量差超过 3 点无法投掷 */
    @SubscribeEvent
    public static void onUseStart(LivingEntityUseItemEvent.Start event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        ItemStack stack = event.getItem();
        WeaponCategory cat = classify(stack);
        if (cat != WeaponCategory.BOW && cat != WeaponCategory.THROWN) return;
        if (strengthDeficit(p, stack) > WeaponCategory.MAX_DEFICIT) {
            deny(p, cat == WeaponCategory.BOW ? "message.zhushenspace.weapon.cant_draw" : "message.zhushenspace.weapon.too_heavy");
            event.setCanceled(true);
            return;
        }
        if (cat == WeaponCategory.BOW && !twoHanded(p, stack)) {
            deny(p, "message.zhushenspace.weapon.two_handed");
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        ItemStack stack = event.getItemStack();
        if (classify(stack) != WeaponCategory.BOW) return;
        if (!twoHanded(p, stack)) {
            deny(p, "message.zhushenspace.weapon.two_handed");
            event.setCanceled(true);
        } else if (strengthDeficit(p, stack) > WeaponCategory.MAX_DEFICIT) {
            deny(p, "message.zhushenspace.weapon.cant_draw");
            event.setCanceled(true);
        }
    }

    /** 双手：两条手臂都在，且另一只手空着（副手可以放箭 / 烟花火箭作为弹药） */
    private static boolean twoHanded(ServerPlayer p, ItemStack bow) {
        var limbs = p.getData(ModAttachments.PLAYER_LIMBS);
        if (limbs.isSevered(LimbPart.RIGHT_ARM) || limbs.isSevered(LimbPart.LEFT_ARM)) return false;
        boolean inMain = p.getMainHandItem() == bow;
        ItemStack other = p.getItemInHand(inMain ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        return other.isEmpty() || other.is(ItemTags.ARROWS) || other.is(Items.FIREWORK_ROCKET);
    }

    /** 每次弓箭攻击都消耗一支箭（无限附魔也不例外，创造模式除外） */
    @SubscribeEvent
    public static void onLoose(ArrowLooseEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.getAbilities().instabuild) return;
        ItemStack bow = event.getBow();
        if (BowItem.getPowerForTime(event.getCharge()) < 0.1f) return;
        int inf;
        try {
            inf = EnchantmentHelper.getItemEnchantmentLevel(p.registryAccess()
                    .registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.INFINITY), bow);
        } catch (RuntimeException e) {
            return;
        }
        if (inf <= 0) return;
        ItemStack ammo = p.getProjectile(bow);
        if (!ammo.isEmpty() && ammo.is(Items.ARROW)) ammo.shrink(1);
    }

    // ===== 面板（不含目标防御与距离） =====

    /** 近战 / 弓 / 投掷的面板伤害；枪械由 TaczGunEvents.panelDamage 计算 */
    public static float panel(ServerPlayer p, float vanillaAttackDamage) {
        ItemStack stack = p.getMainHandItem();
        WeaponCategory cat = classify(stack);
        int deficit = strengthDeficit(p, stack);
        switch (cat.group) {
            case BOW -> {
                float wd = stack.is(Items.CROSSBOW) ? 9f : 6f; // 满弦箭矢的典型伤害
                return Math.max(0f, Math.min(attr(p, AttributeType.AGILITY) + skill(p, SkillType.ATHLETICS) + wd - deficit * 2,
                        wd * 2 + skill(p, SkillType.ATHLETICS) + spec(stack).strReq()));
            }
            case THROWN -> {
                float wd = 8f;
                int ath = skill(p, SkillType.ATHLETICS), str = attr(p, AttributeType.STRENGTH);
                return Math.max(0f, Math.min(attr(p, AttributeType.AGILITY) + ath + wd - deficit * WeaponCategory.REQ_PENALTY, wd + ath + str));
            }
            default -> {
                if (cat.group == WeaponCategory.Group.GUN) cat = WeaponCategory.GENERIC;
                float wd = weaponDamage(p, vanillaAttackDamage);
                return Math.max(0f, attr(p, AttributeType.STRENGTH) + skill(p, cat.skill) + wd
                        - deficit * WeaponCategory.REQ_PENALTY - professionPenalty(p, cat));
            }
        }
    }
}
