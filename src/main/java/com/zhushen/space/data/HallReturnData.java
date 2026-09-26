package com.zhushen.space.data;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.neoforged.neoforge.common.util.INBTSerializable;

/**
 * 主神空间大厅返回点（Attachment 持久化，死亡保留）。
 * 之前只存在内存 Map 里，服务器重启 / 关服后丢失，玩家只能被送回世界出生点。
 */
public class HallReturnData implements INBTSerializable<CompoundTag> {

    private GlobalPos pos;

    public GlobalPos get() {
        return pos;
    }

    public void set(GlobalPos pos) {
        this.pos = pos;
    }

    /** 取出并清空 */
    public GlobalPos take() {
        GlobalPos p = pos;
        pos = null;
        return p;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        if (pos != null) {
            GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE, pos)
                    .result().ifPresent(t -> tag.put("Pos", t));
        }
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        pos = tag.contains("Pos")
                ? GlobalPos.CODEC.parse(NbtOps.INSTANCE, tag.get("Pos")).result().orElse(null)
                : null;
    }
}
