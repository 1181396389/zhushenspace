package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端 → 服务端：提交属性 XP / 技能等级 / 专长等级 / 建卡专长选择 */
public record CommitBuildPayload(int[] attrXp, int[] skills, int[] featMask, int si1, int si3a, int si3b)
        implements CustomPacketPayload {
    public static final Type<CommitBuildPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "commit_build"));
    public static final StreamCodec<FriendlyByteBuf, CommitBuildPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (p, b) -> {
                b.writeVarIntArray(p.attrXp); b.writeVarIntArray(p.skills); b.writeVarIntArray(p.featMask);
                b.writeVarInt(p.si1 + 1); b.writeVarInt(p.si3a + 1); b.writeVarInt(p.si3b + 1);
            },
            b -> new CommitBuildPayload(b.readVarIntArray(), b.readVarIntArray(), b.readVarIntArray(),
                    b.readVarInt() - 1, b.readVarInt() - 1, b.readVarInt() - 1));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
