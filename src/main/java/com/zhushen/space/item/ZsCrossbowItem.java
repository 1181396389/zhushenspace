package com.zhushen.space.item;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.MeleeWeapon;
import com.zhushen.space.data.ZsWeapon;
import com.zhushen.space.data.LimbPart;
import com.zhushen.space.data.ModAttachments;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;
import java.util.function.Predicate;

/**
 * 基础冷兵器的弩（轻弩 / 重弩）：沿用原版弩的装填（按住右键约一个移动动作）与发射，只接受弩矢作为弹药；
 * 无耐久、不可附魔。命中判定由 CombatFormula 的弩分支按模板结算（敏捷 + 运动 + 武器伤害 − 防御 − 距离减值，
 * 破甲 / 伤势等级 / 伤害类型取模板，【双手】单手持用成功数减半）。
 */
public class ZsCrossbowItem extends CrossbowItem implements ZsWeapon {

    private final MeleeWeapon weapon;

    public ZsCrossbowItem(MeleeWeapon weapon, Properties props) {
        super(props);
        this.weapon = weapon;
    }

    @Override
    public MeleeWeapon weapon() { return weapon; }

    /** 发射时单手持用（【双手】）：弩矢命中时成功数减半 */
    public static final String ONE_HAND_TAG = "zhushenspace_one_hand";

    /** 双手持用：双臂健在，另一只手空着或只拿着弩矢 */
    public static boolean twoHanded(Player p, ItemStack bow) {
        var limbs = p.getData(ModAttachments.PLAYER_LIMBS);
        if (limbs.isSevered(LimbPart.RIGHT_ARM) || limbs.isSevered(LimbPart.LEFT_ARM)) return false;
        ItemStack other = p.getItemInHand(p.getMainHandItem() == bow ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        return other.isEmpty() || BOLT.test(other);
    }

    @Override
    protected Projectile createProjectile(Level level, LivingEntity shooter, ItemStack weapon, ItemStack ammo, boolean crit) {
        Projectile proj = super.createProjectile(level, shooter, weapon, ammo, crit);
        if (shooter instanceof Player p && weapon.getItem() instanceof ZsCrossbowItem cb
                && cb.weapon.has(MeleeWeapon.Trait.TWO_HANDED) && !twoHanded(p, weapon))
            proj.getPersistentData().putBoolean(ONE_HAND_TAG, true);
        return proj;
    }

    private static final Predicate<ItemStack> BOLT = s -> s.is(ZhuShenSpace.CROSSBOW_BOLT.get());

    /** 手弩的装填需要一只空手：另一只手拿着东西时不能开始拉弦 */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack st = player.getItemInHand(hand);
        if (weapon.has(MeleeWeapon.Trait.RELOAD_HAND) && !isCharged(st)) {
            ItemStack other = player.getItemInHand(hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
            var limbs = player.getData(ModAttachments.PLAYER_LIMBS);
            boolean armMissing = limbs.isSevered(LimbPart.RIGHT_ARM) || limbs.isSevered(LimbPart.LEFT_ARM);
            if (!other.isEmpty() || armMissing) {
                if (!level.isClientSide) player.displayClientMessage(Component.translatable("msg.zhushenspace.weapon.reload_hand"), true);
                return InteractionResultHolder.fail(st);
            }
        }
        return super.use(level, player, hand);
    }

    @Override
    public Predicate<ItemStack> getSupportedHeldProjectiles() { return BOLT; }

    @Override
    public Predicate<ItemStack> getAllSupportedProjectiles() { return BOLT; }

    @Override
    public int getDefaultProjectileRange() { return weapon.throwRange; }

    @Override
    public boolean isEnchantable(ItemStack stack) { return false; }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tips, TooltipFlag flag) {
        super.appendHoverText(stack, context, tips, flag); // 已装填的弩矢
        ZsWeaponItem.describe(weapon, stack, tips);
    }
}
