package com.zhushen.space.data;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能点达到门槛后解锁的主动技能（可拖入战斗预设九宫格，按键 1~9 使用）。
 *
 * 能力按 gate（解锁途径）分类：
 * - owner != null：技能点达到门槛解锁
 * - GATE_NEILI：获得内力池时自动解锁
 * - GATE_TAI_CHI：解锁太极拳流派后自动解锁（每式消耗 2 点内力）
 */
public enum SkillAbility {
    /** 自我保护：极短时间内护甲增加（敏捷+运动）点数，冷却 30 秒 */
    SELF_PROTECTION(SkillType.ATHLETICS, 3, 30 * 20, null, "self_protection"),
    /** 跳跃：短时间内提升（力量+运动）的跳跃力，仅维持一次跳跃 */
    LEAP(SkillType.ATHLETICS, 4, 8 * 20, null, "leap"),
    /** 攀爬：可爬升（力量+运动）点数的高度 */
    CLIMB(SkillType.ATHLETICS, 5, 15 * 20, null, "climb"),
    /** 肉搏格挡：短时间内增加相当于肉搏点数的护甲 */
    BRAWL_BLOCK(SkillType.BRAWL, 3, 20 * 20, null, "brawl_block"),
    /** 摔绊：命中使目标倒地，飞行中的目标直接坠落 */
    TRIP(SkillType.BRAWL, 4, 12 * 20, null, "trip"),
    /** 冲锋攻击：每移动 1 格 +1 攻击伤害，命中时消耗并暂时失去等量基础防御 */
    CHARGE_ATTACK(SkillType.BRAWL, 5, 30 * 20, null, "charge_attack"),
    /** 白刃格挡：短时间内增加相当于白刃点数的护甲 */
    BLADE_BLOCK(SkillType.BLADE, 3, 20 * 20, null, "blade_block"),
    /** 内力吐息（自动档）：开启后近战攻击（含普攻）消耗 1 点内力，+6 伤害；获得内力池自动解锁 */
    NEILI_BREATH(null, 0, 0, "neili", "neili_breath"),
    /** 打坐：禁步 5 秒后回满内力（耐力+感知），冷却 5 分钟；获得内力池自动解锁 */
    NEILI_MEDITATE(null, 0, 300 * 20, "neili", "neili_meditate"),
    /** 掤：肉搏攻击 +27 招式伤害 */
    WARD_OFF(null, 0, 20, "tai_chi", "tc_ward_off"),
    /** 捋：命中后额外 +21 伤害 */
    ROLL_BACK(null, 0, 20, "tai_chi", "tc_roll_back"),
    /** 挤：+6 伤害，无视最多 6 点伤害吸收（破魔） */
    PRESS(null, 0, 20, "tai_chi", "tc_press"),
    /** 按：肉搏攻击 +9 伤害 */
    PUSH(null, 0, 20, "tai_chi", "tc_push"),
    /** 采：命中后额外造成 3 点严重伤害（削减最大生命值） */
    PULL(null, 0, 20, "tai_chi", "tc_pull"),
    /** 挒：命中后压制目标（高速优势：虚弱 II + 缓慢 II，5 秒） */
    SPLIT(null, 0, 20, "tai_chi", "tc_split"),
    /** 肘：+6 破甲（按目标护甲比例转化为额外伤害） */
    ELBOW(null, 0, 20, "tai_chi", "tc_elbow"),
    /** 靠：命中后按伤害击退目标，撞墙则受到钝击严重伤害 */
    SHOULDER(null, 0, 20, "tai_chi", "tc_shoulder"),
    /** 听劲：10 秒内自身攻击 -20%、受到伤害 -20%（以柔化刚） */
    TING_JIN(null, 0, 200, "tai_chi", "tc_tingjin"),
    /** 八劲合一（被动）：习得八劲后可购——徒手普攻伤害＞6 时附带八种劲力各一次，八式能耗降为 1 点 */
    EIGHT_POWERS(null, 0, 0, "tai_chi", "tc_eight_powers"),
    /** 引手：进入 1 分钟引手姿态，期间受击使攻击者叠加减值层（每层攻击 -3 / 防御 -1，上限=肉搏等级） */
    YIN_SHOU(null, 0, 1200, "tai_chi", "tc_yinshou"),
    /** 揽雀尾：待势 5 秒，期间首次受到近战攻击时攻击力对拼，胜利则缴械并使该次攻击落空（结算耗 3 内力、冷却 10 秒） */
    LAN_QUE_WEI(null, 0, 100, "tai_chi", "tc_lanquewei"),
    /** 云手：待势 5 秒，期间首次受到近战攻击时擒抱对抗，胜利则擒抱目标并使该次攻击伤害减少对抗差值（结算耗 3 内力、冷却 10 秒） */
    CLOUD_HANDS(null, 0, 100, "tai_chi", "tc_cloudhands"),
    /** 海底针：待势 5 秒，期间受到近战攻击或身边目标主动移动时摔绊对抗，胜利则使攻击伤害减少差值/2 或使其失去移动力（结算耗 3 内力、冷却 10 秒） */
    SEA_BOTTOM_NEEDLE(null, 0, 100, "tai_chi", "tc_seabottom"),
    /** 缠丝劲（被动）：内力池不空时，缴械/擒抱/摔绊对抗获得 +8 加值 */
    COILING_SILK(null, 0, 0, "tai_chi", "tc_coilingsilk"),
    /** 太极化劲：待势 5 秒，期间受到使用能量池的近战攻击时反击肉搏，命中后可连锁揽雀尾/云手/海底针，成功则封印其能量池 1 分钟（结算耗 5 内力、冷却 20 秒） */
    TAI_CHI_DISSOLVE(null, 0, 100, "tai_chi", "tc_dissolve");

    public static final String GATE_NEILI = "neili";
    public static final String GATE_TAI_CHI = "tai_chi";

    public static final int COUNT = values().length;
    /** 冲锋攻击伤害加成上限（格） */
    public static final int CHARGE_DAMAGE_CAP = 15;
    /** 临时护甲/跳跃增益持续时长（tick） */
    public static final int BUFF_DURATION_TICKS = 5 * 20;
    /** 攀爬最长时间（tick） */
    public static final int CLIMB_DURATION_TICKS = 8 * 20;
    /** 摔绊待触发窗口（tick） */
    public static final int TRIP_WINDOW_TICKS = 8 * 20;
    /** 冲锋攻击开启窗口（tick） */
    public static final int CHARGE_WINDOW_TICKS = 10 * 20;
    /** 冲锋防御削弱持续（tick） */
    public static final int CHARGE_DEBUFF_TICKS = 3 * 20;
    /** 听劲持续时长（tick）：自身攻击 -20%、受伤 -20% */
    public static final int TINGJIN_DURATION_TICKS = 10 * 20;
    /** 引手姿态持续时长（tick）：期间受击自动为攻击者叠加减值 */
    public static final int YINSHOU_DURATION_TICKS = 60 * 20;
    /** 引手单层减值持续时长（tick）：重新获得减值则刷新 */
    public static final int YINSHOU_LAYER_TICKS = 60 * 20;
    /** 揽雀尾待势窗口（tick）：期间首次受到近战攻击时自动结算 */
    public static final int LANQUEWEI_WINDOW_TICKS = 5 * 20;
    /** 揽雀尾结算后的真实冷却（tick） */
    public static final int LANQUEWEI_COOLDOWN_TICKS = 10 * 20;
    /** 云手待势窗口（tick） */
    public static final int CLOUD_HANDS_WINDOW_TICKS = 5 * 20;
    /** 云手结算后的真实冷却（tick） */
    public static final int CLOUD_HANDS_COOLDOWN_TICKS = 10 * 20;
    /** 海底针待势窗口（tick） */
    public static final int SEA_BOTTOM_WINDOW_TICKS = 5 * 20;
    /** 海底针结算后的真实冷却（tick） */
    public static final int SEA_BOTTOM_COOLDOWN_TICKS = 10 * 20;
    /** 太极化劲待势窗口（tick） */
    public static final int DISSOLVE_WINDOW_TICKS = 5 * 20;
    /** 太极化劲结算后的真实冷却（tick） */
    public static final int DISSOLVE_COOLDOWN_TICKS = 20 * 20;
    /** 缠丝劲：对抗加值 */
    public static final int COILING_SILK_BONUS = 8;
    /** 太极化劲：能量池封印持续时长（tick） */
    public static final int DISSOLVE_SEAL_TICKS = 60 * 20;

    /** null = 非技能点解锁的能力（由 gate 指定解锁途径） */
    private final SkillType owner;
    private final int requiredLevel;
    private final int cooldownTicks;
    /** 解锁途径：null=技能点 / GATE_NEILI / GATE_TAI_CHI */
    private final String gate;
    private final String key;

    SkillAbility(SkillType owner, int requiredLevel, int cooldownTicks, String gate, String key) {
        this.owner = owner;
        this.requiredLevel = requiredLevel;
        this.cooldownTicks = cooldownTicks;
        this.gate = gate;
        this.key = key;
    }

    /** 所属技能（技能点解锁的能力为 null 之外的值，其余为 null） */
    public SkillType owner() {
        return owner;
    }

    /** 是否为内力系能力（获得内力池时自动解锁） */
    public boolean isNeiliAbility() {
        return GATE_NEILI.equals(gate);
    }

    /** 是否为流派系能力（解锁对应流派后自动解锁） */
    public boolean isSchoolAbility() {
        return GATE_TAI_CHI.equals(gate);
    }

    public String gate() {
        return gate;
    }

    /** 解锁所需技能点数 */
    public int requiredLevel() {
        return requiredLevel;
    }

    /** 冷却时长（tick） */
    public int cooldownTicks() {
        return cooldownTicks;
    }

    public String key() {
        return key;
    }

    public String nameKey() {
        return "ability.zhushenspace." + key;
    }

    /** 预设格子/HUD 中显示的短标签 */
    public String shortKey() {
        return "ability.zhushenspace." + key + ".short";
    }

    public String descKey() {
        return "ability.zhushenspace." + key + ".desc";
    }

    /** 技能图标纹理（32×32，textures/gui/skills/&lt;key&gt;.png） */
    public ResourceLocation iconTexture() {
        return ResourceLocation.fromNamespaceAndPath("zhushenspace", "textures/gui/skills/" + key + ".png");
    }

    /** 指定技能点数组下已解锁的主动技能（不含内力系/流派系） */
    public static List<SkillAbility> unlocked(int[] skillPoints) {
        return unlocked(skillPoints, false, false);
    }

    /** 已解锁的主动技能：技能点达标 + 内力系（拥有内力池） */
    public static List<SkillAbility> unlocked(int[] skillPoints, boolean hasNeiliPool) {
        return unlocked(skillPoints, hasNeiliPool, false);
    }

    /**
     * 已解锁的主动技能：技能点达到门槛的能力 + 内力系能力（拥有内力池时）
     * + 流派系能力（解锁对应流派时）。
     */
    public static List<SkillAbility> unlocked(int[] skillPoints, boolean hasNeiliPool,
                                              boolean taiChiUnlocked) {
        List<SkillAbility> result = new ArrayList<>();
        for (SkillAbility ability : values()) {
            if (ability.isNeiliAbility()) {
                if (hasNeiliPool) result.add(ability);
            } else if (ability.isSchoolAbility()) {
                if (taiChiUnlocked) result.add(ability);
            } else if (skillPoints[ability.owner.ordinal()] >= ability.requiredLevel) {
                result.add(ability);
            }
        }
        return result;
    }

    /** 校验槽位预设：每个位置 -1（空）或合法能力序号，且不重复 */
    public static boolean isValidSlots(int[] slots) {
        if (slots == null || slots.length != 9) return false;
        boolean[] seen = new boolean[COUNT];
        for (int s : slots) {
            if (s == -1) continue;
            if (s < 0 || s >= COUNT || seen[s]) return false;
            seen[s] = true;
        }
        return true;
    }
}
