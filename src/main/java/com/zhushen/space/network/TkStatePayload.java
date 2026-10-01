package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 服务端 → 本人与周围玩家：念动力操控状态。
 * ticks = 剩余时长（0 = 结束）；weapons = 悬浮在身边的武器（按序号对应出击包中的 idx）；held = 隔空托着的实体（-1 = 无）。
 */
public record TkStatePayload(int entity, int ticks, List<ItemStack> weapons, int held) implements CustomPacketPayload {
    public static final Type<TkStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "tk_state"));
    private static final StreamCodec<RegistryFriendlyByteBuf, List<ItemStack>> STACKS =
            ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list());
    public static final StreamCodec<RegistryFriendlyByteBuf, TkStatePayload> STREAM_CODEC = StreamCodec.of(
            (b, p) -> { b.writeVarInt(p.entity); b.writeVarInt(p.ticks); STACKS.encode(b, p.weapons); b.writeVarInt(p.held + 1); },
            b -> new TkStatePayload(b.readVarInt(), b.readVarInt(), STACKS.decode(b), b.readVarInt() - 1));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
