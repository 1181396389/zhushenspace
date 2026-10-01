package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：装备位穿脱状态（穿戴 / 解下进度、锁定），以及身体导致的装备位变化（bodyFlags）。
 * bodyFlags：bit0 无腿（失去鞋位）· bit1 失去一只手（手套 / 护腕位与一个戒指位）· bit2 双手皆失（全部戒指位）。
 */
public record SyncGearPayload(List<Entry> entries, int bodyFlags) implements CustomPacketPayload {

    /** remain / total：剩余与总刻数（仅穿戴中 / 解下中有意义）；locked：需要先解下才能取出 */
    public record Entry(String key, String item, byte state, int remain, int total, boolean locked) {}

    public static final Type<SyncGearPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "sync_gear"));

    public static final StreamCodec<FriendlyByteBuf, SyncGearPayload> STREAM_CODEC =
            CustomPacketPayload.codec(SyncGearPayload::write, SyncGearPayload::read);

    private static void write(SyncGearPayload p, FriendlyByteBuf buf) {
        buf.writeVarInt(p.entries.size());
        for (Entry e : p.entries) {
            buf.writeUtf(e.key);
            buf.writeUtf(e.item);
            buf.writeByte(e.state);
            buf.writeVarInt(e.remain);
            buf.writeVarInt(e.total);
            buf.writeBoolean(e.locked);
        }
        buf.writeVarInt(p.bodyFlags);
    }

    private static SyncGearPayload read(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Entry> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(new Entry(buf.readUtf(), buf.readUtf(), buf.readByte(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));
        }
        return new SyncGearPayload(list, buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
