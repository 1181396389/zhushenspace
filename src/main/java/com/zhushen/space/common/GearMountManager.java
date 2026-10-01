package com.zhushen.space.common;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.GearMounts;
import com.zhushen.space.data.ModComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ItemStackedOnOtherEvent;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.ArrayList;
import java.util.List;

/**
 * 插件与可搭载的装置：在背包里拿着插件 / 装置右键点击一件装备即可安装（每件装备各一个）；
 * 空手右键点击已搭载的装备取下最后安装的那一个。安装后随装备一起穿脱、一起损坏，不增加硬度与结构；
 * 只有装备生效时其插件 / 装置才生效（{@link #activeMounts}）。
 */
@EventBusSubscriber(modid = ZhuShenSpace.MODID)
public final class GearMountManager {
    private GearMountManager() {}

    public static final TagKey<Item> PLUGIN = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "plugin"));
    public static final TagKey<Item> MOUNTABLE_DEVICE = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "mountable_device"));

    public static GearMounts mounts(ItemStack st) {
        GearMounts m = st.get(ModComponents.GEAR_MOUNTS.get());
        return m == null ? GearMounts.EMPTY : m;
    }

    /** 能否作为搭载的宿主：单件的护甲 / 饰品类装备（插件与装置本身除外） */
    public static boolean isHost(Player p, ItemStack st) {
        if (st.isEmpty() || st.getCount() != 1 || st.is(PLUGIN) || st.is(MOUNTABLE_DEVICE)) return false;
        if (p.getEquipmentSlotForItem(st).getType() == EquipmentSlot.Type.HUMANOID_ARMOR) return true;
        return CuriosApi.getCurio(st).isPresent() || !CuriosApi.getItemStackSlots(st, p.level()).isEmpty();
    }

    @SubscribeEvent
    public static void onStackedOn(ItemStackedOnOtherEvent e) {
        if (e.getClickAction() != ClickAction.SECONDARY) return;
        Player p = e.getPlayer();
        // 只在背包内安装 / 卸下（穿着的装备先取下）
        if (!(e.getSlot().container instanceof Inventory) || e.getSlot().getContainerSlot() >= 36) return;
        ItemStack host = e.getStackedOnItem();
        ItemStack carried = e.getCarriedItem();
        if (!isHost(p, host)) return;
        GearMounts m = mounts(host);
        if (!carried.isEmpty()) {
            boolean plugin = carried.is(PLUGIN), device = carried.is(MOUNTABLE_DEVICE);
            if (!plugin && !device) return;
            e.setCanceled(true);
            ItemStack occupied = plugin ? m.plugin : m.device;
            if (!occupied.isEmpty()) {
                if (!p.level().isClientSide)
                    p.displayClientMessage(Component.translatable("msg.zhushenspace.gear.mount_full", host.getHoverName(), occupied.getHoverName()), true);
                return;
            }
            ItemStack one = carried.split(1);
            host.set(ModComponents.GEAR_MOUNTS.get(), plugin ? m.withPlugin(one) : m.withDevice(one));
            p.playSound(SoundEvents.ARMOR_EQUIP_CHAIN.value(), 0.8f, 1.2f);
            if (!p.level().isClientSide)
                p.displayClientMessage(Component.translatable("msg.zhushenspace.gear.mount_ok", one.getHoverName(), host.getHoverName()), true);
            return;
        }
        if (m.isEmpty()) return;
        e.setCanceled(true);
        boolean takeDevice = !m.device.isEmpty();
        ItemStack out = takeDevice ? m.device : m.plugin;
        GearMounts left = takeDevice ? m.withDevice(ItemStack.EMPTY) : m.withPlugin(ItemStack.EMPTY);
        if (left.isEmpty()) host.remove(ModComponents.GEAR_MOUNTS.get());
        else host.set(ModComponents.GEAR_MOUNTS.get(), left);
        e.getCarriedSlotAccess().set(out.copy());
        p.level().playSound(null, p.blockPosition(), SoundEvents.ARMOR_EQUIP_GENERIC.value(), SoundSource.PLAYERS, 0.6f, 0.8f);
        if (!p.level().isClientSide)
            p.displayClientMessage(Component.translatable("msg.zhushenspace.gear.unmount", host.getHoverName(), out.getHoverName()), true);
    }

    /** 此刻生效的插件 / 装置（来自生效中的原版护甲；Curios 装备停用时其搭载物同样不生效） */
    public static List<ItemStack> activeMounts(net.minecraft.server.level.ServerPlayer p) {
        List<ItemStack> out = new ArrayList<>();
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (!GearManager.effective(p, s)) continue;
            GearMounts m = mounts(p.getItemBySlot(s));
            if (!m.plugin.isEmpty()) out.add(m.plugin);
            if (!m.device.isEmpty()) out.add(m.device);
        }
        CuriosApi.getCuriosInventory(p).ifPresent(h -> h.getCurios().forEach((id, sh) -> {
            for (int i = 0; i < sh.getSlots(); i++) {
                if (!h.isSlotActive(id, i)) continue;
                GearMounts m = mounts(sh.getStacks().getStackInSlot(i));
                if (!m.plugin.isEmpty()) out.add(m.plugin);
                if (!m.device.isEmpty()) out.add(m.device);
            }
        }));
        return out;
    }
}
