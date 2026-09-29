package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import com.zhushen.space.data.PlayerSkillData;
import com.zhushen.space.data.SkillAbility;
import com.zhushen.space.data.SkillType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端：同步玩家技能数据（加点、两套战斗预设、主动技能冷却与进行中的 buff 状态）。
 *
 * leapRemainTicks / climbRemainTicks：跳跃与攀爬增益的剩余 tick（不活跃为 0）。
 * 玩家移动由客户端预测，客户端需依据该状态在本地同步施加跳跃修改器与攀爬行为。
 */
public record SyncSkillsPayload(int[] points, int totalSkillPoints, int[][] bars, int[] cooldownTicks,
                                int leapRemainTicks, int climbRemainTicks, int[] professions)
        implements CustomPacketPayload {

    public static final Type<SyncSkillsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "sync_skills"));

    public static final StreamCodec<FriendlyByteBuf, SyncSkillsPayload> STREAM_CODEC = CustomPacketPayload.codec(
            SyncSkillsPayload::write, SyncSkillsPayload::read);

    private static void write(SyncSkillsPayload payload, FriendlyByteBuf buf) {
        for (int i = 0; i < SkillType.COUNT; i++) {
            buf.writeVarInt(payload.points[i]);
        }
        buf.writeVarInt(payload.totalSkillPoints);
        for (int b = 0; b < PlayerSkillData.BAR_COUNT; b++) {
            for (int i = 0; i < PlayerSkillData.BAR_SLOTS; i++) {
                buf.writeVarInt(payload.bars[b][i] + 1);
            }
        }
        for (int i = 0; i < SkillAbility.COUNT; i++) {
            buf.writeVarInt(payload.cooldownTicks[i]);
        }
        buf.writeVarInt(payload.leapRemainTicks);
        buf.writeVarInt(payload.climbRemainTicks);
        buf.writeVarInt(payload.professions[0]);
        buf.writeVarInt(payload.professions[1]);
        buf.writeVarInt(payload.professions[2]);
    }

    private static SyncSkillsPayload read(FriendlyByteBuf buf) {
        int[] points = new int[SkillType.COUNT];
        for (int i = 0; i < SkillType.COUNT; i++) {
            points[i] = buf.readVarInt();
        }
        int total = buf.readVarInt();
        int[][] bars = new int[PlayerSkillData.BAR_COUNT][PlayerSkillData.BAR_SLOTS];
        for (int b = 0; b < PlayerSkillData.BAR_COUNT; b++) {
            for (int i = 0; i < PlayerSkillData.BAR_SLOTS; i++) {
                bars[b][i] = buf.readVarInt() - 1;
            }
        }
        int[] cooldowns = new int[SkillAbility.COUNT];
        for (int i = 0; i < SkillAbility.COUNT; i++) {
            cooldowns[i] = buf.readVarInt();
        }
        int leapRemain = buf.readVarInt();
        int climbRemain = buf.readVarInt();
        int[] prof = {buf.readVarInt(), buf.readVarInt(), buf.readVarInt()};
        return new SyncSkillsPayload(points, total, bars, cooldowns, leapRemain, climbRemain, prof);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
