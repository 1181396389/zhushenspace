package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端 → 服务端：提交新的已习得专长掩码（只能新增，不能退回） */
public record CommitFeatsPayload(int owned) implements CustomPacketPayload {
    public static final Type<CommitFeatsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "commit_feats"));
    public static final StreamCodec<FriendlyByteBuf, CommitFeatsPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (p, b) -> b.writeVarInt(p.owned), b -> new CommitFeatsPayload(b.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
