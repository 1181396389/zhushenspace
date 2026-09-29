package com.zhushen.space.data;

/**
 * 专长定义。
 * <ul>
 *   <li>普通专长：每个等级 3 XP × 等级，按顺序逐级学习；直接从高等级开始的专长从最低级开始计。</li>
 *   <li>轮回之境专长：每个等级 6 XP × 等级，规则同上。</li>
 *   <li>建卡专长：只能在建卡时用 XP 选择；各等级分别购买（每级一次），购买高等级需连同低等级价格一起支付（不获得低级效果）。</li>
 * </ul>
 * 新专长直接在此追加（序号只追加，存档按 key 保存）。
 */
public enum FeatType {
    SPECIAL_IDENTITY("special_identity", Category.CREATION, 1, 3),
    SUPERNATURAL_IDENTITY("supernatural_identity", Category.CREATION, 5, 5),
    /** 先天男娘（0 级，0 XP）。前提：男性。性别判定见 common/GenderRules */
    BORN_FEMBOY("born_femboy", Category.NORMAL, 0, 0);

    public enum Category {
        NORMAL(3), REINCARNATION(6), CREATION(3);
        public final int perLevel;
        Category(int perLevel) { this.perLevel = perLevel; }
        public String nameKey() { return "feat_category.zhushenspace." + name().toLowerCase(java.util.Locale.ROOT); }
    }

    public static final FeatType[] VALUES = values();
    public static final int COUNT = VALUES.length;

    public final String key;
    public final Category category;
    public final int minLevel, maxLevel;

    FeatType(String key, Category category, int minLevel, int maxLevel) {
        this.key = key;
        this.category = category;
        this.minLevel = minLevel;
        this.maxLevel = maxLevel;
    }

    public String nameKey() { return "feat.zhushenspace." + key; }

    public String levelDescKey(int level) { return "feat.zhushenspace." + key + ".level" + level; }

    /** 前提条件文本键（语言文件未提供时显示「无」） */
    public String prereqKey() { return "feat.zhushenspace." + key + ".prereq"; }

    public boolean creationOnly() { return category == Category.CREATION; }

    /** 单个等级的价格 */
    public int levelPrice(int level) {
        return category.perLevel * level;
    }

    /** 建卡专长购买某一级的价格（含低级价格） */
    public int payLowerPrice(int level) {
        int c = 0;
        for (int l = minLevel; l <= level; l++) c += levelPrice(l);
        return c;
    }

    /** 等级掩码（bit l = 拥有第 l 级）是否合法 */
    public boolean validMask(int mask) {
        for (int l = 0; l < 31; l++) {
            if ((mask & (1 << l)) != 0 && (l < minLevel || l > maxLevel)) return false;
        }
        if (creationOnly() || mask == 0) return true;
        // 普通 / 轮回：必须自最低级起连续
        int top = 31 - Integer.numberOfLeadingZeros(mask);
        for (int l = minLevel; l <= top; l++) if ((mask & (1 << l)) == 0) return false;
        return true;
    }

    public int cost(int mask) {
        int c = 0;
        for (int l = minLevel; l <= maxLevel; l++) {
            if ((mask & (1 << l)) == 0) continue;
            c += creationOnly() ? payLowerPrice(l) : levelPrice(l);
        }
        return c;
    }

    public static int totalCost(int[] masks) {
        int c = 0;
        for (int i = 0; i < Math.min(masks.length, COUNT); i++) c += VALUES[i].cost(masks[i]);
        return c;
    }

    public static boolean has(int[] masks, FeatType f, int level) {
        return f.ordinal() < masks.length && (masks[f.ordinal()] & (1 << level)) != 0;
    }

    public static FeatType byKey(String key) {
        for (FeatType f : VALUES) if (f.key.equals(key)) return f;
        return null;
    }
}
