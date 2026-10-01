package com.zhushen.space.data;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.util.INBTSerializable;
import org.jetbrains.annotations.Nullable;

/**
 * 新手试炼进度与存档备份（持久化、死亡保留）。
 * <ul>
 *   <li>status：0 未进行 / 1 试炼中 / 2 已完成 / 3 已跳过</li>
 *   <li>backup：进入试炼前的背包、属性、技能、建卡、能量、技艺、伤势、状态、货币等快照，离开时整体还原</li>
 * </ul>
 */
public class TrialData implements INBTSerializable<CompoundTag> {
    public static final int NONE = 0, ACTIVE = 1, DONE = 2, SKIPPED = 3;

    public int status = NONE;
    /** 进入本次试炼前的状态（中途退出时恢复为它，或记为已跳过） */
    public int prevStatus = NONE;
    public int stage;
    /** 当前关卡已完成的目标位 */
    public int done;
    /** 计数型目标的进度（攻击假人次数） */
    public int count;
    /** 已开启的门（第 i 位 = 第 i 关出口） */
    public int doors;
    public int template = -1;
    public int slot = -1;
    public int deaths;
    public long startTick;
    /** 伤势关的伤势是否已施加 */
    public boolean woundsApplied;
    /** 场景生成代数：清理旧实例遗留的实体 */
    public int gen;
    /** 记录：完成次数 / 最快用时（tick，0 = 无） */
    public int completions;
    public long bestTicks;
    public long lastTicks;
    public int lastDeaths;
    @Nullable public GlobalPos returnPos;
    public float returnYaw;
    @Nullable public CompoundTag backup;

    public boolean active() { return status == ACTIVE; }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag t = new CompoundTag();
        t.putInt("Status", status);
        t.putInt("Prev", prevStatus);
        t.putInt("Stage", stage);
        t.putInt("Done", done);
        t.putInt("Count", count);
        t.putInt("Doors", doors);
        t.putInt("Template", template);
        t.putInt("Slot", slot);
        t.putInt("Deaths", deaths);
        t.putLong("Start", startTick);
        t.putBoolean("Wounds", woundsApplied);
        t.putInt("Gen", gen);
        t.putInt("Completions", completions);
        t.putLong("Best", bestTicks);
        t.putLong("Last", lastTicks);
        t.putInt("LastDeaths", lastDeaths);
        if (returnPos != null) {
            t.putString("RetDim", returnPos.dimension().location().toString());
            t.putLong("RetPos", returnPos.pos().asLong());
            t.putFloat("RetYaw", returnYaw);
        }
        if (backup != null) t.put("Backup", backup);
        return t;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag t) {
        status = t.getInt("Status");
        prevStatus = t.getInt("Prev");
        stage = t.getInt("Stage");
        done = t.getInt("Done");
        count = t.getInt("Count");
        doors = t.getInt("Doors");
        template = t.contains("Template") ? t.getInt("Template") : -1;
        slot = t.contains("Slot") ? t.getInt("Slot") : -1;
        deaths = t.getInt("Deaths");
        startTick = t.getLong("Start");
        woundsApplied = t.getBoolean("Wounds");
        gen = t.getInt("Gen");
        completions = t.getInt("Completions");
        bestTicks = t.getLong("Best");
        lastTicks = t.getLong("Last");
        lastDeaths = t.getInt("LastDeaths");
        returnPos = null;
        if (t.contains("RetDim")) {
            ResourceLocation rl = ResourceLocation.tryParse(t.getString("RetDim"));
            if (rl != null) returnPos = GlobalPos.of(ResourceKey.create(Registries.DIMENSION, rl),
                    BlockPos.of(t.getLong("RetPos")));
            returnYaw = t.getFloat("RetYaw");
        }
        backup = t.contains("Backup") ? t.getCompound("Backup") : null;
    }
}
