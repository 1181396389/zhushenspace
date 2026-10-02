package com.zhushen.space.data;

/**
 * 盾牌模板（防具，不属于冷兵器）。
 * 盾牌防御 N/N：前者应对近战攻击，后者应对远程攻击；只在【格挡】（举盾）期间生效。
 * 破甲依次击破 盾牌防御 → 盔甲防御 → 天生防御；措手不及不会失去盾牌防御；接触攻击无视盾牌防御。
 */
public enum ShieldType {
    /** 盾牌：盾牌防御 2/2；【格挡】 */
    SHIELD("shield", 2, 3.0f, 2, 2);

    public final String key;
    public final int volume;
    public final float weight;
    public final int melee, ranged;

    /** 商城价格（奖励点数） */
    public static final int PRICE = 200;

    ShieldType(String key, int volume, float weight, int melee, int ranged) {
        this.key = key;
        this.volume = volume;
        this.weight = weight;
        this.melee = melee;
        this.ranged = ranged;
    }

    public String nameKey() { return "item.zhushenspace." + key; }

    public String descKey() { return "item.zhushenspace." + key + ".desc"; }
}
