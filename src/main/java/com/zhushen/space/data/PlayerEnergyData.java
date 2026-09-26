package com.zhushen.space.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.common.util.INBTSerializable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 玩家能量池数据（作为 Attachment 持久化，死亡保留）。
 *
 * 玩家可以同时拥有多个不同的能量池（按 id 区分），每个池独立记录当前值与上限。
 * 能量池没有通用回复速率——每个能量池都有自己独特的恢复方式，
 * 恢复逻辑由 {@code EnergyManager} 按池 id 分别实现（如：攻击命中恢复 / 原地静立恢复）。
 *
 * 额外状态：内力吐息开关（breathEnabled，拥有内力池时的自动档强化）。
 */
public class PlayerEnergyData implements INBTSerializable<CompoundTag> {

    /** 单个能量池 */
    public static class Pool {
        public double current;
        public double max;
        /** 基础容量（传奇主池加成前；实际上限 = 基础容量 + 主池传奇加成，由 applyMainPoolBonus 推导） */
        public double base;

        public Pool() {
        }

        public Pool(double current, double max) {
            this(current, max, max);
        }

        public Pool(double current, double max, double base) {
            this.current = current;
            this.max = max;
            this.base = base;
        }
    }

    /** id → 池（LinkedHashMap 保持添加顺序，HUD 按此顺序排列） */
    private final Map<String, Pool> pools = new LinkedHashMap<>();

    /** 内力吐息开关（仅在有内力池时生效） */
    private boolean breathEnabled;

    /** 内力余量暂存：摘下饰品移除内力池时保存余量，重新装备时恢复（防止反复摘戴回满） */
    private double neiliCarryover;
    /** 是否有暂存余量（区分“从未摘下过”与“摘下时余量为 0”，否则 0 内力摘戴会被当成首次装备而回满） */
    private boolean hasNeiliCarryover;

    public Map<String, Pool> pools() {
        return pools;
    }

    public boolean isEmpty() {
        return pools.isEmpty();
    }

    public boolean breathEnabled() {
        return breathEnabled;
    }

    public void setBreathEnabled(boolean breathEnabled) {
        this.breathEnabled = breathEnabled;
    }

    /** 上次摘下饰品时的内力余量（重新装备时恢复） */
    public double neiliCarryover() {
        return neiliCarryover;
    }

    public void setNeiliCarryover(double value) {
        this.neiliCarryover = Math.max(0, value);
        this.hasNeiliCarryover = true;
    }

    public boolean hasNeiliCarryover() {
        return hasNeiliCarryover;
    }

    public Pool getPool(String id) {
        return pools.get(id);
    }

    /**
     * 发放能量池（指令用）：不存在则创建并填满，已存在则调整基础容量。
     */
    public Pool grantPool(String id, double max) {
        Pool pool = pools.get(id);
        if (pool == null) {
            pool = new Pool(max, max, max);
            pools.put(id, pool);
        } else if (max != pool.max) {
            pool.base = max;
            pool.max = max;
            pool.current = Math.min(pool.current, max);
        }
        return pool;
    }

    /**
     * 设置基础容量（传奇加成前）：不直接改动 max，
     * 实际上限由 {@link #applyMainPoolBonus} 统一推导。
     */
    public void setBaseCapacity(String id, double base) {
        Pool pool = pools.get(id);
        if (pool == null) {
            pool = new Pool(base, base, base);
            pools.put(id, pool);
        } else {
            pool.base = base;
        }
    }

    /**
     * 应用主池传奇加成：基础容量最大的池视为玩家主能量池，
     * 传奇加成直接扩充其上限（其余池上限 = 各自基础容量），并夹紧当前值。
     */
    public void applyMainPoolBonus(double bonus) {
        Pool largest = null;
        for (Pool pool : pools.values()) {
            if (largest == null || pool.base > largest.base) largest = pool;
        }
        for (Pool pool : pools.values()) {
            double target = pool.base + (pool == largest && bonus > 0 ? bonus : 0);
            pool.max = target;
            pool.current = Math.min(pool.current, target);
        }
    }

    /** 玩家当前的主能量池（基础容量最大者；无池返回 null） */
    public Map.Entry<String, Pool> mainPool() {
        Map.Entry<String, Pool> best = null;
        for (Map.Entry<String, Pool> e : pools.entrySet()) {
            if (best == null || e.getValue().base > best.getValue().base) best = e;
        }
        return best;
    }

    /**
     * 调整能量池容量（直接指定上限，同时更新基础容量）。
     */
    public void setCapacity(String id, double max) {
        Pool pool = pools.get(id);
        if (pool == null) {
            pools.put(id, new Pool(0, max, max));
        } else {
            pool.base = max;
            pool.max = max;
            pool.current = Math.min(pool.current, max);
        }
    }

    public void removePool(String id) {
        pools.remove(id);
    }

    public void clearPools() {
        pools.clear();
    }

    /**
     * 恢复能量（按各池独特的恢复方式调用），返回实际恢复的量（超过上限被截断）。
     */
    public double restore(String id, double amount) {
        if (amount <= 0) return 0;
        Pool pool = pools.get(id);
        if (pool == null || pool.current >= pool.max) return 0;
        double gained = Math.min(amount, pool.max - pool.current);
        pool.current += gained;
        return gained;
    }

    /**
     * 消耗能量，不足时返回 false。
     */
    public boolean consume(String id, double amount) {
        Pool pool = pools.get(id);
        if (pool == null || pool.current < amount) return false;
        pool.current -= amount;
        return true;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Map.Entry<String, Pool> entry : pools.entrySet()) {
            CompoundTag poolTag = new CompoundTag();
            poolTag.putString("Id", entry.getKey());
            poolTag.putDouble("Current", entry.getValue().current);
            poolTag.putDouble("Max", entry.getValue().max);
            poolTag.putDouble("Base", entry.getValue().base);
            list.add(poolTag);
        }
        tag.put("Pools", list);
        tag.putBoolean("BreathEnabled", breathEnabled);
        tag.putDouble("NeiliCarryover", neiliCarryover);
        tag.putBoolean("HasNeiliCarryover", hasNeiliCarryover);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        pools.clear();
        ListTag list = tag.getList("Pools", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag poolTag = list.getCompound(i);
            String id = poolTag.getString("Id");
            if (id.isEmpty()) continue;
            // 旧档迁移：无 Base 字段时基础容量 = 旧上限（下次重算会修正）
            double base = poolTag.contains("Base") ? poolTag.getDouble("Base") : poolTag.getDouble("Max");
            pools.put(id, new Pool(poolTag.getDouble("Current"), poolTag.getDouble("Max"), base));
        }
        breathEnabled = tag.getBoolean("BreathEnabled");
        neiliCarryover = tag.getDouble("NeiliCarryover");
        // 旧存档无此字段：只能按余量 > 0 推断
        hasNeiliCarryover = tag.contains("HasNeiliCarryover")
                ? tag.getBoolean("HasNeiliCarryover")
                : neiliCarryover > 0;
    }
}
