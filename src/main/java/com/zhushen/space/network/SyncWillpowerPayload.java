package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 服务端 → 客户端：意志力状态（强撑剩余 tick、抉择窗口剩余 tick、意志加持 / 意志守御预备状态） */
public record SyncWillpowerPayload(int sustainTicks, int graceTicks, boolean armedCheck, boolean armedGuard)
        implements CustomPacketPayload {

    public static final Type<SyncWillpowerPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "sync_willpower"));

    public static final StreamCodec<FriendlyByteBuf, SyncWillpowerPayload> STREAM_CODEC = CustomPacketPayload.codec(
            SyncWillpowerPayload::write, SyncWillpowerPayload::read);

    private static void write(SyncWillpowerPayload p, FriendlyByteBuf buf) {
        buf.writeVarInt(p.sustainTicks);
        buf.writeVarInt(p.graceTicks);
        buf.writeBoolean(p.armedCheck);
        buf.writeBoolean(p.armedGuard);
    }

    private static SyncWillpowerPayload read(FriendlyByteBuf buf) {
        return new SyncWillpowerPayload(buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
