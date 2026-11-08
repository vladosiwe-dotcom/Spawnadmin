"""Renders build/fracture-dump.txt (top-down disc view) at several crack-progress values."""
import sys
from PIL import Image, ImageDraw

src, out = sys.argv[1], sys.argv[2]
S = 420
shards, cracks = [], []
for line in open(src):
    p = line.split()
    if p[0] == "I":
        ix, iy, open_r, max_front = map(float, p[1:])
    elif p[0] == "S":
        pts = list(map(float, p[4:]))
        shards.append((int(p[2]), int(p[3]), list(zip(pts[::2], pts[1::2]))))
    else:
        cracks.append((int(p[1]),) + tuple(map(float, p[2:])))

def tr(x, y):
    return (S / 2 + x * S * 0.48, S / 2 - y * S * 0.48)

frames = []
for prog in (0.25, 0.6, 1.0):
    im = Image.new("RGB", (S, S), (12, 20, 40))
    d = ImageDraw.Draw(im)
    for det, hov, pts in shards:
        col = (40, 70, 110) if not det else ((90, 60, 140) if hov else (60, 100, 150))
        d.polygon([tr(*q) for q in pts], fill=col)
    front = prog * max_front * 1.02
    for hair, ax, ay, bx, by, da, db, w, width in cracks:
        if hair:
            f = max(0, min(1, (front - da) / w))
            if f > 0:
                d.line([tr(ax, ay), tr(ax + (bx - ax) * f, ay + (by - ay) * f)], fill=(170, 210, 255), width=1)
        else:
            fa = max(0, min(1, (front - da) / w)); fb = max(0, min(1, (front - db) / w))
            col = (235, 250, 255)
            ww = 1 + int(width * 3)
            if fa + fb >= 1:
                d.line([tr(ax, ay), tr(bx, by)], fill=col, width=ww)
            else:
                if fa > 0: d.line([tr(ax, ay), tr(ax + (bx - ax) * fa, ay + (by - ay) * fa)], fill=col, width=ww)
                if fb > 0: d.line([tr(bx, by), tr(bx + (ax - bx) * fb, by + (ay - by) * fb)], fill=col, width=ww)
    d.ellipse([tr(ix, iy)[0] - 4, tr(ix, iy)[1] - 4, tr(ix, iy)[0] + 4, tr(ix, iy)[1] + 4], fill=(255, 220, 120))
    frames.append(im)
sheet = Image.new("RGB", (S * 3, S))
for i, f in enumerate(frames):
    sheet.paste(f, (i * S, 0))
sheet.save(out)
print("shards", len(shards), "detached", sum(s[0] for s in shards), "hover", sum(s[1] for s in shards), "cracks", len(cracks))
