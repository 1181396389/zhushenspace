package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 服务端 → 客户端：某玩家的肢体血量（当前 / 上限，按 LimbPart 顺序）、断肢位掩码与失去的眼睛（1 右眼，2 左眼） */
public record SyncLimbPayload(int entityId, int[] current, int[] max, int severedMask, int eyeMask) implements CustomPacketPayload {

    public static final Type<SyncLimbPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "sync_limbs"));

    public static final StreamCodec<FriendlyByteBuf, SyncLimbPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (p, buf) -> {
                buf.writeVarInt(p.entityId);
                buf.writeVarIntArray(p.current);
                buf.writeVarIntArray(p.max);
                buf.writeVarInt(p.severedMask);
                buf.writeVarInt(p.eyeMask);
            },
            buf -> new SyncLimbPayload(buf.readVarInt(), buf.readVarIntArray(), buf.readVarIntArray(), buf.readVarInt(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
