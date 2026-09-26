package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.AttributeType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端：同步玩家属性数据。
 */
public record SyncAttributesPayload(int[] points, int totalPoints) implements CustomPacketPayload {

    public static final Type<SyncAttributesPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "sync_attributes"));

    public static final StreamCodec<FriendlyByteBuf, SyncAttributesPayload> STREAM_CODEC = CustomPacketPayload.codec(
            SyncAttributesPayload::write, SyncAttributesPayload::read);

    private static void write(SyncAttributesPayload payload, FriendlyByteBuf buf) {
        for (int i = 0; i < AttributeType.COUNT; i++) {
            buf.writeVarInt(payload.points[i]);
        }
        buf.writeVarInt(payload.totalPoints);
    }

    private static SyncAttributesPayload read(FriendlyByteBuf buf) {
        int[] points = new int[AttributeType.COUNT];
        for (int i = 0; i < AttributeType.COUNT; i++) {
            points[i] = buf.readVarInt();
        }
        int total = buf.readVarInt();
        return new SyncAttributesPayload(points, total);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
