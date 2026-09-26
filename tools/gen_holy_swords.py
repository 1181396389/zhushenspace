"""
战斗技能栏的两柄剑（Fate）：A 栏 = 誓约胜利之剑（Excalibur），B 栏 = 乖离剑（Ea）。
逻辑尺寸 240x30，贴图 2 倍 480x60。两柄剑共用同一槽位布局（与 BladeBar.java 常量一致）：
  技能槽 20x20：x = 36 + i*21, y = 5；护手宝石中心 (23,15)；剑柄 0..30。
输出：excalibur_bar.png / excalibur_glow.png（20 帧）/ ea_bar.png / ea_glow.png（20 帧）
用法: python3 tools/gen_holy_swords.py
"""
import math, os
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(ROOT, "..", "src/main/resources/assets/zhushenspace/textures/gui/anim")
PREV = os.path.join(ROOT, "preview")
W, H, K = 240, 30, 2
SLOT0, PITCH, SLOT, SLOTY = 36, 21, 20, 5
GEM = (23, 15)
N = 20
TAU = math.tau

GOLD = (224, 178, 72)
GOLD_HI = (255, 228, 140)
GOLD_LO = (150, 104, 36)
BLUE = (34, 66, 150)
BLUE_HI = (80, 130, 220)
BLUE_LO = (18, 34, 86)
RED = (230, 30, 40)
RED_HI = (255, 110, 90)


def c(v):
    return max(0, min(255, int(v)))


def L(v):
    return v * K


def new():
    return Image.new("RGBA", (W * K, H * K))


def vgrad(img, x0, x1, y0f, y1f, color_at):
    """在 [x0,x1) 列上按每列的 (y0,y1) 竖向填充，color_at(v∈0..1, x) → RGB"""
    px = img.load()
    for x in range(int(x0), int(x1)):
        y0, y1 = y0f(x), y1f(x)
        for y in range(int(round(y0)), int(round(y1))):
            v = (y - y0) / max(1.0, (y1 - y0))
            px[x, y] = color_at(v, x) + (255,)


def sockets(d, rim_hi, rim_lo, inner_top, inner_bot):
    for i in range(9):
        x, y, s = L(SLOT0 + i * PITCH), L(SLOTY), L(SLOT)
        d.rectangle([x - K, y - K, x + s + K - 1, y + s + K - 1], fill=rim_lo)
        d.line([(x - K, y + s + K - 1), (x + s + K - 1, y + s + K - 1)], fill=rim_hi)
        d.line([(x + s + K - 1, y - K), (x + s + K - 1, y + s + K - 1)], fill=rim_hi)
        for yy in range(s):
            f = yy / s
            col = tuple(c(inner_top[j] + (inner_bot[j] - inner_top[j]) * f) for j in range(3))
            d.line([(x, y + yy), (x + s - 1, y + yy)], fill=col)


def gem_socket(d, ring, hole):
    cx, cy, r = L(GEM[0]) + K // 2, L(GEM[1]), L(7)
    d.ellipse([cx - r - K, cy - r - K, cx + r + K, cy + r + K], fill=ring)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=hole)


def save_sheet(name, frames, ms):
    sheet = Image.new("RGBA", (W * K, H * K * len(frames)))
    for i, f in enumerate(frames):
        sheet.paste(f, (0, i * H * K))
    sheet.save(os.path.join(OUT, name + ".png"), optimize=True)
    print(name, len(frames), "frames")


# ============ 誓约胜利之剑 ============
def excalibur():
    img = new()
    d = ImageDraw.Draw(img)
    mid = L(15)
    tip0 = L(222)
    # 剑身：银白双刃直剑，自护手向剑尖微收，末端收成剑尖
    top = lambda x: L(2) + (L(1) * (x - L(30)) / (tip0 - L(30)) if x < tip0 else L(1) + (mid - L(3)) * (x - tip0) / (W * K - tip0))
    bot = lambda x: H * K - top(x)

    def steel(v, x):
        # 上刃亮、中脊微暗、下刃冷灰
        base = 238 - 70 * abs(v - 0.28) ** 0.9
        if abs(v - 0.5) < 0.04:
            base -= 25  # 中脊
        return (c(base - 10), c(base - 4), c(base + 6))
    vgrad(img, L(30), W * K, top, bot, steel)
    # 刃口：上刃白亮、下刃暗线
    for x in range(L(30), W * K):
        img.putpixel((x, int(top(x))), (255, 255, 255, 255))
        img.putpixel((x, int(bot(x)) - 1), (110, 118, 132, 255))
    # 金色镶嵌纹：平行两刃的细金线（至第 8 槽）
    for yy in (L(3) + 1, H * K - L(3) - 2):
        d.line([(L(31), yy), (L(205), yy)], fill=GOLD)
    # 剑根：金蓝饰板（护手之前的一小段）
    d.rectangle([L(30), L(3), L(35) - 1, H * K - L(3) - 1], fill=GOLD_LO)
    d.rectangle([L(31), L(6), L(34) - 1, H * K - L(6) - 1], fill=BLUE)
    d.line([(L(31), L(6)), (L(31), H * K - L(6) - 1)], fill=BLUE_HI)
    sockets(d, (250, 252, 255), (140, 148, 162), (44, 50, 60), (78, 86, 100))
    # 槽间金纹小刻（剑身刻文）
    for i in range(8):
        rx = L(SLOT0 + i * PITCH + SLOT) + K
        d.line([(rx, L(3)), (rx, L(4))], fill=GOLD_HI)
        d.line([(rx, H * K - L(4)), (rx, H * K - L(3))], fill=GOLD_HI)
    # 护手：金色十字格，两端外展（尾端上翘），蓝色珐琅嵌线
    gx0, gx1 = L(19), L(28)
    d.polygon([(L(17), 0), (L(21), L(2)), (L(26), L(2)), (L(30), 0),
               (L(28), L(7)), (gx1, H * K - L(7)), (L(30), H * K - 1), (L(26), H * K - L(2)),
               (L(21), H * K - L(2)), (L(17), H * K - 1), (gx0, H * K - L(7)), (gx0, L(7))], fill=GOLD)
    d.line([(L(17), 0), (gx0, L(7)), (gx0, H * K - L(7)), (L(17), H * K - 1)], fill=GOLD_HI, width=K)
    d.line([(L(30), 0), (gx1, L(7)), (gx1, H * K - L(7)), (L(30), H * K - 1)], fill=GOLD_LO, width=K)
    for yy in (L(3), H * K - L(4)):
        d.rectangle([L(21), yy, L(26), yy + K], fill=BLUE)
    gem_socket(d, GOLD_HI, BLUE_LO)
    # 握柄：皇家蓝 + 金线交叉缠绕
    px = img.load()
    for x in range(L(6), L(17)):
        for y in range(mid - L(4), mid + L(4)):
            v = (y - (mid - L(4))) / L(8)
            shade = 1.15 - 0.5 * abs(v - 0.35)
            col = tuple(c(BLUE[j] * shade) for j in range(3))
            if (x + y) % L(3) < 2 or (x - y) % L(3) < 2:
                col = GOLD if v < 0.6 else GOLD_LO
            px[x, y] = col + (255,)
    # 柄头：金色菱形柄头 + 蓝宝石
    d.polygon([(0, mid), (L(3), mid - L(5)), (L(6), mid), (L(3), mid + L(5))], fill=GOLD)
    d.line([(0, mid), (L(3), mid - L(5)), (L(6), mid)], fill=GOLD_HI, width=K)
    d.ellipse([L(3) - K * 2, mid - K * 2, L(3) + K * 2, mid + K * 2], fill=BLUE_HI)
    img.save(os.path.join(OUT, "excalibur_bar.png"), optimize=True)

    frames = []
    for n in range(N):
        t = n / N
        g = new()
        gd = ImageDraw.Draw(g)
        # 刃口金光：自剑根流向剑尖
        head = L(30) + t * (W * K - L(30)) * 1.25
        for i in range(70):
            x = head - i * 2
            if L(30) <= x < W * K:
                a = c(240 * (1 - i / 70))
                y = int(top(x))
                gd.rectangle([x, y - 1, x + 1, y + 1], fill=GOLD_HI + (a,))
        # 金色镶嵌纹闪烁（逐段点亮）
        p = 0.5 + 0.5 * math.sin(TAU * t)
        for yy in (L(3) + 1, H * K - L(3) - 2):
            for sx in range(L(31), L(205), 12):
                ph = 0.5 + 0.5 * math.sin(TAU * t - sx * 0.02)
                gd.line([(sx, yy), (sx + 6, yy)], fill=GOLD_HI + (c(40 + 150 * ph),))
        # 风王结界：半透明白色风痕自左向右掠过
        for k in range(3):
            fx = ((t + k / 3) % 1) * (W * K + 120) - 60
            fy = L(4) + k * L(10)
            pts = [(fx + i * 6, fy + 5 * math.sin(i * 0.5 + k)) for i in range(18)]
            gd.line(pts, fill=(235, 245, 255, 70), width=K)
        # 护手蓝宝石圈微光
        cx, cy = L(GEM[0]) + K // 2, L(GEM[1])
        gd.ellipse([cx - L(9), cy - L(9), cx + L(9), cy + L(9)], outline=BLUE_HI + (c(60 + 120 * p),), width=K)
        blur = g.filter(ImageFilter.GaussianBlur(2))
        out = new()
        out.alpha_composite(blur)
        out.alpha_composite(g)
        frames.append(out)
    save_sheet("excalibur_glow", frames, 70)
    return img, frames


# ============ 乖离剑 ============
# 三段黑色圆柱逐段收细，段间金环；相邻段反向旋转（红色刻纹上下反向滚动模拟）
SEGS = [(30, 98, 1), (100, 161, 2), (163, 222, 3)]  # (x0, x1, 上下内缩)
BANDS = [(97, 100), (160, 163)]
PAT = 10  # 刻纹竖向周期（逻辑像素）


def ea_pattern(d, x0, x1, y0, y1, offset, color):
    """乖离剑表面的红色刻纹：斜向裂纹 + 断续横纹（按 offset 竖向平移，周期 PAT）"""
    per = L(PAT)
    for x in range(int(x0), int(x1), L(9)):
        for k in range(-1, (y1 - y0) // per + 2):
            yy = y0 + k * per + offset % per
            # 斜向裂纹
            d.line([(x, yy), (x + L(3), yy + L(3))], fill=color, width=1)
            # 断续横纹
            d.line([(x + L(4), yy + L(5)), (x + L(6), yy + L(5))], fill=color, width=1)


def ea():
    img = new()
    d = ImageDraw.Draw(img)
    mid = L(15)
    px = img.load()
    # 圆柱：黑曜质感，上部窄高光带，下部沉入暗部
    def cyl(v, x):
        hi = math.exp(-((v - 0.22) / 0.07) ** 2) * 70
        base = 34 - 26 * abs(v - 0.4) + hi
        return (c(base + 6), c(base), c(base))
    for x0, x1, ins in SEGS:
        vgrad(img, L(x0), L(x1), lambda x, i=ins: L(i), lambda x, i=ins: H * K - L(i), cyl)
        # 段端倒角暗线
        d.line([(L(x0), L(ins)), (L(x0), H * K - L(ins))], fill=(10, 6, 6), width=K)
    # 锥形剑尖
    tip0 = L(222)
    vgrad(img, tip0, W * K, lambda x: L(3) + (mid - L(3)) * (x - tip0) / (W * K - tip0),
          lambda x: H * K - (L(3) + (mid - L(3)) * (x - tip0) / (W * K - tip0)), cyl)
    # 暗红底纹（静态，旋转光纹由 glow 叠层提供）
    for (x0, x1, ins) in SEGS:
        ea_pattern(d, L(x0) + K, L(x1) - K, L(ins), H * K - L(ins), 0, (90, 12, 14))
    # 段间金环
    for b0, b1 in BANDS:
        d.rectangle([L(b0), 0, L(b1) - 1, H * K - 1], fill=GOLD)
        d.line([(L(b0), 0), (L(b0), H * K - 1)], fill=GOLD_HI, width=K)
        d.line([(L(b1) - 1, 0), (L(b1) - 1, H * K - 1)], fill=GOLD_LO, width=K)
    sockets(d, (170, 130, 60), (60, 40, 16), (14, 6, 6), (48, 10, 12))
    # 护手：金色阶梯护环（圆柱接口）+ 红宝石
    d.rectangle([L(20), 0, L(30) - 1, H * K - 1], fill=GOLD)
    d.rectangle([L(27), L(1), L(30) - 1, H * K - L(1) - 1], fill=GOLD_LO)
    d.line([(L(20), 0), (L(20), H * K - 1)], fill=GOLD_HI, width=K)
    for yy in (L(2), H * K - L(3)):
        d.rectangle([L(21), yy, L(26), yy + K], fill=(120, 20, 20))
    gem_socket(d, GOLD_HI, (60, 6, 8))
    # 握柄：金色，菱格纹
    for x in range(L(6), L(20)):
        for y in range(mid - L(4), mid + L(4)):
            v = (y - (mid - L(4))) / L(8)
            shade = 1.1 - 0.45 * abs(v - 0.3)
            col = tuple(c(GOLD[j] * shade) for j in range(3))
            if (x + y) % L(4) == 0 or (x - y) % L(4) == 0:
                col = GOLD_LO
            px[x, y] = col + (255,)
    # 柄头：金色圆柄头 + 红宝石
    d.ellipse([0, mid - L(5), L(7), mid + L(5)], fill=GOLD)
    d.arc([0, mid - L(5), L(7), mid + L(5)], 180, 300, fill=GOLD_HI, width=K)
    d.ellipse([L(2), mid - L(2), L(5), mid + L(2)], fill=RED)
    img.save(os.path.join(OUT, "ea_bar.png"), optimize=True)

    frames = []
    for n in range(N):
        t = n / N
        g = new()
        gd = ImageDraw.Draw(g)
        # 三段反向旋转：刻纹竖向滚动一个周期为一循环（无缝）
        for si, (x0, x1, ins) in enumerate(SEGS):
            direction = 1 if si % 2 == 0 else -1
            off = int(direction * t * L(PAT))
            layer = new()
            ld = ImageDraw.Draw(layer)
            ea_pattern(ld, L(x0) + K, L(x1) - K, L(ins), H * K - L(ins), off, RED + (230,))
            mask = new()
            md = ImageDraw.Draw(mask)
            md.rectangle([L(x0) + K, L(ins), L(x1) - K - 1, H * K - L(ins) - 1], fill=(255, 255, 255, 255))
            for i in range(9):  # 扣除技能槽（含边框），避免刻纹压住技能图标
                sx = L(SLOT0 + i * PITCH)
                md.rectangle([sx - K, L(SLOTY) - K, sx + L(SLOT) + K - 1, L(SLOTY + SLOT) + K - 1], fill=(0, 0, 0, 0))
            layer.putalpha(Image.composite(layer.getchannel("A"), Image.new("L", layer.size, 0), mask.getchannel("A")))
            g.alpha_composite(layer)
        # 剑尖赤色漩涡：三道椭圆弧绕剑尖旋转（等距相位，循环无缝）
        cx, cy = L(229), L(15)
        for k in range(3):
            rx, ry = L(4 + k * 3), L(6 + k * 3)
            start = (360 * t * (1 if k % 2 == 0 else -1) + k * 120) % 360
            gd.arc([cx - rx, cy - ry, cx + rx, cy + ry], start, start + 150, fill=RED_HI + (190 - k * 40,), width=K)
        # 金环与红宝石脉动
        p = 0.5 + 0.5 * math.sin(TAU * t)
        gx, gy = L(GEM[0]) + K // 2, L(GEM[1])
        gd.ellipse([gx - L(9), gy - L(9), gx + L(9), gy + L(9)], outline=RED + (c(70 + 130 * p),), width=K)
        for b0, b1 in BANDS:
            gd.rectangle([L(b0), 0, L(b1) - 1, H * K - 1], outline=GOLD_HI + (c(40 + 90 * p),))
        blur = g.filter(ImageFilter.GaussianBlur(2.5))
        out = new()
        out.alpha_composite(blur)
        out.alpha_composite(g)
        frames.append(out)
    save_sheet("ea_glow", frames, 70)
    return img, frames


def preview(ex, exf, ea_img, eaf):
    """GIF 预览：上 Excalibur、下 Ea，暗底放大"""
    out = []
    for i in range(N):
        bg = Image.new("RGBA", (W * K + 40, H * K * 2 + 60), (22, 12, 9, 255))
        a = ex.copy(); a.alpha_composite(exf[i])
        b = ea_img.copy(); b.alpha_composite(eaf[i])
        bg.alpha_composite(a, (20, 20))
        bg.alpha_composite(b, (20, 40 + H * K))
        out.append(bg.resize((bg.width * 2, bg.height * 2), Image.NEAREST).convert("P", palette=Image.ADAPTIVE))
    out[0].save(os.path.join(PREV, "holy_swords.gif"), save_all=True, append_images=out[1:],
                duration=70, loop=0, disposal=2)
    out[3].save(os.path.join(PREV, "holy_swords_still.png"))


if __name__ == "__main__":
    ex, exf = excalibur()
    ea_img, eaf = ea()
    preview(ex, exf, ea_img, eaf)
