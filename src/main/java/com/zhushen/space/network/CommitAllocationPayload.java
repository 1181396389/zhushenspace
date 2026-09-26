package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.AttributeType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：提交一套完整加点方案（服务端校验）。
 */
public record CommitAllocationPayload(int[] points) implements CustomPacketPayload {

    public static final Type<CommitAllocationPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "commit_allocation"));

    public static final StreamCodec<FriendlyByteBuf, CommitAllocationPayload> STREAM_CODEC = CustomPacketPayload.codec(
            CommitAllocationPayload::write, CommitAllocationPayload::read);

    private static void write(CommitAllocationPayload payload, FriendlyByteBuf buf) {
        for (int i = 0; i < AttributeType.COUNT; i++) {
            buf.writeVarInt(payload.points[i]);
        }
    }

    private static CommitAllocationPayload read(FriendlyByteBuf buf) {
        int[] points = new int[AttributeType.COUNT];
        for (int i = 0; i < AttributeType.COUNT; i++) {
            points[i] = buf.readVarInt();
        }
        return new CommitAllocationPayload(points);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
