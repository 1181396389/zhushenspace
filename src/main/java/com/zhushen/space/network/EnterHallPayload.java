package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：请求进入 / 离开主神空间大厅（主神面板按钮触发）。
 * 服务端根据玩家当前维度决定：不在大厅 → 传送进大厅；在大厅 → 返回记录的主世界位置。
 */
public record EnterHallPayload() implements CustomPacketPayload {

    public static final Type<EnterHallPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "enter_hall"));

    public static final StreamCodec<FriendlyByteBuf, EnterHallPayload> STREAM_CODEC =
            CustomPacketPayload.codec(EnterHallPayload::write, EnterHallPayload::read);

    private static void write(EnterHallPayload payload, FriendlyByteBuf buf) {
    }

    private static EnterHallPayload read(FriendlyByteBuf buf) {
        return new EnterHallPayload();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
