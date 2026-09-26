"""离线模拟 DrawFx 时间线，输出 tools/preview/draw_fx.gif（近似效果，用于评审）"""
import math
import numpy as np
from PIL import Image
A = "src/main/resources/assets/zhushenspace/textures/gui/anim/"
S = 2; W, H = 480, 270
def load(n): return np.asarray(Image.open(A + n + ".png").convert("RGBA")).astype(np.float32) / 255
RAD, STR, RING, PIL_ = load("flare_radial"), load("flare_streak"), load("flare_ring"), load("flare_pillar")
def h(i):
    x = (i * 374761393 + 668265263) & 0xffffffff
    x = ((x ^ (x >> 13)) * 1274126177) & 0xffffffff
    return ((x ^ (x >> 16)) & 0xffffff) / 0x1000000
def add(cv, t, cx, cy, w, hh, rgb, a, rot=0):
    w, hh = int(w * S), int(hh * S)
    if a <= .01 or w < 2 or hh < 2: return
    im = Image.fromarray((t[..., 3] * 255).astype(np.uint8)).resize((w, hh), Image.BILINEAR)
    if rot: im = im.rotate(rot, expand=True, resample=Image.BILINEAR)
    m = np.asarray(im).astype(np.float32) / 255 * min(1, a)
    x0, y0 = int(cx * S - m.shape[1] / 2), int(cy * S - m.shape[0] / 2)
    xs, ys = max(0, x0), max(0, y0); xe, ye = min(cv.shape[1], x0 + m.shape[1]), min(cv.shape[0], y0 + m.shape[0])
    if xe <= xs or ye <= ys: return
    col = np.array([(rgb >> 16 & 255), (rgb >> 8 & 255), rgb & 255], np.float32) / 255
    cv[ys:ye, xs:xe] += m[ys - y0:ye - y0, xs - x0:xe - x0, None] * col
def ease(x): x = min(1, max(0, x)); return -(math.cos(math.pi * x) - 1) / 2
def ec(x): x = min(1, max(0, x)); return 1 - (1 - x) ** 3
bg = np.zeros((H * S, W * S, 3), np.float32); yy = np.linspace(0, 1, H * S)[:, None, None]
bg += np.array([.33, .45, .28]) * (yy > .55) + np.array([.55, .72, .9]) * (yy <= .55)
def frame(sw, t):
    bar = Image.open(A + sw + "_bar.png").convert("RGBA")
    aura, core = (0xFFD27A, 0xCFE6FF) if sw == "excalibur" else (0xFF5A3C, 0xFFB49A)
    cv = bg.copy()
    env = min(1, t / 200) * (1 - min(1, max(0, (t - 1000) / 600)))
    cv *= 1 - .28 * env; cv *= 1 - .45 * env * np.clip((yy - .5) * 2, 0, 1)
    bx, by = W / 2 - 120, H - 31; gx, gy = bx + 23, by + 15; mid = by + 15; tip = bx + 236
    d = ease((t - 320) / 580)
    rise = (1 - ec(t / 250)) * 30
    reveal = int((30 + 210 * d) * S)
    b = np.asarray(bar).astype(np.float32) / 255
    b = b[:, :reveal]; y0 = int((by + rise) * S); x0 = int(bx * S)
    reg = cv[y0:y0 + b.shape[0], x0:x0 + b.shape[1]]; b = b[:reg.shape[0]]
    reg[:] = reg * (1 - b[..., 3:]) + b[..., :3] * b[..., 3:]
    for i in range(22):
        s = h(i); l = (t - s * 120) / (320 - s * 120)
        if 0 < l < 1:
            e = l * l; ang = s * 18.85 + e * 2.2; r = (70 + h(i + 50) * 110) * (1 - e)
            add(cv, RAD, gx + math.cos(ang) * r, gy + math.sin(ang) * r * .55, 6 + 4 * e, 6 + 4 * e, aura, min(1, l * 3))
    ig = 0 if t < 290 or t > 560 else ((t - 290) / 70 if t < 360 else 1 - (t - 360) / 200)
    add(cv, RAD, gx, gy, 70 * ig + 10, 70 * ig + 10, aura, ig); add(cv, STR, gx, gy, 160 * ig, 10, core, ig * .9)
    if 320 <= t < 1020:
        f = bx + 30 + 210 * d; k = 1 if t < 900 else 1 - (t - 900) / 120
        add(cv, RAD, f, mid, 40, 40, aura, .9 * k); add(cv, RAD, f, mid, 14, 14, 0xFFFFFF, k)
        add(cv, STR, f, mid, 70, 8, core, .9 * k, 90)
        add(cv, STR, (bx + 30 + f) / 2, mid, (f - bx - 30) * 1.3, 26, aura, .35 * k)
    for i in range(40):
        bi = 320 + h(i + 7) * 580; age = (t - bi) / 1000; life = .35 + h(i + 90) * .4
        if 0 < age < life:
            x0_ = bx + 30 + 210 * ease((bi - 320) / 580)
            add(cv, RAD, x0_ + (-40 + h(i + 3) * 80) * age, mid + (-70 - h(i + 11) * 90) * age + 260 * age * age, 4, 4, aura, 1 - age / life)
    bb = t - 900
    if 0 <= bb < 700:
        f = 1 - bb / 700; fl = f ** 2.5
        add(cv, RAD, tip, mid, 110 * (.6 + .4 * f), 110 * (.6 + .4 * f), aura, fl); add(cv, RAD, tip, mid, 30, 30, 0xFFFFFF, fl)
        add(cv, STR, tip, mid, W * 1.6, 14 * f + 4, core, fl); add(cv, STR, W / 2, mid, W * 2.2, 3, 0xFFFFFF, fl * .7)
        r = 30 + 170 * ec(bb / 600); add(cv, RING, tip, mid, r, r * .6, aura, f * .8)
        if sw == "excalibur":
            a = min(1, bb / 80) * f; ph = by + 20
            add(cv, PIL_, tip, mid - ph / 2, (18 + 26 * f) * 2.2, ph, aura, a * .6); add(cv, PIL_, tip, mid - ph / 2, (18 + 26 * f) * .7, ph, 0xFFFFFF, a)
        else:
            for i in range(3):
                add(cv, STR, tip, mid, 220 * (.5 + .5 * f), 6, core if i == 1 else aura, f * .8, i * 60 + bb * (.5 if i % 2 == 0 else -.35))
            add(cv, RING, tip, mid, 60 * f + 20, 60 * f + 20, 0xFF2A1A, f * .7)
    return Image.fromarray((np.clip(cv, 0, 1) * 255).astype(np.uint8)).resize((W, H), Image.LANCZOS)
fr = [frame(sw, t) for sw in ("excalibur", "ea") for t in range(0, 1700, 50)]
fr[0].save("tools/preview/draw_fx.gif", save_all=True, append_images=fr[1:], duration=50, loop=0)
fr[30].save("tools/preview/draw_fx_still.png"); print(len(fr))
