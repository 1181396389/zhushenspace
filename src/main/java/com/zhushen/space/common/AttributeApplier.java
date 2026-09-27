package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.AttributeType;
import com.zhushen.space.data.ModAttachments;
import com.zhushen.space.data.PlayerAttributeData;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

/**
 * 将属性点换算为原版属性修改器（瞬态修改器，登录/重生/换维度/提交加点时重新应用）。
 *
 * 每点加成：
 * - 力量：攻击伤害+0.5、攻击速度+0.075、移速+2%、击退抗性+5%
 * - 敏捷：护甲值+1、跳跃高度+0.05、安全坠落高度+1、减速方块（蛛网/灵魂沙等）效率+5%、液体移动效率+10%
 * - 耐力：最大生命+2、氧气储备+20%（药水耐受见 AttributeEvents：每 5 点负面效果时长 -15%）
 * 传奇加成（仅当该属性自身满 5 点时激活，按玩家拥有的传奇点数叠加；任一属性满 5 获得 1 点传奇点数）：
 * - 力量：攻击伤害 +3/点
 * - 敏捷：护甲值 +1/点、护甲韧性 +3/点
 * - 耐力：最大生命 +1/点
 * - 感知：护甲韧性 +1/点（弱点勘破为概率事件，见 AttributeEvents；感知范围 +20m 属于后续功能）
 * - 操作：暴击率 +1%/点（暴击伤害 1.5 倍，见 AttributeEvents）
 * - 沉着（满级后）：移速 +5%/传奇点，封顶 +25%（原 +20%/点无上限，9 点传奇可达 +180%，过快）
 */
public class AttributeApplier {

    public static void apply(Player player) {
        PlayerAttributeData data = player.getData(ModAttachments.PLAYER_ATTRIBUTES);
        int[] p = data.points();
        int leg = data.legendaryPoints();

        // ===== 力量 =====
        set(player, Attributes.ATTACK_DAMAGE, "strength_attack_damage",
                p[AttributeType.STRENGTH.ordinal()] * 0.5
                        + legBonus(p[AttributeType.STRENGTH.ordinal()], leg, 3.0),
                AttributeModifier.Operation.ADD_VALUE);
        set(player, Attributes.ATTACK_SPEED, "strength_attack_speed",
                p[AttributeType.STRENGTH.ordinal()] * 0.075, AttributeModifier.Operation.ADD_VALUE);
        set(player, Attributes.MOVEMENT_SPEED, "strength_speed",
                p[AttributeType.STRENGTH.ordinal()] * 0.02, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        set(player, Attributes.KNOCKBACK_RESISTANCE, "strength_knockback",
                p[AttributeType.STRENGTH.ordinal()] * 0.05, AttributeModifier.Operation.ADD_VALUE);

        // ===== 敏捷 =====
        set(player, Attributes.ARMOR, "agility_armor",
                p[AttributeType.AGILITY.ordinal()] * 1.0
                        + legBonus(p[AttributeType.AGILITY.ordinal()], leg, 1.0),
                AttributeModifier.Operation.ADD_VALUE);
        set(player, Attributes.ARMOR_TOUGHNESS, "agility_armor_toughness",
                legBonus(p[AttributeType.AGILITY.ordinal()], leg, 3.0), AttributeModifier.Operation.ADD_VALUE);
        set(player, Attributes.JUMP_STRENGTH, "agility_jump",
                p[AttributeType.AGILITY.ordinal()] * 0.05, AttributeModifier.Operation.ADD_VALUE);
        set(player, Attributes.SAFE_FALL_DISTANCE, "agility_safe_fall",
                p[AttributeType.AGILITY.ordinal()] * 1.0, AttributeModifier.Operation.ADD_VALUE);
        set(player, Attributes.MOVEMENT_EFFICIENCY, "agility_move_efficiency",
                p[AttributeType.AGILITY.ordinal()] * 0.05, AttributeModifier.Operation.ADD_VALUE);
        set(player, Attributes.WATER_MOVEMENT_EFFICIENCY, "agility_water_efficiency",
                p[AttributeType.AGILITY.ordinal()] * 0.10, AttributeModifier.Operation.ADD_VALUE);

        // ===== 耐力 =====
        set(player, Attributes.MAX_HEALTH, "endurance_max_health",
                p[AttributeType.ENDURANCE.ordinal()] * 2.0
                        + legBonus(p[AttributeType.ENDURANCE.ordinal()], leg, 1.0),
                AttributeModifier.Operation.ADD_VALUE);
        set(player, Attributes.OXYGEN_BONUS, "endurance_oxygen",
                p[AttributeType.ENDURANCE.ordinal()] * 0.2, AttributeModifier.Operation.ADD_VALUE);

        // ===== 感知（传奇韧性）=====
        set(player, Attributes.ARMOR_TOUGHNESS, "perception_armor_toughness",
                legBonus(p[AttributeType.PERCEPTION.ordinal()], leg, 1.0), AttributeModifier.Operation.ADD_VALUE);

        // ===== 沉着（传奇移速：每点传奇 +5%，封顶 +25%）=====
        set(player, Attributes.MOVEMENT_SPEED, "composure_speed",
                Math.min(COMPOSURE_SPEED_CAP,
                        legBonus(p[AttributeType.COMPOSURE.ordinal()], leg, COMPOSURE_SPEED_PER_LEGENDARY)),
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    }

    /** 沉着传奇：每点传奇点数的移动速度加成（乘算） */
    public static final double COMPOSURE_SPEED_PER_LEGENDARY = 0.05;
    /** 沉着传奇：移动速度加成上限 */
    public static final double COMPOSURE_SPEED_CAP = 0.25;

    /** 传奇加成：仅当该属性自身满 5 点时激活，按玩家拥有的传奇点数叠加 */
    private static double legBonus(int ownPoints, int legendary, double perPoint) {
        return ownPoints >= AttributeType.MAX_POINTS ? legendary * perPoint : 0.0;
    }

    private static void set(Player player, Holder<Attribute> attribute, String id,
                            double amount, AttributeModifier.Operation operation) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) return;
        ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, id);
        instance.removeModifier(rl);
        if (amount != 0) {
            instance.addTransientModifier(new AttributeModifier(rl, amount, operation));
        }
    }
}
