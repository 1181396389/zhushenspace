"""属性页（命运石之门）静态效果图：按 GodPanelScreen / SgStyle 的布局与配色近似绘制，3 倍放大。"""
import math
from PIL import Image, ImageDraw, ImageFont, ImageFilter
A = "src/main/resources/assets/zhushenspace/textures/gui/anim/"
K = 3
W, H = 270, 234
CJK = ImageFont.truetype("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", 8 * K, index=2)
TINY = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", int(3.6 * K))
def C(v): return ((v >> 16) & 255, (v >> 8) & 255, v & 255, (v >> 24) & 255)
M = 20
img = Image.new("RGB", ((W + 2 * M) * K, (H + 2 * M) * K), (58, 74, 52))
d = ImageDraw.Draw(img, "RGBA")
def R(x, y, w, h, c): d.rectangle([(x + M) * K, (y + M) * K, (x + M + w) * K - 1, (y + M + h) * K - 1], fill=C(c))
def O(x, y, w, h, c): d.rectangle([(x + M) * K, (y + M) * K, (x + M + w) * K - 1, (y + M + h) * K - 1], outline=C(c), width=K)
def T(s, x, y, c, f=CJK): d.text(((x + M) * K, (y + M) * K - K), s, font=f, fill=C(c))
def TW(s, f=CJK): return d.textlength(s, font=f) / K
glowL = Image.new("RGB", img.size, 0); gd = ImageDraw.Draw(glowL)
def G(cx, cy, r, c, a=1.0):
    col = tuple(int(v * a) for v in C(c)[:3]); gd.ellipse([(cx + M - r) * K, (cy + M - r) * K, (cx + M + r) * K, (cy + M + r) * K], fill=col)
NIXIE, HOT, DIM, BRASS, BRD, OK, KU = 0xFFFF8A2A, 0xFFFFC27A, 0xFF5A3420, 0xFFB08A4A, 0xFF5E4726, 0xFFE9ECEF, 0xFFD2413A
TXT, SUB = 0xFFE6E0D4, 0xFF8C8478
R(2, 2, W, H, 0x66000000); R(0, 0, W, H, 0xF20C0B0E)
for sy in range(2, H - 1, 3): R(1, sy, W - 2, 1, 0x07FFFFFF)
O(0, 0, W, H, BRD)
# 交错世界线
per = 2 * (W - 6 + H - 6)
def perim(s):
    Wi, Hi = W - 6, H - 6; s %= per
    if s < Wi: return 3 + s, 3, 0, 1
    s -= Wi
    if s < Hi: return W - 3, 3 + s, -1, 0
    s -= Hi
    if s < Wi: return W - 3 - s, H - 3, 0, -1
    s -= Wi; return 3, H - 3 - s, 1, 0
last = {}
for s in range(per):
    x, y, nx, ny = perim(s); k = math.sin(s * 2 * math.pi / 72 - 1.3); amp = 2
    cross = abs(k) < .18
    for sign, col in ((1, OK), (-1, KU)):
        o = sign * amp * k; ix, iy = round(x + nx * o), round(y + ny * o)
        lp = last.get(sign, (ix, iy))
        if abs(lp[0] - ix) > 2 or abs(lp[1] - iy) > 2: lp = (ix, iy)
        R(min(lp[0], ix), min(lp[1], iy), abs(lp[0] - ix) + 1, abs(lp[1] - iy) + 1, HOT if cross else col); last[sign] = (ix, iy)
    if cross and abs(k) < .06: G(x, y, 2.5, NIXIE, .5)
gear = Image.open(A + "sg_gear.png")
def gearAt(cx, cy, r, deg):
    g = gear.rotate(deg, resample=Image.BICUBIC).resize((int(2 * r * K), int(2 * r * K)), Image.LANCZOS)
    img.paste(g, (int((cx + M - r) * K), int((cy + M - r) * K)), g)
gearAt(1, 1, 9, 20); gearAt(-3, 13, 5, 5); gearAt(W - 1, H - 1, 9, 40); gearAt(W + 3, H - 13, 5, 12)
# 铭牌
def mono(name, x, y, w, h, c):
    m = Image.open(A + name).getchannel("A").resize((int(w * K), int(h * K)), Image.LANCZOS)
    img.paste(Image.new("RGB", m.size, C(c)[:3]), (int((x + M) * K), int((y + M) * K)), m)
a = "LAB MEM 001  OKABE RINTARO"; aw = TW(a, TINY) + 12; py = H - 3
R(16, py, aw, 7, 0xFF0C0B0E); O(16, py, aw, 7, OK); mono("sg_phone.png", 18, py + 1, 4, 5, OK); T(a, 24, py + 2.2, OK, TINY)
b = "LAB MEM 004  MAKISE KURISU"; bw = TW(b, TINY) + 12; bx = W - 20 - bw
R(bx, py, bw, 7, 0xFF0C0B0E); O(bx, py, bw, 7, KU); T(b, bx + 3, py + 2.2, KU, TINY); mono("sg_amadeus.png", bx + bw - 8, py, 7, 7, KU)
# 标签
tabs = ["属性*", "技能", "战斗预设", "商城"]; tx = 6
for i, t in enumerate(tabs):
    w = TW(t) + 10
    R(tx, 5, w, 14, 0xFF241A12 if i == 0 else 0xFF16141A); O(tx, 5, w, 14, BRASS if i == 0 else BRD)
    T(t, tx + 5, 8, HOT if i == 0 else SUB)
    if i == 0: R(tx + 1, 17, w - 2, 1, NIXIE); G(tx + w / 2, 17.5, 5, NIXIE, .35)
    tx += w + 4
for x0, w, s in ((W - 26 - 36, 32, "大厅"), (W - 26, 20, "⚙")):
    R(x0, 5, w, 14, 0xFF16141A); O(x0, 5, w, 14, BRD); T(s, x0 + (w - TW(s)) / 2, 8, SUB)
# 辉光管
nix = Image.open(A + "sg_nixie.png")
def tube(x, y, ch):
    t = nix.crop((0, 0, 28, 52)).resize((7 * K, 13 * K), Image.LANCZOS); img.paste(t, (int((x + M) * K), int((y + M) * K)), t)
    n = 11 if ch == "." else int(ch) + 1
    G(x + 3.5, y + 5.5, 6, NIXIE, .25)
    l = nix.crop((28 * n, 0, 28 * n + 28, 52)).resize((7 * K, 13 * K), Image.LANCZOS); img.paste(l, (int((x + M) * K), int((y + M) * K)), l)
ay = 22; x = 7
for i, ch in enumerate("0.571046"): tube(x + i * 8, ay, ch)
x = 7 + 63 + 6; T("点数", x, ay + 3, SUB); x += TW("点数") + 3
for ch in "03": tube(x, ay, ch); x += 8
x += 5; T("★", x, ay + 3, 0xFFFFD966); x += TW("★") + 2; tube(x, ay, "1")
cx = W - 40 - 6; rx = cx - 34 - 4
R(rx, ay, 34, 13, 0xFF1A1A1E); O(rx, ay, 34, 13, OK); T("重置", rx + 9, ay + 3, OK)
R(cx, ay, 40, 13, 0xFF8E2226); O(cx, ay, 40, 13, KU); T("确认", cx + 12, ay + 3, 0xFFFFF4EE); G(cx + 20, ay + 6.5, 16, KU, .15)
# 分隔线
sy = 37; R(12, sy, W - 34 - 16, 1, 0x99B08A4A)
for xx in range(16, W - 38, 10): R(xx, sy + 1, 1, 2, 0x44B08A4A)
for i in range(34):
    dy = round(i * .12); R(W - 38 + i, sy - dy, 1, 1, OK); R(W - 38 + i, sy + dy, 1, 1, KU)
gearAt(7.5, 37.5, 3.5, 0)
# 水印
lt, lb = 40, H - 28
bd = Image.open(A + "sg_badge.png").getchannel("A").resize((96 * K, 96 * K), Image.LANCZOS).point(lambda v: v * .07)
img.paste(Image.new("RGB", bd.size, (255, 255, 255)), (int((6 + (W - 12) * .27 - 48 + M) * K), int((lt + (lb - lt) / 2 - 48 + M) * K)), bd)
am = Image.open(A + "sg_amadeus.png").getchannel("A").resize((96 * K, 96 * K), Image.LANCZOS).point(lambda v: v * .1)
img.paste(Image.new("RGB", am.size, C(KU)[:3]), (int((6 + (W - 12) * .73 - 48 + M) * K), int((lt + (lb - lt) / 2 - 48 + M) * K)), am)
# 行
names = ["力量", "敏捷", "耐力", "智力", "感知", "意志", "魅力", "操作", "冷静"]
saved = [3, 2, 4, 1, 5, 2, 0, 1, 2]; cur = [3, 3, 4, 1, 5, 2, 0, 2, 2]
for i, n in enumerate(names):
    ry = lt + i * 18; hov = i == 3; x0 = 6; rw = W - 15; h = 17
    R(x0, ry, rw, h, 0x1EFF8A2A if hov else (0x10FFFFFF if i % 2 else 0x06FFFFFF)); R(x0, ry + h - 1, rw, 1, 0x88FF8A2A if hov else 0x22B08A4A)
    if hov: R(x0, ry + 1, 2, h - 2, NIXIE)
    ty = ry + 5
    T("%02d" % (i + 1), x0 + 5, ty, NIXIE if hov else DIM); R(x0 + 19, ry + 3, 1, h - 6, 0x33B08A4A); T(n, x0 + 24, ty, TXT)
    gx, gy = 80, ry + 8; R(gx, gy, 44 + 1, 1, 0x445A3420)
    if saved[i] > 1: R(gx, gy, (saved[i] - 1) * 11 + 1, 1, NIXIE)
    for p in range(5):
        nx = gx + p * 11
        if p < saved[i]: R(nx - 1, gy - 1, 3, 3, NIXIE); R(nx, gy, 1, 1, 0xFFFFF0D8); G(nx + .5, gy + .5, 4, NIXIE, .5)
        elif p < cur[i]: R(nx - 1, gy - 1, 3, 3, KU); G(nx + .5, gy + .5, 4, KU, .45)
        else: R(nx - 1, gy - 1, 3, 3, 0xFF3A2A20); R(nx, gy, 1, 1, 0xFF0C0B0E)
    v = "MAX" if cur[i] >= 5 else "%d/5" % cur[i]
    T(v, gx + 44 + 6, ty, KU if cur[i] != saved[i] else HOT if cur[i] >= 5 else NIXIE)
    px = W - 12 - 11; mx = px - 14
    if cur[i] < 5: T("×%d" % (cur[i] + 1), mx - TW("×%d" % (cur[i] + 1)) - 4, ty, SUB)
    for bx_, plus in ((mx, False), (px, True)):
        by = ry + 3; O(bx_, by, 11, 11, BRASS); R(bx_ + 3, by + 5, 5, 1, TXT)
        if plus: R(bx_ + 5, by + 3, 1, 5, TXT)
T("完好生命：20", 8, H - 24, SUB); T("支线 0/0/0/0/0  奖励点数 120", 8, H - 12, NIXIE)
glowL = glowL.filter(ImageFilter.GaussianBlur(3 * K))
from PIL import ImageChops
img = ImageChops.add(img, glowL)
img.save("tools/preview/sg_attr_mockup.png"); print(img.size)
