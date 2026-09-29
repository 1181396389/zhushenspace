package com.zhushen.space.data;

import com.zhushen.space.common.FeatEffects;

/**
 * 技艺元数据（与 SkillAbility 中 gate = "art" 的条目一一对应，按枚举顺序）。
 * 价格：scoreCost 积分 + branchCost 个 branchTier 级支线（-1 = 不需要支线）。
 */
public enum ArtSkill {
    SPIRIT_SLASH(SkillAbility.SPIRIT_SLASH, FeatEffects.Pool.SPIRIT, -1, 500, 0, Mode.NONE, 0),
    SPIRIT_HEAL(SkillAbility.SPIRIT_HEAL, FeatEffects.Pool.SPIRIT, 4, 500, 0, Mode.NONE, 0),
    MIND_BLAST(SkillAbility.MIND_BLAST, FeatEffects.Pool.MIND, -1, 500, 0, Mode.NONE, 0,
            new Research("psychic", 81, 1)),
    MIND_SHOCK(SkillAbility.MIND_SHOCK, FeatEffects.Pool.MIND, 4, 500, 1, Mode.NONE, 0),
    HADOKEN(SkillAbility.HADOKEN, FeatEffects.Pool.NEILI, 4, 500, 1, Mode.NONE, 0,
            new Research("gou", 3, 1), new Research("shin", 3, 1),
            new Research("kamehameha", 3, 1), new Research("shinku", 3, 1)),
    BREATH_METHOD(SkillAbility.BREATH_METHOD, FeatEffects.Pool.BUDDHA, -1, 500, 0, Mode.PICK, 3,
            "outer", "middle", "inner"),
    YAKSHA_FLIGHT(SkillAbility.YAKSHA_FLIGHT, FeatEffects.Pool.BUDDHA, 4, 500, 1, Mode.NONE, 0),
    MAGIC_BURST(SkillAbility.MAGIC_BURST, FeatEffects.Pool.MAGIC, -1, 500, 0, Mode.NONE, 0,
            new Research("force", 81, 1)),
    MINOR_WARD(SkillAbility.MINOR_WARD, FeatEffects.Pool.MAGIC, 4, 500, 1, Mode.NONE, 0,
            new Research("guard", 0, 2), new Research("resist", 0, 2)),
    FIVE_ELEMENTS(SkillAbility.FIVE_ELEMENTS, FeatEffects.Pool.DAO, -1, 500, 0, Mode.PICK, -1,
            "thunder", "wind", "water", "fire", "earth"),
    EIGHT_FORMATION(SkillAbility.EIGHT_FORMATION, FeatEffects.Pool.DAO, 4, 500, 0, Mode.CYCLE, 0,
            new Research("sustain", 0, 2), "fire", "cold", "lightning", "acid", "pure_energy"),
    IGNORE_ME(SkillAbility.IGNORE_ME, FeatEffects.Pool.PSYCHIC, 4, 500, 1, Mode.NONE, 0),
    BIO_LIGHTNING(SkillAbility.BIO_LIGHTNING, FeatEffects.Pool.PSYCHIC, 4, 500, 1, Mode.NONE, 0),
    NETHER_VIGOR(SkillAbility.NETHER_VIGOR, FeatEffects.Pool.YOKAI, 4, 500, 1, Mode.NONE, 0),
    WIND_SLASH(SkillAbility.WIND_SLASH, FeatEffects.Pool.YOKAI, 4, 500, 1, Mode.NONE, 0),
    BASIC_PALM(SkillAbility.BASIC_PALM, FeatEffects.Pool.NEILI, 4, 500, 1, Mode.NONE, 0),
    REVIVE(SkillAbility.REVIVE, FeatEffects.Pool.NEILI, 3, 1000, 3, Mode.NONE, 0),
    PHOENIX_FIRE(SkillAbility.PHOENIX_FIRE, FeatEffects.Pool.CHAKRA, 4, 500, 1, Mode.NONE, 0),
    GREAT_FIREBALL(SkillAbility.GREAT_FIREBALL, FeatEffects.Pool.CHAKRA, 3, 1000, 3, Mode.NONE, 0);

    /** 选项模式：NONE 无；PICK 购买后选定（首个免费，其余按 extraCost XP 研发，-1 = 不可再选）；CYCLE 随时切换（轮盘） */
    public enum Mode { NONE, PICK, CYCLE }

    /** 研发：xp 花费；minCaster = 所需施法者等级（D1 C2 B3 A4 S5） */
    public record Research(String key, int xp, int minCaster) {}

    public static final int COUNT = values().length;

    public final SkillAbility ability;
    public final FeatEffects.Pool pool;
    public final int branchTier, scoreCost, cost;
    public final int branchCost;
    public final Mode mode;
    public final int extraCost;
    public final Research[] researches;
    public final String[] options;

    ArtSkill(SkillAbility a, FeatEffects.Pool pool, int tier, int score, int cost, Mode mode, int extra, Object... rest) {
        this.ability = a;
        this.pool = pool;
        this.branchTier = tier;
        this.branchCost = tier < 0 ? 0 : 1;
        this.scoreCost = score;
        this.cost = cost;
        this.mode = mode;
        this.extraCost = extra;
        java.util.List<Research> rs = new java.util.ArrayList<>();
        java.util.List<String> os = new java.util.ArrayList<>();
        for (Object o : rest) {
            if (o instanceof Research r) rs.add(r);
            else os.add((String) o);
        }
        this.researches = rs.toArray(new Research[0]);
        this.options = os.toArray(new String[0]);
    }

    public String key() { return ability.key(); }

    public String optionKey(int i) { return "art.zhushenspace." + name().toLowerCase(java.util.Locale.ROOT) + ".opt." + options[i]; }

    public String researchKey(int i) { return "art.zhushenspace." + name().toLowerCase(java.util.Locale.ROOT) + ".res." + researches[i].key(); }

    public static ArtSkill of(SkillAbility a) {
        for (ArtSkill s : values()) if (s.ability == a) return s;
        return null;
    }

    /** 分类（能量池）顺序：灵力 精神力 灵能 妖力 佛法 魔法 道术 内力 查克拉 */
    public static final FeatEffects.Pool[] CATEGORIES = {
            FeatEffects.Pool.SPIRIT, FeatEffects.Pool.MIND, FeatEffects.Pool.PSYCHIC, FeatEffects.Pool.YOKAI,
            FeatEffects.Pool.BUDDHA, FeatEffects.Pool.MAGIC, FeatEffects.Pool.DAO, FeatEffects.Pool.NEILI,
            FeatEffects.Pool.CHAKRA};

    public static java.util.List<ArtSkill> inCategory(FeatEffects.Pool p) {
        java.util.List<ArtSkill> l = new java.util.ArrayList<>();
        for (ArtSkill s : values()) if (s.pool == p) l.add(s);
        return l;
    }
}
