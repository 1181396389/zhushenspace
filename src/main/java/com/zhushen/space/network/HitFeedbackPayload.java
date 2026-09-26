package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端：命中打击感反馈（本地玩家的攻击命中 / 撞墙结算时触发镜头微震）。
 *
 * @param power 震动幅度（0~1.2，随伤害强度增加）
 * @param ticks 持续 tick 数（4~8）
 */
public record HitFeedbackPayload(float power, int ticks) implements CustomPacketPayload {

    public static final Type<HitFeedbackPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "hit_feedback"));

    public static final StreamCodec<FriendlyByteBuf, HitFeedbackPayload> STREAM_CODEC =
            CustomPacketPayload.codec(HitFeedbackPayload::write, HitFeedbackPayload::read);

    private static void write(HitFeedbackPayload payload, FriendlyByteBuf buf) {
        buf.writeFloat(payload.power);
        buf.writeByte(payload.ticks);
    }

    public static HitFeedbackPayload read(FriendlyByteBuf buf) {
        return new HitFeedbackPayload(buf.readFloat(), buf.readByte());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
