package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端：技艺特效事件（一次性几何特效，非粒子）。
 * <p>
 * 只发送「谁、在哪、什么形状、多强、多久」，客户端按坐标与 seed 自行推进动画，
 * 不再逐帧同步，也不再依赖粒子系统。
 * <p>
 * kind：0 闪光；1 光束；2 地面阵纹；3 锥形冲击；4 折线电弧；5 蓄能球；6 命中爆点；7 弧形刀光
 * from(a)：起点 / 中心；to(b)：终点 / 方向端点；color：ARGB；power：尺寸或强度；life：存活 tick；seed：形状随机种子
 */
public record FxEventPayload(int kind, double ax, double ay, double az,
                             double bx, double by, double bz,
                             int color, float power, int life, int seed) implements CustomPacketPayload {

    public static final Type<FxEventPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "fx_event"));

    public static final StreamCodec<FriendlyByteBuf, FxEventPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (p, buf) -> {
                buf.writeVarInt(p.kind);
                buf.writeDouble(p.ax);
                buf.writeDouble(p.ay);
                buf.writeDouble(p.az);
                buf.writeDouble(p.bx);
                buf.writeDouble(p.by);
                buf.writeDouble(p.bz);
                buf.writeInt(p.color);
                buf.writeFloat(p.power);
                buf.writeVarInt(p.life);
                buf.writeInt(p.seed);
            },
            buf -> new FxEventPayload(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readInt(), buf.readFloat(), buf.readVarInt(), buf.readInt()));

    // ===== kind 常量 =====
    public static final int FLASH = 0;
    public static final int BEAM = 1;
    public static final int FORMATION = 2;
    public static final int CONE = 3;
    public static final int ARC = 4;
    public static final int ORB = 5;
    public static final int IMPACT = 6;
    public static final int SLASH = 7;

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
