package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：主神商城购买流派技能（如太极拳八式）。
 *
 * 服务端校验流派已解锁、能力未购买、货币充足，成功后扣除并置位购买记录。
 */
public record SchoolSkillPurchasePayload(int school, int ability) implements CustomPacketPayload {

    public static final Type<SchoolSkillPurchasePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "school_skill_purchase"));

    public static final StreamCodec<FriendlyByteBuf, SchoolSkillPurchasePayload> STREAM_CODEC =
            CustomPacketPayload.codec(SchoolSkillPurchasePayload::write, SchoolSkillPurchasePayload::read);

    private static void write(SchoolSkillPurchasePayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.school);
        buf.writeVarInt(payload.ability);
    }

    public static SchoolSkillPurchasePayload read(FriendlyByteBuf buf) {
        return new SchoolSkillPurchasePayload(buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
