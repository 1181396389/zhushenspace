package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端（仅自身）：休息状态。
 * kind：0 = 未休息，1 = 短休，2 = 长休；ticks = 本次休息总时长；
 * result（kind = 0 时）：0 = 无，1 = 休息完成，2 = 被中断。
 */
public record RestStatePayload(byte kind, int ticks, byte result) implements CustomPacketPayload {

    public static final Type<RestStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "rest_state"));

    public static final StreamCodec<FriendlyByteBuf, RestStatePayload> STREAM_CODEC =
            CustomPacketPayload.codec(RestStatePayload::write, RestStatePayload::read);

    private static void write(RestStatePayload payload, FriendlyByteBuf buf) {
        buf.writeByte(payload.kind);
        buf.writeVarInt(payload.ticks);
        buf.writeByte(payload.result);
    }

    private static RestStatePayload read(FriendlyByteBuf buf) {
        return new RestStatePayload(buf.readByte(), buf.readVarInt(), buf.readByte());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
