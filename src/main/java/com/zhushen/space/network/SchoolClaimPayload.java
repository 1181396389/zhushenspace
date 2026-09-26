package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：领取流派饰品（已购买流派但背包/饰品栏中没有饰品时免费补发）。
 */
public record SchoolClaimPayload(int school) implements CustomPacketPayload {

    public static final Type<SchoolClaimPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "school_claim"));

    public static final StreamCodec<FriendlyByteBuf, SchoolClaimPayload> STREAM_CODEC =
            CustomPacketPayload.codec(SchoolClaimPayload::write, SchoolClaimPayload::read);

    private static void write(SchoolClaimPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.school);
    }

    public static SchoolClaimPayload read(FriendlyByteBuf buf) {
        return new SchoolClaimPayload(buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
