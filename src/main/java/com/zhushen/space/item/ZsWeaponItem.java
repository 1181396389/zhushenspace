package com.zhushen.space.item;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.MeleeWeapon;
import com.zhushen.space.data.MeleeWeapon.Trait;
import com.zhushen.space.entity.ThrownWeapon;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Locale;

/**
 * 基础冷兵器（数据见 {@link MeleeWeapon}）。
 * <ul>
 *   <li>原版攻击伤害 = 武器伤害（攻击判定公式取「除力量加成外」的部分作为武器伤害）；长柄武器 +2 米触及。</li>
 *   <li>冲击武器：潜行 + 右键在「严重伤害 / 冲击伤害」之间切换（存于物品自定义数据 ZsImpact）。</li>
 *   <li>可投掷的武器（匕首）：按住右键蓄力（至少半秒）后松开投出，命中后落地可捡回。</li>
 * </ul>
 */
public class ZsWeaponItem extends Item {

    private static final ResourceLocation REACH_ID = ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "weapon_reach");

    private final MeleeWeapon weapon;

    public ZsWeaponItem(MeleeWeapon weapon, Properties props) {
        super(props.attributes(modifiers(weapon)));
        this.weapon = weapon;
    }

    public MeleeWeapon weapon() { return weapon; }

    private static ItemAttributeModifiers modifiers(MeleeWeapon w) {
        ItemAttributeModifiers.Builder b = ItemAttributeModifiers.builder()
                .add(Attributes.ATTACK_DAMAGE, new AttributeModifier(BASE_ATTACK_DAMAGE_ID, w.attackModifier(),
                        AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                .add(Attributes.ATTACK_SPEED, new AttributeModifier(BASE_ATTACK_SPEED_ID, w.attackSpeed,
                        AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
        if (w.reachBonus() > 0) {
            b.add(Attributes.ENTITY_INTERACTION_RANGE, new AttributeModifier(REACH_ID, w.reachBonus(),
                    AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
        }
        return b.build();
    }

    @Override
    public boolean isEnchantable(ItemStack stack) { return false; }

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) { return true; }

    // ===== 右键：冲击武器切换 / 投掷蓄力 =====

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack st = player.getItemInHand(hand);
        if (weapon.has(Trait.IMPACT) && player.isSecondaryUseActive()) {
            if (!level.isClientSide) {
                boolean on = !MeleeWeapon.impactMode(st);
                CustomData.update(DataComponents.CUSTOM_DATA, st, tag -> tag.putBoolean("ZsImpact", on));
                player.displayClientMessage(Component.translatable(on ? "msg.zhushenspace.weapon.impact_on"
                        : "msg.zhushenspace.weapon.impact_off", Component.translatable(weapon.nameKey())), true);
                level.playSound(null, player.blockPosition(), SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.PLAYERS, 0.7f, on ? 0.8f : 1.3f);
            }
            return InteractionResultHolder.sidedSuccess(st, level.isClientSide());
        }
        if (weapon.throwRange > 0) {
            player.startUsingItem(hand);
            return InteractionResultHolder.consume(st);
        }
        return InteractionResultHolder.pass(st);
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return weapon.throwRange > 0 ? UseAnim.SPEAR : UseAnim.NONE;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return weapon.throwRange > 0 ? 72000 : 0;
    }

    /** 蓄力至少 10 tick 才投出 */
    public static final int THROW_CHARGE = 10;

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        if (weapon.throwRange <= 0 || !(entity instanceof Player p)) return;
        if (getUseDuration(stack, entity) - timeLeft < THROW_CHARGE) return;
        if (!level.isClientSide) {
            ThrownWeapon tw = new ThrownWeapon(level, p, stack.copyWithCount(1));
            tw.shootFromRotation(p, p.getXRot(), p.getYRot(), 0.0f, 2.0f, 1.0f);
            if (p.hasInfiniteMaterials()) tw.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
            level.addFreshEntity(tw);
            level.playSound(null, tw, SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 0.7f, 1.7f);
            if (!p.hasInfiniteMaterials()) stack.shrink(1);
        }
        p.awardStat(Stats.ITEM_USED.get(this));
    }

    // ===== 说明 =====

    private static String num(float v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tips, TooltipFlag flag) {
        tips.add(Component.translatable("tooltip.zhushenspace.weapon.template",
                Component.translatable(weapon.category.nameKey())).withStyle(ChatFormatting.GOLD));
        tips.add(Component.translatable(weapon.descKey()).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        String dmg = weapon.damage + weapon.severity.name();
        MutableComponent line = Component.translatable(weapon == MeleeWeapon.KNUCKLE
                ? "tooltip.zhushenspace.weapon.natural" : "tooltip.zhushenspace.weapon.damage", dmg);
        if (weapon.armorPierce > 0)
            line.append(" · ").append(Component.translatable("tooltip.zhushenspace.weapon.pierce", weapon.armorPierce));
        line.append(" · ").append(Component.translatable(weapon.kind.nameKey()));
        if (weapon.throwRange > 0)
            line.append(" · ").append(Component.translatable("tooltip.zhushenspace.weapon.throw", weapon.throwRange));
        tips.add(line.withStyle(ChatFormatting.WHITE));
        tips.add(Component.translatable("tooltip.zhushenspace.weapon.bulk", weapon.volume, num(weapon.weight))
                .withStyle(ChatFormatting.GRAY));

        MutableComponent kw = Component.empty();
        boolean any = false;
        for (Trait t : weapon.traits()) {
            if (t.special) continue;
            kw.append(Component.literal("【").append(Component.translatable(t.nameKey())).append("】"));
            any = true;
        }
        if (any) tips.add(kw.withStyle(ChatFormatting.AQUA));
        for (Trait t : weapon.traits()) {
            if (!t.special) continue;
            tips.add(Component.translatable(t.nameKey()).withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal("：").withStyle(ChatFormatting.YELLOW))
                    .append(Component.translatable(t.descKey()).withStyle(ChatFormatting.GRAY)));
        }
        if (weapon.has(Trait.IMPACT)) {
            tips.add(Component.translatable(MeleeWeapon.impactMode(stack) ? "tooltip.zhushenspace.weapon.mode_b"
                    : "tooltip.zhushenspace.weapon.mode_l").withStyle(ChatFormatting.GREEN));
        }
        if (weapon.throwRange > 0)
            tips.add(Component.translatable("tooltip.zhushenspace.weapon.throw_hint").withStyle(ChatFormatting.DARK_GRAY));
        boolean shift = net.neoforged.fml.loading.FMLEnvironment.dist.isClient()
                && com.zhushen.space.client.ClientHooks.shiftDown();
        if (shift) {
            for (Trait t : weapon.traits()) {
                if (t.special) continue;
                tips.add(Component.literal("【").append(Component.translatable(t.nameKey())).append("】：")
                        .withStyle(ChatFormatting.DARK_AQUA)
                        .append(Component.translatable(t.descKey()).withStyle(ChatFormatting.GRAY)));
            }
        } else if (any) {
            tips.add(Component.translatable("tooltip.zhushenspace.weapon.shift").withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
