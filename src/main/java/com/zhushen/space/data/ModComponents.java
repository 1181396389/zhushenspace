package com.zhushen.space.data;

import com.mojang.serialization.Codec;
import com.zhushen.space.ZhuShenSpace;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 物品数据组件 */
public final class ModComponents {
    private ModComponents() {}

    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, ZhuShenSpace.MODID);

    /** 光亮术：照明到期的游戏刻（客户端据此绘制半径 20 米的动态光源） */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Long>> LIGHT_UNTIL =
            COMPONENTS.register("light_until", () -> DataComponentType.<Long>builder()
                    .persistent(Codec.LONG).networkSynchronized(ByteBufCodecs.VAR_LONG).build());

    /** 装备搭载的插件 / 装置（每件装备各一个，随装备一起穿脱与损坏） */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<GearMounts>> GEAR_MOUNTS =
            COMPONENTS.register("gear_mounts", () -> DataComponentType.<GearMounts>builder()
                    .persistent(GearMounts.CODEC).networkSynchronized(GearMounts.STREAM_CODEC).build());
}
