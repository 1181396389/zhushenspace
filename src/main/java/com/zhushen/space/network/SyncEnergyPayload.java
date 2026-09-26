package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端：同步玩家全部能量池（HUD 显示用）。
 *
 * 每个池携带 id、当前值、上限与固定颜色（同一 id 颜色恒定，便于区分多个能量池）。
 * 另同步内力吐息开关状态（HUD 描边高亮用）。
 */
public record SyncEnergyPayload(String[] ids, double[] currents, double[] maxes, int[] colors,
                                boolean breathEnabled)
        implements CustomPacketPayload {

    public static final Type<SyncEnergyPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "sync_energy"));

    public static final StreamCodec<FriendlyByteBuf, SyncEnergyPayload> STREAM_CODEC = CustomPacketPayload.codec(
            SyncEnergyPayload::write, SyncEnergyPayload::read);

    private static void write(SyncEnergyPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.ids.length);
        for (int i = 0; i < payload.ids.length; i++) {
            buf.writeUtf(payload.ids[i], 64);
            buf.writeDouble(payload.currents[i]);
            buf.writeDouble(payload.maxes[i]);
            buf.writeInt(payload.colors[i]);
        }
        buf.writeBoolean(payload.breathEnabled);
    }

    private static SyncEnergyPayload read(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        String[] ids = new String[count];
        double[] currents = new double[count];
        double[] maxes = new double[count];
        int[] colors = new int[count];
        for (int i = 0; i < count; i++) {
            ids[i] = buf.readUtf(64);
            currents[i] = buf.readDouble();
            maxes[i] = buf.readDouble();
            colors[i] = buf.readInt();
        }
        return new SyncEnergyPayload(ids, currents, maxes, colors, buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
