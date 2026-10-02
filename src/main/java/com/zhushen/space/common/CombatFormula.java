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
 * 目标防御：玩家按主神空间防御（基础 / 全力 / 格挡 / 闪避 / 天生 / 盔甲 / 洞察 / 其他，见 {@link Defense}），
 * 其他生物 = 护甲值；原版护甲与保护附魔不再按比例减伤。
 * <p>
 * 器械减值：力量前提每差 1 点 −6（弓为 −2 且距离上限少 1 个射程单位），差超过 3 点无法使用；
 * 需要专业的分类（白刃 / 枪械的细分）没有对应专业 −9。
 * 距离减值：每超过 1 个射程单位 −6；力量不足时额外叠加（投掷每单位再 −6；弓为 2、6、12、20…× 差值）。
 * <p>
 * 基础冷兵器（{@link com.zhushen.space.data.MeleeWeapon}）：轻型武器敏捷代替力量、重武器 −6 / +2 附加成功、
 * 【双手】单手持用减半、【威猛】、破甲，伤势等级与伤害类型按模板（见 {@link WeaponRules}）；投出的匕首走投掷公式。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class CombatFormula {
    private CombatFormula() {}

    // ===== 物品分类（数据包标签，可自行增删） =====

    private static TagKey<Item> tag(String path) {
        return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "weapons/" + path));
    }

    public static final TagKey<Item> LONGSWORD = tag("longsword");
    public static final TagKey<Item> AXE = tag("axe");
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
        com.zhushen.space.data.MeleeWeapon zw = com.zhushen.space.data.MeleeWeapon.of(stack);
        if (zw != null) return new Spec(zw.strReq, zw.throwRange); // 基础冷兵器：模板的前提力量 / 射程
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
        com.zhushen.space.data.MeleeWeapon mw = com.zhushen.space.data.MeleeWeapon.of(stack);
        if (mw != null) return mw.category;
        WeaponCategory gun = TaczCompat.gunCategory(stack);
        if (gun != null) return gun;
        if (stack.is(BOW)) return WeaponCategory.BOW;
        if (stack.is(THROWN)) return WeaponCategory.THROWN;
        if (stack.is(GREATSWORD)) return WeaponCategory.GREATSWORD;
        if (stack.is(RAPIER)) return WeaponCategory.RAPIER;
        if (stack.is(FAN)) return WeaponCategory.FAN;
        if (stack.is(LONGSWORD)) return WeaponCategory.LONGSWORD;
        if (stack.is(AXE)) return WeaponCategory.AXE;
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

    /** 近战攻击的关键属性：轻型武器（匕首）力量 / 敏捷取较高者，其余为力量 */
    public static AttributeType meleeKey(ServerPlayer p, com.zhushen.space.data.MeleeWeapon mw) {
        if (mw != null && mw.has(com.zhushen.space.data.MeleeWeapon.Trait.LIGHT_WEAPON)
                && attr(p, AttributeType.AGILITY) > attr(p, AttributeType.STRENGTH)) return AttributeType.AGILITY;
        return AttributeType.STRENGTH;
    }

    /** 天生武器伤害（拳）：基础 1；手持拳套（肉搏武器）时加上其提升值 */
    public static int naturalWeapon(ServerPlayer p) {
        com.zhushen.space.data.MeleeWeapon mw = com.zhushen.space.data.MeleeWeapon.melee(p.getMainHandItem());
        return 1 + (mw != null && mw.has(com.zhushen.space.data.MeleeWeapon.Trait.BRAWL_WEAPON) ? mw.damage : 0);
    }

    /** 目标防御（本次攻击结算：消耗攻击附带的措手不及与意志守御） */
    public static float defense(LivingEntity victim, DamageSource src) {
        return Defense.of(victim, src, true);
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
        Defense.endFull(p); // 发起攻击：全力防御解除
        if (WillpowerManager.isBonusStrike(p)) return;
        if (DamageRules.hasPending(victim)) return; // 模组能力伤害（心灵 / 回声检定等）自行结算
        if (GunDamage.isGun(src)) {
            // 枪械公式已在 TACZ Pre 事件中结算（含目标防御）；这里只取消原版护甲的重复减伤
            zeroArmor(event);
            return;
        }
        Entity direct = src.getDirectEntity();
        float def;
        float base;
        if (direct == p) {
            def = defense(victim, src);
            ItemStack stack = p.getMainHandItem();
            WeaponCategory cat = classify(stack);
            // 枪托 / 弓身 / 投掷武器拿在手里砸人：视为普通人造物品的白刃攻击
            if (cat.group == WeaponCategory.Group.GUN || cat.group == WeaponCategory.Group.BOW
                    || cat.group == WeaponCategory.Group.THROWN || cat == WeaponCategory.HIDDEN_WEAPON) cat = WeaponCategory.GENERIC;
            int deficit = strengthDeficit(p, stack);
            if (deficit > WeaponCategory.MAX_DEFICIT) {
                deny(p, "message.zhushenspace.weapon.too_heavy");
                event.setCanceled(true);
                return;
            }
            int pen = deficit * WeaponCategory.REQ_PENALTY + professionPenalty(p, cat);
            float wd = weaponDamage(p, event.getAmount());
            DamageRules.noteWeapon(victim, wd); // 「忽略武器伤害 X 点以下」等条件
            // 基础冷兵器：轻型武器（敏捷代替力量）/ 重武器（−6）/ 破甲 / 威猛
            com.zhushen.space.data.MeleeWeapon mw = com.zhushen.space.data.MeleeWeapon.melee(stack);
            AttributeType key = meleeKey(p, mw);
            int dp = 0;
            if (mw != null) {
                if (mw.has(com.zhushen.space.data.MeleeWeapon.Trait.HEAVY_WEAPON)) pen += WeaponRules.HEAVY_PENALTY;
                def = Math.max(0f, def - WeaponRules.pierce(victim, src, p, mw.armorPierce));
                if (mw.has(com.zhushen.space.data.MeleeWeapon.Trait.MIGHTY)) dp += WeaponRules.mighty(victim, src, p, skill(p, cat.skill));
            }
            base = attr(p, key) + skill(p, cat.skill) + wd - def - pen + dp
                    + PoolEffects.skillBonus(p, cat.skill, key) - StatusManager.attackPenalty(p, false, victim);
            base = Math.max(0f, base) * StatusEffects.successFactor(p); // 肌肉痉挛：失去一半自然成功数
            if (mw != null && mw.has(com.zhushen.space.data.MeleeWeapon.Trait.TWO_HANDED) && !WeaponRules.twoHanded(p)) {
                base *= 0.5f; // 【双手】单手持用：失去一半自然成功数
                WeaponRules.oneHandHint(p);
            }
            base = Math.max(0f, base);
            event.setAmount(base);
            DamageCap.setMeleeBase(p, victim, base);
            if (mw != null) {
                WeaponRules.note(victim, src, mw, stack);
                if (mw.has(com.zhushen.space.data.MeleeWeapon.Trait.HEAVY_WEAPON))
                    DamageVariance.addAfterRoll(victim, WeaponRules.HEAVY_BONUS);
            }
        } else if (direct instanceof com.zhushen.space.entity.ThrownWeapon tw && tw.weapon() != null) {
            // 投出的冷兵器：轻投掷武器以敏捷、重投掷武器以力量为关键属性；距离上限 = 基本射程 × 力量
            com.zhushen.space.data.MeleeWeapon w = tw.weapon();
            int str = attr(p, AttributeType.STRENGTH);
            double dist = p.distanceTo(victim);
            float range = w.throwRange;
            if (dist > range * Math.max(1, str)) {
                event.setAmount(0f);
                zeroArmor(event);
                return;
            }
            if (w.has(com.zhushen.space.data.MeleeWeapon.Trait.HIDDEN)) hiddenCheck(p, victim);
            def = defense(victim, src);
            def = Math.max(0f, def - WeaponRules.pierce(victim, src, p, w.armorPierce));
            int distPen = rangeExcess(dist, range) * WeaponCategory.RANGE_PENALTY;
            float wd = w.damage;
            DamageRules.noteWeapon(victim, wd);
            int ath = skill(p, SkillType.ATHLETICS);
            AttributeType key = w.has(com.zhushen.space.data.MeleeWeapon.Trait.LIGHT_THROWN) ? AttributeType.AGILITY : AttributeType.STRENGTH;
            base = attr(p, key) + ath + wd - def - distPen - professionPenalty(p, w.category)
                    + PoolEffects.skillBonus(p, SkillType.ATHLETICS, key) - StatusManager.attackPenalty(p, true, victim);
            base = Math.max(0f, Math.min(base, wd + ath + str)) * StatusEffects.successFactor(p);
            event.setAmount(base);
            WeaponRules.note(victim, src, w, tw.getWeaponItem());
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
            def = defense(victim, src);
            int n = rangeExcess(dist, sp.range());
            int distPen = n * WeaponCategory.RANGE_PENALTY + (deficit > 0 ? n * WeaponCategory.RANGE_PENALTY : 0);
            float wd = event.getAmount();
            DamageRules.noteWeapon(victim, wd); // 「忽略武器伤害 X 点以下」等条件
            int ath = skill(p, SkillType.ATHLETICS);
            base = attr(p, AttributeType.AGILITY) + ath + wd - def - distPen - deficit * WeaponCategory.REQ_PENALTY
                    + PoolEffects.skillBonus(p, SkillType.ATHLETICS, AttributeType.AGILITY) - StatusManager.attackPenalty(p, true, victim);
            base = Math.max(0f, Math.min(base, wd + ath + str)) * StatusEffects.successFactor(p);
            event.setAmount(base);
        } else if (direct instanceof AbstractArrow arrow && rangedOf(arrow) != null) {
            // 基础冷兵器的弓 / 弩：敏捷 + 运动 + 武器伤害 − (防御 − 破甲) − 距离减值；上限 = 武器伤害 × 2 + 运动 + 前提力量
            // 前提力量不足（弓箭）：同原版弓规则——每差 1 点 −2、距离上限缩短、超射程减值加重
            com.zhushen.space.data.MeleeWeapon w = rangedOf(arrow);
            ItemStack fired = arrow.getWeaponItem();
            int deficit = Math.max(0, w.strReq - attr(p, AttributeType.STRENGTH));
            double dist = p.distanceTo(victim);
            float range = w.throwRange;
            if (deficit > WeaponCategory.MAX_DEFICIT || dist > range * (8 - deficit)) {
                event.setAmount(0f);
                zeroArmor(event);
                return;
            }
            def = defense(victim, src);
            def = Math.max(0f, def - WeaponRules.pierce(victim, src, p, w.armorPierce));
            int n = rangeExcess(dist, range);
            int distPen = n * WeaponCategory.RANGE_PENALTY + deficit * n * (n + 1) + deficit * 2;
            float wd = w.damage;
            DamageRules.noteWeapon(victim, wd);
            int ath = skill(p, SkillType.ATHLETICS);
            base = attr(p, AttributeType.AGILITY) + ath + wd - def - distPen
                    + PoolEffects.skillBonus(p, SkillType.ATHLETICS, AttributeType.AGILITY) - StatusManager.attackPenalty(p, true, victim);
            base = Math.max(0f, Math.min(base, wd * 2 + ath + w.strReq)) * StatusEffects.successFactor(p);
            if (arrow.getPersistentData().getBoolean(com.zhushen.space.item.ZsCrossbowItem.ONE_HAND_TAG))
                base *= 0.5f; // 【双手】单手发射：失去一半自然成功数
            event.setAmount(base);
            WeaponRules.note(victim, src, w, fired == null ? ItemStack.EMPTY : fired);
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
            def = defense(victim, src);
            int n = rangeExcess(dist, range);
            int distPen = n * WeaponCategory.RANGE_PENALTY + deficit * n * (n + 1);
            float wd = event.getAmount();
            DamageRules.noteWeapon(victim, wd); // 「忽略武器伤害 X 点以下」等条件
            int ath = skill(p, SkillType.ATHLETICS);
            base = attr(p, AttributeType.AGILITY) + ath + wd - def - distPen - deficit * 2
                    + PoolEffects.skillBonus(p, SkillType.ATHLETICS, AttributeType.AGILITY) - StatusManager.attackPenalty(p, true, victim);
            base = Math.max(0f, Math.min(base, wd * 2 + ath + sp.strReq())) * StatusEffects.successFactor(p);
            event.setAmount(base);
        } else {
            return; // 其他弹射物（雪球等）保持原样
        }
        zeroArmor(event);
    }

    /**
     * 【暗器】：目标进行感知检定（本模组没有调查 / 侦察技能，只用感知），DC 2；
     * 失败则针对本次攻击措手不及（无支线）。非玩家生物没有感知，视为失败。
     */
    private static void hiddenCheck(ServerPlayer p, LivingEntity victim) {
        boolean noticed = victim instanceof ServerPlayer tp
                && Defense.roll(tp, Defense.attr(tp, AttributeType.PERCEPTION)) >= HIDDEN_DC;
        if (noticed) return;
        DamageRules.flatFooted(victim, p, 2); // 由本次攻击附带，防御结算时消耗
        p.displayClientMessage(Component.translatable("msg.zhushenspace.weapon.hidden_unnoticed", victim.getDisplayName()), true);
    }

    public static final int HIDDEN_DC = 2;

    /** 由基础冷兵器的弓 / 弩射出的箭矢 / 弩矢 → 模板，否则 null */
    private static com.zhushen.space.data.MeleeWeapon rangedOf(AbstractArrow arrow) {
        ItemStack w = arrow.getWeaponItem();
        if (w == null || w.isEmpty()) return null;
        com.zhushen.space.data.MeleeWeapon mw = com.zhushen.space.data.MeleeWeapon.of(w);
        return mw != null && mw.ranged() ? mw : null;
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
                    || cat.group == WeaponCategory.Group.THROWN || cat == WeaponCategory.HIDDEN_WEAPON) cat = WeaponCategory.GENERIC;
            int deficit = strengthDeficit(p, stack);
            if (deficit > WeaponCategory.MAX_DEFICIT) return 0f;
            int pen = deficit * WeaponCategory.REQ_PENALTY + professionPenalty(p, cat);
            com.zhushen.space.data.MeleeWeapon mw = com.zhushen.space.data.MeleeWeapon.melee(stack);
            if (mw != null && mw.has(com.zhushen.space.data.MeleeWeapon.Trait.HEAVY_WEAPON)) pen += WeaponRules.HEAVY_PENALTY;
            AttributeType key = meleeKey(p, mw);
            base = attr(p, key) + skill(p, cat.skill) + weaponDamage(p, amount) - pen
                    + PoolEffects.skillBonus(p, cat.skill, key);
            if (mw != null && mw.has(com.zhushen.space.data.MeleeWeapon.Trait.TWO_HANDED) && !WeaponRules.twoHanded(p)) base *= 0.5f;
        } else if (direct instanceof AbstractArrow arrow && rangedOf(arrow) != null) {
            base = attr(p, AttributeType.AGILITY) + skill(p, SkillType.ATHLETICS) + rangedOf(arrow).damage
                    + PoolEffects.skillBonus(p, SkillType.ATHLETICS, AttributeType.AGILITY);
        } else if (direct instanceof AbstractArrow || direct instanceof ThrownTrident) {
            base = attr(p, AttributeType.AGILITY) + skill(p, SkillType.ATHLETICS) + amount
                    + PoolEffects.skillBonus(p, SkillType.ATHLETICS, AttributeType.AGILITY);
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
        if (cat == WeaponCategory.CROSSBOW) return; // 基础冷兵器的弩：【双手】单手发射减半，不禁止
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
                com.zhushen.space.data.MeleeWeapon cw = com.zhushen.space.data.MeleeWeapon.of(stack);
                if (cw != null) { // 基础冷兵器的弓 / 弩
                    int ath = skill(p, SkillType.ATHLETICS);
                    float v = Math.max(0f, Math.min(attr(p, AttributeType.AGILITY) + ath + cw.damage - deficit * 2,
                            cw.damage * 2f + ath + cw.strReq));
                    if (cw.has(com.zhushen.space.data.MeleeWeapon.Trait.TWO_HANDED)
                            && !com.zhushen.space.item.ZsCrossbowItem.twoHanded(p, stack)) v *= 0.5f;
                    return v;
                }
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
                com.zhushen.space.data.MeleeWeapon hw = com.zhushen.space.data.MeleeWeapon.of(stack);
                if (hw != null && hw.hidden()) { // 暗器：显示投掷面板（敏捷 + 运动 + 武器伤害 − 专业减值）
                    int ath = skill(p, SkillType.ATHLETICS), str = attr(p, AttributeType.STRENGTH);
                    return Math.max(0f, Math.min(attr(p, AttributeType.AGILITY) + ath + hw.damage - professionPenalty(p, hw.category),
                            hw.damage + ath + str));
                }
                if (cat.group == WeaponCategory.Group.GUN) cat = WeaponCategory.GENERIC;
                float wd = weaponDamage(p, vanillaAttackDamage);
                com.zhushen.space.data.MeleeWeapon mw = com.zhushen.space.data.MeleeWeapon.melee(stack);
                int pen = deficit * WeaponCategory.REQ_PENALTY + professionPenalty(p, cat);
                boolean heavy = mw != null && mw.has(com.zhushen.space.data.MeleeWeapon.Trait.HEAVY_WEAPON);
                if (heavy) pen += WeaponRules.HEAVY_PENALTY;
                float v = Math.max(0f, attr(p, meleeKey(p, mw)) + skill(p, cat.skill) + wd - pen);
                if (mw != null && mw.has(com.zhushen.space.data.MeleeWeapon.Trait.TWO_HANDED) && !WeaponRules.twoHanded(p)) v *= 0.5f;
                return heavy && v > 0 ? v + WeaponRules.HEAVY_BONUS : v;
            }
        }
    }
}
