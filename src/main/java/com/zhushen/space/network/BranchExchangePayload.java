package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：支线兑换（货币界面拖拽）。
 *
 * fromTier → toTier：相邻等级。
 * toTier = fromTier - 1：拼合（3 个 from → 1 个 to）
 * toTier = fromTier + 1：拆解（1 个 from → 3 个 to）
 * 服务端校验数量，失败发提示。
 */
public record BranchExchangePayload(int fromTier, int toTier) implements CustomPacketPayload {

    public static final Type<BranchExchangePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "branch_exchange"));

    public static final StreamCodec<FriendlyByteBuf, BranchExchangePayload> STREAM_CODEC =
            CustomPacketPayload.codec(BranchExchangePayload::write, BranchExchangePayload::read);

    private static void write(BranchExchangePayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.fromTier);
        buf.writeVarInt(payload.toTier);
    }

    private static BranchExchangePayload read(FriendlyByteBuf buf) {
        return new BranchExchangePayload(buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
