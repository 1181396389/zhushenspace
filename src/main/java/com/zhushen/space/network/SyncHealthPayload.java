package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端：同步玩家 B/L/A 伤势池与服务端认定的生命上限（HUD / 面板显示用）。
 */
public record SyncHealthPayload(int b, int l, int a, int maxHp) implements CustomPacketPayload {

    public static final Type<SyncHealthPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "sync_health"));

    public static final StreamCodec<FriendlyByteBuf, SyncHealthPayload> STREAM_CODEC =
            CustomPacketPayload.codec(SyncHealthPayload::write, SyncHealthPayload::read);

    private static void write(SyncHealthPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.b);
        buf.writeVarInt(payload.l);
        buf.writeVarInt(payload.a);
        buf.writeVarInt(payload.maxHp);
    }

    private static SyncHealthPayload read(FriendlyByteBuf buf) {
        return new SyncHealthPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
