"""집사 아이콘 → 안드로이드 적응형 아이콘 층(앞 그림·단색)과 미리보기 (CHANGES.md 11단계).

쓰는 법: python make_icon.py source.png android/app/src/main/res preview.png  (Pillow·numpy 필요)
바탕은 단색 @color/jarvis_launcher_background(#012D70) — 원본 그림 바탕 남색과 같아야 이음매가 안 보인다.
"""
import math, os, sys
from PIL import Image, ImageChops, ImageDraw, ImageFilter
src, res, out = sys.argv[1], sys.argv[2], sys.argv[3]
NAVY = (1, 45, 112)
im = Image.open(src).convert('RGB'); W = im.size[0]
px = im.load()
# 내용(남색에서 먼 픽셀) 경계와 중심에서의 최대 반지름
pts = [(x, y) for y in range(0, W, 2) for x in range(0, W, 2) if sum((px[x, y][i] - NAVY[i]) ** 2 for i in range(3)) > 60 ** 2]
x0, x1 = min(p[0] for p in pts), max(p[0] for p in pts); y0, y1 = min(p[1] for p in pts), max(p[1] for p in pts)
cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
rmax = max(math.hypot(x - cx, y - cy) for x, y in pts)
HAIR_ALPHA = 0.45                # 상태바 아이콘의 머리카락 불투명도
R_DP = 32.0                      # 내용 반지름 32dp — 안전원(지름 66dp) 안
scale_dp = R_DP / rmax           # 원본 1px = ? dp
print(f'bbox {x0},{y0}-{x1},{y1} center {cx:.0f},{cy:.0f} rmax {rmax:.0f}px → image {W*scale_dp:.1f}dp')

import numpy as np
from PIL import ImageDraw as _D
a = np.asarray(im).astype(np.float32)
dist = np.sqrt(((a - np.array(NAVY, np.float32)) ** 2).sum(-1))
lum = a @ np.array([0.299, 0.587, 0.114], np.float32)
navy = dist < 60
dark = (lum < 90) & ~navy
lab = Image.fromarray(np.where(dark, 255, 0).astype(np.uint8)).copy()  # fromarray 는 읽기 전용 — floodfill 이 사본에 칠하고 버린다
near_navy = np.asarray(Image.fromarray((navy * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(11))) > 0
for y, x in zip(*np.nonzero(dark & near_navy)):   # 남색에 닿은 검은 덩어리 = 머리 → 실루엣에 넣는다
    if lab.getpixel((int(x), int(y))) == 255:
        _D.floodfill(lab, (int(x), int(y)), 128)
hole = np.asarray(Image.fromarray(np.where(np.asarray(lab) == 255, 255, 0).astype(np.uint8)).filter(ImageFilter.MaxFilter(5))) > 0
mono_alpha = np.clip((dist - 20) / 60, 0, 1) * np.where(hole, np.clip((lum - 90) / 80, 0, 1), 1)
MONO = Image.fromarray((mono_alpha * 255).astype(np.uint8))
print('구멍 픽셀', int(hole.sum()))

def layer(px_per_dp, mode):
    C = round(108 * px_per_dp); s = scale_dp * px_per_dp
    sz = round(W * s)
    img = im.resize((sz, sz), Image.LANCZOS)
    ox = round(C / 2 - cx * s); oy = round(C / 2 - cy * s)
    canvas = Image.new('RGBA', (C, C), (0, 0, 0, 0))
    if mode == 'fg':
        # 붙인 그림 가장자리(전부 남색)를 투명으로 번지게 — 배경 단색과 이음매가 안 보이게.
        # 바탕 RGB 를 남색으로 깔고 알파만 따로 — 투명 검정 위에 붙이면 가장자리가 어둡게 번진다
        rgb = Image.new('RGB', (C, C), NAVY); rgb.paste(img, (ox, oy))
        mask = Image.new('L', (sz, sz), 0)
        f = round(sz * 0.08)
        ImageDraw.Draw(mask).rectangle([f, f, sz - f, sz - f], fill=255)
        mask = mask.filter(ImageFilter.GaussianBlur(f / 2.5))
        alpha = Image.new('L', (C, C), 0); alpha.paste(mask, (ox, oy))
        canvas = rgb.convert('RGBA'); canvas.putalpha(alpha)
    else:
        # 단색(테마 아이콘): 머리·얼굴·말풍선 꼬리·나비넥타이 테두리 = 한 덩어리, 콧수염·나비넥타이 속 = 구멍
        white = Image.new('RGBA', (sz, sz), (255, 255, 255, 255))
        canvas.paste(white, (ox, oy), MONO.resize((sz, sz), Image.LANCZOS))
    return canvas

DENS = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
for d, k in DENS.items():
    os.makedirs(f'{res}/mipmap-{d}', exist_ok=True)
    layer(k, 'fg').save(f'{res}/mipmap-{d}/jarvis_launcher_foreground.png', optimize=True)
    layer(k, 'mono').save(f'{res}/mipmap-{d}/jarvis_launcher_monochrome.png', optimize=True)

# 상태바 알림 아이콘(16단계): 단색 실루엣만 쓰인다(안드로이드가 흰색으로 칠함) — 테마 아이콘과 같은 실루엣을 24dp 칸에 꽉 차게
# 머리카락은 반투명(상태바에선 회색으로 보인다) — 말풍선 얼굴과 색이 다르게(10-09 주현님 「머리카락은 말풍선이랑 색 다르게」)
hair = np.asarray(lab) == 128
hair_soft = np.asarray(Image.fromarray((hair * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(2))) / 255.0
stat_alpha = mono_alpha * (1 - hair_soft * (1 - HAIR_ALPHA))
STAT = Image.fromarray((stat_alpha * 255).astype(np.uint8))
bbox = STAT.getbbox()
sil = STAT.crop(bbox)
for d, k in DENS.items():
    C = round(24 * k); inner = round(22 * k)
    w, h = sil.size; f = inner / max(w, h)
    small = sil.resize((max(1, round(w * f)), max(1, round(h * f))), Image.LANCZOS)
    stat = Image.new('RGBA', (C, C), (255, 255, 255, 0))
    stat.paste(Image.new('RGBA', small.size, (255, 255, 255, 255)), ((C - small.size[0]) // 2, (C - small.size[1]) // 2), small)
    os.makedirs(f'{res}/drawable-{d}', exist_ok=True)
    stat.save(f'{res}/drawable-{d}/ic_stat_jarvis.png', optimize=True)

# 미리보기: 원·둥근네모 마스크 + 테마 아이콘
fg = layer(4, 'fg'); mono = layer(4, 'mono'); C = fg.size[0]
def masked(shape, theme=False):
    base = Image.new('RGBA', (C, C), (232, 222, 248, 255) if theme else NAVY + (255,))
    base.alpha_composite(Image.composite(Image.new('RGBA', (C, C), (72, 52, 120, 255)), Image.new('RGBA', (C, C), (0, 0, 0, 0)), mono.split()[3]) if theme else fg)
    vis = round(C * 72 / 108); o = (C - vis) // 2
    crop = base.crop((o, o, o + vis, o + vis))
    m = Image.new('L', (vis, vis), 0); d = ImageDraw.Draw(m)
    (d.ellipse if shape == 'circle' else lambda b, fill: d.rounded_rectangle(b, radius=vis * 0.3, fill=fill))([0, 0, vis - 1, vis - 1], fill=255)
    crop.putalpha(m); return crop
tiles = [masked('circle'), masked('squircle'), masked('squircle', True)]
sheet = Image.new('RGBA', (sum(t.size[0] for t in tiles) + 40 * 4, tiles[0].size[1] + 80), (245, 245, 245, 255))
x = 40
for t in tiles: sheet.alpha_composite(t, (x, 40)); x += t.size[0] + 40
small = masked('squircle').resize((48 * 3 // 2, 48 * 3 // 2), Image.LANCZOS)
sheet.alpha_composite(small, (sheet.size[0] - 120, sheet.size[1] - 100))
sheet.save(out)
