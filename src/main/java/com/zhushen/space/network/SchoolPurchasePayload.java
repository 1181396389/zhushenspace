package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：主神商城购买请求（流派等）。
 *
 * 服务端校验前提与价格，成功后扣除货币并解锁，
 * 同时发放流派饰品（装备到流派饰品栏后才生效）。
 */
public record SchoolPurchasePayload(int school) implements CustomPacketPayload {

    public static final Type<SchoolPurchasePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "school_purchase"));

    public static final StreamCodec<FriendlyByteBuf, SchoolPurchasePayload> STREAM_CODEC =
            CustomPacketPayload.codec(SchoolPurchasePayload::write, SchoolPurchasePayload::read);

    private static void write(SchoolPurchasePayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.school);
    }

    private static SchoolPurchasePayload read(FriendlyByteBuf buf) {
        return new SchoolPurchasePayload(buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
