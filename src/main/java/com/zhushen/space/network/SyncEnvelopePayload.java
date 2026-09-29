package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 服务端 → 客户端：是否已使用主神邀请函（未使用前不能打开主神面板） */
public record SyncEnvelopePayload(boolean used) implements CustomPacketPayload {
    public static final Type<SyncEnvelopePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "sync_envelope"));
    public static final StreamCodec<FriendlyByteBuf, SyncEnvelopePayload> STREAM_CODEC = CustomPacketPayload.codec(
            (p, b) -> b.writeBoolean(p.used), b -> new SyncEnvelopePayload(b.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
