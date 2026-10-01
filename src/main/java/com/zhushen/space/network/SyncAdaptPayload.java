package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：魔虚罗法阵的适应状态。
 * wheel = 法阵累计转动次数（渲染旋转角度，发给自己与周围玩家）；entries 只发给佩戴者本人。
 * label 为翻译键列表（以「·」连接显示）；turns 已转动次数；progress 下一次转动的进度（0~1）；
 * grace 距离开始遗忘还剩的刻数（0 = 正在遗忘）。entries 按最近遇到排序。
 */
public record SyncAdaptPayload(int entityId, int wheel, List<Entry> entries) implements CustomPacketPayload {

    public record Entry(List<String> label, int turns, float progress, int grace) {}

    public static final Type<SyncAdaptPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "sync_adapt"));

    public static final StreamCodec<FriendlyByteBuf, SyncAdaptPayload> STREAM_CODEC =
            CustomPacketPayload.codec(SyncAdaptPayload::write, SyncAdaptPayload::read);

    private static void write(SyncAdaptPayload p, FriendlyByteBuf buf) {
        buf.writeVarInt(p.entityId);
        buf.writeVarInt(p.wheel);
        buf.writeVarInt(p.entries.size());
        for (Entry e : p.entries) {
            buf.writeVarInt(e.label.size());
            for (String s : e.label) buf.writeUtf(s);
            buf.writeVarInt(e.turns);
            buf.writeFloat(e.progress);
            buf.writeVarInt(e.grace);
        }
    }

    private static SyncAdaptPayload read(FriendlyByteBuf buf) {
        int id = buf.readVarInt();
        int wheel = buf.readVarInt();
        int n = buf.readVarInt();
        List<Entry> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int m = buf.readVarInt();
            List<String> label = new ArrayList<>(m);
            for (int j = 0; j < m; j++) label.add(buf.readUtf());
            list.add(new Entry(label, buf.readVarInt(), buf.readFloat(), buf.readVarInt()));
        }
        return new SyncAdaptPayload(id, wheel, list);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
