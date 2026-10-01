package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端 → 服务端：念动力操控期间的操作（0 = 空手右键：取物 / 推开 / 放下，潜行 = 托起；1 = 左键：悬浮武器出击） */
public record TkActionPayload(int action, boolean sneak) implements CustomPacketPayload {
    public static final Type<TkActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "tk_action"));
    public static final StreamCodec<FriendlyByteBuf, TkActionPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (p, b) -> { b.writeVarInt(p.action); b.writeBoolean(p.sneak); },
            b -> new TkActionPayload(b.readVarInt(), b.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
