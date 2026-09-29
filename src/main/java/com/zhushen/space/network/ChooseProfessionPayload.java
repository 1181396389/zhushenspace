package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端 → 服务端：选择专业（group 0 = 白刃，1 = 枪械；category = WeaponCategory 序号） */
public record ChooseProfessionPayload(int group, int category) implements CustomPacketPayload {
    public static final Type<ChooseProfessionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "choose_profession"));
    public static final StreamCodec<FriendlyByteBuf, ChooseProfessionPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (p, b) -> { b.writeVarInt(p.group); b.writeVarInt(p.category); },
            b -> new ChooseProfessionPayload(b.readVarInt(), b.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
