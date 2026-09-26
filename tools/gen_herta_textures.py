"""大黑塔（崩坏：星穹铁道，The Herta）主题贴图 —— 通用界面（商店 / 货币兑换 / 设置 / 背包入口 / 提示框等）。

herta_sky.png    160x120 x 16 帧：深夜紫星空 + 淡银河 + 闪烁星点（面板底纹）
herta_sigil.png  128x128：裙摆上的蓝紫魔法阵（四叶圆弧纹 + 内圈八芒星 + 外圈刻度），白色单色，运行时着色旋转
herta_flower.png 32x32：帽上的紫色五瓣花（金色花蕊）
herta_hat.png    48x48：尖顶弯折的黑色魔女帽 + 紫花 + 金环（面板角饰）
herta_doll.png   32x32：大黑塔的紫色小人偶（像素点眼，头顶小紫花）
herta_chibi.png  由 tools/art/herta_chibi_raw.png 抠图生成（设置界面趴着的 Q 版魔女大黑塔）
"""
import math, os
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.abspath(__file__))
ANIM = os.path.join(ROOT, "..", "src/main/resources/assets/zhushenspace/textures/gui/anim")
GUI = os.path.join(ROOT, "..", "src/main/resources/assets/zhushenspace/textures/gui")
PREV = os.path.join(ROOT, "preview")
SS = 6

VIOLET = (150, 118, 234)
VIOLET_DK = (84, 58, 160)
LAVENDER = (206, 190, 245)
GOLD = (222, 184, 104)
INK = (20, 16, 30)


def sky():
    W, H, N = 160, 120, 16
    rnd = np.random.default_rng(83)  # 天才俱乐部 #83
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    base = np.zeros((H, W, 3), np.float32)
    t = yy / H
    base[..., 0] = 16 + 22 * t
    base[..., 1] = 12 + 10 * t
    base[..., 2] = 34 + 30 * t
    # 淡银河：斜向柔带
    band = np.exp(-(((yy - H * 0.55) - (xx - W / 2) * 0.35) / 16) ** 2)
    noise = np.array(Image.fromarray((rnd.random((H // 4, W // 4)) * 255).astype(np.uint8)).resize((W, H), Image.BICUBIC)) / 255
    base += band[..., None] * (0.4 + 0.6 * noise[..., None]) * np.array([46, 30, 80])
    stars = [(rnd.integers(0, W), rnd.integers(0, H), rnd.random(), rnd.random()) for _ in range(90)]
    frames = []
    for n in range(N):
        img = base.copy()
        for x, y, b, ph in stars:
            tw = 0.35 + 0.65 * (0.5 + 0.5 * math.sin((n / N + ph) * math.tau))
            v = (80 + 175 * b) * tw
            col = np.array([v * 0.9, v * 0.85, v]) if b < 0.8 else np.array([v, v * 0.8, v * 0.95])
            img[y, x] = np.maximum(img[y, x], col)
            if b > 0.85:
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    if 0 <= x + dx < W and 0 <= y + dy < H:
                        img[y + dy, x + dx] = np.maximum(img[y + dy, x + dx], col * 0.45)
        frames.append(Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).convert("RGBA"))
    sheet = Image.new("RGBA", (W, H * N))
    for i, f in enumerate(frames):
        sheet.paste(f, (0, i * H))
    sheet.save(os.path.join(ANIM, "herta_sky.png"), optimize=True)
    return frames[0]


def sigil():
    S = 128 * SS
    m = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(m)
    c = S / 2
    w = int(S * 0.012)
    d.ellipse([c - S * .48, c - S * .48, c + S * .48, c + S * .48], outline=255, width=w)
    d.ellipse([c - S * .44, c - S * .44, c + S * .44, c + S * .44], outline=160, width=w // 2)
    for i in range(72):   # 外圈刻度
        a = i / 72 * math.tau
        r0 = S * (.44 if i % 6 else .415)
        d.line([(c + r0 * math.cos(a), c + r0 * math.sin(a)), (c + S * .48 * math.cos(a), c + S * .48 * math.sin(a))],
               fill=200, width=w // 2)
    # 四叶圆弧纹（裙摆徽记）
    for k in range(4):
        a = k * math.pi / 2
        ox, oy = c + S * .17 * math.cos(a), c + S * .17 * math.sin(a)
        r = S * .19
        d.arc([ox - r, oy - r, ox + r, oy + r], math.degrees(a) - 110, math.degrees(a) + 110, fill=255, width=w)
    d.ellipse([c - S * .25, c - S * .25, c + S * .25, c + S * .25], outline=200, width=w // 2)
    # 八芒星
    pts = []
    for i in range(16):
        a = i / 16 * math.tau - math.pi / 2
        r = S * (.21 if i % 2 == 0 else .09)
        pts.append((c + r * math.cos(a), c + r * math.sin(a)))
    d.line(pts + [pts[0]], fill=255, width=w, joint="curve")
    d.ellipse([c - S * .035, c - S * .035, c + S * .035, c + S * .035], fill=255)
    for k in range(8):  # 外圈小菱形
        a = k / 8 * math.tau + math.pi / 8
        px, py = c + S * .345 * math.cos(a), c + S * .345 * math.sin(a)
        e = S * .018
        d.polygon([(px, py - e * 1.6), (px + e, py), (px, py + e * 1.6), (px - e, py)], fill=255)
    m = m.resize((128, 128), Image.LANCZOS)
    glow = m.filter(ImageFilter.GaussianBlur(2.5))
    a = np.clip(np.array(m).astype(np.float32) + np.array(glow).astype(np.float32) * 0.8, 0, 255).astype(np.uint8)
    out = Image.new("RGBA", (128, 128), (255, 255, 255, 0))
    out.putalpha(Image.fromarray(a))
    out.save(os.path.join(ANIM, "herta_sigil.png"))
    return out


def flower(size=32, save=True):
    S = size * SS
    img = Image.new("RGBA", (S, S))
    d = ImageDraw.Draw(img)
    c = S / 2
    for k in range(5):
        a = k / 5 * math.tau - math.pi / 2
        px, py = c + S * .22 * math.cos(a), c + S * .22 * math.sin(a)
        # 花瓣：尖端略收的椭圆（旋转）
        pet = Image.new("RGBA", (S, S))
        pd = ImageDraw.Draw(pet)
        pd.ellipse([c - S * .15, c - S * .30, c + S * .15, c + S * .02], fill=VIOLET + (255,), outline=VIOLET_DK + (255,),
                   width=SS)
        pd.line([(c, c - S * .26), (c, c - S * .02)], fill=LAVENDER + (200,), width=SS)
        pet = pet.rotate(-math.degrees(a + math.pi / 2), center=(c, c), resample=Image.BICUBIC)
        img.alpha_composite(pet)
    d.ellipse([c - S * .09, c - S * .09, c + S * .09, c + S * .09], fill=(250, 226, 170, 255))
    for k in range(5):
        a = k / 5 * math.tau
        d.ellipse([c + S * .06 * math.cos(a) - SS, c + S * .06 * math.sin(a) - SS,
                   c + S * .06 * math.cos(a) + SS, c + S * .06 * math.sin(a) + SS], fill=GOLD + (255,))
    img = img.resize((size, size), Image.LANCZOS)
    if save:
        img.save(os.path.join(ANIM, "herta_flower.png"))
    return img


def hat():
    S = 48 * SS
    img = Image.new("RGBA", (S, S))
    d = ImageDraw.Draw(img)
    L = lambda x, y: (x * SS, y * SS)
    # 帽檐
    d.ellipse([*L(2, 30), *L(46, 42)], fill=(24, 20, 34, 255), outline=(96, 80, 140, 255), width=SS)
    d.ellipse([*L(6, 31.5), *L(42, 39)], fill=(50, 40, 90, 255))
    # 帽身 + 弯折尖顶
    d.polygon([L(12, 36), L(19, 16), L(24, 10), L(31, 7), L(40, 9), L(34, 11), L(29, 15), L(33, 36)],
              fill=(28, 24, 40, 255), outline=(96, 80, 140, 255))
    d.line([L(19, 16), L(24, 10), L(31, 7), L(40, 9)], fill=(120, 100, 180, 255), width=SS)
    # 帽带
    d.polygon([L(12.6, 32.5), L(32.6, 32.5), L(33, 36), L(12, 36)], fill=(60, 44, 110, 255))
    d.ellipse([*L(29, 32), *L(32, 35)], outline=GOLD + (255,), width=SS)
    img = img.resize((48, 48), Image.LANCZOS)
    f = flower(14, save=False)
    img.alpha_composite(f, (13, 25))
    img.alpha_composite(flower(10, save=False), (22, 27))
    img.save(os.path.join(ANIM, "herta_hat.png"))
    return img


def doll():
    S = 32 * SS
    img = Image.new("RGBA", (S, S))
    d = ImageDraw.Draw(img)
    L = lambda x, y: (x * SS, y * SS)
    # 圆顶身体 + 下摆三个小波浪
    d.ellipse([*L(4, 7), *L(28, 29)], fill=(86, 70, 120, 255))
    d.rectangle([*L(4, 18), *L(28, 26)], fill=(86, 70, 120, 255))
    for k in range(3):
        d.ellipse([*L(4 + k * 8, 23), *L(12 + k * 8, 30)], fill=(86, 70, 120, 255))
    d.ellipse([*L(7, 9), *L(18, 15)], fill=(130, 112, 168, 255))   # 高光
    # 像素脸（白色方块眼 + 口）
    face = (240, 236, 250, 255)
    for x0, y0 in ((10, 15), (12, 15), (10, 17), (19, 15), (21, 15), (21, 17)):
        d.rectangle([*L(x0, y0), *L(x0 + 1.6, y0 + 1.6)], fill=face)
    d.rectangle([*L(14, 20), *L(18, 21.4)], fill=face)
    img = img.resize((32, 32), Image.LANCZOS)
    img.alpha_composite(flower(11, save=False), (18, 1))
    img.save(os.path.join(ANIM, "herta_doll.png"))
    return img


def chibi():
    """抠绿：tools/art/herta_chibi_raw.png → textures/gui/herta_chibi.png（高 256，手臂支撑线约在 73%）"""
    im = np.array(Image.open(os.path.join(ROOT, "art", "herta_chibi_raw.png")).convert("RGB")).astype(np.float32)
    r, g, b = im[..., 0], im[..., 1], im[..., 2]
    key = g - np.maximum(r, b)
    a = np.clip(1 - (key - 40) / 80, 0, 1)
    out = np.dstack([r, np.minimum(g, np.maximum(r, b) + 8), b, a * 255]).astype(np.uint8)
    img = Image.fromarray(out, "RGBA")
    img = img.crop(img.getchannel("A").point(lambda v: 255 if v > 20 else 0).getbbox())
    w, h = img.size
    img = img.resize((round(w * 256 / h), 256), Image.LANCZOS)
    img.save(os.path.join(GUI, "herta_chibi.png"))
    return img


if __name__ == "__main__":
    s = sky(); sg = sigil(); fl = flower(); ht = hat(); dl = doll(); ch = chibi()
    prev = Image.new("RGBA", (520, 280), INK + (255,))
    prev.alpha_composite(s.resize((320, 240)), (0, 0))
    col = Image.new("RGBA", sg.size, (140, 150, 255, 0)); col.putalpha(sg.getchannel("A"))
    prev.alpha_composite(col, (96, 56))
    prev.alpha_composite(fl.resize((64, 64)), (330, 10))
    prev.alpha_composite(ht.resize((96, 96)), (400, 0))
    prev.alpha_composite(dl.resize((64, 64)), (330, 90))
    prev.alpha_composite(ch.resize((114, 128)), (400, 110))
    prev.save(os.path.join(PREV, "herta_textures.png"))
    print("ok")
