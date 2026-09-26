package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.SkillType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：提交一套完整技能加点方案（服务端校验）。
 */
public record CommitSkillAllocationPayload(int[] points) implements CustomPacketPayload {

    public static final Type<CommitSkillAllocationPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "commit_skill_allocation"));

    public static final StreamCodec<FriendlyByteBuf, CommitSkillAllocationPayload> STREAM_CODEC = CustomPacketPayload.codec(
            CommitSkillAllocationPayload::write, CommitSkillAllocationPayload::read);

    private static void write(CommitSkillAllocationPayload payload, FriendlyByteBuf buf) {
        for (int i = 0; i < SkillType.COUNT; i++) {
            buf.writeVarInt(payload.points[i]);
        }
    }

    private static CommitSkillAllocationPayload read(FriendlyByteBuf buf) {
        int[] points = new int[SkillType.COUNT];
        for (int i = 0; i < SkillType.COUNT; i++) {
            points[i] = buf.readVarInt();
        }
        return new CommitSkillAllocationPayload(points);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
