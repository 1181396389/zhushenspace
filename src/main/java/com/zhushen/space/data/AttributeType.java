package com.zhushen.space.data;

/**
 * 诸神空间 9 大属性定义与加点规则。
 *
 * 规则：
 * - 自由点数默认 12（每封主神邀请函 +12）
 * - 单个属性上限 5 点
 * - 第 1~4 点每点消耗 1 点，第 5 点消耗 2 点（即满级 5 点共消耗 6 点）
 * - 属性达到 5 点时获得 1 点传奇点数（传奇点数 = 满级属性数量）
 */
public enum AttributeType {
    STRENGTH("strength"),
    AGILITY("agility"),
    ENDURANCE("endurance"),
    INTELLIGENCE("intelligence"),
    PERCEPTION("perception"),
    RESOLVE("resolve"),
    CHARM("charm"),
    OPERATION("operation"),
    COMPOSURE("composure");

    public static final int COUNT = values().length;
    public static final int MAX_POINTS = 5;
    /** 新玩家初始自由点数（未使用邀请函时为 0） */
    public static final int DEFAULT_TOTAL_POINTS = 0;
    /** 每封主神邀请函提供的自由点数 */
    public static final int ENVELOPE_POINTS = 12;

    private final String key;

    AttributeType(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public String nameKey() {
        return "attribute.zhushenspace." + key;
    }

    public String descKey() {
        return "attribute.zhushenspace." + key + ".desc";
    }

    public String legendKey() {
        return "attribute.zhushenspace." + key + ".legend";
    }

    /** 加到 amount 级所需总消耗（第 5 点消耗 2 点） */
    public static int costToReach(int amount) {
        if (amount <= 0) return 0;
        return amount <= 4 ? amount : 6;
    }

    /** 一组加点方案的总消耗 */
    public static int totalCost(int[] points) {
        int sum = 0;
        for (int p : points) {
            sum += costToReach(p);
        }
        return sum;
    }

    /** 传奇点数 = 达到 5 点的属性数量 */
    public static int legendaryCount(int[] points) {
        int n = 0;
        for (int p : points) {
            if (p >= MAX_POINTS) n++;
        }
        return n;
    }

    /** 从 cur 升到下一级需要的点数；已满级返回 -1 */
    public static int stepCost(int cur) {
        if (cur >= MAX_POINTS) return -1;
        return cur == 4 ? 2 : 1;
    }

    /** 校验一组加点是否合法（范围 0~5） */
    public static boolean isValid(int[] points) {
        if (points == null || points.length != COUNT) return false;
        for (int p : points) {
            if (p < 0 || p > MAX_POINTS) return false;
        }
        return true;
    }
}
