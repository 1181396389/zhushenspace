"""宝具拔出光效贴图（白色灰度，运行时着色 + 加色混合）：
flare_radial 64x64 柔光核；flare_streak 256x16 横向镜头光条；flare_ring 128x128 冲击环；flare_pillar 32x256 光柱。"""
import math
from PIL import Image
OUT = "src/main/resources/assets/zhushenspace/textures/gui/anim/"
def mk(w, h, f):
    im = Image.new("RGBA", (w, h))
    px = im.load()
    for y in range(h):
        for x in range(w):
            a = max(0.0, min(1.0, f((x + .5) / w * 2 - 1, (y + .5) / h * 2 - 1)))
            px[x, y] = (255, 255, 255, int(a * 255))
    return im
def radial(u, v):
    r = math.hypot(u, v)
    return max(0, 1 - r) ** 2.2 + 0.6 * max(0, 1 - r * 4) ** 2
def streak(u, v):
    return max(0, 1 - abs(u)) ** 1.6 * math.exp(-(v * 3.2) ** 2) * (1 + 0.8 * math.exp(-(v * 12) ** 2))
def ring(u, v):
    r = math.hypot(u, v)
    return math.exp(-((r - .82) / .06) ** 2) + 0.35 * math.exp(-((r - .82) / .16) ** 2) * (r < .82)
def pillar(u, v):  # v=-1 顶端，v=1 底端（剑尖处）
    fall = ((v + 1) / 2) ** 0.8
    return math.exp(-(u * 2.6) ** 2) * fall * (1 + 1.2 * math.exp(-(u * 9) ** 2))
for n, w, h, f in [("flare_radial", 64, 64, radial), ("flare_streak", 256, 16, streak),
                   ("flare_ring", 128, 128, ring), ("flare_pillar", 32, 256, pillar)]:
    mk(w, h, f).save(OUT + n + ".png")
print("ok")
