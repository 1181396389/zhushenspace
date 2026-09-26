"""命运石之门（Steins;Gate）属性页贴图。

sg_nixie.png   12 格 × (28x52)：格 0 = 辉光管空管（玻璃、蜂窝阳极网、叠放的暗数字、底座针脚），
               格 1..10 = 点亮数字 0-9，格 11 = 小数点（仅灯丝 + 辉光，叠加在空管上）。逻辑尺寸 7x13。
sg_gear.png    64x64 黄铜齿轮（12 齿、5 辐、轴孔）——外框交错齿轮，运行时旋转。
sg_badge.png   128x128 未来道具研究所 LAB MEM 徽章（白色单色，运行时着色）——冈部伦太郎（Lab Mem 001，创立者）。
sg_amadeus.png 128x128 神经元脑图（白色单色）——牧濑红莉栖（脑科学 / Amadeus）。
sg_phone.png   48x64 翻盖手机（白色单色）——冈部的「El Psy Kongroo」。
"""
import math
from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = "src/main/resources/assets/zhushenspace/textures/gui/anim/"
PREV = "tools/preview/"
SS = 6  # 超采样倍数
FONT_B = "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf"
FONT_S = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"

# ---------- 辉光管数字笔画（单位框 10 x 18） ----------
def digit_strokes(d, n):
    E = lambda *b: ("ell", b)
    A = lambda b, s, e: ("arc", b, s, e)
    L = lambda *p: ("line", p)
    return {
        0: [E(0, 0, 10, 18)],
        1: [L((5, 0), (5, 18)), L((5, 0), (2.2, 3.2))],
        2: [A((0, 0, 10, 9.5), 180, 35), L((8.6, 7.4), (0, 18), (10, 18))],
        3: [A((0.6, 0, 9.4, 8.6), 195, 90), A((0, 8.6, 10, 18), 270, 160)],
        4: [L((7.2, 18), (7.2, 0), (0, 12.2), (10, 12.2))],
        5: [L((9, 0), (1.6, 0), (1, 7.6)), A((0, 6.4, 10, 18), 215, 155)],
        6: [E(0, 7.6, 10, 18), A((0, 0, 20, 30), 185, 262)],
        7: [L((0, 0), (10, 0), (3.4, 18))],
        8: [E(1, 0, 9, 8.6), E(0, 8.6, 10, 18)],
        9: [E(0, 0, 10, 10.4), A((-10, -12, 10, 18), 5, 82)],
        10: [E(4, 15.6, 6, 17.6)],  # 小数点
    }[n]


def draw_digit(d, n, ox, oy, sc, fill, width):
    for kind, *args in digit_strokes(d, n):
        tr = lambda p: (ox + p[0] * sc, oy + p[1] * sc)
        if kind == "ell":
            x0, y0, x1, y1 = args[0]
            d.ellipse([*tr((x0, y0)), *tr((x1, y1))], outline=fill, width=width)
        elif kind == "arc":
            (x0, y0, x1, y1), s, e = args
            d.arc([*tr((x0, y0)), *tr((x1, y1))], s, e, fill=fill, width=width)
        else:
            d.line([tr(p) for p in args[0]], fill=fill, width=width, joint="curve")


CW, CH = 28, 52  # 单格贴图尺寸（逻辑 7x13 的 4 倍）


def tube_cell():
    W, H = CW * SS, CH * SS
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    # 玻璃管身：圆顶 + 深色玻璃
    d.rounded_rectangle([SS * 1, SS * 1, W - SS * 1, H - SS * 7], radius=W // 2 - SS, fill=(22, 17, 20, 235),
                        outline=(120, 100, 90, 150), width=SS)
    # 蜂窝阳极网
    for yy in range(SS * 6, H - SS * 10, SS * 3):
        for xx in range(SS * 3 + (yy // (SS * 3) % 2) * SS * 2, W - SS * 3, SS * 4):
            d.ellipse([xx, yy, xx + SS * 0.9, yy + SS * 0.9], fill=(70, 58, 50, 110))
    # 叠放的未点亮数字（辉光管特征：可以看到后面层叠的阴极）
    sc = (W - SS * 8) / 10
    for n in (8, 3, 0, 6, 1, 9, 2, 5, 4, 7):
        draw_digit(d, n, SS * 4, SS * 7, sc, (86, 64, 50, 120), max(1, int(SS * 0.7)))
    # 玻璃高光（左侧竖条 + 顶部弧光）
    d.rounded_rectangle([SS * 3, SS * 5, SS * 5, H - SS * 12], radius=SS, fill=(255, 255, 255, 38))
    d.arc([SS * 3, SS * 2, W - SS * 3, SS * 14], 200, 340, fill=(255, 255, 255, 60), width=SS)
    # 底座 + 针脚
    d.rectangle([SS * 2, H - SS * 8, W - SS * 2, H - SS * 3], fill=(48, 38, 30, 255))
    d.rectangle([SS * 2, H - SS * 8, W - SS * 2, H - SS * 7], fill=(150, 116, 64, 255))
    for px in range(SS * 5, W - SS * 3, SS * 5):
        d.rectangle([px, H - SS * 3, px + SS, H], fill=(140, 130, 120, 255))
    return im.resize((CW, CH), Image.LANCZOS)


def lit_cell(n):
    W, H = CW * SS, CH * SS
    mask = Image.new("L", (W, H), 0)
    sc = (W - SS * 8) / 10
    draw_digit(ImageDraw.Draw(mask), n, SS * 4, SS * 7, sc, 255, int(SS * 1.5))
    mask = mask.resize((CW, CH), Image.LANCZOS)
    glow = mask.filter(ImageFilter.GaussianBlur(3.2))
    halo = mask.filter(ImageFilter.GaussianBlur(1.2))
    out = Image.new("RGBA", (CW, CH))
    px = out.load()
    for y in range(CH):
        for x in range(CW):
            c, h1, g = mask.getpixel((x, y)) / 255, halo.getpixel((x, y)) / 255, glow.getpixel((x, y)) / 255
            # 核心近白黄，外晕橙
            a = min(1, c + h1 * 1.3 + g * 1.6)
            if a <= 0:
                continue
            t = min(1, c * 1.2)
            r = 255
            gg = int(120 + 110 * t)
            b = int(30 + 150 * t * t)
            px[x, y] = (r, gg, b, int(255 * a))
    return out


def nixie():
    sheet = Image.new("RGBA", (CW * 12, CH))
    sheet.paste(tube_cell(), (0, 0))
    for i in range(11):
        sheet.paste(lit_cell(i), (CW * (i + 1), 0))
    sheet.save(OUT + "sg_nixie.png")
    return sheet


# ---------- 齿轮 ----------
def gear_poly(cx, cy, r_out, r_in, teeth, phase=0.0):
    pts = []
    for i in range(teeth * 4):
        a = phase + i / (teeth * 4) * 2 * math.pi
        r = r_out if i % 4 in (1, 2) else r_in
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def gear():
    S = 64 * SS
    im = Image.new("RGBA", (S, S))
    d = ImageDraw.Draw(im)
    c = S / 2
    d.polygon(gear_poly(c, c, S * 0.49, S * 0.40, 12), fill=(120, 88, 42, 255))
    d.polygon(gear_poly(c, c, S * 0.47, S * 0.385, 12), fill=(196, 150, 78, 255))
    d.ellipse([c - S * .34, c - S * .34, c + S * .34, c + S * .34], fill=(150, 112, 56, 255))
    d.ellipse([c - S * .30, c - S * .30, c + S * .30, c + S * .30], fill=(0, 0, 0, 0))
    for k in range(5):  # 钟表齿轮式细辐
        a = k / 5 * 2 * math.pi
        d.line([(c, c), (c + S * .31 * math.cos(a), c + S * .31 * math.sin(a))], fill=(170, 128, 64, 255), width=int(S * .06))
    d.ellipse([c - S * .11, c - S * .11, c + S * .11, c + S * .11], fill=(206, 164, 92, 255))
    d.ellipse([c - S * .045, c - S * .045, c + S * .045, c + S * .045], fill=(30, 22, 16, 255))
    # 上半部高光
    hl = Image.new("L", (S, S), 0)
    ImageDraw.Draw(hl).ellipse([0, -S * .2, S, S * .6], fill=60)
    light = Image.new("RGBA", (S, S), (255, 240, 200, 0))
    light.putalpha(Image.composite(hl, Image.new("L", (S, S), 0), im.getchannel("A")))
    im = Image.alpha_composite(im, light)
    im.resize((64, 64), Image.LANCZOS).save(OUT + "sg_gear.png")


# ---------- 未来道具研究所徽章 ----------
def badge():
    S = 128 * SS
    im = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(im)
    c = S / 2
    d.polygon(gear_poly(c, c, S * .49, S * .44, 20), fill=255)
    d.ellipse([c - S * .42, c - S * .42, c + S * .42, c + S * .42], fill=0)
    d.ellipse([c - S * .40, c - S * .40, c + S * .40, c + S * .40], outline=255, width=int(S * .012))
    d.ellipse([c - S * .29, c - S * .29, c + S * .29, c + S * .29], outline=255, width=int(S * .012))
    font = ImageFont.truetype(FONT_B, int(S * .062))
    text = "FUTURE GADGET LABORATORY \u2022 "
    total = sum(font.getlength(ch) for ch in text)
    ang = -math.pi / 2 - (total / (S * .345)) / 2 * 0  # 从顶部顺时针环绕一周
    scale = 2 * math.pi / total
    for ch in text:
        w = font.getlength(ch)
        a = ang + (w / 2) * scale
        tile = Image.new("L", (int(S * .12), int(S * .12)), 0)
        ImageDraw.Draw(tile).text((tile.width / 2, tile.height / 2), ch, font=font, fill=255, anchor="mm")
        tile = tile.rotate(-math.degrees(a) - 90, resample=Image.BICUBIC)
        r = S * .345
        im.paste(255, (int(c + r * math.cos(a) - tile.width / 2), int(c + r * math.sin(a) - tile.height / 2)), tile)
        ang += w * scale
    f2 = ImageFont.truetype(FONT_B, int(S * .095))
    d.text((c, c - S * .075), "LAB", font=f2, fill=255, anchor="mm")
    d.text((c, c + S * .045), "MEM", font=f2, fill=255, anchor="mm")
    d.line([(c - S * .2, c + S * .12), (c + S * .2, c + S * .12)], fill=255, width=int(S * .01))
    f3 = ImageFont.truetype(FONT_S, int(S * .05))
    d.text((c, c + S * .18), "No.001", font=f3, fill=255, anchor="mm")
    save_mono(im, 128, "sg_badge.png")


# ---------- Amadeus 神经元脑 ----------
def amadeus():
    S = 128 * SS
    im = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(im)
    w = int(S * .014)
    # 侧视大脑轮廓（额叶在左，小脑在右下）
    outline = []
    for i in range(181):
        t = i / 180 * 2 * math.pi
        rx, ry = S * .40, S * .30
        bump = 1 + .05 * math.sin(7 * t) + .04 * math.sin(3 * t + 1)
        x = S * .5 + rx * math.cos(t) * bump
        y = S * .46 + ry * math.sin(t) * bump * (1.12 if math.sin(t) < 0 else .9)
        outline.append((x, y))
    d.line(outline + [outline[0]], fill=255, width=w, joint="curve")
    d.ellipse([S * .58, S * .60, S * .82, S * .78], outline=255, width=w)  # 小脑
    for k in range(5):
        d.arc([S * .6 + k * 0, S * .63 + k * S * .025, S * .8, S * .66 + k * S * .025], 20, 160, fill=255, width=w // 2)
    d.line([(S * .56, S * .72), (S * .54, S * .88)], fill=255, width=w * 2)  # 脑干
    # 脑回（波浪沟）
    for row in range(4):
        pts = []
        for i in range(60):
            x = S * (.20 + i / 59 * .6)
            y = S * (.30 + row * .085) + S * .018 * math.sin(i / 59 * 11 + row * 1.7)
            pts.append((x, y))
        d.line(pts, fill=150, width=w // 2 + 1, joint="curve")
    # 神经元网络（节点 + 突触连线）
    nodes = [(.28, .30), (.42, .22), (.58, .24), (.72, .33), (.34, .46), (.50, .40), (.66, .48),
             (.26, .58), (.44, .58), (.60, .62), (.80, .45)]
    edges = [(0, 1), (1, 2), (2, 3), (0, 4), (1, 5), (2, 5), (3, 6), (4, 5), (5, 6), (4, 7), (4, 8), (5, 8),
             (6, 9), (8, 9), (3, 10), (6, 10), (7, 8)]
    for a, b in edges:
        d.line([(nodes[a][0] * S, nodes[a][1] * S), (nodes[b][0] * S, nodes[b][1] * S)], fill=255, width=w)
    for x, y in nodes:
        r = S * .022
        d.ellipse([x * S - r, y * S - r, x * S + r, y * S + r], fill=255)
    save_mono(im, 128, "sg_amadeus.png")


def phone():
    W, H = 48 * SS, 64 * SS
    im = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(im)
    w = int(SS * 2.4)
    # 翻盖手机：上屏 + 铰链 + 下键盘 + 天线
    d.rounded_rectangle([W * .18, H * .08, W * .82, H * .46], radius=SS * 5, outline=255, width=w)
    d.rectangle([W * .28, H * .14, W * .72, H * .38], outline=255, width=w // 2 + 1)
    d.rounded_rectangle([W * .14, H * .46, W * .86, H * .52], radius=SS * 2, fill=255)
    d.rounded_rectangle([W * .18, H * .52, W * .82, H * .95], radius=SS * 5, outline=255, width=w)
    for r in range(4):
        for cix in range(3):
            x = W * (.32 + cix * .18)
            y = H * (.60 + r * .08)
            d.ellipse([x - SS * 2, y - SS * 1.5, x + SS * 2, y + SS * 1.5], fill=255)
    d.line([(W * .74, H * .08), (W * .80, 0)], fill=255, width=w)
    save_mono(im, None, "sg_phone.png", size=(48, 64))


def save_mono(mask, s, name, size=None):
    size = size or (s, s)
    m = mask.resize(size, Image.LANCZOS)
    out = Image.new("RGBA", size, (255, 255, 255, 0))
    out.putalpha(m)
    out.save(OUT + name)


if __name__ == "__main__":
    sheet = nixie()
    gear()
    badge()
    amadeus()
    phone()
    # 预览：1.048596 读数
    prev = Image.new("RGBA", (CW * 8 + 16, CH + 16), (12, 11, 14, 255))
    tube = sheet.crop((0, 0, CW, CH))
    for i, ch in enumerate("1.048596"):
        n = 10 if ch == "." else int(ch)
        prev.alpha_composite(tube, (8 + i * CW, 8))
        cell = sheet.crop((CW * (n + 1), 0, CW * (n + 2), CH))
        prev.alpha_composite(cell, (8 + i * CW, 8))
    prev.resize((prev.width * 2, prev.height * 2), Image.NEAREST).save(PREV + "sg_meter.png")
    print("ok")
