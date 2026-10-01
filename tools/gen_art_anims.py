"""生成五个弹体技艺（灵斩 / 风斩 / 波动拳 / 八阵图 / 豪火球）的 KosmX/GeckoLib 动作文件 art.json。

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

if __name__ == "__main__":
    path = os.path.join(OUT, "art.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"format_version": "1.8.0", "animations": A}, f, ensure_ascii=False, indent=1)
    print(f"{len(A)} 个技艺动作 → {os.path.relpath(path)}")
