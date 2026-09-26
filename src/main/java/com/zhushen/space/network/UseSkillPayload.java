package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：在战斗模式下按下 1~9，使用当前技能栏对应槽位的主动技能。
 */
public record UseSkillPayload(int bar, int slot) implements CustomPacketPayload {

    public static final Type<UseSkillPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "use_skill"));

    public static final StreamCodec<FriendlyByteBuf, UseSkillPayload> STREAM_CODEC = CustomPacketPayload.codec(
            UseSkillPayload::write, UseSkillPayload::read);

    private static void write(UseSkillPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.bar);
        buf.writeVarInt(payload.slot);
    }

    private static UseSkillPayload read(FriendlyByteBuf buf) {
        return new UseSkillPayload(buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
