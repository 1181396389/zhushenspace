package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.FeatType;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.SkillType;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 普通专长效果与后续系统接口。
 * 已实现：末世之人（毒素/疾病免疫、生命 +2）、蛮族（困难地形免疫、属性 +1、非重甲移速）、巨大身材（体型放大、属性修正）。
 * 预留接口：体型等级、非人类判定、附加成功、科学全专业、各类能量池。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class FeatEffects {
    private FeatEffects() {}

    /** 各能量池（灵力 / 精神力 / 妖力 / 佛力 / 魔力 / 道力 / 灵能 / 内力 / 查克拉），待能量系统对接 */
    public enum Pool {
        SPIRIT(FeatType.SIXTH_SENSE, "spirit", AttributeType.RESOLVE, AttributeType.COMPOSURE),
        MIND(FeatType.PSIONIC_TALENT, "mind", AttributeType.RESOLVE, AttributeType.COMPOSURE),
        YOKAI(FeatType.YOKAI_BLOOD, "yokai", AttributeType.CHARM, AttributeType.ENDURANCE),
        BUDDHA(FeatType.REINCARNATED_BUDDHA, "buddha", AttributeType.RESOLVE, AttributeType.CHARM),
        MAGIC(FeatType.MAGIC_CONSTITUTION, "magic", AttributeType.INTELLIGENCE, AttributeType.PERCEPTION),
        DAO(FeatType.INNATE_DAO_BODY, "dao", AttributeType.PERCEPTION, AttributeType.CHARM),
        PSYCHIC(FeatType.PSYCHIC_CONSTITUTION, "psychic", AttributeType.RESOLVE, AttributeType.COMPOSURE),
        NEILI(FeatType.MARTIAL_PRODIGY, "neili", AttributeType.ENDURANCE, AttributeType.PERCEPTION),
        CHAKRA(FeatType.CHAKRA_CONSTITUTION, "chakra", AttributeType.PERCEPTION, AttributeType.ENDURANCE);
        public final FeatType feat;
        public final String id;
        public final AttributeType a, b;
        Pool(FeatType f, String id, AttributeType a, AttributeType b) { feat = f; this.id = id; this.a = a; this.b = b; }
        /** 基础容量 = 两项相关属性之和（与内力池 耐力+感知 一致） */
        public double capacity(int[] pts) { return pts[a.ordinal()] + pts[b.ordinal()]; }
        public static Pool byId(String id) { for (Pool p : values()) if (p.id.equals(id)) return p; return null; }
    }

    public static boolean has(Player p, FeatType f) {
        int m = p.getData(ModAttachments.PLAYER_BUILD).featMask[f.ordinal()];
        return (m & FeatType.LEVEL_BITS) != 0;
    }

    public static int mask(Player p, FeatType f) {
        return p.getData(ModAttachments.PLAYER_BUILD).featMask[f.ordinal()];
    }

    public static List<Pool> pools(Player p) {
        List<Pool> r = new ArrayList<>();
        for (Pool pool : Pool.values()) if (has(p, pool.feat)) r.add(pool);
        return r;
    }

    /** 各级技能/法术所需属性（D..S = 0..4）：6/11/16/21/31 */
    public static final int[] TIER_ATTR = {6, 11, 16, 21, 31};
    /** 法术类所需神秘学（或武学所需肉搏/白刃）：4/7/10/12/14 */
    public static final int[] TIER_SKILL = {4, 7, 10, 12, 14};

    /** 非人类（妖族血脉）：阻止强化「人类限定」资源 */
    public static boolean nonHuman(Player p) { return has(p, FeatType.YOKAI_BLOOD); }

    /** 体型：0 = 中型（人类），1 = 大型 */
    public static int size(Player p) { return has(p, FeatType.GIANT_BODY) ? 1 : 0; }

    /** 擒抱 / 冲撞 / 摔绊 / 「巨人战士」等体型前提时的体型（天生怪力 +1，可与「强健背肌」叠加） */
    public static int sizeForChecks(Player p) { return size(p) + (has(p, FeatType.MONSTROUS_STRENGTH) ? 1 : 0); }

    /** 体积：正常 5，巨大身材 7 */
    public static int volume(Player p) { return has(p, FeatType.GIANT_BODY) ? 7 : 5; }

    /** 智力相关检定的附加成功（未来人 +1） */
    public static int intBonusSuccess(Player p) { return has(p, FeatType.FUTURE_HUMAN) ? 1 : 0; }

    /** 科学：未来人拥有全专业（含「解读图纸」），专业加值 2；否则 0 表示按常规 */
    public static int scienceSpecialtyBonus(Player p) { return has(p, FeatType.FUTURE_HUMAN) ? 2 : 0; }

    public static final String SCIENCE_BLUEPRINT = "blueprint_reading";

    /** 专长带来的属性加值（作用于原版修改器；不改变加点上限） */
    public static int[] attrBonus(Player p) {
        int[] b = new int[AttributeType.COUNT];
        if (has(p, FeatType.BARBARIAN)) {
            int c = FeatType.choice(mask(p, FeatType.BARBARIAN));
            if (c >= 0 && c <= 2) b[c]++;
        }
        if (has(p, FeatType.GIANT_BODY)) {
            int v = volume(p) - 5;
            b[AttributeType.STRENGTH.ordinal()] += v / 2;
            b[AttributeType.AGILITY.ordinal()] -= v / 2;
        }
        return b;
    }

    /** 由 AttributeApplier 调用 */
    public static void applyModifiers(Player p) {
        set(p, Attributes.MAX_HEALTH, "feat_wastelander_hp", has(p, FeatType.WASTELANDER) ? 2 : 0, AttributeModifier.Operation.ADD_VALUE);
        boolean barb = has(p, FeatType.BARBARIAN);
        set(p, Attributes.MOVEMENT_EFFICIENCY, "feat_barbarian_terrain", barb ? 1 : 0, AttributeModifier.Operation.ADD_VALUE);
        set(p, Attributes.WATER_MOVEMENT_EFFICIENCY, "feat_barbarian_water", barb ? 1 : 0, AttributeModifier.Operation.ADD_VALUE);
        // 巨大身材：身高约 3 米（1.8 → 3.0）；天生防御 +1、闪避防御 -1（护甲净变化 0，另由敏捷 -1 体现）
        set(p, Attributes.SCALE, "feat_giant_scale", has(p, FeatType.GIANT_BODY) ? 3.0 / 1.8 - 1 : 0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        // 巨大身材：天生防御 +1（闪避防御 -1 待闪避防御系统，见 dodgeDefenseBonus）
        set(p, Attributes.ARMOR, "feat_giant_natural", has(p, FeatType.GIANT_BODY) ? 1 : 0, AttributeModifier.Operation.ADD_VALUE);
        updateBarbarianSpeed(p);
    }

    /** 闪避防御加值（巨大身材：体积每比 5 高 2 → -1；闪避防御可以为负）。待闪避防御系统接入 */
    public static int dodgeDefenseBonus(Player p) {
        int v = volume(p) - 5;
        return v >= 0 ? -(v / 2) : -v;
    }

    /** 蛮族：未穿重甲时基础移速 +4 米（1 米 = 基础移速的 10%） */
    public static final double BARBARIAN_SPEED = 0.4;

    private static void updateBarbarianSpeed(Player p) {
        boolean on = has(p, FeatType.BARBARIAN) && !wearingHeavy(p);
        set(p, Attributes.MOVEMENT_SPEED, "feat_barbarian_speed", on ? BARBARIAN_SPEED : 0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
    }

    /** 重甲：铁 / 钻石 / 下界合金护甲 */
    public static boolean wearingHeavy(Player p) {
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack st = p.getItemBySlot(s);
            if (st.getItem() instanceof ArmorItem a) {
                var m = a.getMaterial();
                var v = m.value();
                if (v == ArmorMaterials.IRON.value() || v == ArmorMaterials.DIAMOND.value() || v == ArmorMaterials.NETHERITE.value()) return true;
            }
        }
        return false;
    }

    private static void set(Player player, Holder<Attribute> attribute, String id, double amount, AttributeModifier.Operation op) {
        AttributeInstance inst = player.getAttribute(attribute);
        if (inst == null) return;
        ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, id);
        AttributeModifier cur = inst.getModifier(rl);
        if (cur != null && cur.amount() == amount) return;
        inst.removeModifier(rl);
        if (amount != 0) inst.addTransientModifier(new AttributeModifier(rl, amount, op));
    }

    // ===== 事件 =====

    /** 末世之人：免疫自然本质的毒素和疾病（中毒、饥饿、反胃） */
    @SubscribeEvent
    public static void onEffect(MobEffectEvent.Applicable e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!has(p, FeatType.WASTELANDER)) return;
        Holder<MobEffect> eff = e.getEffectInstance().getEffect();
        if (eff.is(MobEffects.POISON) || eff.is(MobEffects.HUNGER) || eff.is(MobEffects.CONFUSION)) {
            e.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
        }
    }

    private static Field stuck;

    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post e) {
        Player p = e.getEntity();
        boolean barb = p.level().isClientSide
                ? (com.zhushen.space.client.ClientBuildData.featMask.length > FeatType.BARBARIAN.ordinal()
                   && (com.zhushen.space.client.ClientBuildData.featMask[FeatType.BARBARIAN.ordinal()] & FeatType.LEVEL_BITS) != 0)
                : has(p, FeatType.BARBARIAN);
        if (barb) {
            // 蛛网等困难地形：清除「卡住」减速
            try {
                if (stuck == null) {
                    stuck = net.minecraft.world.entity.Entity.class.getDeclaredField("stuckSpeedMultiplier");
                    stuck.setAccessible(true);
                }
                stuck.set(p, Vec3.ZERO);
            } catch (Throwable ignored) {}
        }
        if (!p.level().isClientSide && p.tickCount % 10 == 0) updateBarbarianSpeed(p);
    }
}
