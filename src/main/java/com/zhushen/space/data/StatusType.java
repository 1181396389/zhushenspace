package com.zhushen.space.data;

/**
 * 不良状态点数的类型（规则书「不良状态点数的类型」）。
 * 每种类型有两项关键抵抗属性与默认豁免；点数按阈值分为 轻度 / 重度 / 毁灭性后果 三档（见 StatusManager）。
 * 目前已录入：冻结、燃烧、耳鸣、目眩；其余类型待规则补全后追加（只在末尾追加，勿改顺序——存档按序号保存）。
 */
public enum StatusType {
    /** 冻结：力量 / 敏捷，强韧豁免；与燃烧相互反制 */
    FREEZE("freeze", AttributeType.STRENGTH, AttributeType.AGILITY, Save.FORTITUDE),
    /** 燃烧：敏捷 / 耐力，反射豁免（扑灭火焰）；与冻结相互反制 */
    BURN("burn", AttributeType.AGILITY, AttributeType.ENDURANCE, Save.REFLEX),
    /** 耳鸣：耐力 / 感知，强韧豁免 */
    TINNITUS("tinnitus", AttributeType.ENDURANCE, AttributeType.PERCEPTION, Save.FORTITUDE),
    /** 目眩：耐力 / 感知，强韧豁免 */
    DAZZLE("dazzle", AttributeType.ENDURANCE, AttributeType.PERCEPTION, Save.FORTITUDE);

    public enum Save { FORTITUDE, REFLEX, WILL }

    public enum Tier { NONE, LIGHT, HEAVY, DESTRUCTIVE }

    public static final int COUNT = values().length;

    public final String key;
    public final AttributeType attr1, attr2;
    public final Save save;

    StatusType(String key, AttributeType a1, AttributeType a2, Save save) {
        this.key = key;
        this.attr1 = a1;
        this.attr2 = a2;
        this.save = save;
    }

    public int bit() { return 1 << ordinal(); }

    public String nameKey() { return "status.zhushenspace." + key; }

    /** 该类型某一档的状态名（如 冻结 → 重度「冻伤」、毁灭「冰封」） */
    public String tierKey(Tier t) { return "status.zhushenspace." + key + "." + t.name().toLowerCase(java.util.Locale.ROOT); }

    public static StatusType byKey(String k) {
        for (StatusType t : values()) if (t.key.equals(k)) return t;
        return null;
    }
}
