package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 服务端 → 客户端：专长点 / 已习得专长 / 是否已使用主神邀请函（决定能否打开主神面板） */
public record SyncFeatsPayload(int totalPoints, int owned, boolean envelopeUsed) implements CustomPacketPayload {
    public static final Type<SyncFeatsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "sync_feats"));
    public static final StreamCodec<FriendlyByteBuf, SyncFeatsPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (p, b) -> { b.writeVarInt(p.totalPoints); b.writeVarInt(p.owned); b.writeBoolean(p.envelopeUsed); },
            b -> new SyncFeatsPayload(b.readVarInt(), b.readVarInt(), b.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
