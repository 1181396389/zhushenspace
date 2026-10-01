package com.zhushen.space.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.common.util.INBTSerializable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 装备位穿脱状态（Attachment，死亡不保留）。键为位置键（{@link GearSlot#vanillaKey} / {@link GearSlot#curioKey}）。
 * <ul>
 *   <li>DONNING 穿戴中：已放入装备位，但尚未生效</li>
 *   <li>WORN 已穿戴：生效；需要穿脱时间的装备此时无法直接取下（先开始解下）</li>
 *   <li>DOFFING 解下中：不再生效</li>
 *   <li>LOOSE 已解开：不生效，可以直接取下</li>
 * </ul>
 */
public class PlayerGearData implements INBTSerializable<CompoundTag> {

    public static final byte DONNING = 0, WORN = 1, DOFFING = 2, LOOSE = 3;

    public static final class Entry {
        /** 物品注册名（换成另一种物品 = 重新穿戴） */
        public String item;
        public byte state;
        /** 计时（服务器主世界 gameTime） */
        public long start, end;

        public Entry(String item, byte state, long start, long end) {
            this.item = item;
            this.state = state;
            this.start = start;
            this.end = end;
        }
    }

    public final Map<String, Entry> entries = new LinkedHashMap<>();
    /** 首次运行：已穿着的装备直接视为已穿戴 */
    public boolean initialized;

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        CompoundTag list = new CompoundTag();
        for (var e : entries.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putString("Item", e.getValue().item);
            t.putByte("State", e.getValue().state);
            t.putLong("Start", e.getValue().start);
            t.putLong("End", e.getValue().end);
            list.put(e.getKey(), t);
        }
        tag.put("Entries", list);
        tag.putBoolean("Init", initialized);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        entries.clear();
        CompoundTag list = tag.getCompound("Entries");
        for (String k : list.getAllKeys()) {
            if (list.getTagType(k) != Tag.TAG_COMPOUND) continue;
            CompoundTag t = list.getCompound(k);
            entries.put(k, new Entry(t.getString("Item"), t.getByte("State"), t.getLong("Start"), t.getLong("End")));
        }
        initialized = tag.getBoolean("Init");
    }
}
