"""大黑塔主题效果图（近似 ZsTheme / HertaChibi 的布局与配色，3 倍）：左 = 商店风格面板，右 = 设置界面底板 + 趴着的 Q 版大黑塔。"""
from PIL import Image, ImageDraw, ImageFont
A = "src/main/resources/assets/zhushenspace/textures/gui/"
K = 3
F = ImageFont.truetype("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", 8 * K, index=2)
W, H = 470, 230
img = Image.new("RGBA", (W * K, H * K), (46, 58, 40, 255))
def C(v): return ((v >> 16) & 255, (v >> 8) & 255, v & 255, (v >> 24) & 255)
def over(im, x, y):
    img.alpha_composite(im, (int(x * K), int(y * K)))
def rect(x, y, w, h, c, outline=None):
    lay = Image.new("RGBA", img.size); d = ImageDraw.Draw(lay)
    d.rectangle([x * K, y * K, (x + w) * K - 1, (y + h) * K - 1], fill=C(c) if c else None,
                outline=C(outline) if outline else None, width=K)
    img.alpha_composite(lay)
def text(s, x, y, c):
    ImageDraw.Draw(img).text((x * K, y * K - K), s, font=F, fill=C(c))
def tex(name, w, h, rot=0):
    im = Image.open(A + name).convert("RGBA")
    if rot: im = im.rotate(rot, resample=Image.BICUBIC)
    return im.resize((int(w * K), int(h * K)), Image.LANCZOS)
# ---- 面板 ----
px, py, pw, ph = 14, 22, 230, 190
sky = Image.open(A + "anim/herta_sky.png").crop((0, 0, 160, 120)).resize((pw * K, ph * K), Image.BICUBIC)
over(sky, px, py); rect(px, py, pw, ph, 0x9E120E1C)
sg = Image.open(A + "anim/herta_sigil.png").resize((150 * K, 150 * K), Image.LANCZOS)
col = Image.new("RGBA", sg.size, (140, 156, 255, 0)); col.putalpha(sg.getchannel("A").point(lambda v: v * .16))
over(col, px + pw / 2 - 75, py + ph / 2 - 65)
rect(px, py, pw, ph, None, 0xFF8C6FE0); rect(px + 2, py + 2, pw - 4, ph - 4, None, 0x22CDBEF5)
tabs = ["属性", "技能", "战斗预设", "商城"]; tx = px + 6
for i, t in enumerate(tabs):
    w = len(t) * 8 + 10; sel = i == 3
    rect(tx, py + 5, w, 14, 0xE04A3688 if sel else 0x66241C3C, 0xFFCDBEF5 if sel else 0x887A62C4)
    text(t, tx + 5, py + 8, 0xFFFFFFFF if sel else 0xFFBBAEDD)
    if sel:
        rect(tx, py + 19, w, 1, 0xFFCDBEF5); over(tex("anim/herta_flower.png", 7, 7), tx + w / 2 - 3.5, py + 16)
    tx += w + 4
rect(px + 4, py + 37, pw - 8, 1, 0x338C6FE0)
for i, (n, c) in enumerate([("太极拳 · 入门", "120"), ("内力心法", "80"), ("剑冢残片", "260")]):
    y = py + 44 + i * 44; hov = i == 1
    rect(px + 8, y, pw - 16, 38, 0xAA3A2C6E if hov else 0x881A1430, 0xFFCDBEF5 if hov else 0x667A62C4)
    text(n, px + 14, y + 6, 0xFFEDE8F6); text("奖励点数 " + c, px + 14, y + 22, 0xFFE0BE7A)
    bx = px + pw - 52
    rect(bx, y + 20, 40, 13, 0xE04A3688 if hov else 0xCC241C3C, 0xFFCDBEF5 if hov else 0xFF7A62C4)
    text("购买", bx + 12, y + 23, 0xFFFFFFFF if hov else 0xFFBBAEDD)
    if hov: over(tex("anim/herta_flower.png", 7, 7), bx - 2.5, y + 17.5)
for fx, fy, s in ((px, py, 11), (px, py + ph, 9), (px + pw, py + ph, 11)):
    over(tex("anim/herta_flower.png", s, s, 20), fx - s / 2, fy - s / 2)
over(tex("anim/herta_hat.png", 22, 22, -4), px + pw - 16, py - 13)
for i, (x, y) in enumerate([(40, 80), (120, 140), (200, 60), (90, 180), (170, 110)]):
    rect(px + x, py + y, 2, 1, 0xBFF0B8DC); rect(px + x + 1, py + y + 1, 2, 1, 0xBFF0B8DC)
# ---- 设置界面底板 + 趴着的大黑塔 ----
cx = 360; cardY = H - 60
rect(cx - 106, cardY, 216, 56, 0x881A1430, 0x667A62C4)
over(tex("anim/herta_flower.png", 10, 10, 30), cx - 111, cardY - 5)
for (x, y, w, s) in [(-100, 8, 40, "－"), (-56, 8, 60, "缩放 100%"), (8, 8, 40, "＋"), (-100, 32, 100, "重置"), (8, 32, 96, "完成")]:
    rect(cx + x, cardY + y, w, 20, 0xCC241C3C, 0xFF7A62C4); text(s, cx + x + w / 2 - len(s) * 4, cardY + y + 6, 0xFFBBAEDD)
ch = Image.open(A + "herta_chibi.png"); s = 72 / 256
chi = ch.resize((round(227 * s * K), round(256 * s * K)), Image.LANCZOS)
over(chi, cx + 50, cardY - 188 * s)
# 气泡
bx, by = cx + 20, cardY - 188 * s - 22
rect(bx, by, 118, 16, 0xEB120E1C, 0xFFCDBEF5); text("叫我「女士」。嗯，这才对。", bx + 5, by + 4, 0xFFEDE8F6)
over(tex("anim/herta_flower.png", 7, 7), bx + 114, by - 3.5)
text("能量池界面设置", cx - 30, 10, 0xFFD6C8FA); over(tex("anim/herta_hat.png", 16, 16), cx + 30, 3)
img.save("tools/preview/herta_mockup.png"); print("ok")
