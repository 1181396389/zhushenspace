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
def nebula():
    W, H, N = 160, 120, 24
    rnd = random.Random(7)
    waves = [(rnd.uniform(0.02, 0.07), rnd.uniform(0.02, 0.07), rnd.uniform(0, TAU), rnd.choice([1, -1]))
             for _ in range(6)]
    stars = [(rnd.randrange(W), rnd.randrange(H), rnd.uniform(0, TAU), rnd.random()) for _ in range(70)]
    frames = []
    for n in range(N):
        t = n / N
        img = Image.new("RGBA", (W, H))
        px = img.load()
        for y in range(H):
            for x in range(W):
                v = 0.0
                for fx, fy, ph, d in waves:
                    v += math.sin(x * fx + y * fy + ph + d * TAU * t)
                v = (v / len(waves) + 1) / 2  # 0..1
                v = v ** 2.2
                # 深空蓝 → 青 → 淡紫
                r = 6 + 40 * v * (0.5 + 0.5 * math.sin(x * 0.03 + TAU * t))
                g = 14 + 70 * v
                b = 28 + 120 * v
                px[x, y] = (clamp(r), clamp(g), clamp(b), 255)
        d = ImageDraw.Draw(img)
        for sx, sy, ph, big in stars:
            a = (math.sin(ph + TAU * t * (2 if big > 0.8 else 1)) + 1) / 2
            c = (clamp(170 + 85 * a), clamp(200 + 55 * a), 255, clamp(80 + 175 * a))
            d.point((sx, sy), fill=c)
            if big > 0.85 and a > 0.6:
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    d.point((sx + dx, sy + dy), fill=(150, 200, 255, clamp(120 * a)))
        frames.append(img)
    save("nebula", frames, 90)


# ---------- 2. 旋转太极图 ----------
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
def corner():
    S, N = 9, 16
    frames = []
    for n in range(N):
        a = 0.5 + 0.5 * math.sin(TAU * n / N)
        img = Image.new("RGBA", (S, S))
        d = ImageDraw.Draw(img)
        c = S // 2
        d.polygon([(c, 0), (S - 1, c), (c, S - 1), (0, c)], fill=(91, 155, 213, 255))
        d.polygon([(c, 2), (S - 3, c), (c, S - 3), (2, c)], fill=(clamp(150 + 105 * a), clamp(215 + 40 * a), 255, 255))
        d.point((c, c), fill=(255, 255, 255, 255))
        frames.append(img)
    save("corner", frames, 80)


if __name__ == "__main__":
    nebula(); taiji(); sigil(); energy_flow(); corner()
