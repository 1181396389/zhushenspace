#!/usr/bin/env python3
"""把 Blockbench 自由模型（.bbmodel）烘焙为运行时使用的四边形网格 + 贴图。

用法：python3 tools/bake_bbmodel.py art/mahoraga_wheel.bbmodel mahoraga_wheel
输出：
  src/main/resources/assets/zhushenspace/models/entity/<name>.json   （四边形，单位：像素）
  src/main/resources/assets/zhushenspace/textures/entity/<name>_model.png  （第一张贴图）
规则与 Blockbench / 原版方块模型一致：元素旋转 = Rz·Ry·Rx（绕 origin），
面的四个顶点顺序取自原版 FaceInfo（从外侧看为逆时针），UV 角顺序 (u0,v0)(u0,v1)(u1,v1)(u1,v0)，面旋转按 90° 平移。
"""
import base64, json, math, os, sys

FACES = {
    'down':  [(0,0,1),(0,0,0),(1,0,0),(1,0,1)],
    'up':    [(0,1,0),(0,1,1),(1,1,1),(1,1,0)],
    'north': [(1,1,0),(1,0,0),(0,0,0),(0,1,0)],
    'south': [(0,1,1),(0,0,1),(1,0,1),(1,1,1)],
    'west':  [(0,1,0),(0,0,0),(0,0,1),(0,1,1)],
    'east':  [(1,1,1),(1,0,1),(1,0,0),(1,1,0)],
}

def mat(rx, ry, rz):
    def m(a, b):
        return [[sum(a[i][k]*b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]
    cx, sx = math.cos(rx), math.sin(rx); cy, sy = math.cos(ry), math.sin(ry); cz, sz = math.cos(rz), math.sin(rz)
    X = [[1,0,0],[0,cx,-sx],[0,sx,cx]]; Y = [[cy,0,sy],[0,1,0],[-sy,0,cy]]; Z = [[cz,-sz,0],[sz,cz,0],[0,0,1]]
    return m(m(Z, Y), X)

def apply(M, v): return [sum(M[i][k]*v[k] for k in range(3)) for i in range(3)]

def bake(path, name, root):
    d = json.load(open(path, encoding='utf-8'))
    tw = d.get('resolution', {}).get('width', 16); th = d.get('resolution', {}).get('height', 16)
    quads = []
    ys = []
    for e in d['elements']:
        if e.get('type', 'cube') != 'cube' or e.get('visibility') is False: continue
        inf = e.get('inflate', 0)
        f = [c - inf for c in e['from']]; t = [c + inf for c in e['to']]; o = e.get('origin', [0,0,0])
        r = [math.radians(a) for a in e.get('rotation', [0,0,0])]
        M = mat(*r)
        for fn, corners in FACES.items():
            fc = e['faces'].get(fn)
            if not fc or fc.get('texture') is None: continue
            u0, v0, u1, v1 = fc['uv']; rot = (fc.get('rotation', 0) // 90) % 4
            uvc = [(u0,v0),(u0,v1),(u1,v1),(u1,v0)]
            vs = []
            for i, c in enumerate(corners):
                p = [t[k] if c[k] else f[k] for k in range(3)]
                p = apply(M, [p[k]-o[k] for k in range(3)]); p = [p[k]+o[k] for k in range(3)]
                u, v = uvc[(i+rot) % 4]
                vs.append([round(p[0],4), round(p[1],4), round(p[2],4), round(u/tw,5), round(v/th,5)])
                ys.append(p[1])
            a = [vs[1][k]-vs[0][k] for k in range(3)]; b = [vs[2][k]-vs[0][k] for k in range(3)]
            n = [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]]
            ln = math.sqrt(sum(x*x for x in n)) or 1
            quads.append({'v': vs, 'n': [round(x/ln,4) for x in n]})
    out = {'source': os.path.basename(path), 'name': d.get('name'), 'quads': quads}
    res = os.path.join(root, 'src/main/resources/assets/zhushenspace')
    os.makedirs(os.path.join(res, 'models/entity'), exist_ok=True)
    json.dump(out, open(os.path.join(res, f'models/entity/{name}.json'), 'w'), separators=(',', ':'))
    tex = d['textures'][0]['source'].split(',', 1)[1]
    open(os.path.join(res, f'textures/entity/{name}_model.png'), 'wb').write(base64.b64decode(tex))
    print(f'{len(quads)} quads, y {min(ys):.2f}..{max(ys):.2f}')

if __name__ == '__main__':
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    bake(sys.argv[1], sys.argv[2], root)
