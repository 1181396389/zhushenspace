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
 *   <li>攻击方式：潜行 + 右键切换伤害类型（长剑：穿刺 / 挥砍）与冲击武器的「严重 / 冲击」（自定义数据 ZsMode）。</li>
 *   <li>可投掷的武器（匕首、飞斧、飞锤）：按住右键蓄力（至少半秒）后松开投出，命中后落地可捡回。</li>
 * </ul>
 */
public class ZsWeaponItem extends Item implements com.zhushen.space.data.ZsWeapon {

    private static final ResourceLocation REACH_ID = ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "weapon_reach");

    private final MeleeWeapon weapon;

    public ZsWeaponItem(MeleeWeapon weapon, Properties props) {
        super(props.attributes(modifiers(weapon)));
        this.weapon = weapon;
    }

    @Override
    public MeleeWeapon weapon() { return weapon; }

    private static ItemAttributeModifiers modifiers(MeleeWeapon w) {
        if (w.hidden()) return ItemAttributeModifiers.EMPTY; // 暗器：拿在手里不是近战武器
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
        if (weapon.modeCount() > 1 && player.isSecondaryUseActive()) {
            if (!level.isClientSide) {
                int next = (weapon.mode(st) + 1) % weapon.modeCount();
                CustomData.update(DataComponents.CUSTOM_DATA, st, tag -> {
                    tag.remove("ZsImpact");
                    tag.putInt("ZsMode", next);
                });
                player.displayClientMessage(Component.translatable("msg.zhushenspace.weapon.mode",
                        Component.translatable(weapon.nameKey()), modeLabel(weapon, next)), true);
                level.playSound(null, player.blockPosition(), SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.PLAYERS, 0.7f,
                        0.8f + 0.5f * next / Math.max(1, weapon.modeCount() - 1));
            }
            return InteractionResultHolder.sidedSuccess(st, level.isClientSide());
        }
        if (weapon.hidden()) {
            // 暗器：右键直接甩出（不需要蓄力），每次一枚，短暂冷却
            if (!level.isClientSide) {
                ThrownWeapon tw = new ThrownWeapon(level, player, st.copyWithCount(1));
                tw.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0f, 2.2f, 0.8f);
                tw.pickup = AbstractArrow.Pickup.DISALLOWED; // 消耗品：不可捡回
                level.addFreshEntity(tw);
                level.playSound(null, tw, SoundEvents.ARROW_SHOOT, SoundSource.PLAYERS, 0.5f, 2.0f);
                if (!player.hasInfiniteMaterials()) st.shrink(1);
            }
            player.getCooldowns().addCooldown(this, HIDDEN_COOLDOWN);
            player.swing(hand, true);
            player.awardStat(Stats.ITEM_USED.get(this));
            return InteractionResultHolder.sidedSuccess(st, level.isClientSide());
        }
        if (weapon.throwable()) {
            player.startUsingItem(hand);
            return InteractionResultHolder.consume(st);
        }
        return InteractionResultHolder.pass(st);
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return weapon.throwable() && !weapon.hidden() ? UseAnim.SPEAR : UseAnim.NONE;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return weapon.throwable() && !weapon.hidden() ? 72000 : 0;
    }

    /** 暗器连续甩出的间隔（tick） */
    public static final int HIDDEN_COOLDOWN = 10;

    /** 蓄力至少 10 tick 才投出 */
    public static final int THROW_CHARGE = 10;

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        if (!weapon.throwable() || weapon.hidden() || !(entity instanceof Player p)) return;
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

    /** 攻击方式名称：伤害类型（+ 冲击武器的伤势等级） */
    public static Component modeLabel(MeleeWeapon w, int mode) {
        MutableComponent c = Component.translatable(w.kindOf(mode).nameKey());
        if (w.has(Trait.IMPACT)) c.append(" · ").append(Component.translatable("tooltip.zhushenspace.weapon.sev_" + w.severityOf(mode).name().toLowerCase(java.util.Locale.ROOT)));
        return c;
    }

    // ===== 说明 =====

    private static String num(float v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tips, TooltipFlag flag) {
        describe(weapon, stack, tips);
    }

    /** 模板说明（近战武器与弩共用） */
    public static void describe(MeleeWeapon weapon, ItemStack stack, List<Component> tips) {
        tips.add(Component.translatable("tooltip.zhushenspace.weapon.template",
                Component.translatable(weapon.category.nameKey())).withStyle(ChatFormatting.GOLD));
        tips.add(Component.translatable(weapon.descKey()).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        String dmg = weapon.damage + weapon.severity.name();
        MutableComponent line = Component.translatable(weapon == MeleeWeapon.KNUCKLE
                ? "tooltip.zhushenspace.weapon.natural" : "tooltip.zhushenspace.weapon.damage", dmg);
        if (weapon.armorPierce > 0)
            line.append(" · ").append(Component.translatable("tooltip.zhushenspace.weapon.pierce", weapon.armorPierce));
        line.append(" · ");
        for (int i = 0; i < weapon.kinds.length; i++) {
            if (i > 0) line.append(Component.translatable("tooltip.zhushenspace.weapon.or"));
            line.append(Component.translatable(weapon.kinds[i].nameKey()));
        }
        if (weapon.throwRange > 0)
            line.append(" · ").append(Component.translatable(weapon.ranged() ? "tooltip.zhushenspace.weapon.range"
                    : "tooltip.zhushenspace.weapon.throw", weapon.throwRange));
        tips.add(line.withStyle(ChatFormatting.WHITE));
        if (weapon.hidden())
            tips.add(Component.translatable("tooltip.zhushenspace.weapon.bulk_hidden").withStyle(ChatFormatting.GRAY));
        else
            tips.add(Component.translatable("tooltip.zhushenspace.weapon.bulk", weapon.volume, num(weapon.weight))
                    .withStyle(ChatFormatting.GRAY));
        if (weapon.strReq > 0)
            tips.add(Component.translatable("tooltip.zhushenspace.weapon.str_req", weapon.strReq).withStyle(ChatFormatting.RED));

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
        if (weapon.modeCount() > 1) {
            tips.add(Component.translatable("tooltip.zhushenspace.weapon.mode", modeLabel(weapon, weapon.mode(stack)))
                    .withStyle(ChatFormatting.GREEN));
        }
        if (weapon.hidden())
            tips.add(Component.translatable("tooltip.zhushenspace.weapon.hidden_hint").withStyle(ChatFormatting.DARK_GRAY));
        else if (weapon.throwable())
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
