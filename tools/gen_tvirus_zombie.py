"""
T 病毒丧尸贴图（原版僵尸 64x64 贴图布局，只用上半 64x32，左臂/左腿镜像右侧）。

风格：生化危机式感染者 —— 苍白灰绿的尸皮 + 紫黑静脉 + 凹陷眼窝里的红色瞳光 + 撕裂的嘴，
破烂的灰白衬衫（领口血迹顺胸口淌下、背后三道爪痕）、撕破膝盖的深色牛仔裤、旧皮鞋。
逐面光照：顶面最亮、正面次之、侧面/背面偏暗、底面最暗；材质带轻微确定性噪点（非椒盐杂点）。

用法: python3 tools/gen_tvirus_zombie.py
输出: src/main/resources/assets/zhushenspace/textures/entity/t_virus_zombie.png
预览: tools/preview/t_virus_zombie_preview.png（正面 / 背面展开 + 贴图原图）
"""
import os
from PIL import Image

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(ROOT, "..", "src/main/resources/assets/zhushenspace/textures/entity/t_virus_zombie.png")
PREV = os.path.join(ROOT, "preview", "t_virus_zombie_preview.png")

# ===== 调色板 =====
MAT = {
    # 字符: (基色, 噪点幅度)
    "s": ((168, 176, 150), 3),   # 尸皮
    "S": ((140, 150, 124), 3),   # 尸皮阴影
    "d": ((102, 110, 90), 4),    # 尸皮深影 / 凹陷
    "h": ((190, 196, 172), 4),   # 尸皮高光
    "v": ((96, 72, 108), 3),     # 静脉（紫）
    "b": ((150, 22, 22), 6),     # 鲜血
    "B": ((96, 12, 14), 4),      # 暗血
    "k": ((70, 22, 20), 3),      # 干涸血痂
    "w": ((122, 58, 52), 4),     # 伤口腐肉
    "o": ((34, 26, 28), 2),      # 眼窝 / 口腔
    "r": ((232, 40, 28), 0),     # 红瞳
    "R": ((255, 132, 92), 0),    # 红瞳高光
    "t": ((208, 198, 168), 3),   # 牙
    "g": ((118, 34, 40), 3),     # 牙龈
    "H": ((52, 44, 38), 3),      # 纠结的头发
    "c": ((118, 136, 156), 5),   # 褪色蓝工装衬衫
    "C": ((94, 110, 130), 4),    # 衬衫阴影 / 褶皱
    "x": ((66, 76, 92), 3),      # 衬衫深褶 / 破口边
    "l": ((42, 34, 30), 3),      # 皮带
    "m": ((140, 130, 104), 3),   # 皮带扣
    "j": ((54, 64, 88), 4),      # 牛仔裤
    "J": ((40, 48, 68), 3),      # 牛仔裤阴影
    "q": ((70, 82, 108), 3),     # 牛仔裤磨白
    "f": ((46, 36, 30), 3),      # 皮鞋
    "F": ((28, 22, 20), 2),      # 鞋底
    ".": None,                   # 透明
}

# 逐面光照
LIGHT = {"top": 1.10, "front": 1.00, "right": 0.90, "left": 0.90, "back": 0.84, "bottom": 0.74}


def h32(*v):
    """确定性整数哈希 → [0,1)"""
    x = 2166136261
    for n in v:
        x = ((x ^ (n & 0xFFFFFFFF)) * 16777619) & 0xFFFFFFFF
    x ^= x >> 13
    x = (x * 1274126177) & 0xFFFFFFFF
    return (x & 0xFFFF) / 65536.0


def put(img, ox, oy, rows, face, seed):
    """把字符网格画到 (ox, oy)，带逐面光照、自上而下的轻微渐暗与确定性噪点"""
    px = img.load()
    hgt = len(rows)
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            m = MAT[ch]
            if m is None:
                px[ox + x, oy + y] = (0, 0, 0, 0)
                continue
            (r, g, b), amp = m
            lit = LIGHT[face]
            if ch not in "rR":  # 红瞳自发光，不受光照
                lit *= 1.03 - 0.08 * (y / max(1, hgt - 1))
            else:
                lit = 1.0
            n = (h32(seed, x, y) - 0.5) * 2 * amp
            px[ox + x, oy + y] = tuple(max(0, min(255, int(c * lit + n))) for c in (r, g, b)) + (255,)


# ===== 头部（8x8x8，贴图原点 0,0） =====
HEAD_TOP = [
    "HHHHHHHH",
    "HHHHHHHH",
    "HHHssSHH",
    "HHswkwsH",
    "HHSwbwSH",
    "HHHSsSHH",
    "HHHHHHHH",
    "HHHHHHHH",
]
HEAD_BOTTOM = [
    "SSSSSSSS",
    "SdSSSSdS",
    "SSSvSSSS",
    "SSSSSBSS",
    "SSvSSSSS",
    "SSSSSSSS",
    "SdSSSSdS",
    "SSSSSSSS",
]
# 脸部（逐行说明）
HEAD_FRONT = [
    "HHHssHHH",   # 发际线：稀疏纠结的头发
    "HssskwsH",   # 额头右侧一道撕裂伤
    "sddssdds",   # 眉骨阴影
    "sorSSros",   # 凹陷眼窝 + 红瞳（靠内侧，显得凶狠）
    "sddSSdds",   # 眼袋青黑
    "svsddssS",   # 鼻梁阴影 + 左脸静脉
    "Sotgtgos",   # 撕裂的嘴：牙齿与牙龈交错
    "SBobBoBS",   # 下巴：血顺嘴角淌下
]
HEAD_RIGHT = [
    "HHHHHHHH",
    "HHHHHHHH",
    "sHHsssHs",
    "sssSSsss",
    "SsswwsSs",
    "SsskwSss",
    "SSsssSsS",
    "SSSSSdSS",
]
HEAD_LEFT = [
    "HHHHHHHH",
    "HHHHHHHH",
    "sHssHHHs",
    "ssssSsss",
    "sSssssvs",
    "ssssssSS",
    "SsSsSSSS",
    "SSdSSSSS",
]
HEAD_BACK = [
    "HHHHHHHH",
    "HHHHHHHH",
    "HHHHwkHH",
    "HHHwbBwH",
    "sHHHkwHs",
    "ssHHHHss",
    "SSsSSvSS",
    "SSSSdSSS",
]

# ===== 身体（8x12x4，贴图原点 16,16） =====
BODY_TOP = [
    "ccSssScc",
    "cCSssSCc",
    "cCCccCCc",
    "cccccccc",
]
BODY_BOTTOM = [
    "llllllll",
    "llllllll",
    "llllllll",
    "llllllll",
]
BODY_FRONT = [
    "ccCsbCcc",   # V 领：露出的脖颈与血迹
    "cCCsbbCc",
    "ccCbbBCc",
    "cCcbBbcC",   # 领口的血顺胸口往下淌
    "ccCBbBcc",
    "CcxsbBCc",   # 左胸撕破的洞，露出伤口
    "cxwsbcCc",
    "ccxxkcCc",
    "cCccBcxc",
    "ccCcBCsx",   # 下摆撕裂
    "llllmlll",   # 皮带
    "lllmmlll",
]
BODY_BACK = [
    "cccCCccc",
    "cCcccCcc",
    "ccxcccCc",
    "cCsxcccc",   # 三道斜向爪痕（露出皮肉与血）
    "ccbsxcxc",
    "cxcbsxsc",
    "ccxcbsbx",
    "cCcxcbsc",
    "ccCcxcbc",
    "cccCcCxc",
    "llllllll",
    "llllllll",
]
BODY_RIGHT = [
    "cCcc",
    "cCcc",
    "ccCc",
    "cCcb",
    "ccCB",
    "cCcc",
    "cccC",
    "Cccc",
    "cCcc",
    "ccCx",
    "llll",
    "llll",
]
BODY_LEFT = [
    "ccCc",
    "cCcc",
    "Cccc",
    "cCcc",
    "ccCc",
    "cxsc",
    "cswc",
    "cCxc",
    "ccCc",
    "xcCc",
    "llll",
    "llll",
]

# ===== 手臂（4x12x4，贴图原点 40,16）：破烂短袖 + 青紫的手臂 + 血手 =====
ARM_TOP = ["cCcc", "cCCc", "cCCc", "ccCc"]
ARM_BOTTOM = ["SBSk", "SSBS", "kSSB", "SBSS"]
ARM_FRONT = [
    "cCcc",
    "cCCc",
    "cxCx",
    "xsxs",
    "ssss",
    "Ssvs",
    "ssSs",
    "sSvs",
    "SsSs",
    "SkSb",
    "bSBS",
    "BbSB",
]
ARM_BACK = [
    "ccCc",
    "cCCc",
    "Cxcx",
    "sxsx",
    "ssss",
    "sSss",
    "Ssss",
    "sdws",
    "Swks",
    "SSsS",
    "SbSS",
    "BSbS",
]
ARM_OUT = [
    "cCcc",
    "Cccc",
    "cCxc",
    "xsxs",
    "ssss",
    "ssvs",
    "Ssss",
    "sSss",
    "SsSS",
    "SSsS",
    "SkSB",
    "bSBS",
]
ARM_IN = [
    "ccCc",
    "cCcc",
    "Cxcc",
    "sxxs",
    "ssSs",
    "sSss",
    "Ssss",
    "sSSs",
    "SsSs",
    "SSSS",
    "SBSS",
    "SSbS",
]

# ===== 腿（4x12x4，贴图原点 0,16）：深色牛仔裤 + 膝盖破洞 + 旧皮鞋 =====
LEG_TOP = ["jjJj", "jJjj", "jjjJ", "Jjjj"]
LEG_BOTTOM = ["FFFF", "FFFF", "FFFF", "FFFF"]
LEG_FRONT = [
    "jjJj",
    "jqjJ",
    "jjjJ",
    "jBjj",   # 大腿上的血点
    "Jjqj",
    "xwsx",   # 膝盖撕破：露出擦伤
    "jskj",
    "Jjqj",
    "jJjj",
    "jjJq",
    "ffff",
    "fFff",
]
LEG_BACK = [
    "jJjj",
    "jjjJ",
    "Jjjj",
    "jjJj",
    "jJjj",
    "jjjJ",
    "Jjkj",
    "jjBj",
    "jJjj",
    "Jjjj",
    "ffff",
    "ffFf",
]
LEG_OUT = [
    "jjJj",
    "jJjj",
    "jjjJ",
    "Jjjj",
    "jqjj",
    "jjJj",
    "jJjj",
    "jjjJ",
    "Jjqj",
    "jJjj",
    "ffff",
    "Ffff",
]
LEG_IN = [
    "Jjjj",
    "jjJj",
    "jJjj",
    "jjjJ",
    "jjJj",
    "Jjjj",
    "jjJj",
    "jJjk",
    "jjjj",
    "Jjjj",
    "ffff",
    "ffff",
]


def box(img, u, v, w, h, d, faces, seed):
    """按原版 Cube UV 展开：top(u+d,v) bottom(u+d+w,v) right(u,v+d) front(u+d,v+d) left(u+d+w,v+d) back(u+2d+w,v+d)"""
    top, bottom, right, front, left, back = faces
    put(img, u + d, v, top, "top", seed + 1)
    put(img, u + d + w, v, bottom, "bottom", seed + 2)
    put(img, u, v + d, right, "right", seed + 3)
    put(img, u + d, v + d, front, "front", seed + 4)
    put(img, u + d + w, v + d, left, "left", seed + 5)
    put(img, u + 2 * d + w, v + d, back, "back", seed + 6)


def check(name, rows, w, h):
    assert len(rows) == h, (name, len(rows))
    for r in rows:
        assert len(r) == w, (name, r)


def main():
    for n, rows, w, h in [
        ("head_top", HEAD_TOP, 8, 8), ("head_bottom", HEAD_BOTTOM, 8, 8), ("head_front", HEAD_FRONT, 8, 8),
        ("head_right", HEAD_RIGHT, 8, 8), ("head_left", HEAD_LEFT, 8, 8), ("head_back", HEAD_BACK, 8, 8),
        ("body_top", BODY_TOP, 8, 4), ("body_bottom", BODY_BOTTOM, 8, 4), ("body_front", BODY_FRONT, 8, 12),
        ("body_back", BODY_BACK, 8, 12), ("body_right", BODY_RIGHT, 4, 12), ("body_left", BODY_LEFT, 4, 12),
        ("arm_top", ARM_TOP, 4, 4), ("arm_bottom", ARM_BOTTOM, 4, 4), ("arm_front", ARM_FRONT, 4, 12),
        ("arm_back", ARM_BACK, 4, 12), ("arm_out", ARM_OUT, 4, 12), ("arm_in", ARM_IN, 4, 12),
        ("leg_top", LEG_TOP, 4, 4), ("leg_bottom", LEG_BOTTOM, 4, 4), ("leg_front", LEG_FRONT, 4, 12),
        ("leg_back", LEG_BACK, 4, 12), ("leg_out", LEG_OUT, 4, 12), ("leg_in", LEG_IN, 4, 12),
    ]:
        check(n, rows, w, h)

    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    box(img, 0, 0, 8, 8, 8, (HEAD_TOP, HEAD_BOTTOM, HEAD_RIGHT, HEAD_FRONT, HEAD_LEFT, HEAD_BACK), 100)
    # 帽子层（32,0）保持透明
    box(img, 16, 16, 8, 12, 4, (BODY_TOP, BODY_BOTTOM, BODY_RIGHT, BODY_FRONT, BODY_LEFT, BODY_BACK), 200)
    box(img, 40, 16, 4, 12, 4, (ARM_TOP, ARM_BOTTOM, ARM_OUT, ARM_FRONT, ARM_IN, ARM_BACK), 300)
    box(img, 0, 16, 4, 12, 4, (LEG_TOP, LEG_BOTTOM, LEG_OUT, LEG_FRONT, LEG_IN, LEG_BACK), 400)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    img.save(OUT)
    preview(img)
    print("saved", os.path.normpath(OUT))


def preview(tex):
    """正面 / 背面平面展开预览（左臂左腿按原版镜像）+ 贴图原图"""
    S = 12

    def crop(x, y, w, h, mirror=False):
        c = tex.crop((x, y, x + w, y + h))
        return c.transpose(Image.FLIP_LEFT_RIGHT) if mirror else c

    def figure(front):
        fig = Image.new("RGBA", (16, 32), (0, 0, 0, 0))
        if front:
            fig.paste(crop(8, 8, 8, 8), (4, 0))
            fig.paste(crop(20, 20, 8, 12), (4, 8))
            fig.paste(crop(44, 20, 4, 12), (0, 8))              # 右臂（画面左侧）
            fig.paste(crop(44, 20, 4, 12, True), (12, 8))       # 左臂镜像
            fig.paste(crop(4, 20, 4, 12), (4, 20))
            fig.paste(crop(4, 20, 4, 12, True), (8, 20))
        else:
            fig.paste(crop(24, 8, 8, 8), (4, 0))
            fig.paste(crop(32, 20, 8, 12), (4, 8))
            fig.paste(crop(52, 20, 4, 12, True), (0, 8))
            fig.paste(crop(52, 20, 4, 12), (12, 8))
            fig.paste(crop(12, 20, 4, 12, True), (4, 20))
            fig.paste(crop(12, 20, 4, 12), (8, 20))
        return fig.resize((16 * S, 32 * S), Image.NEAREST)

    W = 16 * S * 2 + 64 * 6 + 60
    H = max(32 * S, 64 * 6) + 30
    out = Image.new("RGBA", (W, H), (38, 40, 44, 255))
    out.alpha_composite(figure(True), (15, 15))
    out.alpha_composite(figure(False), (30 + 16 * S, 15))
    sheet = Image.new("RGBA", (64, 64), (60, 62, 66, 255))
    sheet.alpha_composite(tex)
    out.alpha_composite(sheet.resize((64 * 6, 64 * 6), Image.NEAREST), (45 + 32 * S, 15))
    os.makedirs(os.path.dirname(PREV), exist_ok=True)
    out.save(PREV)


if __name__ == "__main__":
    main()
