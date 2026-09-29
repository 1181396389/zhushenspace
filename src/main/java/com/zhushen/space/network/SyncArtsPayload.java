package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 服务端 → 客户端：技艺购买 / 选项 / 研发 / 轮盘设置 + 当前 XP */
public record SyncArtsPayload(long owned, int[] opt, int[] cur, int[] res, int holdback, boolean amp, int xp, int flags)
        implements CustomPacketPayload {
    public static final Type<SyncArtsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "sync_arts"));
    public static final StreamCodec<FriendlyByteBuf, SyncArtsPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (p, b) -> {
                b.writeLong(p.owned);
                b.writeVarIntArray(p.opt);
                b.writeVarIntArray(p.cur);
                b.writeVarIntArray(p.res);
                b.writeInt(p.holdback);
                b.writeBoolean(p.amp);
                b.writeVarInt(p.xp);
                b.writeVarInt(p.flags);
            },
            b -> new SyncArtsPayload(b.readLong(), b.readVarIntArray(), b.readVarIntArray(), b.readVarIntArray(),
                    b.readInt(), b.readBoolean(), b.readVarInt(), b.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
