package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 服务端 → 客户端：魔力感知到的实体（仅本人可见的发光轮廓）与剩余时间 */
public record MagicSensePayload(int[] ids, int ticks) implements CustomPacketPayload {
    public static final Type<MagicSensePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "magic_sense"));
    public static final StreamCodec<FriendlyByteBuf, MagicSensePayload> STREAM_CODEC = CustomPacketPayload.codec(
            (p, b) -> { b.writeVarIntArray(p.ids); b.writeVarInt(p.ticks); },
            b -> new MagicSensePayload(b.readVarIntArray(), b.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
