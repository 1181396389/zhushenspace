package com.zhushen.space.data;

/**
 * 不良状态点数的类型（规则书「不良状态点数的类型」）。
 * 每种类型有一到两项关键抵抗属性与默认豁免；点数按阈值分为 轻度 / 重度 / 毁灭性后果 三档（见 StatusManager）。
 * 只在末尾追加，勿改顺序——存档按序号保存。
 */
public enum StatusType {
    /** 冻结：力量 / 敏捷，强韧；与燃烧相互反制 */
    FREEZE("freeze", AttributeType.STRENGTH, AttributeType.AGILITY, Save.FORTITUDE, Condition.FROSTBITE, Condition.FROZEN),
    /** 燃烧：敏捷 / 耐力，反射（扑灭火焰）；与冻结相互反制 */
    BURN("burn", AttributeType.AGILITY, AttributeType.ENDURANCE, Save.REFLEX, Condition.INCINERATING, Condition.CREMATING),
    /** 耳鸣：耐力 / 感知，强韧 */
    TINNITUS("tinnitus", AttributeType.ENDURANCE, AttributeType.PERCEPTION, Save.FORTITUDE, Condition.HEARING_IMPAIRED, Condition.DEAF),
    /** 目眩：耐力 / 感知，强韧 */
    DAZZLE("dazzle", AttributeType.ENDURANCE, AttributeType.PERCEPTION, Save.FORTITUDE, Condition.VISION_IMPAIRED, Condition.BLIND),
    /** 恶心：耐力 / 决心，强韧 */
    NAUSEA("nausea", AttributeType.ENDURANCE, AttributeType.RESOLVE, Save.FORTITUDE, Condition.NAUSEOUS, Condition.MODS),
    /** 晶化：耐力 / 决心，强韧 */
    CRYSTAL("crystal", AttributeType.ENDURANCE, AttributeType.RESOLVE, Save.FORTITUDE, Condition.STIFF, Condition.PETRIFIED),
    /** 精神束缚：决心 / 沉着，意志 */
    BIND("bind", AttributeType.RESOLVE, AttributeType.COMPOSURE, Save.WILL, Condition.LOST_SELF, Condition.ENSLAVED),
    /** 纠缠：力量 / 敏捷，反射 */
    ENTANGLE("entangle", AttributeType.STRENGTH, AttributeType.AGILITY, Save.REFLEX, Condition.ROOTED, Condition.DISABLED),
    /** 麻痹：耐力 / 决心，强韧 */
    PARALYSIS("paralysis", AttributeType.ENDURANCE, AttributeType.RESOLVE, Save.FORTITUDE, Condition.ROOTED, Condition.PARALYZED),
    /** 晕眩：耐力 / 决心，强韧或意志 */
    DAZE("daze", AttributeType.ENDURANCE, AttributeType.RESOLVE, Save.FORT_OR_WILL, Condition.UNBALANCED, Condition.UNCONSCIOUS),
    /** 剧痛：耐力 / 决心，强韧或意志 */
    PAIN("pain", AttributeType.ENDURANCE, AttributeType.RESOLVE, Save.FORT_OR_WILL, Condition.SPASM, Condition.UNCONSCIOUS),
    /** 疲乏：耐力 / 力量，强韧 */
    FATIGUE("fatigue", AttributeType.ENDURANCE, AttributeType.STRENGTH, Save.FORTITUDE, Condition.SPASM, Condition.EXHAUSTED),
    /** 魅惑：决心 / 风度，意志（针对沉迷目标） */
    CHARM("charm", AttributeType.RESOLVE, AttributeType.CHARM, Save.WILL, Condition.INFATUATED, Condition.ENSLAVED),
    /** 沮丧：决心 / 沉着，意志；与亢奋相互反制 */
    DEPRESSION("depression", AttributeType.RESOLVE, AttributeType.COMPOSURE, Save.WILL, Condition.LOST_SELF, Condition.MISANTHROPY),
    /** 亢奋：决心 / 沉着，意志；与沮丧相互反制 */
    EXCITEMENT("excitement", AttributeType.RESOLVE, AttributeType.COMPOSURE, Save.WILL, Condition.MANIC, Condition.HYSTERIA),
    /** 恐惧：决心 / 沉着，意志（针对恐惧目标） */
    FEAR("fear", AttributeType.RESOLVE, AttributeType.COMPOSURE, Save.WILL, Condition.PANIC, Condition.TERROR),
    /** 欲眠：决心 / 沉着，意志 */
    DROWSY("drowsy", AttributeType.RESOLVE, AttributeType.COMPOSURE, Save.WILL, Condition.ASLEEP, Condition.ETERNAL_SLEEP),
    /** 肢体妨害：力量/敏捷取高 + 耐力，强韧；点数按肢体分别计算 */
    LIMB("limb", AttributeType.STRENGTH, AttributeType.ENDURANCE, Save.FORTITUDE, Condition.LIMB_DISABLED, Condition.LIMB_DISABLED),
    /** 失速：力量 / 敏捷，强韧或反射 */
    SLOW("slow", AttributeType.STRENGTH, AttributeType.AGILITY, Save.FORT_OR_REFLEX, Condition.ROOTED, Condition.DISABLED),
    /** 流血：只有耐力（毁灭性后果阈值计算两遍耐力），强韧 */
    BLEED("bleed", AttributeType.ENDURANCE, AttributeType.ENDURANCE, Save.FORTITUDE, Condition.BLOOD_LOSS, Condition.MODS);

    public enum Save { FORTITUDE, REFLEX, WILL, FORT_OR_WILL, FORT_OR_REFLEX }

    public enum Tier { NONE, LIGHT, HEAVY, DESTRUCTIVE }

    public static final int COUNT = values().length;

    public final String key;
    public final AttributeType attr1, attr2;
    public final Save save;
    /** 重度不良状态 / 毁灭性后果对应的固有不良状态 */
    public final Condition heavy, destructive;

    StatusType(String key, AttributeType a1, AttributeType a2, Save save, Condition heavy, Condition destructive) {
        this.key = key;
        this.attr1 = a1;
        this.attr2 = a2;
        this.save = save;
        this.heavy = heavy;
        this.destructive = destructive;
    }

    public int bit() { return 1 << ordinal(); }

    public String nameKey() { return "status.zhushenspace." + key; }

    /** 该类型某一档的显示名：轻度 = 「X点数」，重度 / 毁灭 = 对应固有不良状态名 */
    public String tierKey(Tier t) {
        return switch (t) {
            case HEAVY -> heavy.nameKey();
            case DESTRUCTIVE -> destructive.nameKey();
            default -> nameKey();
        };
    }

    /** 相互反制的类型（冻结 ↔ 燃烧，沮丧 ↔ 亢奋） */
    public StatusType opposite() {
        return switch (this) {
            case FREEZE -> BURN;
            case BURN -> FREEZE;
            case DEPRESSION -> EXCITEMENT;
            case EXCITEMENT -> DEPRESSION;
            default -> null;
        };
    }

    /** 带有特定目标的类型（魅惑 → 沉迷目标，恐惧 → 恐惧目标） */
    public boolean targeted() { return this == CHARM || this == FEAR; }

    public static StatusType byKey(String k) {
        for (StatusType t : values()) if (t.key.equals(k)) return t;
        return null;
    }
}
