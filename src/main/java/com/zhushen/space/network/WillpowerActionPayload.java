package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端 → 服务端：意志力按键（0 = G 强撑 / 意志加持，1 = B 意志守御） */
public record WillpowerActionPayload(int action) implements CustomPacketPayload {

    public static final Type<WillpowerActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "willpower_action"));

    public static final StreamCodec<FriendlyByteBuf, WillpowerActionPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (p, buf) -> buf.writeVarInt(p.action), buf -> new WillpowerActionPayload(buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
