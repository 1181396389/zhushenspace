package com.zhushen.space.entity.dismember;

/**
 * T 病毒丧尸的身体部位（测试功能：部位碰撞箱 + 部位血条 + 断肢）。
 * <p>
 * 几何参数以「未缩放的成年体」为准（单位：格），运行时按实体缩放（幼体 / scale 属性）等比缩放：
 * <ul>
 *   <li>lateral：沿实体右侧方向的偏移（正 = 右侧）；forward：沿面朝方向的偏移；bottom：碰撞箱底部离脚底的高度</li>
 *   <li>丧尸双臂前伸，所以手臂碰撞箱位于肩高并向前偏移</li>
 * </ul>
 * 血量：hpFraction × 最大生命值（仅四肢）；头部与躯干不单独计量，直接使用怪物本体生命值。
 * 命中四肢时本体只承受 {@link #mainDamageFactor} 比例的伤害，部位血量承受全额伤害。
 * <p>
 * 断肢模型参数：modelPart 为原版僵尸模型（ModelLayers.ZOMBIE）的子部件名，
 * cx/cy/cz 为该部件方块在部件局部坐标系中的中心（像素），thickness 为横躺时的厚度（格）。
 */
public enum BodyPart {
    //        宽     高     右偏    前偏   底高   血量比例 本体伤害比例 模型部件      cx  cy  cz  厚度
    HEAD     (0.55f, 0.55f, 0f,     0f,    1.45f, 0.00f, 1.0f, "head",      0, -4, 0, 0.25f),
    TORSO    (0.55f, 0.75f, 0f,     0f,    0.72f, 1.00f, 1.0f, "body",      0, 6, 0, 0.125f),
    RIGHT_ARM(0.34f, 0.34f, 0.33f,  0.38f, 1.20f, 0.25f, 0.5f, "right_arm", -1, 4, 0, 0.125f),
    LEFT_ARM (0.34f, 0.34f, -0.33f, 0.38f, 1.20f, 0.25f, 0.5f, "left_arm",  1, 4, 0, 0.125f),
    RIGHT_LEG(0.30f, 0.74f, 0.13f,  0f,    0f,    0.30f, 0.5f, "right_leg", 0, 6, 0, 0.125f),
    LEFT_LEG (0.30f, 0.74f, -0.13f, 0f,    0f,    0.30f, 0.5f, "left_leg",  0, 6, 0, 0.125f);

    /** 双腿都断后，上半身整体下沉的高度（腿长） */
    public static final float LEG_DROP = 0.75f;

    public final float width, height, lateral, forward, bottom, hpFraction, mainDamageFactor;
    public final String modelPart;
    public final float cx, cy, cz, thickness;

    BodyPart(float width, float height, float lateral, float forward, float bottom,
             float hpFraction, float mainDamageFactor, String modelPart,
             float cx, float cy, float cz, float thickness) {
        this.width = width;
        this.height = height;
        this.lateral = lateral;
        this.forward = forward;
        this.bottom = bottom;
        this.hpFraction = hpFraction;
        this.mainDamageFactor = mainDamageFactor;
        this.modelPart = modelPart;
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.thickness = thickness;
    }

    /** 断肢位掩码 */
    public int bit() {
        return 1 << ordinal();
    }

    /** 是否可断（躯干不可断） */
    public boolean severable() {
        return this != TORSO;
    }

    /**
     * 是否拥有独立的部位血量。
     * 头部不单独计血：命中头部只按爆头结算本体生命值，本体被头部命中击杀时才会断头（终结演出），
     * 避免「部位血量 < 本体血量」导致爆头几枪即死。
     */
    public boolean hasOwnPool() {
        return isArm() || isLeg();
    }

    public boolean isLeg() {
        return this == RIGHT_LEG || this == LEFT_LEG;
    }

    public boolean isArm() {
        return this == RIGHT_ARM || this == LEFT_ARM;
    }

    /** 断口（流血点）相对碰撞箱底部的高度比例：腿在顶端（髋部），手臂在中部（肩），头在底部（颈） */
    public float stumpHeightRatio() {
        return isLeg() ? 1.0f : this == HEAD ? 0.0f : 0.5f;
    }

    private static final BodyPart[] VALUES = values();

    public static BodyPart byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : TORSO;
    }
}
