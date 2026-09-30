package com.zhushen.space.data;

/**
 * 固有不良状态（规则书「固有不良状态的类型」及各点数的重度 / 毁灭性后果）。
 * 既可以由不良状态点数的档位派生，也可以由能力 / 指令直接施加（带持续时间）。
 * 所有固有不良状态在判定某些能力时皆视为重度不良状态。
 * 按序号存为位掩码（long），只在末尾追加。
 */
public enum Condition {
    FROSTBITE("frostbite"),            // 冻伤
    FROZEN("frozen"),                  // 冰封
    INCINERATING("incinerating"),      // 焚烧
    CREMATING("cremating"),            // 火化
    HEARING_IMPAIRED("hearing_impaired"), // 听觉障碍
    DEAF("deaf"),                      // 耳聋
    VISION_IMPAIRED("vision_impaired"),   // 视觉障碍
    BLIND("blind"),                    // 目盲
    NAUSEOUS("nauseous"),              // 反胃
    MODS("mods"),                      // 多系统器官功能衰竭
    STIFF("stiff"),                    // 僵化
    PETRIFIED("petrified"),            // 石化
    LOST_SELF("lost_self"),            // 迷失自我
    ENSLAVED("enslaved"),              // 精神奴役
    ROOTED("rooted"),                  // 定身
    DISABLED("disabled"),              // 失能
    PARALYZED("paralyzed"),            // 瘫痪
    UNBALANCED("unbalanced"),          // 失衡
    UNCONSCIOUS("unconscious"),        // 昏迷
    SPASM("spasm"),                    // 肌肉痉挛
    EXHAUSTED("exhausted"),            // 力竭
    INFATUATED("infatuated"),          // 迷情
    MISANTHROPY("misanthropy"),        // 厌世
    MANIC("manic"),                    // 狂躁
    HYSTERIA("hysteria"),              // 歇斯底里
    PANIC("panic"),                    // 惊慌逃窜
    TERROR("terror"),                  // 惊惧
    ASLEEP("asleep"),                  // 睡眠
    ETERNAL_SLEEP("eternal_sleep"),    // 永眠
    BLOOD_LOSS("blood_loss"),          // 失血过多
    OPEN_WOUND("open_wound"),          // 开放性创口
    LIMB_DISABLED("limb_disabled"),    // 肢体残障
    STUNNED("stunned"),                // 震慑
    HELPLESS("helpless"),              // 无助
    FLOATING("floating"),              // 浮空
    IMPRISONED("imprisoned"),          // 禁锢
    SILENCED("silenced"),              // 沉默
    BANISHED("banished"),              // 放逐
    TAUNTED("taunted"),                // 嘲讽
    FLAT_FOOTED("flat_footed"),        // 措手不及
    ASPHYXIA("asphyxia"),              // 窒息
    STARVING("starving"),              // 饥渴
    WEARY("weary");                    // 疲惫

    public static final int COUNT = values().length;

    public final String key;

    Condition(String key) {
        this.key = key;
    }

    public long bit() { return 1L << ordinal(); }

    public String nameKey() { return "condition.zhushenspace." + key; }

    public String descKey() { return "condition.zhushenspace." + key + ".desc"; }

    public static Condition byKey(String k) {
        for (Condition c : values()) if (c.key.equals(k)) return c;
        return null;
    }
}
