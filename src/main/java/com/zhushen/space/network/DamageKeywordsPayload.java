package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：本人当前的减伤关键字（免疫 / 忽略 / 硬度 / 抵消 / 抗力 / 减免 / 吸收 / 阈值 / 转化 / 易伤），
 * 供属性面板与防御 HUD 悬停显示。
 */
public record DamageKeywordsPayload(List<Entry> entries) implements CustomPacketPayload {

    public static final int IMMUNE = 0, IMMUNE_SEV = 1, IGNORE = 2, IGNORE_IF = 3, HARDNESS = 4, OFFSET = 5,
            RESIST = 6, DR = 7, ABSORB = 8, THRESHOLD = 9, CONVERT = 10, VULN_DOUBLE = 11, VULN = 12, LIGHT = 13, DARK = 14;
    /** extra 中的标记位：由物品提供 */
    public static final int ITEM_BIT = 1 << 16;

    /**
     * @param type   关键字（上面的常量）
     * @param value  数值（转化：-1 = 全部）
     * @param kinds  伤害类型位掩码（0 = 默认范围）
     * @param extra  DR 弱点位掩码 / 吸收范围序号 / 转化层序号 / 免疫等级位掩码，| ITEM_BIT
     * @param source 来源翻译键（可为空）
     */
    public record Entry(int type, int value, int kinds, int extra, String source) {}

    public static final Type<DamageKeywordsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "damage_keywords"));

    public static final StreamCodec<FriendlyByteBuf, DamageKeywordsPayload> STREAM_CODEC =
            CustomPacketPayload.codec(DamageKeywordsPayload::write, DamageKeywordsPayload::read);

    private static void write(DamageKeywordsPayload p, FriendlyByteBuf buf) {
        buf.writeVarInt(p.entries.size());
        for (Entry e : p.entries) {
            buf.writeVarInt(e.type());
            buf.writeVarInt(e.value());
            buf.writeVarInt(e.kinds());
            buf.writeVarInt(e.extra());
            buf.writeUtf(e.source() == null ? "" : e.source(), 256);
        }
    }

    private static DamageKeywordsPayload read(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), 256);
        List<Entry> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            l.add(new Entry(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(256)));
        }
        return new DamageKeywordsPayload(List.copyOf(l));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
