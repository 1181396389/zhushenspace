package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：使用能量池附带的技能（自动获得，无需加点）。
 *
 * ability：0 = 内力吐息开关（自动档）；1 = 打坐（禁步回满内力）
 */
public record UseEnergyAbilityPayload(int ability) implements CustomPacketPayload {

    public static final int ABILITY_BREATH = 0;
    public static final int ABILITY_MEDITATE = 1;

    public static final Type<UseEnergyAbilityPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "use_energy_ability"));

    public static final StreamCodec<FriendlyByteBuf, UseEnergyAbilityPayload> STREAM_CODEC =
            CustomPacketPayload.codec(UseEnergyAbilityPayload::write, UseEnergyAbilityPayload::read);

    private static void write(UseEnergyAbilityPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.ability);
    }

    private static UseEnergyAbilityPayload read(FriendlyByteBuf buf) {
        return new UseEnergyAbilityPayload(buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
