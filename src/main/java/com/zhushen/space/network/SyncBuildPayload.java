package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 服务端 → 客户端：建卡 XP 数据 */
public record SyncBuildPayload(int totalXp, boolean created, int[] attrXp, int[] featMask,
                               int si1, int si3a, int si3b, int giftedXp, int pendingItems, boolean pendingExchange)
        implements CustomPacketPayload {
    public static final Type<SyncBuildPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "sync_build"));
    public static final StreamCodec<FriendlyByteBuf, SyncBuildPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (p, b) -> {
                b.writeVarInt(p.totalXp); b.writeBoolean(p.created);
                b.writeVarIntArray(p.attrXp); b.writeVarIntArray(p.featMask);
                b.writeVarInt(p.si1 + 1); b.writeVarInt(p.si3a + 1); b.writeVarInt(p.si3b + 1);
                b.writeVarInt(p.giftedXp); b.writeVarInt(p.pendingItems); b.writeBoolean(p.pendingExchange);
            },
            b -> new SyncBuildPayload(b.readVarInt(), b.readBoolean(), b.readVarIntArray(), b.readVarIntArray(),
                    b.readVarInt() - 1, b.readVarInt() - 1, b.readVarInt() - 1, b.readVarInt(), b.readVarInt(), b.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
