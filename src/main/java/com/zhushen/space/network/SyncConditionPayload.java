package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端：本地玩家的身体状况。
 * flags：1 力竭，2 昏睡，4 倒地，8 正在爬起，16 无法行动，32 可自由移动（飞行 / 蛛行术）。
 * points / tiers / heavy / destructive 按 StatusType 顺序：点数、当前档位、重度阈值、毁灭阈值；permanent = 毁灭性后果位。
 */
public record SyncConditionPayload(float thirst, float stamina, float staminaMax, float sleep, int flags,
                                   int[] points, int[] tiers, int[] heavy, int[] destructive, int permanent)
        implements CustomPacketPayload {

    public static final Type<SyncConditionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "sync_condition"));

    public static final StreamCodec<FriendlyByteBuf, SyncConditionPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (p, buf) -> {
                buf.writeFloat(p.thirst);
                buf.writeFloat(p.stamina);
                buf.writeFloat(p.staminaMax);
                buf.writeFloat(p.sleep);
                buf.writeVarInt(p.flags);
                buf.writeVarIntArray(p.points);
                buf.writeVarIntArray(p.tiers);
                buf.writeVarIntArray(p.heavy);
                buf.writeVarIntArray(p.destructive);
                buf.writeVarInt(p.permanent);
            },
            buf -> new SyncConditionPayload(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readVarInt(), buf.readVarIntArray(), buf.readVarIntArray(), buf.readVarIntArray(),
                    buf.readVarIntArray(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
