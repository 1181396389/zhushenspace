package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** No client supplied duration/damage: the server owns the charge clock. */
public record ChargeSkillPayload(int bar, int slot, int action, int nonce) implements CustomPacketPayload {
    public static final int START = 0, RELEASE = 1, CANCEL = 2;
    public static final Type<ChargeSkillPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "charge_skill"));
    public static final StreamCodec<FriendlyByteBuf, ChargeSkillPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (p, b) -> { b.writeVarInt(p.bar); b.writeVarInt(p.slot); b.writeVarInt(p.action); b.writeInt(p.nonce); },
            b -> new ChargeSkillPayload(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readInt()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
