package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 盔甲减值：盔甲对人物动作的影响与限制。
 *
 * 每件盔甲的盔甲减值 = 该件护甲值的一半（向下取整），
 * 全身盔甲减值 1:1 减少基础防御（护甲属性），但不会将基础防御减小到 0 以下
 * （护甲属性下限 0，由属性系统自动钳制）。
 * 增益（全力防御类：自我保护/格挡等）与减值同为加法修饰器，
 * 先加后减，最终防御以 0 为下限——与规则书运算顺序一致。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public class ArmorPenaltyManager {

    /** 盔甲减值修饰器 id */
    private static final ResourceLocation ARMOR_PENALTY_MOD =
            ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "armor_penalty");

    /** 已应用减值缓存：玩家 UUID → 当前修饰器数值（-1 = 无修饰器） */
    private static final Map<UUID, Integer> APPLIED = new HashMap<>();

    /** 玩家当前盔甲减值：每件护甲的护甲值一半（向下取整）之和 */
    public static int penaltyOf(ServerPlayer player) {
        int penalty = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) continue;
            ItemStack piece = player.getItemBySlot(slot);
            if (piece.getItem() instanceof ArmorItem armor) {
                penalty += armor.getDefense() / 2;
            }
        }
        return penalty;
    }

    /** 每 10 tick（0.5 秒）对账一次：随穿脱盔甲增减基础防御 */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 10 != 0) return;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            reconcile(player);
        }
    }

    private static void reconcile(ServerPlayer player) {
        int penalty = penaltyOf(player);
        int applied = APPLIED.getOrDefault(player.getUUID(), -1);
        if (penalty == applied) return;

        AttributeInstance armor = player.getAttribute(Attributes.ARMOR);
        if (armor != null) {
            armor.removeModifier(ARMOR_PENALTY_MOD);
            if (penalty > 0) {
                armor.addTransientModifier(new AttributeModifier(ARMOR_PENALTY_MOD, -penalty,
                        AttributeModifier.Operation.ADD_VALUE));
            }
        }
        APPLIED.put(player.getUUID(), penalty);
    }

    /** 登出清理缓存（瞬态修饰器随实体消失，无需处理） */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        APPLIED.remove(event.getEntity().getUUID());
    }
}
