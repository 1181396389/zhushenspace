package com.zhushen.space.data;

/**
 * 专长（咒术回战主题）。四条专长线，线内按顺序解锁（需先习得前一项）。
 * 当前为框架版：效果文案见语言文件 feat.zhushenspace.<key>.effect，数值效果待设计后接入。
 */
public enum FeatType {
    // —— 五条悟 · 无下限咒术 ——
    SIX_EYES(Line.GOJO, 1, "six_eyes"),
    BLUE(Line.GOJO, 2, "blue"),
    RED(Line.GOJO, 2, "red"),
    HOLLOW_PURPLE(Line.GOJO, 3, "hollow_purple"),
    // —— 两面宿傩 · 御厨子 ——
    DISMANTLE(Line.SUKUNA, 1, "dismantle"),
    CLEAVE(Line.SUKUNA, 2, "cleave"),
    FUGA(Line.SUKUNA, 2, "fuga"),
    MALEVOLENT_SHRINE(Line.SUKUNA, 3, "malevolent_shrine"),
    // —— 虎杖悠仁 ——
    DIVERGENT_FIST(Line.ITADORI, 1, "divergent_fist"),
    BLACK_FLASH(Line.ITADORI, 2, "black_flash"),
    // —— 伏黑惠 · 十种影法术 ——
    DIVINE_DOGS(Line.MEGUMI, 1, "divine_dogs"),
    CHIMERA_SHADOW_GARDEN(Line.MEGUMI, 2, "chimera_shadow_garden");

    public enum Line { GOJO, SUKUNA, ITADORI, MEGUMI }

    public static final int COUNT = values().length;
    /** 首次使用主神邀请函发放的专长点 */
    public static final int ENVELOPE_FEAT_POINTS = 3;

    public final Line line;
    public final int cost;
    public final String key;

    FeatType(Line line, int cost, String key) {
        this.line = line;
        this.cost = cost;
        this.key = key;
    }

    public int bit() {
        return 1 << ordinal();
    }

    /** 同线前一项（线首返回 null） */
    public FeatType prerequisite() {
        int i = ordinal();
        if (i == 0) return null;
        FeatType prev = values()[i - 1];
        return prev.line == line ? prev : null;
    }

    public static int cost(int mask) {
        int c = 0;
        for (FeatType f : values()) if ((mask & f.bit()) != 0) c += f.cost;
        return c;
    }

    /** 掩码内每项专长的前置都在掩码内 */
    public static boolean valid(int mask) {
        if ((mask & ~((1 << COUNT) - 1)) != 0) return false;
        for (FeatType f : values()) {
            if ((mask & f.bit()) == 0) continue;
            FeatType p = f.prerequisite();
            if (p != null && (mask & p.bit()) == 0) return false;
        }
        return true;
    }

    public static FeatType byKey(String key) {
        for (FeatType f : values()) if (f.key.equals(key)) return f;
        return null;
    }
}
