"""生成技艺（四个样板 ArtSkill）的 KosmX/GeckoLib 动作文件 art.json。

约定与 tools/gen_taiji_anims.py 一致：
  手臂/腿 x 负值 = 向前抬起；右臂 y 正 = 向内、z 正 = 向外；左臂镜像。
  躯干 torso x 正 = 前倾；body 位置 y 负 = 下沉（1/16 格）。
结构：先站姿（0 → 0.12s 抬手），出招定格（约 0.12 → 0.3s，与服务端即时判定同步），随后收势回正。
运行：python3 tools/gen_art_anims.py
"""
import json, os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                   "src/main/resources/assets/zhushenspace/player_animations")
Z = [0, 0, 0]
S, B, Q, C = "easeOutSine", "easeOutBack", "easeOutQuart", "easeOutCubic"
A = {}


def kf(frames):
    out = {}
    for t, v in frames:
        e = "easeInOutSine"
        if isinstance(v, tuple):
            v, e = v
        key = f"{t:.2f}".rstrip("0").rstrip(".") if t else "0.0"
        out[key] = {"vector": v, "easing": e}
    return out


def anim(length, bones, loop=False):
    return {"loop": loop, "animation_length": length,
            "bones": {n: {ch: kf(fr) for ch, fr in chans.items()} for n, chans in bones.items()}}


def mirror(v):
    return [v[0], -v[1], -v[2]]


def arms(r, l):
    return {"right_arm": {"rotation": r}, "left_arm": {"rotation": l}}


def R(*frames):
    return {"rotation": list(frames)}


def build(name, length, strike, hold_until, arms_r, arms_l, torso, head=None, body=None, legs=None):
    """通用构造：站姿抬臂 → 出招定格 → 收势。"""
    st, hu = strike, hold_until
    A[name] = anim(length, {
        **arms(
            [(0, Z), (0.12, arms_r[0]), (st, (arms_r[1], Q)), (hu, arms_r[1]), (length, (Z, S))],
            [(0, Z), (0.12, arms_l[0]), (st, (arms_l[1], Q)), (hu, arms_l[1]), (length, (Z, S))],
        ),
        "torso": R((0, Z), (st, (torso, Q)), (hu, torso), (length, (Z, S))),
        "head": R((0, Z), (st, (head if head else [-4, 0, 0], Q)), (length, Z)),
        "body": {"position": [(0, Z), (st, (body if body else [0, -1, 0], Q)), (hu, body if body else [0, -1, 0]), (length, Z)]},
        "left_leg": R((0, Z), (st, (legs[0] if legs else [-14, 0, 0], Q)), (hu, legs[0] if legs else [-14, 0, 0]), (length, Z)),
        "right_leg": R((0, Z), (st, (legs[1] if legs else [10, 0, 0], Q)), (hu, legs[1] if legs else [10, 0, 0]), (length, Z)),
    })


# ===== 1 灵斩：剑指前劈 =====
build("art_spirit_slash", 0.72, 0.14, 0.46,
      ([-40, 20, 10], [-88, 26, 4]), ([30, 18, 8], [-96, 30, 2]),
      [6, -10, 0], [-6, -8, 0], [0, -1.5, 0], ([-20, 0, -4], [14, 0, 4]))

# ===== 5 波动拳：腰侧蓄气 → 双掌前推 =====
build("art_hadoken", 0.95, 0.16, 0.62,
      ([-20, 40, 30], [-92, 14, 4]), ([-20, -40, -30], [-92, -14, -4]),
      [4, 0, 0], [-8, 0, 0], [0, -2.5, 0], ([-22, 0, 0], [16, 0, 0]))

# Raised palms; seal stays in front rather than on the ground.
build("art_formation", 0.9, 0.16, 0.6,
      ([-125, 24, 10], [-100, 10, 3]), ([-125, -24, -10], [-100, -10, -3]),
      [4,0,0], [-3,0,0])
build("art_wind_slash", 0.65, 0.13, 0.28,
      ([-30,-30,60],[-80,65,-20]), ([10,-20,-10],[-25,-40,-15]), [4,24,0], [0,12,0])
# Release from the finished tiger seal: no second windup after key-up.
A["art_fireball"] = anim(0.7, {
    **arms([(0,[-104,36,10]),(.1,[-75,22,35]),(.4,[-75,22,35]),(.7,Z)],
           [(0,[-104,-36,-10]),(.1,[-75,-22,-35]),(.4,[-75,-22,-35]),(.7,Z)]),
    "torso": R((0,[-10,0,0]),(.1,[16,0,0]),(.4,[12,0,0]),(.7,Z)),
    "head": R((0,[-12,0,0]),(.1,[8,0,0]),(.7,Z))
})
# Non-looping 11s clips hold the pose; server caps power at 2s, cancels at 10s.
def charge(name, right, left, torso):
    A[name]=anim(11, {
        **arms([(0,Z),(.22,right),(2,right),(10.5,right),(11,Z)],
               [(0,Z),(.22,left),(2,left),(10.5,left),(11,Z)]),
        "torso": R((0,Z),(.22,torso),(10.5,torso),(11,Z)),
        "left_leg": R((0,Z),(.22,[-18,0,-4]),(10.5,[-18,0,-4]),(11,Z)),
        "right_leg": R((0,Z),(.22,[12,0,4]),(10.5,[12,0,4]),(11,Z))
    })
charge("art_charge_wave",[-40,45,30],[-45,-30,-25],[4,-14,0])
charge("art_charge_seal",[-125,24,10],[-125,-24,-10],[-3,0,0])
charge("art_charge_fire",[-104,36,10],[-104,-36,-10],[-10,0,0])
# Seal sequence in the mandatory first 0.6s, then held at the mouth.
A["art_charge_fire"]["bones"]["right_arm"]["rotation"] = kf([(0,Z),(.12,[-85,48,14]),(.25,[-100,25,5]),(.4,[-95,42,12]),(.6,[-104,36,10]),(10.5,[-104,36,10]),(11,Z)])
A["art_charge_fire"]["bones"]["left_arm"]["rotation"] = kf([(0,Z),(.12,[-85,-48,-14]),(.25,[-100,-25,-5]),(.4,[-95,-42,-12]),(.6,[-104,-36,-10]),(10.5,[-104,-36,-10]),(11,Z)])

if __name__ == "__main__":
    art = A
    path = os.path.join(OUT, "art.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"format_version": "1.8.0", "animations": art}, f, ensure_ascii=False, indent=1)
    print(f"{len(art)} 个技艺动作 → {os.path.relpath(path)}")
