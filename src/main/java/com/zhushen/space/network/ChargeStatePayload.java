package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端（仅自身）：蓄力状态。服务端接受蓄力后发送 active = true（客户端才显示蓄力环），
 * 蓄力以任何方式结束（释放 / 取消 / 失效 / 超时）时发送 active = false。skill = 技艺序号。
 */
public record ChargeStatePayload(int nonce, boolean active, int skill) implements CustomPacketPayload {
    public static final Type<ChargeStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "charge_state"));
    public static final StreamCodec<FriendlyByteBuf, ChargeStatePayload> STREAM_CODEC = CustomPacketPayload.codec(
            (p, b) -> { b.writeInt(p.nonce); b.writeBoolean(p.active); b.writeVarInt(p.skill); },
            b -> new ChargeStatePayload(b.readInt(), b.readBoolean(), b.readVarInt()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
