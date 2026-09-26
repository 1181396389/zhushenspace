"""
战斗技能栏的两柄剑（Fate）：A 栏 = 誓约胜利之剑（Excalibur），B 栏 = 乖离剑（Ea）。按参考图高精度重绘。
逻辑尺寸 240x30，贴图 4 倍 960x120（先以 8 倍绘制再缩小抗锯齿）。两柄剑共用槽位布局（与 BladeBar.java 常量一致）：
  技能槽 20x20：x = 36 + i*21, y = 5；剑柄与护手在 x < 35。
  护手字母（栏位 A/B）位置：Excalibur (29,15) 蓝色饰板中央；Ea (22,9) 护手蓝圆。
输出：excalibur_bar.png / excalibur_glow.png（20 帧）/ ea_bar.png / ea_glow.png（20 帧）+ tools/preview/holy_swords.gif
用法: python3 tools/gen_holy_swords.py
"""
import math, os
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(ROOT, "..", "src/main/resources/assets/zhushenspace/textures/gui/anim")
PREV = os.path.join(ROOT, "preview")
W, H = 240, 30
K = 4          # 输出倍数
D = 8          # 绘制倍数
SLOT0, PITCH, SLOT, SLOTY = 36, 21, 20, 5
N = 20
TAU = math.tau
EX_GEM, EA_GEM = (29, 15), (22, 9)

GOLD = (222, 176, 70)
GOLD_HI = (255, 232, 150)
GOLD_LO = (140, 96, 32)
GOLD_DK = (92, 60, 18)
BLUE = (30, 52, 138)
BLUE_HI = (78, 118, 214)
BLUE_LO = (14, 24, 72)


def L(v):
    return v * D


def canvas():
    return Image.new("RGBA", (W * D, H * D))


def down(img):
    return img.resize((W * K, H * K), Image.LANCZOS)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def poly(d, pts, fill, outline=None, width=0):
    d.polygon([(L(x), L(y)) for x, y in pts], fill=fill)
    if outline:
        d.line([(L(x), L(y)) for x, y in pts + [pts[0]]], fill=outline, width=width, joint="curve")


def shade_region(img, mask, fn):
    """对 mask 覆盖的像素按 fn(xs, ys) -> (N,3) 上色（numpy 向量化）"""
    a = np.array(img)
    m = np.array(mask) > 0
    ys, xs = np.nonzero(m)
    cols = fn(xs.astype(np.float32), ys.astype(np.float32))
    a[ys, xs, :3] = np.clip(cols, 0, 255).astype(np.uint8)
    a[ys, xs, 3] = 255
    return Image.fromarray(a)


def mask_of(pts=None, rects=(), ellipses=()):
    m = Image.new("L", (W * D, H * D), 0)
    d = ImageDraw.Draw(m)
    if pts:
        d.polygon([(L(x), L(y)) for x, y in pts], fill=255)
    for r in rects:
        d.rectangle([L(r[0]), L(r[1]), L(r[2]) - 1, L(r[3]) - 1], fill=255)
    for e in ellipses:
        d.ellipse([L(e[0]), L(e[1]), L(e[2]), L(e[3])], fill=255)
    return m


def gold_metal(xs, ys, cy, r, base=GOLD):
    """圆柱金属竖向明暗：上 1/3 高光、下部沉暗"""
    v = np.clip((ys / D - cy) / r, -1, 1)
    k = 0.72 + 0.5 * np.exp(-((v + 0.45) / 0.28) ** 2) - 0.32 * np.clip(v, 0, 1)
    return np.stack([base[0] * k, base[1] * k, base[2] * k], 1)


def sockets(d, rim_hi, rim_lo, inner_top, inner_bot):
    for i in range(9):
        x, y, s = L(SLOT0 + i * PITCH), L(SLOTY), L(SLOT)
        d.rectangle([x - D, y - D, x + s + D - 1, y + s + D - 1], fill=rim_lo)
        d.rectangle([x - D, y + s, x + s + D - 1, y + s + D - 1], fill=rim_hi)
        d.rectangle([x + s, y - D, x + s + D - 1, y + s + D - 1], fill=rim_hi)
        for yy in range(s):
            d.line([(x, y + yy), (x + s - 1, y + yy)], fill=lerp(inner_top, inner_bot, yy / s))
        d.line([(x, y), (x + s - 1, y)], fill=lerp(inner_top, (0, 0, 0), 0.5), width=D // 2)


def glass_sockets(img, tint, alpha, rim_hi, rim_lo):
    """技能槽 = 嵌在剑身上的暗色玻璃窗：半透明压暗（剑身 / 刻纹透出）+ 细边（左上暗、右下亮）"""
    a = np.array(img).astype(np.float32)
    for i in range(9):
        x, y, s = L(SLOT0 + i * PITCH), L(SLOTY), L(SLOT)
        reg = a[y:y + s, x:x + s, :3]
        grad = np.linspace(1.0, 0.75, s)[:, None, None]
        a[y:y + s, x:x + s, :3] = reg * (1 - alpha) + np.array(tint) * alpha * grad
        a[y:y + s, x:x + s, 3] = 255
        e = D // 2
        a[y - e:y, x - e:x + s + e, :3] = rim_lo
        a[y:y + s, x - e:x, :3] = rim_lo
        a[y + s:y + s + e, x - e:x + s + e, :3] = rim_hi
        a[y - e:y + s + e, x + s:x + s + e, :3] = rim_hi
        a[y - e:y + s + e, x - e:x + s + e, 3] = 255
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))


def script(d, x0, x1, y, h, color, seed):
    """细小刻文（仿参考图剑身 / 护手上的古文字）：随机笔画组成的字符串"""
    rnd = np.random.default_rng(seed)
    x = x0
    while x < x1:
        w = rnd.uniform(0.9, 1.6)
        for _ in range(rnd.integers(2, 4)):
            ax, ay = x + rnd.uniform(0, w), y + rnd.uniform(0, h)
            bx, by = x + rnd.uniform(0, w), y + rnd.uniform(0, h)
            d.line([(L(ax), L(ay)), (L(bx), L(by))], fill=color, width=max(1, D // 5))
        if rnd.random() < 0.35:
            d.arc([L(x), L(y), L(x + w), L(y + h)], rnd.uniform(0, 360), rnd.uniform(0, 360) + 200, fill=color,
                  width=max(1, D // 5))
        x += w + rnd.uniform(0.25, 0.6)


def save_sheet(name, frames):
    sheet = Image.new("RGBA", (W * K, H * K * len(frames)))
    for i, f in enumerate(frames):
        sheet.paste(f, (0, i * H * K))
    sheet.save(os.path.join(OUT, name + ".png"), optimize=True)
    print(name, len(frames), "frames")


# ============ 誓约胜利之剑 ============
EX_TIP0 = 222


def ex_top(x):
    if x < EX_TIP0:
        return 2.6 + 0.8 * (x - 34) / (EX_TIP0 - 34)
    return 3.4 + (15 - 3.4) * ((x - EX_TIP0) / (W - EX_TIP0)) ** 1.1


def excalibur():
    img = canvas()
    # ---- 剑身：拉丝银刃，上斜面亮、中脊一亮一暗、下斜面冷暗，近剑尖泛淡金 ----
    pts = [(x, ex_top(x)) for x in np.linspace(33, W, 200)] + [(x, H - ex_top(x)) for x in np.linspace(W, 33, 200)]
    rnd = np.random.default_rng(3)
    brush = np.convolve(rnd.normal(0, 1, H * D + 40), np.ones(9) / 9, "same")[:H * D]

    def steel(xs, ys):
        x = xs / D
        top = np.array([ex_top(v) for v in x])
        v = (ys / D - top) / (H - 2 * top)
        k = np.where(v < 0.10, 250 - 60 * v / 0.10,
            np.where(v < 0.47, 226 - 20 * (v - 0.1) / 0.37,
            np.where(v < 0.50, 252,
            np.where(v < 0.53, 150,
            np.where(v < 0.90, 188 - 26 * (v - 0.53) / 0.37, 140)))))
        k = k + brush[ys.astype(int)] * 7
        warm = np.clip((x - 120) / 120, 0, 1) * 16
        return np.stack([k + warm * 0.6, k + warm * 0.3, k + 8 - warm * 0.4], 1)
    img = shade_region(img, mask_of(pts), steel)
    d = ImageDraw.Draw(img)
    # 刃口：上刃白线、下刃暗线
    d.line([(L(x), L(ex_top(x))) for x in np.linspace(34, W, 120)], fill=(255, 255, 255), width=D // 2)
    d.line([(L(x), L(H - ex_top(x)) - 2) for x in np.linspace(34, W, 120)], fill=(96, 104, 118), width=D // 2)
    # 剑根刻文（参考图：护手下方剑身上的暗色古文字），置于上沿留白
    script(d, 35.5, 120, 3.0, 1.35, (70, 72, 84), 7)
    script(d, 150, 205, 3.2, 1.2, (96, 98, 110), 11)
    img = glass_sockets(img, (30, 36, 48), 0.62, (236, 240, 248), (96, 104, 120))
    d = ImageDraw.Draw(img)

    # ---- 蓝色饰板（护手下方，指向剑尖的五边形；金边 + 皇家蓝珐琅 + 金色鸢尾纹）----
    plate = [(23.5, 6.2), (30.5, 6.2), (34.6, 15), (30.5, 23.8), (23.5, 23.8)]
    poly(d, plate, GOLD_LO)
    inner = [(24.6, 7.4), (30, 7.4), (33.3, 15), (30, 22.6), (24.6, 22.6)]
    img = shade_region(img, mask_of(inner), lambda xs, ys: np.stack(
        [BLUE[0] + 30 * np.exp(-((ys / D - 10) / 3) ** 2), BLUE[1] + 40 * np.exp(-((ys / D - 10) / 3) ** 2),
         BLUE[2] + 60 * np.exp(-((ys / D - 10) / 3) ** 2)], 1))
    d = ImageDraw.Draw(img)
    d.line([(L(x), L(y)) for x, y in plate + [plate[0]]], fill=GOLD_HI, width=D // 2)
    # 鸢尾纹（朝剑尖）：中茎 + 两侧卷叶 + 横箍
    fl = GOLD_HI
    d.line([(L(26), L(15)), (L(32.6), L(15))], fill=fl, width=D // 2 + 1)
    d.polygon([(L(32.6), L(15)), (L(30.8), L(14)), (L(30.8), L(16))], fill=fl)
    for s in (-1, 1):
        d.arc([L(26.5), L(15 - 0.2 * s - 3.4) if s < 0 else L(15.2), L(31.5), L(14.8) if s < 0 else L(15 + 3.4 + 0.2)],
              180 if s < 0 else 90, 270 if s < 0 else 180, fill=fl, width=D // 2)
    d.line([(L(27.6), L(12.8)), (L(27.6), L(17.2))], fill=fl, width=D // 2)

    # ---- 护手：金色人字形十字格（剑身侧平直，柄侧中凹，两端斜切）----
    guard = [(24, 0.6), (24, 29.4), (21.6, 29.4), (18, 27.6), (19.6, 22), (20.4, 15), (19.6, 8), (18, 2.4), (21.6, 0.6)]
    gm = mask_of(guard)
    img = shade_region(img, gm, lambda xs, ys: np.where(
        (xs / D < 21.8 + 0 * ys)[:, None], np.array(GOLD_HI) * 0.95, np.array(GOLD) * 0.92)
        * (1 - 0.18 * np.abs(ys / D - 15)[:, None] / 15))
    d = ImageDraw.Draw(img)
    d.line([(L(21.8), L(1.2)), (L(21.8), L(28.8))], fill=GOLD_LO, width=D // 3)   # 棱线
    d.line([(L(x), L(y)) for x, y in guard + [guard[0]]], fill=GOLD_DK, width=D // 3)
    d.line([(L(21.6), L(0.6)), (L(24), L(0.6))], fill=(255, 250, 220), width=D // 3)
    for y in (4.2, 25.8):   # 两端浅刻槽
        d.line([(L(20.4), L(y)), (L(23.4), L(y))], fill=GOLD_LO, width=D // 3)

    # ---- 握柄：深蓝皮革（圆柱明暗 + 斜缠纹），两端金箍 ----
    grip = mask_of(rects=[(6.5, 11.2, 18.4, 18.8)])
    img = shade_region(img, grip, lambda xs, ys: gold_metal(xs, ys, 15, 3.8, BLUE)
                       * (0.86 + 0.14 * (((xs + ys * 0.7) / D) % 1.6 > 0.3))[:, None])
    d = ImageDraw.Draw(img)
    for x0, x1 in ((5.6, 7.0), (17.4, 18.6)):
        img2 = shade_region(img, mask_of(rects=[(x0, 10.6, x1, 19.4)]), lambda xs, ys: gold_metal(xs, ys, 15, 4.4))
        img = img2
    # ---- 柄头：金色短柱 + 圆帽 ----
    img = shade_region(img, mask_of(rects=[(2.2, 11.8, 5.8, 18.2)], ellipses=[(0.4, 11.4, 4.2, 18.6)]),
                       lambda xs, ys: gold_metal(xs, ys, 15, 3.6))
    d = ImageDraw.Draw(img)
    d.line([(L(3.4), L(12)), (L(3.4), L(18))], fill=GOLD_LO, width=D // 3)
    bar = down(img)
    bar.save(os.path.join(OUT, "excalibur_bar.png"), optimize=True)

    # ---- 流光帧：刃口金光自剑根流向剑尖 + 斜向镜面反光扫过剑身 + 饰板微光 + 风王结界风痕 ----
    blade_m = np.array(mask_of(pts).resize((W * K, H * K), Image.LANCZOS)).astype(np.float32) / 255
    sock = Image.new("L", (W * K, H * K), 255)
    sd = ImageDraw.Draw(sock)
    for i in range(9):
        sx = (SLOT0 + i * PITCH) * K
        sd.rectangle([sx - K, SLOTY * K - K, sx + SLOT * K + K - 1, (SLOTY + SLOT) * K + K - 1], fill=0)
    sock = np.array(sock).astype(np.float32) / 255
    frames = []
    yy, xx = np.mgrid[0:H * K, 0:W * K].astype(np.float32)
    for n in range(N):
        t = n / N
        g = Image.new("RGBA", (W * K, H * K))
        gd = ImageDraw.Draw(g)
        head = 34 + t * (W - 34) * 1.3
        for i in range(90):
            x = head - i * 0.7
            if 34 <= x < W:
                a = int(240 * (1 - i / 90))
                y = ex_top(x)
                gd.ellipse([x * K - 2, y * K - 2, x * K + 2, y * K + 2], fill=GOLD_HI + (a,))
        # 斜向镜面反光（循环无缝）
        band = ((xx / K + yy / K * 1.6) / 70 - t) % 1
        spec = np.exp(-((band - 0.5) / 0.035) ** 2) * blade_m * (0.35 + 0.65 * sock) * 150
        sp = np.zeros((H * K, W * K, 4), np.uint8)
        sp[..., :3] = 255
        sp[..., 3] = np.clip(spec, 0, 255).astype(np.uint8)
        g.alpha_composite(Image.fromarray(sp))
        # 风王结界：淡白风痕
        for k in range(3):
            fx = ((t + k / 3) % 1) * (W + 60) - 30
            fy = 4 + k * 10
            gd.line([((fx + i * 1.5) * K, (fy + 1.3 * math.sin(i * 0.5 + k)) * K) for i in range(20)],
                    fill=(235, 245, 255, 60), width=K // 2)
        p = 0.5 + 0.5 * math.sin(TAU * t)
        gd.polygon([(x * K, y * K) for x, y in [(23.5, 6.2), (30.5, 6.2), (34.6, 15), (30.5, 23.8), (23.5, 23.8)]],
                   outline=BLUE_HI + (int(80 + 140 * p),))
        blur = g.filter(ImageFilter.GaussianBlur(3))
        out = Image.new("RGBA", g.size)
        out.alpha_composite(blur)
        out.alpha_composite(g)
        frames.append(out)
    save_sheet("excalibur_glow", frames)
    return bar, frames


# ============ 乖离剑 ============
# 三段圆柱（斜切接缝）+ 红色折线刻纹；相邻段反向旋转——刻纹定义在圆柱展开面上，按 asin 投影到屏幕，旋转即展开面平移
SEGS = [(34.6, 101.5, 3.0), (101.5, 167.5, 3.4), (167.5, 229.0, 3.8)]   # (x0, x1, 上下内缩)
SEAM = 1.8          # 斜切接缝的倾斜（上端比下端右移的逻辑像素）
EA_PER = 12.0       # 刻纹沿圆周的周期（逻辑像素，展开面）


def zigzag_field(w_px, per_px, phase_px, scale):
    """在展开面 (x, u) 上绘制折线刻纹，返回 [u, x] 的 0..1 强度（u 方向周期 per_px）"""
    hgt = int(per_px * 3)
    im = Image.new("L", (w_px, hgt), 0)
    d = ImageDraw.Draw(im)
    s = scale
    for base in np.arange(-per_px, hgt + per_px, per_px):
        for j, off in enumerate((0, 2.2 * s)):   # 两条平行粗线为一组
            y0 = base + off
            pts = []
            x = -30 * s
            while x < w_px + 30 * s:
                # 梯形折线：平 9 → 斜升 3 → 平 5 → 斜降 3 （参考图的锯齿雷纹）
                pts += [(x, y0), (x + 9 * s, y0), (x + 12 * s, y0 - 3.2 * s), (x + 17 * s, y0 - 3.2 * s), (x + 20 * s, y0)]
                x += 20 * s
            d.line(pts, fill=255, width=int(1.05 * s), joint="curve")
    a = np.array(im).astype(np.float32) / 255
    return a, hgt


SOCKET_DIM = 0.35   # 刻纹透过技能槽玻璃的亮度


def ea_pattern_layer(scale, t_frac, dims):
    """返回 [H*scale, W*scale] 的刻纹强度（已按圆柱投影、按段反向旋转、扣除技能槽）"""
    Wp, Hp = W * scale, H * scale
    out = np.zeros((Hp, Wp), np.float32)
    field, fh = zigzag_field(Wp, EA_PER * scale, 0, scale)
    ys = np.arange(Hp, dtype=np.float32) / scale
    for si, (x0, x1, ins) in enumerate(SEGS):
        cy, r = 15.0, 15.0 - ins
        v = np.clip((ys - cy) / r, -1, 1)
        theta = np.arcsin(v)
        u = theta * r                                 # 展开面坐标
        direction = 1 if si % 2 == 0 else -1
        shift = direction * t_frac * EA_PER + si * 3.7
        ui = (((u + shift) % EA_PER) * scale + EA_PER * scale).astype(int) % fh
        facing = np.clip(np.cos(theta), 0, 1) ** 0.6
        inside = (np.abs(ys - cy) < r)
        rows = field[ui] * (facing * inside)[:, None]
        xs = np.arange(Wp) / scale
        for yi in range(Hp):
            if not inside[yi]:
                continue
            yl = ys[yi]
            sk = SEAM * (0.5 - (yl - ins) / (2 * r))   # 斜切：上端右移
            lo, hi = x0 + sk + 0.6, x1 + (SEAM * (0.5 - (yl - ins) / (2 * r)) if si < 2 else 0) - 0.6
            if si == 2:
                hi = min(hi, 229 - (1 - math.sqrt(max(0, 1 - ((yl - cy) / r) ** 2))) * 5)
            sel = (xs >= lo) & (xs < hi)
            out[yi, sel] = np.maximum(out[yi, sel], rows[yi, sel])
    # 扣除技能槽（含边框）
    for i in range(9):
        sx = (SLOT0 + i * PITCH - 1) * scale
        out[(SLOTY - 1) * scale:(SLOTY + SLOT + 1) * scale, sx:sx + (SLOT + 2) * scale] *= SOCKET_DIM
    return out


def ea():
    img = canvas()
    # ---- 三段圆柱：暗绯黑曜质感（中线偏红、上缘窄高光、边缘沉黑）----
    def body(ins):
        r = 15 - ins

        def f(xs, ys):
            v = np.clip((ys / D - 15) / r, -1, 1)
            hi = np.exp(-((v + 0.55) / 0.12) ** 2) * 60
            core = (1 - np.abs(v) ** 1.6)
            return np.stack([18 + 52 * core + hi, 6 + 8 * core + hi * 0.7, 8 + 10 * core + hi * 0.7], 1)
        return f
    for si, (x0, x1, ins) in enumerate(SEGS):
        top, bot = ins, H - ins
        right_top = x1 + SEAM / 2 if si < 2 else x1
        right_bot = x1 - SEAM / 2 if si < 2 else x1
        pts = [(x0 + SEAM / 2, top), (right_top, top), (right_bot, bot), (x0 - SEAM / 2, bot)]
        if si == 2:
            # 圆形剑尖：末段以半椭圆收圆
            r = 15 - ins
            arc = [(x1 + 5 * math.cos(a) - 5, 15 + r * math.sin(a)) for a in np.linspace(-math.pi / 2, math.pi / 2, 30)]
            pts = [(x0 + SEAM / 2, top)] + arc + [(x0 - SEAM / 2, bot)]
        img = shade_region(img, mask_of(pts), body(ins))
    d = ImageDraw.Draw(img)
    # 斜切接缝：暗缝 + 一侧金属亮边
    for si in range(2):
        x1, ins = SEGS[si][1], SEGS[si + 1][2]
        d.line([(L(x1 + SEAM / 2), L(SEGS[si][2])), (L(x1 - SEAM / 2), L(H - SEGS[si][2]))], fill=(4, 0, 0), width=D)
        d.line([(L(x1 + SEAM / 2 + 0.5), L(ins)), (L(x1 - SEAM / 2 + 0.5), L(H - ins))], fill=(110, 40, 40), width=D // 3)
    # 静态暗红刻纹（发光层由 glow 帧提供）
    pat = ea_pattern_layer(D, 0, None)
    a = np.array(img)
    m = pat > 0.05
    a[m, 0] = np.clip(a[m, 0] * (1 - pat[m]) + 150 * pat[m], 0, 255)
    a[m, 1] = np.clip(a[m, 1] * (1 - pat[m]) + 20 * pat[m], 0, 255)
    a[m, 2] = np.clip(a[m, 2] * (1 - pat[m]) + 28 * pat[m], 0, 255)
    img = Image.fromarray(a)
    d = ImageDraw.Draw(img)
    img = glass_sockets(img, (8, 2, 3), 0.45, (130, 44, 40), (20, 4, 4))
    d = ImageDraw.Draw(img)

    # ---- 金头：圆形剑尖外罩金帽（环箍 + 圆顶 + 小尖钮）----
    img = shade_region(img, mask_of(rects=[(229.2, 9.2, 231.4, 20.8)]), lambda xs, ys: gold_metal(xs, ys, 15, 5.8))
    img = shade_region(img, mask_of(ellipses=[(228.6, 10.4, 237.4, 19.6)]) if False else
                       mask_of(pts=[(231.2, 10.4)] + [(231.2 + 5.2 * math.cos(a), 15 + 4.6 * math.sin(a))
                                                     for a in np.linspace(-math.pi / 2, math.pi / 2, 24)] + [(231.2, 19.6)]),
                       lambda xs, ys: gold_metal(xs, ys, 15, 4.6))
    img = shade_region(img, mask_of(ellipses=[(235.6, 13.3, 239.6, 16.7)]), lambda xs, ys: gold_metal(xs, ys, 15, 1.7, GOLD_HI))
    d = ImageDraw.Draw(img)
    d.line([(L(229.2), L(9.2)), (L(229.2), L(20.8))], fill=GOLD_HI, width=D // 3)
    d.line([(L(231.4), L(9.6)), (L(231.4), L(20.4))], fill=GOLD_DK, width=D // 3)
    d.arc([L(227.8), L(11.2), L(235.8), L(18.8)], 290, 350, fill=(255, 250, 220), width=D // 3)

    # ---- 护手：金色不对称翼（上翼尖指向柄侧，下翼沿剑身方向收尖），蓝色爪痕 + 蓝圆 + 刻文 ----
    guard = [(17.5, 12.5), (13.0, 0.4), (19.5, 1.4), (27.5, 1.0), (34.8, 3.6), (34.8, 22.5),
             (33.8, 29.6), (27.0, 27.2), (20.5, 26.8), (16.8, 20.5)]

    def gshade(xs, ys):
        x, y = xs / D, ys / D
        # 折面：沿对角棱线分为亮 / 暗两面
        lit = (y < 0.62 * (x - 14) + 4)
        k = np.where(lit, 1.08, 0.86) - 0.12 * np.clip((x - 17) / 18, 0, 1)
        return np.stack([GOLD[0] * k, GOLD[1] * k, GOLD[2] * k], 1)
    img = shade_region(img, mask_of(guard), gshade)
    d = ImageDraw.Draw(img)
    d.line([(L(15.5), L(4.8)), (L(34.4), L(16.5))], fill=GOLD_HI, width=D // 3)     # 折棱
    # 蓝色爪痕（参考图：自上缘斜落的锥形蓝条）
    for x0, y0, x1, y1, w0 in ((26.5, 1.6, 21.8, 14.5, 1.5), (30.0, 2.4, 25.4, 18.5, 1.7), (33.4, 4.2, 29.2, 21.5, 1.6),
                               (34.4, 12.5, 31.8, 25.5, 1.0)):
        nx, ny = (y1 - y0), -(x1 - x0)
        ln = math.hypot(nx, ny)
        nx, ny = nx / ln * w0 / 2, ny / ln * w0 / 2
        poly(d, [(x0 - nx, y0 - ny), (x0 + nx, y0 + ny), (x1, y1)], BLUE)
        d.line([(L(x0 - nx), L(y0 - ny)), (L(x1), L(y1))], fill=BLUE_HI, width=D // 4)
    # 蓝圆（栏位字母位置）
    gx, gy = EA_GEM
    d.ellipse([L(gx - 3.6), L(gy - 3.6), L(gx + 3.6), L(gy + 3.6)], fill=GOLD_LO)
    d.ellipse([L(gx - 3.0), L(gy - 3.0), L(gx + 3.0), L(gy + 3.0)], fill=BLUE)
    d.arc([L(gx - 2.4), L(gy - 2.4), L(gx + 2.4), L(gy + 2.4)], 200, 300, fill=BLUE_HI, width=D // 3)
    # 下缘刻文
    script(d, 19.5, 33.5, 23.8, 1.5, GOLD_DK, 5)
    d.line([(L(x), L(y)) for x, y in guard + [guard[0]]], fill=GOLD_DK, width=D // 3)
    d.line([(L(13.0), L(0.4)), (L(19.5), L(1.4)), (L(27.5), L(1.0))], fill=GOLD_HI, width=D // 3)

    # ---- 握柄：细金杆 + 带缺口的环节，柄头为扇形展开 ----
    img = shade_region(img, mask_of(rects=[(4.0, 13.2, 17.6, 16.8)]), lambda xs, ys: gold_metal(xs, ys, 15, 1.9))
    img = shade_region(img, mask_of(ellipses=[(7.6, 11.2, 12.2, 18.8)]), lambda xs, ys: gold_metal(xs, ys, 15, 3.8))
    d = ImageDraw.Draw(img)
    d.line([(L(9.9), L(12.4)), (L(9.9), L(17.6))], fill=GOLD_DK, width=D // 2)         # 环节缺口
    d.arc([L(8.4), L(12.0), L(11.4), L(18.0)], 200, 330, fill=GOLD_HI, width=D // 3)
    fan = [(4.6, 13.4), (0.4, 8.6), (1.8, 15.0), (0.4, 21.4), (4.6, 16.6)]
    img = shade_region(img, mask_of(fan), lambda xs, ys: gold_metal(xs, ys, 15, 6.4))
    d = ImageDraw.Draw(img)
    for yy in (10.4, 12.8, 17.2, 19.6):   # 扇骨
        d.line([(L(4.2), L(15)), (L(1.0), L(yy))], fill=GOLD_LO, width=D // 4)
    d.line([(L(0.4), L(8.6)), (L(1.8), L(15)), (L(0.4), L(21.4))], fill=GOLD_HI, width=D // 4)
    bar = down(img)
    bar.save(os.path.join(OUT, "ea_bar.png"), optimize=True)

    # ---- 流光帧：三段反向旋转的赤红刻纹（发光） + 金头 / 蓝圆脉动 ----
    frames = []
    for n in range(N):
        t = n / N
        p = ea_pattern_layer(K, t, None)
        layer = np.zeros((H * K, W * K, 4), np.uint8)
        layer[..., 0] = 255
        layer[..., 1] = 40
        layer[..., 2] = 56
        layer[..., 3] = np.clip(p * 255, 0, 255).astype(np.uint8)
        g = Image.fromarray(layer)
        gd = ImageDraw.Draw(g)
        pulse = 0.5 + 0.5 * math.sin(TAU * t)
        gd.ellipse([(gx - 4.2) * K, (gy - 4.2) * K, (gx + 4.2) * K, (gy + 4.2) * K], outline=BLUE_HI + (int(70 + 120 * pulse),), width=K // 2)
        gd.ellipse([234.5 * K, 12.5 * K, 240 * K, 17.5 * K], fill=GOLD_HI + (int(50 + 110 * pulse),))
        blur = g.filter(ImageFilter.GaussianBlur(4))
        core = np.array(g)
        hot = core.copy()
        hot[..., 1] = np.clip(hot[..., 1].astype(int) + (core[..., 3].astype(int) * 0.55), 0, 255).astype(np.uint8)
        hot[..., 2] = np.clip(hot[..., 2].astype(int) + (core[..., 3].astype(int) * 0.4), 0, 255).astype(np.uint8)
        out = Image.new("RGBA", g.size)
        out.alpha_composite(blur)
        out.alpha_composite(blur)
        out.alpha_composite(Image.fromarray(hot))
        frames.append(out)
    save_sheet("ea_glow", frames)
    return bar, frames


def preview(ex, exf, ea_img, eaf):
    """GIF 预览：上 Excalibur、下 Ea，暗底（贴图 4 倍原样）"""
    out = []
    for i in range(N):
        bg = Image.new("RGBA", (W * K + 40, H * K * 2 + 60), (20, 16, 18, 255))
        a = ex.copy(); a.alpha_composite(exf[i])
        b = ea_img.copy(); b.alpha_composite(eaf[i])
        bg.alpha_composite(a, (20, 20))
        bg.alpha_composite(b, (20, 40 + H * K))
        out.append(bg.convert("P", palette=Image.ADAPTIVE))
    out[0].save(os.path.join(PREV, "holy_swords.gif"), save_all=True, append_images=out[1:],
                duration=70, loop=0, disposal=2)
    still = Image.new("RGBA", (W * K + 40, H * K * 2 + 60), (20, 16, 18, 255))
    a = ex.copy(); a.alpha_composite(exf[3]); b = ea_img.copy(); b.alpha_composite(eaf[3])
    still.alpha_composite(a, (20, 20)); still.alpha_composite(b, (20, 40 + H * K))
    still.save(os.path.join(PREV, "holy_swords_still.png"))


if __name__ == "__main__":
    ex, exf = excalibur()
    ea_img, eaf = ea()
    preview(ex, exf, ea_img, eaf)
