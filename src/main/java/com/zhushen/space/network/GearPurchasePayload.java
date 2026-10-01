package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端 → 服务端：主神商城购买装备（ShopGear 序号） */
public record GearPurchasePayload(int gear) implements CustomPacketPayload {

    public static final Type<GearPurchasePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "gear_purchase"));

    public static final StreamCodec<FriendlyByteBuf, GearPurchasePayload> STREAM_CODEC =
            CustomPacketPayload.codec(GearPurchasePayload::write, GearPurchasePayload::read);

    private static void write(GearPurchasePayload p, FriendlyByteBuf buf) { buf.writeVarInt(p.gear); }

    private static GearPurchasePayload read(FriendlyByteBuf buf) { return new GearPurchasePayload(buf.readVarInt()); }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
