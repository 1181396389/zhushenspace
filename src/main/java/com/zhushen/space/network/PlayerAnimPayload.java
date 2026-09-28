package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 服务端 → 客户端：对某玩家播放动作（KosmX Player Animator；name 为空 = 停止） */
public record PlayerAnimPayload(int entityId, String name) implements CustomPacketPayload {

    public static final Type<PlayerAnimPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "player_anim"));

    public static final StreamCodec<FriendlyByteBuf, PlayerAnimPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (p, buf) -> {
                buf.writeVarInt(p.entityId);
                buf.writeUtf(p.name, 64);
            },
            buf -> new PlayerAnimPayload(buf.readVarInt(), buf.readUtf(64)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
