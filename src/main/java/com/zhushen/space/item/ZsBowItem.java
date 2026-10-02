package com.zhushen.space.item;

import com.zhushen.space.data.MeleeWeapon;
import com.zhushen.space.data.ZsWeapon;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 基础冷兵器的弓箭：沿用原版弓的拉弓与射箭（弹药为箭矢），无耐久、不可附魔。
 * 命中由 CombatFormula 的弓弩分支按模板结算（敏捷 + 运动 + 武器伤害 − 防御 − 距离减值；前提力量不足的减值同原版弓规则）。
 * 必须双手（另一只手空着或拿着箭矢），力量差超过 3 点拉不开（CombatFormula.onUseStart）。
 */
public class ZsBowItem extends BowItem implements ZsWeapon {

    private final MeleeWeapon weapon;

    public ZsBowItem(MeleeWeapon weapon, Properties props) {
        super(props);
        this.weapon = weapon;
    }

    @Override
    public MeleeWeapon weapon() { return weapon; }

    @Override
    public int getDefaultProjectileRange() { return weapon.throwRange; }

    @Override
    public boolean isEnchantable(ItemStack stack) { return false; }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tips, TooltipFlag flag) {
        ZsWeaponItem.describe(weapon, stack, tips);
    }
}
