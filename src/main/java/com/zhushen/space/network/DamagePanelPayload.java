package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端：玩家当前手持物的面板伤害（浮动前的 100% 值），供战斗模式伤害区间 HUD 热更新显示。
 *
 * @param kind    0 = 近战（空手 / 冷兵器 / 其他物品），1 = 枪械（TACZ）
 * @param panel   面板伤害（100%）；区间下限由客户端按浮动下限比例计算
 * @param pellets 每次射击的弹丸数（霰弹枪 &gt; 1，其余为 1）
 */
public record DamagePanelPayload(byte kind, float panel, int pellets) implements CustomPacketPayload {

    public static final Type<DamagePanelPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "damage_panel"));

    public static final StreamCodec<FriendlyByteBuf, DamagePanelPayload> STREAM_CODEC =
            CustomPacketPayload.codec(DamagePanelPayload::write, DamagePanelPayload::read);

    private static void write(DamagePanelPayload payload, FriendlyByteBuf buf) {
        buf.writeByte(payload.kind);
        buf.writeFloat(payload.panel);
        buf.writeVarInt(payload.pellets);
    }

    private static DamagePanelPayload read(FriendlyByteBuf buf) {
        return new DamagePanelPayload(buf.readByte(), buf.readFloat(), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
