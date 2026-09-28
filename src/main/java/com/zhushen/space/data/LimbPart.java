package com.zhushen.space.data;

/**
 * 玩家肢体部位（躯干不单独计量，直接由 B/L/A 总伤势承担）。
 * 部位血量上限 = 生命上限 × {@link #hpFraction}（向上取整，至少 1）。
 * <ul>
 *   <li>头：血量清空 → 昏迷（与伤势满载的昏迷规则一致，可用意志力强撑）</li>
 *   <li>四肢：血量清空 → 断肢（任何伤害都可以造成断肢），需管理员指令恢复</li>
 * </ul>
 */
public enum LimbPart {
    HEAD(0.25f, "head"),
    RIGHT_ARM(0.30f, "rightArm"),
    LEFT_ARM(0.30f, "leftArm"),
    RIGHT_LEG(0.35f, "rightLeg"),
    LEFT_LEG(0.35f, "leftLeg");

    public static final int COUNT = values().length;

    public final float hpFraction;
    /** KosmX 动作库中的模型部件名 */
    public final String modelName;

    LimbPart(float hpFraction, String modelName) {
        this.hpFraction = hpFraction;
        this.modelName = modelName;
    }

    public int bit() {
        return 1 << ordinal();
    }

    public boolean severable() {
        return this != HEAD;
    }

    public boolean isArm() {
        return this == RIGHT_ARM || this == LEFT_ARM;
    }

    public boolean isLeg() {
        return this == RIGHT_LEG || this == LEFT_LEG;
    }

    public int maxHp(float maxHealth) {
        return Math.max(1, (int) Math.ceil(maxHealth * hpFraction));
    }

    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public String nameKey() {
        return "limb.zhushenspace." + key();
    }

    public static LimbPart byKey(String key) {
        for (LimbPart p : values()) if (p.key().equals(key)) return p;
        return null;
    }
}
