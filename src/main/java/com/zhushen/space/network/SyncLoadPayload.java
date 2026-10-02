package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 服务端 → 客户端：负重（携带重量与三档上限，公斤；exempt = 创造 / 旁观不受影响） */
public record SyncLoadPayload(float weight, float light, float medium, float heavy, boolean exempt) implements CustomPacketPayload {

    public static final Type<SyncLoadPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "sync_load"));

    public static final StreamCodec<FriendlyByteBuf, SyncLoadPayload> STREAM_CODEC =
            CustomPacketPayload.codec(SyncLoadPayload::write, SyncLoadPayload::read);

    private static void write(SyncLoadPayload p, FriendlyByteBuf buf) {
        buf.writeFloat(p.weight);
        buf.writeFloat(p.light);
        buf.writeFloat(p.medium);
        buf.writeFloat(p.heavy);
        buf.writeBoolean(p.exempt);
    }

    private static SyncLoadPayload read(FriendlyByteBuf buf) {
        return new SyncLoadPayload(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
