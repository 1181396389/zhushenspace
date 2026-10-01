package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 服务端 → 本人与周围玩家：第 idx 柄悬浮武器飞向 target，flight tick 后命中，随后飞回 */
public record TkStrikePayload(int owner, int idx, int target, int flight) implements CustomPacketPayload {
    public static final Type<TkStrikePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "tk_strike"));
    public static final StreamCodec<FriendlyByteBuf, TkStrikePayload> STREAM_CODEC = CustomPacketPayload.codec(
            (p, b) -> { b.writeVarInt(p.owner); b.writeVarInt(p.idx); b.writeVarInt(p.target); b.writeVarInt(p.flight); },
            b -> new TkStrikePayload(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
