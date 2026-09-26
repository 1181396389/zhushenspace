package com.zhushen.space.network;

import com.zhushen.space.ZhuShenSpace;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：保存某一套战斗预设栏（-1 为空，否则为 SkillAbility 序号）。
 */
public record EquipSkillsPayload(int bar, int[] slots) implements CustomPacketPayload {

    public static final int SLOT_COUNT = 9;

    public static final Type<EquipSkillsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ZhuShenSpace.MODID, "equip_skills"));

    public static final StreamCodec<FriendlyByteBuf, EquipSkillsPayload> STREAM_CODEC = CustomPacketPayload.codec(
            EquipSkillsPayload::write, EquipSkillsPayload::read);

    private static void write(EquipSkillsPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.bar);
        for (int i = 0; i < SLOT_COUNT; i++) {
            buf.writeVarInt(payload.slots[i] + 1);
        }
    }

    private static EquipSkillsPayload read(FriendlyByteBuf buf) {
        int bar = buf.readVarInt();
        int[] slots = new int[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) {
            slots[i] = buf.readVarInt() - 1;
        }
        return new EquipSkillsPayload(bar, slots);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
