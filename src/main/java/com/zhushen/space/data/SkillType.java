package com.zhushen.space.data;

/**
 * 诸神空间 9 大技能定义与加点规则（技能加点页）。
 *
 * 规则：
 * - 技能点默认 0（每封主神邀请函额外 +15）
 * - 单个技能上限 5 点
 * - 第 1~3 点每点消耗 1 点，3→4、4→5 各消耗 2 点（满级 5 点共消耗 7 点）
 *
 * 各技能效果：
 * - 运动：无被动；3/4/5 点解锁 自我保护 / 跳跃 / 攀爬
 * - 肉搏：每点 +1 徒手攻击力；3/4/5 点解锁 肉搏格挡 / 摔绊 / 冲锋攻击
 * - 白刃：每点 +1 冷兵器（非枪械武器）攻击力；3 点解锁 白刃格挡
 * - 手艺：无被动
 * - 神秘学：每点 +1 智力
 * - 科学：每点 +1 智力
 * - 动物沟通：无被动
 * - 表达：无被动
 * - 枪械：TACZ 枪械弹头直击伤害加成（需安装 TACZ，见 compat.TaczGunEvents）：
 *   常规枪每点 +1；霰弹枪 / 机枪 / 弹匣≥50 步枪独立乘区每点 +10%；狙击 / 火箭筒独立乘区每点 +50%；不作用于爆炸伤害
 *
 * 注意：技能点按枚举序号存档，新技能只能追加在末尾，不能插入中间。
 */
public enum SkillType {
    ATHLETICS("athletics"),
    BRAWL("brawl"),
    BLADE("blade"),
    CRAFT("craft"),
    OCCULTISM("occultism"),
    SCIENCE("science"),
    ANIMAL_COMMUNICATION("animal_communication"),
    EXPRESSION("expression"),
    FIREARMS("firearms");

    public static final int COUNT = values().length;
    public static final int MAX_POINTS = 5;
    /** 新玩家初始技能点数（未使用邀请函时为 0） */
    public static final int DEFAULT_TOTAL_SKILL_POINTS = 0;
    /** 每封主神邀请函提供的技能点数 */
    public static final int ENVELOPE_SKILL_POINTS = 15;

    private final String key;

    SkillType(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public String nameKey() {
        return "skill.zhushenspace." + key;
    }

    public String descKey() {
        return "skill.zhushenspace." + key + ".desc";
    }

    /** 消耗曲线：第 1~3 点每点 1 点，3→4、4→5 各消耗 2 点（满级 5 点共 7 点） */
    public static int costToReach(int amount) {
        if (amount <= 0) return 0;
        return amount <= 3 ? amount : 3 + 2 * (amount - 3);
    }

    /** 一组技能加点方案的总消耗 */
    public static int totalCost(int[] points) {
        int sum = 0;
        for (int p : points) {
            sum += costToReach(p);
        }
        return sum;
    }

    /** 从 cur 升到下一级需要的点数；已满级返回 -1 */
    public static int stepCost(int cur) {
        if (cur >= MAX_POINTS) return -1;
        return cur >= 3 ? 2 : 1;
    }

    /** 校验一组技能加点是否合法（范围 0~5） */
    public static boolean isValid(int[] points) {
        if (points == null || points.length != COUNT) return false;
        for (int p : points) {
            if (p < 0 || p > MAX_POINTS) return false;
        }
        return true;
    }
}
