"""程序化合成技艺（19 个 ArtSkill / 9 个能量池）的分层音效。

输出：src/main/resources/assets/zhushenspace/sounds/art_<pool>_<cast|release|hit|impact>.ogg
  9 池 × 4 层 = 36 个 ogg（44100Hz 单声道，Vorbis 编码，音量归一化到 0.85）
随后自动写入 sounds.json 与 zh_cn / en_us 的字幕条目。

设计：每个能量池有自己的音色签名（谐波结构 + 噪声成分 + 音高走向），
保证 9 个流派在只靠听觉时也能分辨；「impact」层比「hit」更低更重，用于重击叠加。

依赖：numpy + soundfile（pip install numpy soundfile）
运行：python3 tools/gen_art_sfx.py
"""
import json
import os
import numpy as np
import soundfile as sf

SR = 44100
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SOUND_DIR = os.path.join(ROOT, "src/main/resources/assets/zhushenspace/sounds")
SOUNDS_JSON = os.path.join(ROOT, "src/main/resources/assets/zhushenspace/sounds.json")
LANG = os.path.join(ROOT, "src/main/resources/assets/zhushenspace/lang")

rng = np.random.default_rng(20240930)


# ===== 基础工具 =====

def t(n):
    return np.arange(n) / SR


def noise(n):
    return rng.normal(0, 1, n)


def env(n, attack=0.01, power=2.5, hold=0.0):
    """指数衰减包络（attack 秒上升，其余按 power 次幂衰减，hold 秒保持）"""
    a = max(1, int(attack * SR))
    e = np.ones(n)
    e[:a] = np.linspace(0, 1, a) ** 0.6
    rest = n - a
    if rest > 0:
        k = np.linspace(0, 1, rest)
        e[a:] = np.clip(1 - k, 0, 1) ** power
    if hold > 0:
        h = int(hold * SR)
        e[:min(n, h)] = np.maximum(e[:min(n, h)], np.linspace(0, 1, min(n, h)))
    return e


def sine(freq, n, phase=0.0):
    f = np.full(n, float(freq)) if np.isscalar(freq) else np.asarray(freq)
    return np.sin(2 * np.pi * np.cumsum(f) / SR + phase)


def chirp(f0, f1, n, expo=True):
    k = np.linspace(0, 1, n)
    k = k ** 2 if expo else k
    return f0 + (f1 - f0) * k


def biquad(x, kind, f0, q=0.8):
    """单段双二次滤波（lowpass / highpass / bandpass）"""
    w0 = 2 * np.pi * f0 / SR
    alpha = np.sin(w0) / (2 * q)
    cw = np.cos(w0)
    if kind == "lowpass":
        b = [(1 - cw) / 2, 1 - cw, (1 - cw) / 2]
        a = [1 + alpha, -2 * cw, 1 - alpha]
    elif kind == "highpass":
        b = [(1 + cw) / 2, -(1 + cw), (1 + cw) / 2]
        a = [1 + alpha, -2 * cw, 1 - alpha]
    else:
        b = [alpha, 0, -alpha]
        a = [1 + alpha, -2 * cw, 1 - alpha]
    y = np.zeros_like(x)
    for i in range(len(x)):
        x1 = x[i - 1] if i >= 1 else 0.0
        x2 = x[i - 2] if i >= 2 else 0.0
        y1 = y[i - 1] if i >= 1 else 0.0
        y2 = y[i - 2] if i >= 2 else 0.0
        y[i] = (b[0] * x[i] + b[1] * x1 + b[2] * x2 - a[1] * y1 - a[2] * y2) / a[0]
    return y


def reverb(x, mix=0.25, decay=0.35):
    """几条梳状延迟叠加，做出空间感"""
    out = x.copy()
    for d, g in ((0.011, 0.5), (0.0197, 0.42), (0.0331, 0.34), (0.0473, 0.26)):
        dl = int(d * SR)
        buf = np.zeros(len(x) + dl)
        buf[:len(x)] = x
        fb = np.zeros(len(x) + dl)
        for i in range(dl, len(fb)):
            fb[i] = buf[i] + fb[i - dl] * decay * g
        out += fb[:len(x)] * g
    return x * (1 - mix) + out * mix


def modal(freqs, amps, decays, n, detune=0.0):
    """模态合成：一组非谐分音（钟 / 磬 / 金属）"""
    x = np.zeros(n)
    for f, a, d in zip(freqs, amps, decays):
        f = f * (1 + detune)
        x += a * sine(f, n) * np.exp(-np.linspace(0, 1, n) / max(1e-3, d))
    return x


def norm(x, peak=0.85):
    x = np.nan_to_num(x)
    m = np.max(np.abs(x))
    return x * (peak / m) if m > 1e-9 else x


def tail_fade(x, ms=8):
    k = max(1, int(SR * ms / 1000))
    if len(x) > 2 * k:
        x[:k] *= np.linspace(0, 1, k)
        x[-k:] *= np.linspace(1, 0, k)
    return x


def write(name, x):
    x = tail_fade(norm(x))
    path = os.path.join(SOUND_DIR, name + ".ogg")
    sf.write(path, x.astype(np.float32), SR, format="OGG", subtype="VORBIS")
    return os.path.relpath(path, ROOT)


# ===== 各池音色 =====

def spirit_cast():
    n = int(0.9 * SR)
    bell = modal([523, 1046, 1568, 2093, 2794, 3520], [1, .55, .35, .22, .12, .08],
                 [.55, .42, .3, .2, .14, .1], n)
    air = biquad(noise(n), "bandpass", 2600, 1.6) * env(n, .06, 3.0) * .35
    return reverb(bell * env(n, .004, 1.2) + air, .3, .45)


def spirit_release():
    n = int(0.75 * SR)
    f = chirp(420, 1900, n)
    shine = sine(f, n) * env(n, .05, 1.6) * .8
    shine += modal([1318, 1760, 2637], [.4, .3, .2], [.4, .3, .2], n)
    return reverb(shine + biquad(noise(n), "bandpass", 5200, 1.2) * env(n, .04, 3) * .25, .35, .4)


def spirit_hit():
    n = int(0.42 * SR)
    ping = modal([1244, 2489, 3723], [1, .5, .25], [.3, .2, .14], n)
    tink = biquad(noise(n), "bandpass", 3600, 1.1) * env(n, .002, 6) * .5
    return reverb(ping * env(n, .002, 1.4) + tink, .22, .3)


def spirit_impact():
    n = int(0.6 * SR)
    body = sine(chirp(180, 70, n), n) * env(n, .006, 1.6)
    sub = sine(55, n) * env(n, .01, 2.0) * .8
    return reverb(body + sub + biquad(noise(n), "lowpass", 400, .7) * env(n, .004, 3) * .3, .2, .35)


def mind_cast():
    n = int(0.85 * SR)
    sub = sine(chirp(140, 62, n), n) * env(n, .05, 1.4)
    trem = 1 + .35 * np.sin(2 * np.pi * 11 * t(n))
    return reverb(sub * trem + sine(93, n) * env(n, .1, 2) * .4, .3, .5)


def mind_release():
    n = int(0.9 * SR)
    d = sine(70, n) * env(n, .2, 1.2) * .9
    rise = sine(chirp(300, 900, n), n) * env(n, .25, 1.5) * .5
    trem = 1 + .5 * np.sin(2 * np.pi * 6.5 * t(n))
    return reverb((d + rise) * trem, .35, .55)


def mind_hit():
    n = int(0.55 * SR)
    click = biquad(noise(n), "highpass", 3000, .9) * env(n, .001, 9) * .8
    ring = sine(4200, n) * env(n, .003, 2.2) * .35
    sub = sine(chirp(120, 58, n), n) * env(n, .004, 2.4) * .7
    return reverb(click + ring + sub, .25, .3)


def mind_impact():
    n = int(0.8 * SR)
    thump = sine(chirp(90, 38, n), n) * env(n, .008, 1.5)
    return reverb(thump + biquad(noise(n), "lowpass", 220, .7) * env(n, .01, 2.5) * .45, .25, .45)


def psychic_cast():
    n = int(0.6 * SR)
    x = np.zeros(n)
    for i in range(14):  # 噼啪
        st = int(rng.uniform(0, .5) * SR)
        ln = int(rng.uniform(.006, .03) * SR)
        seg = biquad(noise(ln), "bandpass", rng.uniform(1800, 6000), 1.4) * env(ln, .001, 5)
        x[st:st + ln] += seg * rng.uniform(.4, 1)
    hum = sine(160, n) * env(n, .05, 1.8) * .3
    return reverb(x + hum, .2, .3)


def psychic_release():
    n = int(0.7 * SR)
    saw = 2 * (np.cumsum(np.full(n, 220.0)) / SR % 1) - 1
    f = chirp(180, 520, n)
    saw2 = 2 * (np.cumsum(f) / SR % 1) - 1
    vib = .5 + .5 * np.sin(2 * np.pi * 17 * t(n))
    return reverb(saw2 * env(n, .03, 1.5) * (.6 + .4 * vib) + saw * env(n, .02, 3) * .2, .25, .35)


def psychic_hit():
    n = int(0.5 * SR)
    zap = sine(chirp(3200, 520, n), n) * env(n, .001, 3.5) * .9
    crack = biquad(noise(n), "highpass", 2200, .9) * env(n, .001, 6) * .7
    return reverb(zap + crack + sine(chirp(140, 60, n), n) * env(n, .004, 2.5) * .6, .22, .3)


def psychic_impact():
    n = int(0.62 * SR)
    lo = sine(chirp(110, 40, n), n) * env(n, .006, 1.6)
    hi = biquad(noise(n), "bandpass", 3000, 1.0) * env(n, .002, 4) * .35
    return reverb(lo + hi, .25, .4)


def yokai_cast():
    n = int(0.95 * SR)
    f = chirp(120, 90, n)
    growl = np.tanh(3 * (sine(f, n) + .5 * sine(f * 1.005, n) + .35 * sine(f * .5, n)))
    rasp = biquad(noise(n), "bandpass", 700, 1.0) * .35
    return reverb(growl * env(n, .06, 1.5) + rasp * env(n, .1, 2.2), .3, .45)


def yokai_release():
    n = int(0.85 * SR)
    f = chirp(220, 620, n)
    shriek = np.tanh(2.5 * sine(f, n)) * env(n, .05, 1.4)
    fmt = biquad(shriek, "bandpass", 1200, 1.6) * 1.4 + shriek * .5
    return reverb(fmt + biquad(noise(n), "bandpass", 1600, 1.2) * env(n, .05, 2) * .3, .3, .4)


def yokai_hit():
    n = int(0.5 * SR)
    crunch = biquad(noise(n), "bandpass", 900, .9) * env(n, .002, 5)
    thud = sine(chirp(150, 60, n), n) * env(n, .004, 2.2)
    return reverb(crunch * .8 + thud * .7, .25, .35)


def yokai_impact():
    n = int(0.75 * SR)
    f = np.full(n, 80.0)
    roar = np.tanh(3 * sine(chirp(96, 62, n), n)) * env(n, .01, 1.4)
    return reverb(roar + sine(46, n) * env(n, .01, 2) * .7, .3, .5)


def buddha_cast():
    n = int(1.6 * SR)
    gong = modal([110, 163, 221, 297, 401, 542, 733], [1, .7, .5, .36, .26, .18, .12],
                 [1.2, .95, .8, .65, .5, .4, .3], n, detune=.0015)
    return reverb(gong * env(n, .01, .9) + sine(220, n) * env(n, .05, 3) * .18, .4, .6)


def buddha_release():
    n = int(1.1 * SR)
    x = np.zeros(n)
    for i, f in enumerate((523, 659, 784, 1046)):
        st = int(i * .09 * SR)
        ln = n - st
        x[st:] += modal([f, f * 2.76, f * 5.4], [1, .3, .15], [.5, .3, .2], ln) * env(ln, .01, 1.8) * (.9 - i * .12)
    return reverb(x, .4, .55)


def buddha_hit():
    n = int(0.8 * SR)
    bell = modal([659, 1318, 1815, 2637], [1, .5, .3, .18], [.55, .4, .3, .2], n)
    return reverb(bell * env(n, .003, 1.3), .35, .45)


def buddha_impact():
    n = int(1.2 * SR)
    gong = modal([82, 123, 168, 227, 309], [1, .8, .6, .4, .25], [1.3, 1.0, .8, .6, .45], n)
    return reverb(gong * env(n, .006, .85), .4, .65)


def magic_cast():
    n = int(0.8 * SR)
    fm = sine(440, n) * env(n, .02, 1.3)
    mod = .8 * sine(1150, n) * np.exp(-np.linspace(0, 3.5, n))
    bell = np.sin(2 * np.pi * np.cumsum(440 + mod * 240) / SR)
    return reverb(bell * env(n, .01, 1.5) * .9 + modal([1320, 1760], [.25, .18], [.35, .25], n), .35, .45)


def magic_release():
    n = int(0.9 * SR)
    x = np.zeros(n)
    for i, f in enumerate((392, 587, 784, 988, 1175)):
        st = int(i * .055 * SR)
        ln = n - st
        x[st:] += sine(f, ln) * env(ln, .008, 2.2) * (.8 - i * .1)
    return reverb(x + biquad(noise(n), "bandpass", 4400, 1.3) * env(n, .02, 3) * .2, .4, .5)


def magic_hit():
    n = int(0.5 * SR)
    glass = modal([1864, 2793, 3724, 5587], [1, .5, .3, .15], [.28, .2, .14, .09], n)
    shard = biquad(noise(n), "highpass", 4000, .9) * env(n, .001, 7) * .45
    return reverb(glass * env(n, .002, 1.6) + shard, .3, .35)


def magic_impact():
    n = int(0.85 * SR)
    boom = sine(chirp(150, 48, n), n) * env(n, .006, 1.4) + sine(58, n) * env(n, .01, 2.2) * .8
    res = modal([196, 293, 411], [.5, .35, .2], [.6, .45, .3], n)
    return reverb(boom + res * env(n, .01, 1.8), .35, .5)


def dao_cast():
    n = int(0.7 * SR)
    jade = modal([784, 1175, 1568, 2350], [1, .45, .3, .15], [.32, .24, .18, .12], n)
    knock = biquad(noise(n), "bandpass", 2400, 1.5) * env(n, .001, 8) * .35
    return reverb(jade * env(n, .003, 1.5) + knock, .35, .4)


def dao_release():
    n = int(1.2 * SR)
    x = np.zeros(n)
    for i in range(9):  # 风铃
        st = int(rng.uniform(0, .55) * SR)
        ln = n - st
        f = float(rng.choice([880, 1108, 1318, 1568, 1760, 2093]))
        x[st:] += modal([f, f * 2.8], [1, .2], [.45, .3], ln) * env(ln, .004, 1.9) * rng.uniform(.4, .9)
    return reverb(x + biquad(noise(n), "bandpass", 1400, .8) * env(n, .15, 2.2) * .18, .45, .55)


def dao_hit():
    n = int(0.5 * SR)
    clink = modal([1318, 1976, 2794], [1, .5, .25], [.3, .22, .15], n)
    return reverb(clink * env(n, .002, 1.8), .3, .35)


def dao_impact():
    n = int(0.95 * SR)
    g = modal([147, 220, 311, 415], [1, .6, .38, .22], [.9, .7, .55, .4], n)
    return reverb(g * env(n, .005, 1.0) + sine(73, n) * env(n, .01, 2.4) * .5, .4, .6)


def neili_cast():
    n = int(0.6 * SR)
    who = biquad(noise(n), "bandpass", 500, .8) * env(n, .18, 2.4)
    who += biquad(noise(n), "bandpass", 1300, .9) * env(n, .22, 2.6) * .6
    return reverb(who * 1.2, .3, .45)


def neili_release():
    n = int(0.7 * SR)
    f = chirp(700, 300, n)
    swipe = biquad(noise(n), "bandpass", 900, .7) * env(n, .05, 2.6) * 1.2
    tone = sine(f, n) * env(n, .03, 2.2) * .35
    return reverb(swipe + tone, .3, .4)


def neili_hit():
    n = int(0.55 * SR)
    thud = sine(chirp(190, 70, n), n) * env(n, .003, 1.8)
    cloth = biquad(noise(n), "bandpass", 800, .9) * env(n, .002, 5) * .7
    return reverb(thud + cloth, .25, .4)


def neili_impact():
    n = int(0.9 * SR)
    heavy = sine(chirp(140, 44, n), n) * env(n, .004, 1.3)
    crack = biquad(noise(n), "bandpass", 1200, .8) * env(n, .002, 4) * .6
    return reverb(heavy + sine(52, n) * env(n, .008, 2.0) * .8 + crack, .3, .5)


def chakra_cast():
    n = int(0.75 * SR)
    hiss = biquad(noise(n), "highpass", 1800, .8) * env(n, .12, 2.2)
    breath = biquad(noise(n), "bandpass", 700, .9) * env(n, .15, 2.4) * 1.1
    return reverb(hiss * .7 + breath, .25, .4)


def chakra_release():
    n = int(0.85 * SR)
    roar = biquad(noise(n), "lowpass", 1400, .7) * env(n, .04, 2.2) * 1.3
    tone = sine(chirp(300, 140, n), n) * env(n, .03, 2.0) * .4
    crackle = np.zeros(n)
    for _ in range(9):
        st = int(rng.uniform(0, .6) * SR)
        ln = int(rng.uniform(.005, .02) * SR)
        crackle[st:st + ln] += biquad(noise(ln), "bandpass", rng.uniform(1500, 4000), 1.2) * env(ln, .001, 5) * .5
    return reverb(roar + tone + crackle, .3, .45)


def chakra_hit():
    n = int(0.55 * SR)
    burst = biquad(noise(n), "lowpass", 2200, .7) * env(n, .002, 3.2)
    low = sine(chirp(160, 66, n), n) * env(n, .003, 2.0) * .8
    return reverb(burst + low, .28, .4)


def chakra_impact():
    n = int(1.1 * SR)
    boom = biquad(noise(n), "lowpass", 900, .7) * env(n, .008, 1.6) * 1.2
    sub = sine(chirp(120, 36, n), n) * env(n, .01, 1.4)
    return reverb(boom + sub + sine(48, n) * env(n, .012, 2.0) * .7, .35, .55)


POOLS = {
    "spirit": (spirit_cast, spirit_release, spirit_hit, spirit_impact),
    "mind": (mind_cast, mind_release, mind_hit, mind_impact),
    "psychic": (psychic_cast, psychic_release, psychic_hit, psychic_impact),
    "yokai": (yokai_cast, yokai_release, yokai_hit, yokai_impact),
    "buddha": (buddha_cast, buddha_release, buddha_hit, buddha_impact),
    "magic": (magic_cast, magic_release, magic_hit, magic_impact),
    "dao": (dao_cast, dao_release, dao_hit, dao_impact),
    "neili": (neili_cast, neili_release, neili_hit, neili_impact),
    "chakra": (chakra_cast, chakra_release, chakra_hit, chakra_impact),
}

LAYERS = ("cast", "release", "hit", "impact")

# 字幕
SUB = {
    "spirit": ("灵力起手", "灵力释放", "灵力命中", "灵力重击"),
    "mind": ("精神力起手", "精神力释放", "精神力命中", "精神力重击"),
    "psychic": ("灵能起手", "灵能释放", "灵能命中", "灵能重击"),
    "yokai": ("妖力起手", "妖力释放", "妖力命中", "妖力重击"),
    "buddha": ("佛法起手", "佛法释放", "佛法命中", "佛法重击"),
    "magic": ("魔法起手", "魔法释放", "魔法命中", "魔法重击"),
    "dao": ("道术起手", "道术释放", "道术命中", "道术重击"),
    "neili": ("内劲起手", "内劲释放", "内劲命中", "内劲重击"),
    "chakra": ("查克拉起手", "查克拉释放", "查克拉命中", "查克拉重击"),
}
SUB_EN = {
    "spirit": ("Spirit energy gathers", "Spirit released", "Spirit strikes", "Spirit impact"),
    "mind": ("Mind energy gathers", "Mind released", "Mind strikes", "Mind impact"),
    "psychic": ("Psychic energy gathers", "Psychic released", "Psychic strikes", "Psychic impact"),
    "yokai": ("Yokai energy gathers", "Yokai released", "Yokai strikes", "Yokai impact"),
    "buddha": ("Buddhist power gathers", "Buddhist power released", "Buddhist power strikes", "Buddhist impact"),
    "magic": ("Magic gathers", "Magic released", "Magic strikes", "Magic impact"),
    "dao": ("Dao power gathers", "Dao power released", "Dao power strikes", "Dao impact"),
    "neili": ("Inner force gathers", "Inner force released", "Inner force strikes", "Inner force impact"),
    "chakra": ("Chakra gathers", "Chakra released", "Chakra strikes", "Chakra impact"),
}


def main():
    os.makedirs(SOUND_DIR, exist_ok=True)
    made = []
    for pool, fns in POOLS.items():
        for layer, fn in zip(LAYERS, fns):
            made.append(write(f"art_{pool}_{layer}", fn()))

    sounds = json.load(open(SOUNDS_JSON, encoding="utf-8"))
    for pool in POOLS:
        for i, layer in enumerate(LAYERS):
            key = f"art_{pool}_{layer}"
            sounds[key] = {
                "subtitle": f"subtitles.zhushenspace.{key}",
                "sounds": [{"name": f"zhushenspace:{key}", "volume": 1.0, "pitch": 1.0,
                            "stream": False, "attenuation_distance": 32}],
            }
    json.dump(sounds, open(SOUNDS_JSON, "w", encoding="utf-8"), ensure_ascii=False, indent=2)

    for lang, table in (("zh_cn", SUB), ("en_us", SUB_EN)):
        path = os.path.join(LANG, lang + ".json")
        data = json.load(open(path, encoding="utf-8"))
        for pool, names in table.items():
            for layer, name in zip(LAYERS, names):
                data[f"subtitles.zhushenspace.art_{pool}_{layer}"] = name
        json.dump(data, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)

    print(f"生成 {len(made)} 个音效，sounds.json 共 {len(sounds)} 条")
    for m in made:
        print("  ", m)


if __name__ == "__main__":
    main()
