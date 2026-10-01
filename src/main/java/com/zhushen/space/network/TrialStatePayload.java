package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 本人：新手试炼状态。
 * status 0 未进行 / 1 试炼中 / 2 已完成 / 3 已跳过；done = 当前关卡已完成目标位；count = 计数目标进度；
 * pickNonce 变化 → 打开试用角色选择；finishNonce 变化 → 显示结算面板（用时 ticks、倒下 deaths、受伤 hits、
 * 技能使用 skills、击败 kills、最快纪录 best / 是否刷新 newBest）。
 */
public record TrialStatePayload(int status, int stage, int done, int count, int template, int deaths,
                                int pickNonce, int finishNonce, long ticks, int completions,
                                int hits, int skills, int kills, long best, boolean newBest) implements CustomPacketPayload {
    public static final Type<TrialStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "trial_state"));

    public static final StreamCodec<FriendlyByteBuf, TrialStatePayload> STREAM_CODEC = StreamCodec.of(
            (b, p) -> {
                b.writeVarInt(p.status); b.writeVarInt(p.stage); b.writeVarInt(p.done); b.writeVarInt(p.count);
                b.writeVarInt(p.template + 1); b.writeVarInt(p.deaths); b.writeVarInt(p.pickNonce);
                b.writeVarInt(p.finishNonce); b.writeVarLong(p.ticks); b.writeVarInt(p.completions);
                b.writeVarInt(p.hits); b.writeVarInt(p.skills); b.writeVarInt(p.kills); b.writeVarLong(p.best);
                b.writeBoolean(p.newBest);
            },
            b -> new TrialStatePayload(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(),
                    b.readVarInt() - 1, b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarLong(), b.readVarInt(),
                    b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarLong(), b.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
