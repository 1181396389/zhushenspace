"""
生成主神空间 UI 动态贴图（竖向精灵图：每帧上下堆叠，游戏内按时间逐帧播放，相当于 GIF）。
同时导出 GIF 预览到 tools/preview/ 便于查看。
用法: python3 tools/gen_anim_textures.py
"""
import math, os, random
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(ROOT, "..", "src/main/resources/assets/zhushenspace/textures/gui/anim")
PREV = os.path.join(ROOT, "preview")
os.makedirs(OUT, exist_ok=True)
os.makedirs(PREV, exist_ok=True)
TAU = math.tau


def save(name, frames, ms):
    w, h = frames[0].size
    sheet = Image.new("RGBA", (w, h * len(frames)))
    for i, f in enumerate(frames):
        sheet.paste(f, (0, i * h))
    sheet.save(os.path.join(OUT, name + ".png"), optimize=True)
    # GIF 预览（放大 3 倍，深色底）
    prev = []
    for f in frames:
        bg = Image.new("RGBA", f.size, (8, 14, 22, 255))
        bg.alpha_composite(f)
        prev.append(bg.resize((w * 3, h * 3), Image.NEAREST).convert("P", palette=Image.ADAPTIVE))
    prev[0].save(os.path.join(PREV, name + ".gif"), save_all=True, append_images=prev[1:],
                 duration=ms, loop=0, disposal=2)
    print(name, f"{w}x{h} x{len(frames)} frames")


def clamp(v, a=0, b=255):
    return max(a, min(b, int(v)))


# ---------- 1. 星云背景（主神空间面板底纹） ----------
def taiji():
    S, N, SS = 32, 24, 8  # 超采样抗锯齿
    frames = []
    for n in range(N):
        big = Image.new("RGBA", (S * SS, S * SS))
        d = ImageDraw.Draw(big)
        c = S * SS / 2
        r = c - 3 * SS
        # 外发光环
        glow = Image.new("RGBA", big.size)
        gd = ImageDraw.Draw(glow)
        pulse = 0.5 + 0.5 * math.sin(TAU * n / N)
        gd.ellipse([c - r - 2 * SS, c - r - 2 * SS, c + r + 2 * SS, c + r + 2 * SS],
                   fill=(80, 190, 255, int(90 + 80 * pulse)))
        glow = glow.filter(ImageFilter.GaussianBlur(SS * 1.5))
        big.alpha_composite(glow)
        light, dark = (232, 244, 255, 255), (10, 22, 34, 255)
        d.ellipse([c - r, c - r, c + r, c + r], fill=light)
        d.pieslice([c - r, c - r, c + r, c + r], 90, 270, fill=dark)
        d.ellipse([c - r / 2, c - r, c + r / 2, c], fill=dark)
        d.ellipse([c - r / 2, c, c + r / 2, c + r], fill=light)
        e = r / 7
        d.ellipse([c - e, c - r / 2 - e, c + e, c - r / 2 + e], fill=light)
        d.ellipse([c - e, c + r / 2 - e, c + e, c + r / 2 + e], fill=dark)
        d.ellipse([c - r, c - r, c + r, c + r], outline=(91, 155, 213, 255), width=SS)
        big = big.rotate(-360 * n / N, resample=Image.BICUBIC)
        frames.append(big.resize((S, S), Image.LANCZOS))
    save("taiji", frames, 70)


# ---------- 3. 符文法阵（标题饰纹 / 打坐进度环） ----------
def sigil():
    S, N, SS = 64, 32, 4
    rnd = random.Random(3)
    runes = [rnd.randrange(5) for _ in range(16)]
    frames = []
    for n in range(N):
        t = n / N
        big = Image.new("RGBA", (S * SS, S * SS))
        d = ImageDraw.Draw(big)
        c = S * SS / 2
        pulse = 0.5 + 0.5 * math.sin(TAU * t)
        col = (90, 200, 255, int(170 + 85 * pulse))
        dim = (60, 140, 210, int(110 + 60 * pulse))

        def ring(rad, w, fill):
            d.ellipse([c - rad, c - rad, c + rad, c + rad], outline=fill, width=w)

        R = c - 2 * SS
        ring(R, SS, col)
        ring(R - 7 * SS, SS, dim)
        # 外圈符文（顺时针）
        for i, kind in enumerate(runes):
            a = TAU * i / len(runes) + TAU * t / 4
            rr = R - 3.5 * SS
            x, y = c + rr * math.cos(a), c + rr * math.sin(a)
            s = 1.6 * SS
            if kind == 0:
                d.line([x - s, y, x + s, y], fill=col, width=SS)
            elif kind == 1:
                d.line([x, y - s, x, y + s], fill=col, width=SS)
            elif kind == 2:
                d.ellipse([x - s * .7, y - s * .7, x + s * .7, y + s * .7], outline=col, width=SS)
            elif kind == 3:
                d.polygon([(x, y - s), (x + s, y + s), (x - s, y + s)], outline=col)
            else:
                d.line([x - s, y - s, x + s, y + s], fill=col, width=SS)
        # 内圈六芒星（逆时针）
        for k in range(2):
            pts = []
            for i in range(3):
                a = TAU * i / 3 + k * TAU / 6 - TAU * t / 6 - TAU / 4
                pts.append((c + (R - 9 * SS) * math.cos(a), c + (R - 9 * SS) * math.sin(a)))
            d.polygon(pts, outline=dim)
            d.line(pts + [pts[0]], fill=dim, width=SS)
        ring(5 * SS + pulse * 2 * SS, SS, col)
        glow = big.filter(ImageFilter.GaussianBlur(SS * 2))
        out = Image.new("RGBA", big.size)
        out.alpha_composite(glow)
        out.alpha_composite(big)
        frames.append(out.resize((S, S), Image.LANCZOS))
    save("sigil", frames, 60)


# ---------- 4. 能量流光（能量条/进度条填充叠层，白色，游戏内按池颜色染色） ----------
def energy_flow():
    W, H, N = 16, 32, 16
    rnd = random.Random(11)
    bubbles = [(rnd.uniform(1, W - 2), rnd.uniform(0, H), rnd.uniform(0.6, 1.4)) for _ in range(7)]
    frames = []
    for n in range(N):
        t = n / N
        img = Image.new("RGBA", (W, H))
        px = img.load()
        for y in range(H):
            for x in range(W):
                # 向上流动的斜向条纹
                v = math.sin((y + x * 0.6) * TAU / 16 + TAU * t)
                a = clamp(30 + 30 * v)
                px[x, y] = (255, 255, 255, a)
        d = ImageDraw.Draw(img)
        for bx, by, sp in bubbles:
            yy = (by - t * H * sp) % H  # 保证循环
            d.point((int(bx), int(yy)), fill=(255, 255, 255, 200))
            d.point((int(bx), int(yy + 1) % H), fill=(255, 255, 255, 90))
        frames.append(img)
    save("energy_flow", frames, 60)


# ---------- 5. 边框角饰（四角闪烁的菱形光点） ----------
def cosmos():
    W, H, N = 256, 192, 32
    rnd = random.Random(42)
    # 静态底图：深空 + 银河星云带（斜向），用多层正弦噪声
    base = Image.new("RGBA", (W, H))
    px = base.load()
    for y in range(H):
        for x in range(W):
            # 银河带：到斜线距离
            d = (y - (H * 0.75 - x * 0.45)) / 38.0
            band = math.exp(-d * d)
            n = (math.sin(x * 0.09 + y * 0.05) + math.sin(x * 0.031 - y * 0.087 + 1.3)
                 + math.sin(x * 0.17 + y * 0.13 + 2.1) * 0.5) / 2.5
            neb = max(0.0, band * (0.65 + 0.45 * n))
            r = 4 + 70 * neb * (0.6 + 0.4 * math.sin(x * 0.02))
            g = 3 + 30 * neb
            b = 14 + 110 * neb
            # 边缘暗角
            vx, vy = (x - W / 2) / (W / 2), (y - H / 2) / (H / 2)
            vig = 1 - 0.45 * (vx * vx + vy * vy)
            px[x, y] = (clamp(r * vig), clamp(g * vig), clamp(b * vig), 255)
    # 银河尘埃：带内密集小星
    base = base.convert("RGB"); d0 = ImageDraw.Draw(base, "RGBA")
    for _ in range(900):
        x = rnd.uniform(0, W)
        y = H * 0.75 - x * 0.45 + rnd.gauss(0, 20)
        if 0 <= y < H:
            a = rnd.randint(40, 140)
            d0.point((x, y), fill=(200, 190, 255, a))
    # 螺旋星系（右上）
    gal = Image.new("RGBA", (W, H))
    gd = ImageDraw.Draw(gal)
    gx, gy = W * 0.78, H * 0.22
    for i in range(1400):
        arm = i % 2
        t = rnd.uniform(0, 3.2)
        ang = t * 2.2 + arm * math.pi + rnd.gauss(0, 0.25)
        rr = t * 9
        x = gx + rr * math.cos(ang)
        y = gy + rr * math.sin(ang) * 0.45
        a = int(200 * (1 - t / 3.4))
        gd.point((x, y), fill=(210, 200, 255, max(20, a)))
    gd.ellipse([gx - 4, gy - 2, gx + 4, gy + 2], fill=(255, 240, 220, 255))
    gal = Image.alpha_composite(gal.filter(ImageFilter.GaussianBlur(1.2)), gal)
    base = base.convert("RGBA"); base.alpha_composite(gal)
    base.alpha_composite(base.filter(ImageFilter.GaussianBlur(0.6)).point(lambda v: v // 3))

    stars = [(rnd.randrange(W), rnd.randrange(H), rnd.uniform(0, TAU), rnd.random(),
              rnd.choice([(255, 255, 255), (180, 210, 255), (255, 230, 190), (220, 190, 255)]))
             for _ in range(110)]
    frames = []
    for n in range(N):
        t = n / N
        img = base.copy()
        img = img.convert("RGB"); d = ImageDraw.Draw(img, "RGBA")
        for sx, sy, ph, big, col in stars:
            a = (math.sin(ph + TAU * t * (2 if big > 0.7 else 1)) + 1) / 2
            d.point((sx, sy), fill=col + (clamp(60 + 195 * a),))
            if big > 0.9:  # 亮星十字星芒
                for k in (1, 2):
                    fa = clamp(170 * a / k)
                    for dx, dy in ((k, 0), (-k, 0), (0, k), (0, -k)):
                        d.point((sx + dx, sy + dy), fill=col + (fa,))
        # 流星：前 40% 帧从左上划向右下
        if t < 0.4:
            p = t / 0.4
            hx, hy = 20 + p * 150, 10 + p * 70
            for i in range(18):
                a = clamp(255 * (1 - i / 18) * (1 - p * 0.5))
                d.point((hx - i * 2, hy - i * 0.93), fill=(230, 240, 255, a))
                d.point((hx - i * 2 + 1, hy - i * 0.93), fill=(230, 240, 255, a // 2))
        frames.append(img.convert("RGBA"))
    save("cosmos", frames, 100)


if __name__ == "__main__":
    import sys
    todo = sys.argv[1:] or ["taiji", "sigil", "energy_flow", "cosmos"]
    for name in todo:
        globals()[name]()
