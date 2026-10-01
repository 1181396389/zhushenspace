package com.zhushen.space.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * 装备上搭载的插件与装置（数据组件 {@code zhushenspace:gear_mounts}）：每件装备最多一个插件、一个可搭载的装置。
 * 它们随装备一起穿脱、一起损坏，不增加装备的硬度与结构。
 */
public final class GearMounts {
    public final ItemStack plugin;
    public final ItemStack device;

    public static final GearMounts EMPTY = new GearMounts(ItemStack.EMPTY, ItemStack.EMPTY);

    public GearMounts(ItemStack plugin, ItemStack device) {
        this.plugin = plugin == null ? ItemStack.EMPTY : plugin;
        this.device = device == null ? ItemStack.EMPTY : device;
    }

    public ItemStack plugin() { return plugin; }

    public ItemStack device() { return device; }

    public boolean isEmpty() { return plugin.isEmpty() && device.isEmpty(); }

    public GearMounts withPlugin(ItemStack s) { return new GearMounts(s, device); }

    public GearMounts withDevice(ItemStack s) { return new GearMounts(plugin, s); }

    public static final Codec<GearMounts> CODEC = RecordCodecBuilder.create(i -> i.group(
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("plugin", ItemStack.EMPTY).forGetter(GearMounts::plugin),
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("device", ItemStack.EMPTY).forGetter(GearMounts::device)
    ).apply(i, GearMounts::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, GearMounts> STREAM_CODEC = StreamCodec.composite(
            ItemStack.OPTIONAL_STREAM_CODEC, GearMounts::plugin,
            ItemStack.OPTIONAL_STREAM_CODEC, GearMounts::device,
            GearMounts::new);

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GearMounts m)) return false;
        return ItemStack.matches(plugin, m.plugin) && ItemStack.matches(device, m.device);
    }

    @Override
    public int hashCode() {
        return 31 * ItemStack.hashItemAndComponents(plugin) + ItemStack.hashItemAndComponents(device);
    }
}
