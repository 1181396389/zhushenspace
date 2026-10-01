package com.zhushen.space.data;

/**
 * 主神商城：装备（物品）。价格 = branchCost 个 branchTier 级支线 + scoreCost 积分（支线序号 0 S · 1 A · 2 B · 3 C · 4 D）。
 * 可以重复购买（装备可能遗失）。
 */
public enum ShopGear {
    /** 魔虚罗之法阵：B 级 · 头盔位 · 适应 */
    MAHORAGA_WHEEL("mahoraga_wheel", 2, 1, 8000, PowerRank.B);

    public final String key;
    public final int branchTier, branchCost, scoreCost;
    public final PowerRank rank;

    ShopGear(String key, int branchTier, int branchCost, int scoreCost, PowerRank rank) {
        this.key = key;
        this.branchTier = branchTier;
        this.branchCost = branchCost;
        this.scoreCost = scoreCost;
        this.rank = rank;
    }

    public static final int COUNT = values().length;

    public String nameKey() { return "item.zhushenspace." + key; }

    public String descKey() { return "shop.zhushenspace.gear." + key + ".desc"; }
}
