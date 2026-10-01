package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：新手试炼操作。
 * 0 进入试炼 / 1 跳过 / 2 已打开主神面板 / 3 选择试用角色（value = 序号）/ 4 已进入战斗模式 / 5 退出试炼。
 */
public record TrialActionPayload(int action, int value) implements CustomPacketPayload {
    public static final int ENTER = 0, SKIP = 1, NOTE_PANEL = 2, PICK = 3, NOTE_COMBAT = 4, QUIT = 5;

    public static final Type<TrialActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "trial_action"));

    public static final StreamCodec<FriendlyByteBuf, TrialActionPayload> STREAM_CODEC = StreamCodec.of(
            (b, p) -> { b.writeVarInt(p.action); b.writeVarInt(p.value); },
            b -> new TrialActionPayload(b.readVarInt(), b.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
