package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：技艺操作。
 * action：0 购买；1 选择 / 研发选项（value = 选项序号）；2 研发（value = 研发序号）；
 * 3 设置留手（value = -1 关闭 / 1..100）；4 设置增幅（value 0/1）；5 切换当前选项（CYCLE）
 */
public record ArtActionPayload(int action, int art, int value) implements CustomPacketPayload {
    public static final Type<ArtActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "art_action"));
    public static final StreamCodec<FriendlyByteBuf, ArtActionPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (p, b) -> { b.writeVarInt(p.action); b.writeVarInt(p.art); b.writeInt(p.value); },
            b -> new ArtActionPayload(b.readVarInt(), b.readVarInt(), b.readInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
