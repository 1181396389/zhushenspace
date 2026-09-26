"""
新月同行（超管局）风格贴图 —— 纪念用，按主美公开的设计原则还原：
银灰（冷灰）为底、橙色点缀；复古工业感：仪器圆盘、刻度、打孔卡、条形码、源数学数字。
- xyt_paper.png  技能页底纹：冷灰档案纸 + 细网格 + 左侧刻度尺 + 条形码 + 十字定位标
- xyt_dial.png   仪器圆盘（外圈刻度顺转、内圈逆转、中圈数字静止；橙色零位标）
用法: python3 tools/gen_xyt_textures.py
"""
import math, os, random
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(ROOT, "..", "src/main/resources/assets/zhushenspace/textures/gui/anim")
PREV = os.path.join(ROOT, "preview")
TAU = math.tau
ORANGE = (240, 112, 30)
INK = (28, 31, 35)

def font(sz):
    for p in ["/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf",
              "/usr/share/fonts/dejavu/DejaVuSansMono.ttf"]:
        if os.path.exists(p):
            return ImageFont.truetype(p, sz)
    return ImageFont.load_default()


def paper():
    W, H = 256, 192
    rnd = random.Random(314159)
    img = Image.new("RGBA", (W, H))
    px = img.load()
    for y in range(H):
        for x in range(W):
            # 冷灰：左上略亮，右下略暗；极细颗粒
            t = (x / W * 0.4 + y / H * 0.6)
            v = 208 - 16 * t + rnd.uniform(-2.5, 2.5)
            px[x, y] = (int(v - 5), int(v - 1), int(v + 3), 255)
    # 装饰全部画在透明叠层上再合成（ImageDraw 直接写 RGBA 会覆盖而非混合，半透明线会把纸面打成透明洞）
    ov = Image.new("RGBA", (W, H))
    d = ImageDraw.Draw(ov)
        # 极淡网格：16px 细线 + 64px 主线（克制，仅作纸面质感）
    for x in range(0, W, 16):
        d.line([(x, 0), (x, H)], fill=(255, 255, 255, 16 if x % 64 else 34))
    for y in range(0, H, 16):
        d.line([(0, y), (W, y)], fill=(255, 255, 255, 16 if y % 64 else 34))
    # 左侧刻度尺（每 4px 一刻，每 20px 长刻 + 数字）
    f = font(7)
    d.line([(10, 0), (10, H)], fill=INK + (90,))
    for i, y in enumerate(range(2, H, 4)):
        long_ = i % 5 == 0
        d.line([(10, y), (10 + (5 if long_ else 2), y)], fill=INK + (110 if long_ else 70,))
        if long_ and i % 10 == 0:
            d.text((1, y - 4), str(i // 10), font=f, fill=INK + (120,))
    # 条形码（右下）
    x = W - 60
    for i in range(46):
        w = rnd.choice([1, 1, 1, 2])
        if rnd.random() < 0.62:
            d.rectangle([x, H - 20, x + w - 1, H - 9], fill=INK + (95,))
        x += w + (1 if rnd.random() < 0.7 else 0)
        if x > W - 8:
            break
    d.text((W - 60, H - 8), "ZS-0314-15926", font=font(6), fill=INK + (110,))
    # 十字定位标（四角内侧）
    for cx, cy in ((22, H - 12),):
        d.line([(cx - 3, cy), (cx + 3, cy)], fill=INK + (100,))
        d.line([(cx, cy - 3), (cx, cy + 3)], fill=INK + (100,))
    # 橙色小色块（品牌点缀：左上角）
    d.rectangle([14, 3, 17, 6], fill=ORANGE + (255,))
    img.alpha_composite(ov)
    img.save(os.path.join(OUT, "xyt_paper.png"), optimize=True)
    print("xyt_paper", img.size)
    return img


def dial():
    S, N, SS = 96, 30, 4
    f = font(7 * SS)
    frames = []
    for n in range(N):
        t = n / N
        big = Image.new("RGBA", (S * SS, S * SS))
        d = ImageDraw.Draw(big)
        c = S * SS / 2
        R = c - 2 * SS
        a_in = 150

        def ring(r, w=1):
            d.ellipse([c - r, c - r, c + r, c + r], outline=INK + (a_in,), width=w * SS // 2)

        ring(R, 2)
        ring(R - 9 * SS)
        ring(R - 22 * SS)
        ring(R - 30 * SS)
        # 外圈：60 刻，顺时针转一刻（6°）为一循环
        rot = TAU / 60 * t
        for i in range(60):
            a = rot + TAU * i / 60 - TAU / 4
            l = 7 if i % 5 == 0 else 3
            r0, r1 = R - l * SS, R
            d.line([(c + r0 * math.cos(a), c + r0 * math.sin(a)), (c + r1 * math.cos(a), c + r1 * math.sin(a))],
                   fill=INK + (a_in,), width=SS if i % 5 else SS * 3 // 2)
        # 中圈数字（静止）0-9
        for i in range(10):
            a = TAU * i / 10 - TAU / 4
            rr = R - 15.5 * SS
            x, y = c + rr * math.cos(a), c + rr * math.sin(a)
            d.text((x, y), str(i), font=f, fill=INK + (a_in,), anchor="mm")
        # 内圈：30 刻，逆时针转一刻（12°）为一循环
        rot2 = -TAU / 30 * t
        for i in range(30):
            a = rot2 + TAU * i / 30
            r0, r1 = R - 30 * SS, R - 26 * SS
            d.line([(c + r0 * math.cos(a), c + r0 * math.sin(a)), (c + r1 * math.cos(a), c + r1 * math.sin(a))],
                   fill=INK + (a_in,), width=SS)
        # 橙色零位标（随外圈）
        a = rot - TAU / 4
        tip = (c + (R + 1 * SS) * math.cos(a), c + (R + 1 * SS) * math.sin(a))
        b1 = (c + (R - 8 * SS) * math.cos(a - 0.06), c + (R - 8 * SS) * math.sin(a - 0.06))
        b2 = (c + (R - 8 * SS) * math.cos(a + 0.06), c + (R - 8 * SS) * math.sin(a + 0.06))
        d.polygon([tip, b1, b2], fill=ORANGE + (255,))
        # 中心轴 + 指针
        d.ellipse([c - 3 * SS, c - 3 * SS, c + 3 * SS, c + 3 * SS], fill=INK + (200,))
        pa = -TAU / 4 + TAU * 0.13
        d.line([(c, c), (c + (R - 32 * SS) * math.cos(pa), c + (R - 32 * SS) * math.sin(pa))],
               fill=ORANGE + (230,), width=SS)
        frames.append(big.resize((S, S), Image.LANCZOS))
    sheet = Image.new("RGBA", (S, S * N))
    for i, fr in enumerate(frames):
        sheet.paste(fr, (0, i * S))
    sheet.save(os.path.join(OUT, "xyt_dial.png"), optimize=True)
    prev = []
    for fr in frames:
        bg = Image.new("RGBA", fr.size, (205, 209, 213, 255))
        bg.alpha_composite(fr)
        prev.append(bg.resize((S * 3, S * 3), Image.NEAREST).convert("P", palette=Image.ADAPTIVE))
    prev[0].save(os.path.join(PREV, "xyt_dial.gif"), save_all=True, append_images=prev[1:], duration=100, loop=0)
    print("xyt_dial", S, "x", N)


if __name__ == "__main__":
    paper()
    dial()
