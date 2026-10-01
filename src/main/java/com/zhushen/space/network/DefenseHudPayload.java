package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端：防御 / 豁免 HUD 的显示值（不含浮动；能量加值按开启时 +3 预览，不消耗能量）。
 *
 * @param def    当前防御（整套盔甲、无特定攻击者）
 * @param base   基础防御
 * @param armor  盔甲防御
 * @param will   意志豁免
 * @param reflex 反射豁免
 * @param fort   强韧豁免
 * @param area   范围豁免（无法反射时 0）
 * @param flags  Defense.HUD_*：全力防御 / 措手不及 / 无法反射 / 无法格挡
 */
public record DefenseHudPayload(int def, int base, int armor, int will, int reflex, int fort, int area, int flags)
        implements CustomPacketPayload {

    public static final Type<DefenseHudPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "defense_hud"));

    public static final StreamCodec<FriendlyByteBuf, DefenseHudPayload> STREAM_CODEC =
            CustomPacketPayload.codec(DefenseHudPayload::write, DefenseHudPayload::read);

    private static void write(DefenseHudPayload p, FriendlyByteBuf buf) {
        buf.writeVarInt(p.def);
        buf.writeVarInt(p.base);
        buf.writeVarInt(p.armor);
        buf.writeVarInt(p.will);
        buf.writeVarInt(p.reflex);
        buf.writeVarInt(p.fort);
        buf.writeVarInt(p.area);
        buf.writeVarInt(p.flags);
    }

    private static DefenseHudPayload read(FriendlyByteBuf buf) {
        return new DefenseHudPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
