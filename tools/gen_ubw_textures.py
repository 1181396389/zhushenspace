"""
无限剑制风格贴图：
- ubw.png        战斗预设背景（燃烧黄昏天空、空中缓转巨大齿轮、荒原、插满大地的无数刀剑、升腾火星）
（两柄剑技能栏见 gen_holy_swords.py）
用法: python3 tools/gen_ubw_textures.py
"""
import math, os, random
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(ROOT, "..", "src/main/resources/assets/zhushenspace/textures/gui/anim")
PREV = os.path.join(ROOT, "preview")
TAU = math.tau


def clamp(v, a=0, b=255):
    return max(a, min(b, int(v)))


def save(name, frames, ms, prev_scale=2):
    w, h = frames[0].size
    sheet = Image.new("RGBA", (w, h * len(frames)))
    for i, f in enumerate(frames):
        sheet.paste(f, (0, i * h))
    sheet.save(os.path.join(OUT, name + ".png"), optimize=True)
    prev = []
    for f in frames:
        bg = Image.new("RGBA", f.size, (20, 10, 8, 255))
        bg.alpha_composite(f)
        prev.append(bg.resize((w * prev_scale, h * prev_scale), Image.NEAREST).convert("P", palette=Image.ADAPTIVE))
    prev[0].save(os.path.join(PREV, name + ".gif"), save_all=True, append_images=prev[1:],
                 duration=ms, loop=0, disposal=2)
    print(name, f"{w}x{h} x{len(frames)}")


def gear(draw, cx, cy, r, teeth, rot, fill):
    pts = []
    for i in range(teeth * 4):
        a = rot + TAU * i / (teeth * 4)
        rr = r if (i % 4) in (0, 1) else r * 0.84
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    draw.polygon(pts, fill=fill)
    draw.ellipse([cx - r * 0.62, cy - r * 0.62, cx + r * 0.62, cy + r * 0.62], fill=(0, 0, 0, 0))
    draw.ellipse([cx - r * 0.5, cy - r * 0.5, cx + r * 0.5, cy + r * 0.5], outline=fill, width=max(1, int(r * 0.08)))
    for k in range(6):
        a = rot + TAU * k / 6
        draw.line([cx, cy, cx + r * 0.62 * math.cos(a), cy + r * 0.62 * math.sin(a)], fill=fill,
                  width=max(1, int(r * 0.07)))
    draw.ellipse([cx - r * 0.14, cy - r * 0.14, cx + r * 0.14, cy + r * 0.14], fill=fill)


def sword(draw, x, y, length, angle, s, rim):
    """插在地上的剑：(x,y) 为入土点，angle 为偏离竖直的角度（度），s 为粗细比例"""
    a = math.radians(angle)
    dx, dy = math.sin(a), -math.cos(a)
    px, py = -dy, dx  # 垂直方向
    blade_w = max(1.0, 2.2 * s)
    hx, hy = x + dx * length, y + dy * length  # 护手位置
    body = (22, 10, 8, 255)
    draw.polygon([(x + px * blade_w * 0.3, y + py * blade_w * 0.3),
                  (hx + px * blade_w, hy + py * blade_w),
                  (hx - px * blade_w, hy - py * blade_w),
                  (x - px * blade_w * 0.3, y - py * blade_w * 0.3)], fill=body)
    # 刃口反光（夕照一侧）
    draw.line([(x + px * blade_w * 0.3, y + py * blade_w * 0.3), (hx + px * blade_w, hy + py * blade_w)],
              fill=rim, width=max(1, int(s * 0.8)))
    gw = blade_w * 3.2
    draw.line([(hx + px * gw, hy + py * gw), (hx - px * gw, hy - py * gw)], fill=body, width=max(1, int(2 * s)))
    gx, gy = hx + dx * length * 0.22, hy + dy * length * 0.22
    draw.line([(hx, hy), (gx, gy)], fill=body, width=max(1, int(1.8 * s)))
    r = max(1.0, 1.6 * s)
    draw.ellipse([gx - r, gy - r, gx + r, gy + r], fill=body)


# ---------------- 1. 无限剑制背景 ----------------
def ubw():
    W, H, N, SS = 256, 192, 24, 2
    HORIZON = 118
    rnd = random.Random(1999)
    # 天空 + 荒原静态层（超采样）
    sky = Image.new("RGBA", (W, H))
    px = sky.load()
    for y in range(H):
        for x in range(W):
            if y < HORIZON:
                t = y / HORIZON
                r = 38 + 200 * t ** 1.6
                g = 10 + 95 * t ** 2.2
                b = 8 + 20 * t
                # 灰烬云带
                n = math.sin(x * 0.045 + y * 0.21) + math.sin(x * 0.017 - y * 0.12 + 2) * 0.8
                cloud = max(0.0, n) * (0.25 + 0.5 * (1 - abs(t - 0.45) * 2))
                r, g, b = r * (1 - 0.35 * cloud), g * (1 - 0.45 * cloud), b * (1 - 0.3 * cloud)
            else:
                t = (y - HORIZON) / (H - HORIZON)
                r = 70 - 50 * t ** 0.6
                g = 28 - 18 * t ** 0.6
                b = 16 - 8 * t
                r += 10 * math.sin(x * 0.3 + y * 0.7)  # 地表碎石质感
            px[x, y] = (clamp(r), clamp(g), clamp(b), 255)
    # 地平线炽光
    glow = Image.new("RGBA", (W, H))
    ImageDraw.Draw(glow).rectangle([0, HORIZON - 6, W, HORIZON + 3], fill=(255, 150, 50, 170))
    sky.alpha_composite(glow.filter(ImageFilter.GaussianBlur(6)))

    # 剑冢（由远及近，越近越大）
    swords = Image.new("RGBA", (W * SS, H * SS))
    sd = ImageDraw.Draw(swords)
    items = []
    for _ in range(60):
        depth = rnd.random() ** 1.7  # 0 远 → 1 近
        y = HORIZON + 2 + depth * (H - HORIZON - 4)
        items.append((depth, rnd.uniform(-10, W + 10), y, rnd.uniform(-28, 28)))
    items.sort()
    for depth, x, y, ang in items:
        s = 0.45 + depth * 1.6
        length = 8 + depth * 34
        rim = (255, clamp(140 + 60 * (1 - depth)), 60, clamp(120 + 100 * depth))
        sword(sd, x * SS, y * SS, length * SS, ang, s * SS / 1.5, rim)
    swords = swords.resize((W, H), Image.LANCZOS)

    gears = [(W * 0.16, 52, 38, 12, 1), (W * 0.63, 34, 52, 16, -1), (W * 0.93, 80, 28, 10, 1), (W * 0.40, 88, 18, 8, -1)]
    embers = [(rnd.uniform(0, W), rnd.uniform(0, H), rnd.uniform(0.5, 1.5), rnd.uniform(0, TAU), rnd.random())
              for _ in range(70)]
    frames = []
    for n in range(N):
        t = n / N
        img = sky.copy()
        # 巨大齿轮：旋转一个齿距完成循环（无缝）
        gl = Image.new("RGBA", (W * SS, H * SS))
        gd = ImageDraw.Draw(gl)
        for gx, gy, r, teeth, d in gears:
            gear(gd, gx * SS, gy * SS, (r + 1.2) * SS, teeth, d * TAU * t / teeth, (255, 140, 60, 90))
            gear(gd, gx * SS, gy * SS, r * SS, teeth, d * TAU * t / teeth, (48, 16, 10, 185))
        gl = gl.resize((W, H), Image.LANCZOS)
        img.alpha_composite(gl)
        img.alpha_composite(swords)
        # 火星升腾（混合绘制）
        img = img.convert("RGB"); d = ImageDraw.Draw(img, "RGBA")
        for ex, ey, sp, ph, big in embers:
            yy = (ey - t * H * sp) % H
            xx = ex + 4 * math.sin(ph + TAU * t * 2)
            a = (math.sin(ph + TAU * t * 3) + 1) / 2
            col = (255, clamp(150 + 90 * a), clamp(40 + 60 * a), clamp(120 + 135 * a))
            d.point((xx, yy), fill=col)
            if big > 0.75:
                d.point((xx, yy + 1), fill=(255, 120, 30, clamp(90 * a)))
                d.point((xx + 1, yy), fill=(255, 190, 80, clamp(110 * a)))
        frames.append(img.convert("RGBA"))
    save("ubw", frames, 90)


if __name__ == "__main__":
    ubw()
