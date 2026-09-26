package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端：同步主神空间进度（货币 + 流派购买记录 + 已购技能位掩码）。
 */
public record SyncProgressPayload(int[] branches, int score, boolean[] schools, int[] schoolSkillBits)
        implements CustomPacketPayload {

    public static final Type<SyncProgressPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "sync_progress"));

    public static final StreamCodec<FriendlyByteBuf, SyncProgressPayload> STREAM_CODEC = CustomPacketPayload.codec(
            SyncProgressPayload::write, SyncProgressPayload::read);

    private static void write(SyncProgressPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.branches.length);
        for (int b : payload.branches) buf.writeVarInt(b);
        buf.writeVarInt(payload.score);
        buf.writeVarInt(payload.schools.length);
        for (boolean s : payload.schools) buf.writeBoolean(s);
        buf.writeVarInt(payload.schoolSkillBits.length);
        for (int bits : payload.schoolSkillBits) buf.writeVarInt(bits);
    }

    private static SyncProgressPayload read(FriendlyByteBuf buf) {
        int branchCount = buf.readVarInt();
        int[] branches = new int[branchCount];
        for (int i = 0; i < branchCount; i++) branches[i] = buf.readVarInt();
        int score = buf.readVarInt();
        int schoolCount = buf.readVarInt();
        boolean[] schools = new boolean[schoolCount];
        for (int i = 0; i < schoolCount; i++) schools[i] = buf.readBoolean();
        int bitsCount = buf.readVarInt();
        int[] bits = new int[bitsCount];
        for (int i = 0; i < bitsCount; i++) bits[i] = buf.readVarInt();
        return new SyncProgressPayload(branches, score, schools, bits);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
