package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端（自身 + 周围追踪者）：打坐姿态开始 / 结束。
 * ticks &gt; 0 = 开始盘坐调息（持续 ticks）；ticks = 0 = 收功起身（完成或被中断）。
 */
public record MeditatePosePayload(int entityId, int ticks) implements CustomPacketPayload {

    public static final Type<MeditatePosePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "meditate_pose"));

    public static final StreamCodec<FriendlyByteBuf, MeditatePosePayload> STREAM_CODEC =
            CustomPacketPayload.codec(MeditatePosePayload::write, MeditatePosePayload::read);

    private static void write(MeditatePosePayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.entityId);
        buf.writeVarInt(payload.ticks);
    }

    private static MeditatePosePayload read(FriendlyByteBuf buf) {
        return new MeditatePosePayload(buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
