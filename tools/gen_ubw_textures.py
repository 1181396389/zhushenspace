"""
无限剑制风格贴图：
- ubw.png        战斗预设背景（燃烧黄昏天空、空中缓转巨大齿轮、荒原、插满大地的无数刀剑、升腾火星）
- blade_bar.png  战斗技能栏：一柄横置巨剑（剑柄/护手/剑身），九个技能槽为剑身上的锻铸凹槽（2 倍分辨率）
- blade_glow.png 剑身流光叠层（刃口游走的炽光 + 符文脉动）
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


# ---------------- 2. 巨剑技能栏 ----------------
# 逻辑尺寸 240x30（贴图 2 倍 480x60）。剑柄 0..30，护手宝石中心 (23,15)，
# 技能槽 20x20：x = 36 + i*21, y = 5；剑尖 223..240
BW, BH, K = 240, 30, 2
SLOT0, PITCH, SLOT, SLOTY = 36, 21, 20, 5


def blade_bar():
    W, H = BW * K, BH * K
    img = Image.new("RGBA", (W, H))
    d = ImageDraw.Draw(img)
    mid = H / 2
    # 剑身（钢铁渐变），剑尖渐收
    tip0 = 222 * K
    top, bot = 1 * K, H - 1 * K
    for x in range(30 * K, W):
        if x < tip0:
            y0, y1 = top, bot
        else:
            f = (x - tip0) / (W - tip0)
            y0, y1 = top + (mid - top) * f, bot - (bot - mid) * f
        for y in range(int(y0), int(y1)):
            v = (y - y0) / max(1, (y1 - y0))
            # 上刃亮、下刃暗，中间血槽
            base = 70 + 90 * (1 - abs(v - 0.3) * 1.6)
            if abs(y - mid) < 1.5 * K:
                base -= 35
            img.putpixel((x, y), (clamp(base * 0.92), clamp(base * 0.88), clamp(base * 0.9), 255))
    # 刃口高光线
    d.line([(30 * K, top), (tip0, top)], fill=(235, 225, 215, 255), width=K)
    d.line([(tip0, top), (W - 1, mid)], fill=(235, 225, 215, 255), width=K)
    d.line([(30 * K, bot - 1), (tip0, bot - 1)], fill=(60, 40, 35, 255), width=K)
    d.line([(tip0, bot - 1), (W - 1, mid)], fill=(60, 40, 35, 255), width=K)
    # 锻铸凹槽 + 槽间符文
    for i in range(9):
        x = (SLOT0 + i * PITCH) * K
        y = SLOTY * K
        s = SLOT * K
        d.rectangle([x - K, y - K, x + s + K - 1, y + s + K - 1], fill=(28, 18, 16, 255))
        for yy in range(s):
            f = yy / s
            d.line([(x, y + yy), (x + s - 1, y + yy)], fill=(clamp(20 + 40 * f), clamp(10 + 8 * f), 8, 255))
        d.line([(x - K, y + s + K - 1), (x + s + K - 1, y + s + K - 1)], fill=(170, 150, 140, 255), width=1)
        d.line([(x + s + K - 1, y - K), (x + s + K - 1, y + s + K - 1)], fill=(150, 130, 120, 255), width=1)
        # 槽四角铆钉
        for cx, cy in ((x + 3, y + 3), (x + s - 4, y + 3), (x + 3, y + s - 4), (x + s - 4, y + s - 4)):
            d.rectangle([cx - 1, cy - 1, cx, cy], fill=(90, 60, 45, 255))
        if i < 8:  # 槽间刻纹
            rx = x + s + K + 1
            d.line([(rx, mid - 5), (rx, mid + 5)], fill=(40, 26, 22, 255), width=1)
    # 剑柄：护手（竖向十字格）
    gx0, gx1 = 18 * K, 30 * K
    d.polygon([(gx0, 0), (gx1, 2 * K), (gx1, H - 2 * K), (gx0, H - 1)], fill=(96, 62, 30, 255))
    d.line([(gx0, 0), (gx0, H - 1)], fill=(220, 170, 90, 255), width=K)
    d.line([(gx1 - 1, 2 * K), (gx1 - 1, H - 2 * K)], fill=(60, 36, 18, 255), width=K)
    # 宝石座
    cx, cy, r = 23 * K + K // 2, mid, 7 * K
    d.ellipse([cx - r - K, cy - r - K, cx + r + K, cy + r + K], fill=(200, 150, 70, 255))
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(40, 10, 8, 255))
    # 握柄（缠绳）
    for x in range(5 * K, gx0):
        for y in range(int(mid - 4 * K), int(mid + 4 * K)):
            stripe = ((x + (y - mid) * 0.8) // (2 * K)) % 2
            c = (70, 30, 20) if stripe else (40, 18, 12)
            img.putpixel((x, y), c + (255,))
    # 柄头
    d.ellipse([0, mid - 5 * K, 7 * K, mid + 5 * K], fill=(200, 150, 70, 255))
    d.ellipse([2 * K, mid - 3 * K, 5 * K, mid + 3 * K], fill=(120, 20, 15, 255))
    img.save(os.path.join(OUT, "blade_bar.png"), optimize=True)
    print("blade_bar", img.size)
    return img


def blade_glow():
    W, H, N = BW * K, BH * K, 20
    mid = H / 2
    frames = []
    for n in range(N):
        t = n / N
        img = Image.new("RGBA", (W, H))
        d = ImageDraw.Draw(img)
        # 刃口炽光自柄向剑尖游走
        head = 30 * K + t * (W - 30 * K) * 1.25
        for i in range(60):
            x = head - i * 2
            if x < 30 * K or x >= W:
                continue
            a = clamp(230 * (1 - i / 60))
            tip0 = 222 * K
            yt = K if x < tip0 else K + (mid - K) * (x - tip0) / (W - tip0)
            d.rectangle([x, yt - 1, x + 1, yt + 1], fill=(255, 190, 90, a))
        # 槽间刻纹与护手宝石脉动
        p = 0.5 + 0.5 * math.sin(TAU * t)
        for i in range(8):
            rx = (SLOT0 + i * PITCH + SLOT) * K + K + 1
            ph = 0.5 + 0.5 * math.sin(TAU * t - i * 0.6)
            d.line([(rx, mid - 5), (rx, mid + 5)], fill=(255, 120, 40, clamp(60 + 180 * ph)), width=1)
        d.ellipse([23 * K + K // 2 - 9 * K, mid - 9 * K, 23 * K + K // 2 + 9 * K, mid + 9 * K],
                  outline=(255, 150, 60, clamp(50 + 120 * p)), width=K)
        glow = img.filter(ImageFilter.GaussianBlur(2))
        out = Image.new("RGBA", (W, H))
        out.alpha_composite(glow)
        out.alpha_composite(img)
        frames.append(out)
    save("blade_glow", frames, 70, 1)


if __name__ == "__main__":
    ubw()
    bar = blade_bar()
    blade_glow()
    # 合成预览：背景 + 两柄剑栏
    bg = Image.open(os.path.join(OUT, "ubw.png")).crop((0, 0, 256, 192)).resize((512, 384), Image.NEAREST)
    small = bar.resize((BW, BH), Image.LANCZOS)
    for i, y in enumerate((30, 110)):
        bg.alpha_composite(small.resize((int(BW * 2), int(BH * 2)), Image.NEAREST), (16, y))
    bg.save(os.path.join(PREV, "ubw_preview.png"))
