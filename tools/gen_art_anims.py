"""生成全部 23 个技艺（5 个弹体技艺 + 14 个非弹体技艺 + 4 个魔法专业法术）的 KosmX/GeckoLib 动作文件 art.json。

约定与 tools/gen_taiji_anims.py 一致：
  手臂/腿 x 负值 = 向前抬起；右臂 y 正 = 向内、z 正 = 向外；左臂镜像。
  躯干 torso x 正 = 前倾、y 正 = 向右转腰；body 位置 y 负 = 下沉（1/16 格）；*_arm_bend = 肘弯。
动漫式节奏：预备（反向蓄势）→ 极快的出招（easeOutExpo）→ 定格（残心）→ 缓收。
出招帧与服务端弹体离手延迟对齐（ArtBallistics.*_DELAY）：
  灵斩 4 tick = 0.2s；风斩 5 tick = 0.25s；波动拳 2 tick = 0.1s；豪火球 3 tick = 0.15s；八阵图即时。
运行：python3 tools/gen_art_anims.py
"""
import json, math, os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                   "src/main/resources/assets/zhushenspace/player_animations")
Z = [0, 0, 0]
EXPO, BACK, QUART, CUBIC, SINE, LIN = "easeOutExpo", "easeOutBack", "easeOutQuart", "easeOutCubic", "easeInOutSine", "linear"
ROT = ["right_arm", "left_arm", "torso", "head", "left_leg", "right_leg"]
BEND = ["right_arm_bend", "left_arm_bend"]
A = {}


def key(t):
    return f"{t:.3f}".rstrip("0").rstrip(".") if t else "0.0"


def P(base=None, **kw):
    """姿势：未指定的骨骼取 base（默认站姿）"""
    pose = {b: Z for b in ROT}
    pose.update({b: [0, 0] for b in BEND})
    pose["body"] = Z
    if base:
        pose.update(base)
    pose.update(kw)
    return pose


def seq(name, length, keys):
    """keys: [(时间, 姿势, 缓动)]，所有骨骼共用同一组关键帧（姿势连贯、不漂移）"""
    bones = {}
    for b in ROT + BEND + ["body"]:
        ch = "position" if b == "body" else "rotation"
        frames = {}
        for t, pose, ease in keys:
            frames[key(t)] = {"vector": pose[b], "easing": ease}
        bones[b] = {ch: frames}
    A[name] = {"loop": False, "animation_length": length, "bones": bones}


def add(v, d):
    return [a + b for a, b in zip(v, d)]


NEUTRAL = P()

# ===== 灵斩：单手举刀过顶 → 一刀竖劈（刀光竖立飞出），左手剑指护胸 → 甩向身后 =====
SPIRIT_READY = P(
    right_arm=[-168, 8, 6], left_arm=[-62, -38, -4], torso=[-10, 4, 0], head=[-6, 0, 0],
    body=[0, -0.5, 0], left_leg=[-10, 0, -3], right_leg=[6, 0, 3],
    right_arm_bend=[40, 0], left_arm_bend=[70, 0])
SPIRIT_CUT = P(
    right_arm=[-52, 6, 0], left_arm=[24, 0, -26], torso=[24, 4, 0], head=[-18, 0, 0],
    body=[0, -4, 0], left_leg=[-34, 0, -6], right_leg=[24, 0, 8],
    right_arm_bend=[0, 0], left_arm_bend=[10, 0])
SPIRIT_HOLD = P(SPIRIT_CUT, right_arm=[-48, 6, 0], torso=[26, 4, 0])
seq("art_spirit_slash", 0.9, [
    (0, NEUTRAL, LIN),
    (0.13, SPIRIT_READY, BACK),        # 举刀过顶蓄势
    (0.19, SPIRIT_CUT, EXPO),          # 竖劈（刀光在 0.2s 离手）
    (0.5, SPIRIT_HOLD, LIN),           # 残心
    (0.9, NEUTRAL, SINE),
])

# ===== 风斩：双手举刀过右肩 → 斜劈至左下 =====
WIND_UP = P(
    right_arm=[-165, -8, 18], left_arm=[-150, -38, 8], torso=[-12, 18, 4], head=[-8, -10, 0],
    body=[0, -0.5, 0], left_leg=[-10, 0, -4], right_leg=[8, 0, 4],
    right_arm_bend=[30, 0], left_arm_bend=[40, 0])
WIND_CUT = P(
    right_arm=[-38, 58, -18], left_arm=[-26, 4, -30], torso=[26, -30, -6], head=[-14, 18, 0],
    body=[0, -4.5, 0], left_leg=[-34, 0, -8], right_leg=[24, 0, 10])
WIND_HOLD = P(WIND_CUT, torso=[28, -34, -6], right_arm=[-34, 62, -18])
seq("art_wind_slash", 1.0, [
    (0, NEUTRAL, LIN),
    (0.17, WIND_UP, BACK),             # 高举蓄势（略带回弹）
    (0.24, WIND_CUT, EXPO),            # 劈下（风刃 0.25s 离手）
    (0.58, WIND_HOLD, LIN),
    (1.0, NEUTRAL, SINE),
])

# ===== 波动拳 =====
# 蓄力：沉腰、双掌合于右腰后侧，身体微微颤抖（气在聚集）
WAVE_CHARGE = P(
    right_arm=[28, 22, 16], left_arm=[-28, -72, 8], torso=[8, 30, 0], head=[-4, -26, 0],
    body=[0, -3.5, 0], left_leg=[-26, 0, -8], right_leg=[18, 0, 10],
    right_arm_bend=[45, 0], left_arm_bend=[55, 0])


def tremble(pose, i, amp):
    """细小颤抖：手臂与躯干交替偏移"""
    s = 1 if i % 2 == 0 else -1
    j = math.sin(i * 1.7) * 0.5 + 0.5
    return P(pose,
             right_arm=add(pose["right_arm"], [s * amp * (0.6 + 0.4 * j), 0, s * amp * 0.5]),
             left_arm=add(pose["left_arm"], [-s * amp * (0.6 + 0.4 * j), 0, -s * amp * 0.5]),
             torso=add(pose["torso"], [0, 0, s * amp * 0.35]),
             body=add(pose["body"], [0, -0.15 * (1 + s) * amp / 2, 0]))


keys = [(0, NEUTRAL, LIN), (0.2, WAVE_CHARGE, BACK)]
t, i = 0.5, 0
while t < 10.4:
    amp = 0.6 + 2.4 * min(1.0, (t - 0.5) / 1.5)   # 2 秒满蓄后颤抖最强
    keys.append((round(t, 3), tremble(WAVE_CHARGE, i, amp), SINE))
    t += 0.1
    i += 1
keys += [(10.5, WAVE_CHARGE, SINE), (11, NEUTRAL, SINE)]
seq("art_charge_wave", 11, keys)

# 释放：从蓄力姿势直接双掌前推（光弹在 0.1s 离手），推出后略回弹定格
WAVE_PUSH = P(
    right_arm=[-94, 12, 0], left_arm=[-94, -12, 0], torso=[14, -6, 0], head=[-12, 4, 0],
    body=[0, -4.5, 0], left_leg=[-36, 0, -8], right_leg=[26, 0, 10],
    right_arm_bend=[0, 0], left_arm_bend=[0, 0])
WAVE_RECOIL = P(WAVE_PUSH, torso=[9, -4, 0], right_arm=[-90, 14, 0], left_arm=[-90, -14, 0], body=[0, -4, 0])
seq("art_hadoken", 0.95, [
    (0, WAVE_CHARGE, LIN),
    (0.08, WAVE_PUSH, EXPO),
    (0.16, WAVE_RECOIL, QUART),
    (0.6, P(WAVE_RECOIL, torso=[8, -4, 0]), LIN),
    (0.95, NEUTRAL, SINE),
])

# ===== 八阵图 =====
# 蓄力：右手剑指立于面前，左手掐诀于腹前，双脚开立，缓慢吐纳
SEAL_CHARGE = P(
    right_arm=[-118, 34, 2], left_arm=[-48, -46, -4], torso=[-3, 6, 0], head=[-2, -4, 0],
    body=[0, -1.5, 0], left_leg=[-6, 0, -12], right_leg=[4, 0, 12],
    right_arm_bend=[70, 0], left_arm_bend=[60, 0])
keys = [(0, NEUTRAL, LIN), (0.1, P(SEAL_CHARGE, right_arm=[-140, 20, 8], torso=[-6, 0, 0]), CUBIC),
        (0.24, SEAL_CHARGE, BACK)]
t = 0.24
while t + 1.0 < 10.5:
    t += 1.0
    up = (round((t - 0.24) / 1.0)) % 2 == 1
    d = 1 if up else -1
    keys.append((round(t, 3), P(SEAL_CHARGE, torso=add(SEAL_CHARGE["torso"], [-1.5 * d, 0, 0]),
                                 body=add(SEAL_CHARGE["body"], [0, 0.4 * d, 0]),
                                 right_arm=add(SEAL_CHARGE["right_arm"], [-3 * d, 0, 0])), SINE))
keys += [(10.5, SEAL_CHARGE, SINE), (11, NEUTRAL, SINE)]
seq("art_charge_seal", 11, keys)

# 释放：剑指向前一点，左掌外展，阵中激光射出
SEAL_THRUST = P(
    right_arm=[-94, 4, 0], left_arm=[-36, 0, -58], torso=[10, -8, 0], head=[-8, 6, 0],
    body=[0, -2.5, 0], left_leg=[-22, 0, -8], right_leg=[14, 0, 8],
    right_arm_bend=[0, 0], left_arm_bend=[10, 0])
seq("art_formation", 0.95, [
    (0, SEAL_CHARGE, LIN),
    (0.08, SEAL_THRUST, BACK),
    (0.62, P(SEAL_THRUST, torso=[8, -8, 0]), LIN),
    (0.95, NEUTRAL, SINE),
])

# ===== 豪火球 =====
# 蓄力：0.6s 内快速结印（巳→未→申→亥→午→寅），随后寅印置于口前、后仰吸气
def hands(r, l, rb, lb, **kw):
    return P(right_arm=r, left_arm=l, right_arm_bend=[rb, 0], left_arm_bend=[lb, 0], **kw)


SEALS = [
    (0.08, hands([-72, 46, 6], [-72, -46, -6], 70, 70, body=[0, -1, 0])),
    (0.18, hands([-96, 52, 10], [-80, -40, -4], 85, 60, body=[0, -1, 0])),
    (0.28, hands([-82, 40, 2], [-96, -52, -10], 60, 85, body=[0, -1, 0])),
    (0.38, hands([-100, 56, 12], [-100, -56, -12], 90, 90, body=[0, -1, 0])),
    (0.48, hands([-86, 44, 4], [-86, -44, -4], 75, 75, body=[0, -1, 0])),
]
FIRE_INHALE = hands([-112, 40, 8], [-112, -40, -8], 80, 80,
                    torso=[-12, 0, 0], head=[-14, 0, 0], body=[0, 0.5, 0], left_leg=[-8, 0, -4], right_leg=[6, 0, 4])
FIRE_DEEP = P(FIRE_INHALE, torso=[-17, 0, 0], head=[-18, 0, 0], body=[0, 0.8, 0])
keys = [(0, NEUTRAL, LIN)] + [(t, p, QUART) for t, p in SEALS] + [(0.6, FIRE_INHALE, BACK), (2.0, FIRE_DEEP, SINE)]
t, i = 2.0, 0
while t + 0.5 < 10.5:
    t += 0.5
    i += 1
    keys.append((round(t, 3), P(FIRE_DEEP, torso=add(FIRE_DEEP["torso"], [0.8 if i % 2 else -0.8, 0, 0])), SINE))
keys += [(10.5, FIRE_DEEP, SINE), (11, NEUTRAL, SINE)]
seq("art_charge_fire", 11, keys)

# 释放：猛然前倾喷吐（火球 0.15s 离手），右手保持印于口前，左手后撤
FIRE_BLOW = hands([-118, 44, 8], [24, 0, -26], 80, 10,
                  torso=[24, 0, 0], head=[-20, 0, 0], body=[0, -2.5, 0], left_leg=[-26, 0, -6], right_leg=[18, 0, 6])
seq("art_fireball", 0.85, [
    (0, FIRE_DEEP, LIN),
    (0.04, P(FIRE_DEEP, torso=[-20, 0, 0], body=[0, 1, 0]), QUART),  # 最后一口吸气
    (0.13, FIRE_BLOW, EXPO),
    (0.55, P(FIRE_BLOW, torso=[21, 0, 0]), LIN),
    (0.85, NEUTRAL, SINE),
])


# =====================================================================
# 非弹体技艺（v3.2）：每个技艺一套独立动作；出手帧与服务端特效生成延迟对齐（ArtManager 中的 later(...)）
# =====================================================================

def shake(pose, t0, t1, step, amp, bones=("right_arm", "left_arm")):
    """在 [t0, t1] 内按 step 生成细小颤抖关键帧"""
    out, t, i = [], t0, 0
    while t <= t1 + 1e-6:
        s = 1 if i % 2 == 0 else -1
        kw = {b: add(pose[b], [s * amp, 0, s * amp * 0.5]) for b in bones}
        out.append((round(t, 3), P(pose, **kw), SINE))
        t += step
        i += 1
    return out


# 灵力治疗（特效 4 tick = 0.2s）：双掌收于胸前 → 柔和地向前送出，掌心朝前
HEAL_GATHER = P(right_arm=[-58, 34, 0], left_arm=[-58, -34, 0], torso=[-4, 0, 0], head=[-4, 0, 0],
                right_arm_bend=[85, 0], left_arm_bend=[85, 0], body=[0, -0.5, 0])
HEAL_GIVE = P(right_arm=[-86, 12, 4], left_arm=[-86, -12, -4], torso=[6, 0, 0], head=[-6, 0, 0],
              right_arm_bend=[18, 0], left_arm_bend=[18, 0], body=[0, -1, 0], left_leg=[-8, 0, -3], right_leg=[5, 0, 3])
seq("art_spirit_heal", 1.4, [
    (0, NEUTRAL, LIN), (0.12, HEAL_GATHER, CUBIC), (0.22, HEAL_GIVE, BACK),
    (0.6, P(HEAL_GIVE, torso=[4, 0, 0], body=[0, -0.5, 0]), SINE), (0.95, HEAL_GIVE, SINE), (1.4, NEUTRAL, SINE),
])

# 精神冲击（3 tick）：剑指抵住太阳穴 → 头部猛然前送，左手向外甩开
MB_FOCUS = P(right_arm=[-138, 42, 30], right_arm_bend=[120, 0], left_arm=[-20, 0, -10], head=[-6, -8, 0], torso=[-4, 4, 0])
MB_SEND = P(MB_FOCUS, head=[12, 0, 0], torso=[10, -4, 0], left_arm=[-30, 0, -55], left_arm_bend=[5, 0], body=[0, -1.5, 0],
            left_leg=[-14, 0, -4], right_leg=[10, 0, 4])
seq("art_mind_blast", 0.95, [
    (0, NEUTRAL, LIN), (0.1, MB_FOCUS, CUBIC), (0.15, MB_SEND, EXPO), (0.55, P(MB_SEND, head=[9, 0, 0]), LIN), (0.95, NEUTRAL, SINE),
])

# 精神震荡（5 tick）：双手抱头仰面蓄念 → 双臂猛然向两侧张开
MS_HOLD = P(right_arm=[-150, 36, 26], left_arm=[-150, -36, -26], right_arm_bend=[112, 0], left_arm_bend=[112, 0],
            torso=[-9, 0, 0], head=[-14, 0, 0], body=[0, -1, 0])
MS_BURST = P(right_arm=[-82, 0, 62], left_arm=[-82, 0, -62], right_arm_bend=[8, 0], left_arm_bend=[8, 0],
             torso=[13, 0, 0], head=[8, 0, 0], body=[0, -3, 0], left_leg=[-10, 0, -10], right_leg=[10, 0, 10])
seq("art_mind_shock", 1.1, [(0, NEUTRAL, LIN), (0.16, MS_HOLD, BACK)] + shake(MS_HOLD, 0.18, 0.22, 0.02, 2.0) + [
    (0.25, MS_BURST, EXPO), (0.7, P(MS_BURST, torso=[10, 0, 0]), LIN), (1.1, NEUTRAL, SINE),
])

# 息法（8 tick）：双掌合十，深吸（身体上提、后仰）→ 长呼（下沉）
BR_PRAY = P(right_arm=[-52, 38, 0], left_arm=[-52, -38, 0], right_arm_bend=[96, 0], left_arm_bend=[96, 0], head=[4, 0, 0])
BR_IN = P(BR_PRAY, torso=[-5, 0, 0], head=[-6, 0, 0], body=[0, 0.6, 0])
BR_OUT = P(BR_PRAY, torso=[4, 0, 0], head=[8, 0, 0], body=[0, -1.6, 0])
seq("art_breath", 2.0, [
    (0, NEUTRAL, LIN), (0.18, BR_PRAY, CUBIC), (0.75, BR_IN, SINE), (1.45, BR_OUT, SINE), (2.0, NEUTRAL, SINE),
])

# 夜叉空行（6 tick）：屈膝蓄势 → 腾身而起，双臂向后展开如翼
YK_CROUCH = P(right_arm=[-28, 10, 8], left_arm=[-28, -10, -8], torso=[20, 0, 0], head=[6, 0, 0], body=[0, -5, 0],
              left_leg=[-24, 0, -4], right_leg=[-24, 0, 4])
YK_RISE = P(right_arm=[38, 0, 58], left_arm=[38, 0, -58], right_arm_bend=[10, 0], left_arm_bend=[10, 0],
            torso=[-12, 0, 0], head=[-16, 0, 0], body=[0, 0.8, 0], left_leg=[-6, 0, -3], right_leg=[12, 0, 3])
seq("art_yaksha", 1.3, [
    (0, NEUTRAL, LIN), (0.15, YK_CROUCH, CUBIC), (0.3, YK_RISE, EXPO), (0.85, P(YK_RISE, right_arm=[30, 0, 62], left_arm=[30, 0, -62]), SINE),
    (1.3, NEUTRAL, SINE),
])

# 魔能爆（3 tick）：右掌后引、拧腰 → 单掌推出，左手扣住右腕稳住后坐
MG_COCK = P(right_arm=[-70, 22, 12], right_arm_bend=[92, 0], left_arm=[-60, -40, 0], left_arm_bend=[80, 0],
            torso=[0, 16, 0], head=[0, -10, 0], body=[0, -1, 0], left_leg=[-12, 0, -6], right_leg=[8, 0, 6])
MG_PUSH = P(right_arm=[-93, 4, 0], right_arm_bend=[0, 0], left_arm=[-80, -34, 0], left_arm_bend=[40, 0],
            torso=[8, -10, 0], head=[-8, 6, 0], body=[0, -2.5, 0], left_leg=[-24, 0, -8], right_leg=[16, 0, 8])
seq("art_magic_burst", 1.0, [
    (0, NEUTRAL, LIN), (0.1, MG_COCK, BACK), (0.15, MG_PUSH, EXPO), (0.22, P(MG_PUSH, torso=[4, -8, 0], right_arm=[-98, 4, 0]), QUART),
    (0.6, MG_PUSH, LIN), (1.0, NEUTRAL, SINE),
])

# 初级防护（5 tick）：双臂交叉护胸 → 向上向外撑开结界
WD_CROSS = P(right_arm=[-72, 52, 0], left_arm=[-72, -52, 0], right_arm_bend=[92, 0], left_arm_bend=[92, 0],
             torso=[6, 0, 0], head=[6, 0, 0], body=[0, -2, 0])
WD_OPEN = P(right_arm=[-104, 0, 46], left_arm=[-104, 0, -46], right_arm_bend=[10, 0], left_arm_bend=[10, 0],
            torso=[-6, 0, 0], head=[-8, 0, 0], body=[0, -0.5, 0], left_leg=[-6, 0, -8], right_leg=[6, 0, 8])
seq("art_minor_ward", 1.2, [
    (0, NEUTRAL, LIN), (0.15, WD_CROSS, CUBIC), (0.25, WD_OPEN, EXPO), (0.8, P(WD_OPEN, torso=[-4, 0, 0]), LIN), (1.2, NEUTRAL, SINE),
])

# 五行道法（4 tick）：右手夹符举过肩后、左手剑指护胸 → 甩腕掷符
FE_COCK = P(right_arm=[-160, 0, 22], right_arm_bend=[60, 0], left_arm=[-70, -46, 0], left_arm_bend=[110, 0],
            torso=[-8, 20, 0], head=[-4, -14, 0], body=[0, -0.5, 0], left_leg=[-10, 0, -4], right_leg=[8, 0, 4])
FE_THROW = P(right_arm=[-84, 14, 0], right_arm_bend=[0, 0], left_arm=[-70, -46, 0], left_arm_bend=[110, 0],
             torso=[14, -16, 0], head=[-10, 10, 0], body=[0, -2, 0], left_leg=[-26, 0, -6], right_leg=[18, 0, 6])
seq("art_five_elements", 1.0, [
    (0, NEUTRAL, LIN), (0.13, FE_COCK, BACK), (0.2, FE_THROW, EXPO), (0.6, P(FE_THROW, torso=[11, -14, 0]), LIN), (1.0, NEUTRAL, SINE),
])

# 无视我（8 tick）：食指抵唇「嘘」→ 后撤半步，身形消散
IG_SHH = P(right_arm=[-118, 56, 10], right_arm_bend=[128, 0], head=[5, 0, 0], torso=[3, 0, 0])
IG_STEP = P(IG_SHH, torso=[-7, 0, 0], head=[-2, 0, 0], left_leg=[22, 0, -2], right_leg=[-6, 0, 2], body=[0, -1, 0])
seq("art_ignore_me", 0.9, [
    (0, NEUTRAL, LIN), (0.18, IG_SHH, CUBIC), (0.32, IG_SHH, LIN), (0.42, IG_STEP, QUART), (0.9, NEUTRAL, SINE),
])

# 生物闪电（3 tick）：右手回收 → 五指张开向前，左手抓住右前臂，电流冲击下手臂颤抖
BL_PULL = P(right_arm=[-60, 30, 0], right_arm_bend=[90, 0], left_arm=[-40, -30, 0], left_arm_bend=[60, 0], torso=[0, 10, 0], body=[0, -1, 0])
BL_SHOOT = P(right_arm=[-92, 0, 0], right_arm_bend=[0, 0], left_arm=[-76, -40, 0], left_arm_bend=[58, 0],
             torso=[6, -8, 0], head=[-6, 4, 0], body=[0, -2, 0], left_leg=[-18, 0, -6], right_leg=[12, 0, 6])
seq("art_bio_lightning", 1.0, [(0, NEUTRAL, LIN), (0.1, BL_PULL, CUBIC), (0.15, BL_SHOOT, EXPO)]
    + shake(BL_SHOOT, 0.2, 0.6, 0.04, 2.2, bones=("right_arm", "left_arm", "torso")) + [(1.0, NEUTRAL, SINE)])

# 黄泉活力（6 tick）：右拳收腰 → 竖拳于面前、左掌覆拳，沉腰发力
NV_LOAD = P(right_arm=[-18, 12, 12], right_arm_bend=[100, 0], left_arm=[-30, -22, 0], left_arm_bend=[40, 0],
            torso=[4, 14, 0], body=[0, -1.5, 0])
NV_POWER = P(right_arm=[-96, 26, 0], right_arm_bend=[88, 0], left_arm=[-82, -46, 0], left_arm_bend=[100, 0],
             torso=[8, 0, 0], head=[4, 0, 0], body=[0, -3, 0], left_leg=[-14, 0, -12], right_leg=[10, 0, 12])
seq("art_nether_vigor", 1.3, [(0, NEUTRAL, LIN), (0.15, NV_LOAD, CUBIC), (0.3, NV_POWER, BACK)]
    + shake(NV_POWER, 0.34, 0.8, 0.05, 1.6) + [(1.3, NEUTRAL, SINE)])

# 基础掌法（6 tick）：马步、双掌收腰 → 双掌齐推 → 收掌回腰
BP_LOAD = P(right_arm=[12, 16, 12], left_arm=[12, -16, -12], right_arm_bend=[75, 0], left_arm_bend=[75, 0],
            torso=[4, 0, 0], body=[0, -4, 0], left_leg=[-8, 0, -15], right_leg=[-8, 0, 15])
BP_PUSH = P(right_arm=[-88, 8, 0], left_arm=[-88, -8, 0], right_arm_bend=[0, 0], left_arm_bend=[0, 0],
            torso=[11, 0, 0], head=[-8, 0, 0], body=[0, -4.6, 0], left_leg=[-8, 0, -15], right_leg=[-8, 0, 15])
seq("art_basic_palm", 1.3, [
    (0, NEUTRAL, LIN), (0.15, BP_LOAD, CUBIC), (0.28, BP_PUSH, EXPO), (0.6, P(BP_PUSH, torso=[9, 0, 0]), LIN),
    (0.9, BP_LOAD, CUBIC), (1.3, NEUTRAL, SINE),
])

# 起死回生（7 tick）：俯身，双掌按向对方胸口，按压两次注入内力
RV_LEAN = P(right_arm=[-58, 14, 0], left_arm=[-58, -14, 0], right_arm_bend=[12, 0], left_arm_bend=[12, 0],
            torso=[34, 0, 0], head=[12, 0, 0], body=[0, -6, 0], left_leg=[-26, 0, -4], right_leg=[18, 0, 4])
RV_PRESS = P(RV_LEAN, right_arm=[-46, 12, 0], left_arm=[-46, -12, 0], torso=[40, 0, 0], body=[0, -7, 0])
seq("art_revive", 1.5, [
    (0, NEUTRAL, LIN), (0.2, RV_LEAN, CUBIC), (0.35, RV_PRESS, EXPO), (0.5, RV_LEAN, SINE), (0.62, RV_PRESS, EXPO),
    (1.0, RV_LEAN, SINE), (1.5, NEUTRAL, SINE),
])

# 凤仙火（5 tick）：单手结印于口前、深吸 → 连续三口急吐（每口头部前送）
PH_IN = P(right_arm=[-110, 46, 8], right_arm_bend=[112, 0], left_arm=[16, 0, -16], torso=[-10, 0, 0], head=[-10, 0, 0], body=[0, 0.4, 0])
PH_PUFF = P(PH_IN, torso=[14, 0, 0], head=[-16, 0, 0], body=[0, -2, 0], left_leg=[-20, 0, -5], right_leg=[14, 0, 5])
PH_BACK = P(PH_PUFF, torso=[7, 0, 0], head=[-11, 0, 0])
seq("art_phoenix_fire", 1.1, [
    (0, NEUTRAL, LIN), (0.18, PH_IN, CUBIC), (0.25, PH_PUFF, EXPO), (0.32, PH_BACK, SINE), (0.38, PH_PUFF, EXPO),
    (0.45, PH_BACK, SINE), (0.52, PH_PUFF, EXPO), (0.75, PH_BACK, LIN), (1.1, NEUTRAL, SINE),
])


# =====================================================================
# 魔法·专业法术（v3.3）
# =====================================================================

# 轰雷剑（斩击 6 tick = 0.3s）：举刃过顶、左手指尖沿刃身抹过（雷光随之爬满剑身）→ 右上至左下的斜斩
TS_RAISE = P(right_arm=[-172, 4, 8], right_arm_bend=[20, 0], left_arm=[-160, -34, -2], left_arm_bend=[48, 0],
             torso=[-10, 10, 0], head=[-14, -4, 0], body=[0, -0.5, 0], left_leg=[-10, 0, -4], right_leg=[8, 0, 4])
TS_WIPE = P(TS_RAISE, left_arm=[-128, -52, -8], left_arm_bend=[18, 0], torso=[-12, 14, 2], head=[-16, -8, 0])
TS_CUT = P(right_arm=[-36, 56, -20], right_arm_bend=[0, 0], left_arm=[-10, 0, -42], left_arm_bend=[8, 0],
           torso=[26, -32, -6], head=[-14, 20, 0], body=[0, -4.5, 0], left_leg=[-34, 0, -8], right_leg=[24, 0, 10])
seq("art_thunder_sword", 1.05, [
    (0, NEUTRAL, LIN), (0.12, TS_RAISE, BACK), (0.24, TS_WIPE, QUART)] + shake(TS_WIPE, 0.25, 0.27, 0.02, 1.5, bones=("right_arm",)) + [
    (0.31, TS_CUT, EXPO), (0.66, P(TS_CUT, torso=[28, -35, -6], right_arm=[-32, 60, -20]), LIN), (1.05, NEUTRAL, SINE),
])

# 光亮术（5 tick）：低声咏唱，把物品高举过头，仰望点亮的光
LT_HOLD = P(right_arm=[-92, 14, 0], right_arm_bend=[70, 0], left_arm=[-30, -10, -6], head=[6, 0, 0], torso=[2, 0, 0])
LT_RAISE = P(right_arm=[-176, 2, 6], right_arm_bend=[4, 0], left_arm=[-14, 0, -24], left_arm_bend=[10, 0],
             torso=[-8, 0, 0], head=[-26, 0, 0], body=[0, 0.6, 0], left_leg=[-4, 0, -3], right_leg=[4, 0, 3])
seq("art_light", 1.1, [
    (0, NEUTRAL, LIN), (0.14, LT_HOLD, CUBIC), (0.26, LT_RAISE, BACK), (0.7, P(LT_RAISE, head=[-22, 0, 0], body=[0, 0.3, 0]), SINE),
    (1.1, NEUTRAL, SINE),
])

# 照明术（6 tick）：双手合捧于胸前（光在掌中凝聚）→ 向上向外托起、展开，放出四枚光球
IL_CUP = P(right_arm=[-62, 30, 0], left_arm=[-62, -30, 0], right_arm_bend=[70, 0], left_arm_bend=[70, 0],
           torso=[6, 0, 0], head=[10, 0, 0], body=[0, -1, 0])
IL_SPREAD = P(right_arm=[-148, -6, 36], left_arm=[-148, 6, -36], right_arm_bend=[12, 0], left_arm_bend=[12, 0],
              torso=[-8, 0, 0], head=[-18, 0, 0], body=[0, 0.5, 0], left_leg=[-4, 0, -6], right_leg=[4, 0, 6])
seq("art_illumination", 1.25, [
    (0, NEUTRAL, LIN), (0.16, IL_CUP, CUBIC)] + shake(IL_CUP, 0.18, 0.24, 0.02, 1.2) + [
    (0.32, IL_SPREAD, EXPO), (0.8, P(IL_SPREAD, right_arm=[-152, -6, 40], left_arm=[-152, 6, -40]), SINE), (1.25, NEUTRAL, SINE),
])

# 冻寒骨爪（4 tick = 0.2s）：沉身、右手五指成爪垂于身侧后方 → 由下而上反撩抓出，左手护胸
FC_LOW = P(right_arm=[34, -10, 26], right_arm_bend=[60, 0], left_arm=[-64, -40, 0], left_arm_bend=[96, 0],
           torso=[18, 22, 0], head=[-8, -16, 0], body=[0, -4, 0], left_leg=[-24, 0, -6], right_leg=[16, 0, 8])
FC_RAKE = P(right_arm=[-128, 28, -10], right_arm_bend=[48, 0], left_arm=[-60, -44, 0], left_arm_bend=[100, 0],
            torso=[-6, -20, 0], head=[-10, 14, 0], body=[0, -1.5, 0], left_leg=[-30, 0, -6], right_leg=[20, 0, 8])
seq("art_frost_claw", 0.95, [
    (0, NEUTRAL, LIN), (0.12, FC_LOW, BACK), (0.2, FC_RAKE, EXPO), (0.55, P(FC_RAKE, right_arm=[-124, 30, -10], torso=[-4, -18, 0]), LIN),
    (0.95, NEUTRAL, SINE),
])


if __name__ == "__main__":
    path = os.path.join(OUT, "art.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"format_version": "1.8.0", "animations": A}, f, ensure_ascii=False, indent=1)
    print(f"{len(A)} 个技艺动作 → {os.path.relpath(path)}")
