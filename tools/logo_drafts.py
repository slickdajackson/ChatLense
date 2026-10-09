#!/usr/bin/env python3
"""Zeichnet Logo-Entwuerfe (Sprechblase mit KI-Sternen, ohne Lupe) als PNG, ohne Android: python3 tools/logo_drafts.py [Ausgabeordner].
Idee 1: drei Sterne statt der drei Punkte, von gross zu klein. Idee 2: ein grosser und zwei kleine Sterne (Sparkle-Anordnung).
Alles im 48er Raster wie make_logo.py; 3 Pixel je dp (xxhdpi)."""
import sys, os
import numpy as np
from PIL import Image, ImageDraw, ImageFont

OUT = sys.argv[1] if len(sys.argv) > 1 else "renders"
TEAL, VIOLET, ICE, NAVY = (0x4D, 0xE3, 0xD0), (0x8B, 0x7C, 0xFF), (0xEA, 0xF0, 0xFF), (0x0B, 0x10, 0x20)
LAV = (0xB6, 0xAB, 0xFF)
SS = 4  # Ueberabtastung

# ---------- Geometrie (48er Raster) ----------
def bez(p0, p1, p2, n=12):
    return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0], (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]) for t in [i / n for i in range(n)]]

def star(cx, cy, r, k=0.16):
    d = k * r
    pts = [(cx, cy - r), (cx + r, cy), (cx, cy + r), (cx - r, cy)]
    ctl = [(cx + d, cy - d), (cx + d, cy + d), (cx - d, cy + d), (cx - d, cy - d)]
    out = []
    for i in range(4):
        out += bez(pts[i], ctl[i], pts[(i + 1) % 4])
    return out

def arc(cx, cy, r, a0, a1, n=10):
    import math
    return [(cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / n)), cy + r * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]

def bubble():
    r = 9
    pts = [(13, 6), (35, 6)] + arc(35, 15, r, -90, 0) + [(44, 27)] + arc(35, 27, r, 0, 90)[1:] + [(20, 36), (10.5, 43.5), (12, 36), (13, 36)]
    pts += arc(13, 27, r, 90, 180)[1:] + [(4, 15)] + arc(13, 15, r, 180, 270)[1:]
    return pts

# Sterne je Entwurf: Liste (cx, cy, r)
ROW = [(14, 21, 7.2), (27, 21, 4.8), (37, 21, 3.0)]            # Idee 1: gross nach klein in einer Reihe
DIAG = [(13.5, 25, 7.2), (26, 20, 4.8), (36.5, 15.5, 3.0)]     # Idee 1: gross nach klein steigend
SPARK = [(19.5, 22.5, 10.0), (33.0, 14.5, 4.8), (32.5, 29.0, 3.6)]  # Idee 2: ein grosser, zwei kleine daneben
SPARK2 = [(18.5, 24, 9.0), (31.5, 13.5, 4.6), (34, 27.5, 3.2)]

# ---------- Zeichnen ----------
def mask_poly(size, pts, scale):
    m = Image.new("L", (size * SS, size * SS), 0)
    ImageDraw.Draw(m).polygon([(x * scale * SS, y * scale * SS) for x, y in pts], fill=255)
    return m

def mask_outline(size, pts, scale, w):
    m = Image.new("L", (size * SS, size * SS), 0)
    d = ImageDraw.Draw(m)
    p = [(x * scale * SS, y * scale * SS) for x, y in pts] + [(pts[0][0] * scale * SS, pts[0][1] * scale * SS)]
    d.line(p, fill=255, width=int(w * scale * SS), joint="curve")
    return m

def grad(size, c1, c2, x1=4, y1=6, x2=44, y2=44, scale=1.0):
    ys, xs = np.mgrid[0:size * SS, 0:size * SS].astype(np.float32)
    xs /= SS * scale; ys /= SS * scale
    dx, dy = x2 - x1, y2 - y1
    t = np.clip(((xs - x1) * dx + (ys - y1) * dy) / (dx * dx + dy * dy), 0, 1)[..., None]
    a = np.array(c1, np.float32); b = np.array(c2, np.float32)
    return Image.fromarray((a + (b - a) * t).astype(np.uint8), "RGB")

def solid(size, c):
    return Image.new("RGB", (size * SS, size * SS), c)

def paste(canvas, fill, mask):
    canvas.paste(fill, (0, 0), mask)

def draw_mark(kind, size, stars, mono=False):
    """Marke auf transparentem Grund. size = Pixel; Raster 48 -> size."""
    sc = size / 48.0
    img = Image.new("RGB", (size * SS, size * SS), (0, 0, 0))
    alpha = Image.new("L", (size * SS, size * SS), 0)
    def add(fill, mask, erase=False):
        nonlocal alpha
        if erase:
            alpha = Image.composite(Image.new("L", alpha.size, 0), alpha, mask)
        else:
            img.paste(fill, (0, 0), mask)
            alpha = Image.composite(Image.new("L", alpha.size, 255), alpha, mask)
    B = bubble()
    sm = [mask_poly(size, star(*s), sc) for s in stars]
    white = solid(size, (255, 255, 255))
    if kind == "neg" or (mono and kind in ("neg", "light")):
        add(white if mono else grad(size, TEAL, VIOLET, scale=sc), mask_poly(size, B, sc))
        for m in sm: add(None, m, erase=True)
    elif kind == "outline":
        if mono:
            add(white, mask_outline(size, B, sc, 3.0))
            for m in sm: add(white, m)
        else:
            add(solid(size, VIOLET), Image.eval(mask_poly(size, B, sc), lambda v: int(v * 0.18)))
            add(grad(size, TEAL, VIOLET, scale=sc), mask_outline(size, B, sc, 2.6))
            for m, c in zip(sm, [ICE, TEAL, LAV]): add(solid(size, c), m)
    elif kind == "light":
        add(solid(size, ICE), mask_poly(size, B, sc))
        for m, c in zip(sm, [None, VIOLET, (0x2F, 0xBF, 0xAE)]):
            add(grad(size, TEAL, VIOLET, scale=sc) if c is None else solid(size, c), m)
    elif kind == "glass":  # dunkle Glasflaeche mit hellem Rand und hellen Sternen
        add(solid(size, (0x2A, 0x2F, 0x5E)), mask_poly(size, B, sc))
        add(grad(size, TEAL, VIOLET, scale=sc), mask_outline(size, B, sc, 2.0))
        for m, c in zip(sm, [ICE, ICE, ICE]): add(solid(size, c), m)
    rgba = img.convert("RGBA"); rgba.putalpha(alpha)
    return rgba.resize((size, size), Image.LANCZOS)

def bg_layer(size, style):
    if style == 0:
        g = grad(size, (0x2A, 0x1F, 0x6B), NAVY, 0, 0, 108, 108, scale=size / 108)
    else:
        g = grad(size, (0x24, 0x30, 0x61), NAVY, 0, 0, 108, 108, scale=size / 108)
    return g.resize((size, size), Image.LANCZOS).convert("RGBA")

def adaptive(kind, stars, dp, mask, mono=False, mpx=3):
    S = int(108 * dp / 72 * mpx)   # Ebene so gross, dass der sichtbare Teil (72 dp) dp * mpx Pixel misst
    vis = int(dp * mpx)
    layer = bg_layer(S, 0) if not mono else Image.new("RGBA", (S, S), (0x2B, 0x3A, 0x55, 255))
    markpx = int(60 * dp / 72 * mpx)  # Marke fuellt 60 von 108 dp (Gruppe: Verschiebung 24, Skalierung 1,25)
    m = draw_mark(kind, markpx, stars, mono)
    if mono:
        t = Image.new("RGBA", m.size, (0xA8, 0xC7, 0xFA, 255)); t.putalpha(m.split()[3]); m = t
    layer.alpha_composite(m, ((S - markpx) // 2, (S - markpx) // 2))
    off = (S - vis) // 2
    crop = layer.crop((off, off, off + vis, off + vis))
    mk = Image.new("L", (vis * SS, vis * SS), 0); d = ImageDraw.Draw(mk)
    if mask == "circle": d.ellipse([0, 0, vis * SS - 1, vis * SS - 1], fill=255)
    else: d.rounded_rectangle([0, 0, vis * SS - 1, vis * SS - 1], radius=int(vis * SS * 0.25), fill=255)
    crop.putalpha(mk.resize((vis, vis), Image.LANCZOS))
    return crop

def font(n):
    return ImageFont.load_default(size=n)

def sheet(name, title, sub, kind, stars):
    W, H = 411 * 3, 1500
    im = Image.new("RGB", (W, H), (0x10, 0x14, 0x1C)); d = ImageDraw.Draw(im)
    d.text((30, 24), title, fill=(255, 255, 255), font=font(40))
    d.text((30, 78), sub, fill=(0xB8, 0xC2, 0xD6), font=font(26))
    y = 140
    d.text((30, y), "Adaptive Ebenen, 120 dp: Kreis, abgerundet, einfarbig", fill=(0xB8, 0xC2, 0xD6), font=font(26)); y += 44
    x = 30
    for mask, mono in (("circle", False), ("square", False), ("circle", True)):
        im.paste(adaptive(kind, stars, 120, mask, mono), (x, y), adaptive(kind, stars, 120, mask, mono)); x += 120 * 3 + 20
    y += 120 * 3 + 40
    d.text((30, y), "Echte Groesse 48 dp (und 96 dp)", fill=(0xB8, 0xC2, 0xD6), font=font(26)); y += 44
    x = 30
    for mask, mono, dp in (("circle", False, 48), ("square", False, 48), ("circle", True, 48), ("circle", False, 96)):
        a = adaptive(kind, stars, dp, mask, mono); im.paste(a, (x, y), a); x += dp * 3 + 30
    y += 96 * 3 + 40
    d.text((30, y), "Marke im Punkt (60 dp), 48 dp frei auf dunklem Grund, 24 dp Statusleiste (weiss)", fill=(0xB8, 0xC2, 0xD6), font=font(26)); y += 44
    x = 30
    dot = Image.new("RGBA", (180, 180), (0, 0, 0, 0)); dd = ImageDraw.Draw(dot)
    dd.ellipse([0, 0, 179, 179], fill=(0x1B, 0x22, 0x30, 255), outline=(0x4D, 0xE3, 0xD0, 255), width=3)
    dot.alpha_composite(draw_mark(kind, 120, stars), (30, 30)); im.paste(dot, (x, y), dot); x += 210
    m48 = draw_mark(kind, 144, stars); im.paste(m48, (x, y + 18), m48); x += 180
    st = draw_mark(kind, 72, stars, mono=True); im.paste(st, (x, y + 40), st); x += 110
    # grosse Marke
    y += 210
    d.text((30, y), "Marke gross", fill=(0xB8, 0xC2, 0xD6), font=font(26)); y += 44
    big = draw_mark(kind, 480, stars); im.paste(big, (30, y), big)
    im = im.crop((0, 0, W, y + 480 + 30))
    im.save(os.path.join(OUT, name + ".png"))
    return im

DRAFTS = [
    ("entwurf-1a", "Entwurf 1a (Idee 1)", "Blase als Flaeche im Verlauf, drei Sterne gross nach klein in einer Reihe, ausgespart", "neg", ROW),
    ("entwurf-1b", "Entwurf 1b (Idee 1)", "Blase als Umriss, drei Sterne gross nach klein in einer Reihe, gefuellt (hell, tuerkis, lavendel)", "outline", ROW),
    ("entwurf-1c", "Entwurf 1c (Idee 1)", "Helle Blase, drei Sterne gross nach klein steigend, im Verlauf", "light", DIAG),
    ("entwurf-1d", "Entwurf 1d (Idee 1)", "Dunkle Glasblase mit Verlaufsrand, drei helle Sterne gross nach klein steigend", "glass", DIAG),
    ("entwurf-2a", "Entwurf 2a (Idee 2)", "Blase als Flaeche im Verlauf, ein grosser Stern und zwei kleine daneben, ausgespart", "neg", SPARK),
    ("entwurf-2b", "Entwurf 2b (Idee 2)", "Helle Blase, ein grosser Stern im Verlauf und zwei kleine daneben", "light", SPARK2),
]

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    ims = [sheet(*d) for d in DRAFTS]
    # Uebersicht: alle Marken gross auf dunklem Grund plus 48 dp im Kreis
    W = 411 * 3; cell = 400; H = 60 + 3 * (cell + 40) + 40
    ov = Image.new("RGB", (W, 40 + 3 * (cell + 70)), (0x10, 0x14, 0x1C)); d = ImageDraw.Draw(ov)
    for i, (n, t, s, k, st) in enumerate(DRAFTS):
        cx, cy = (i % 2) * (W // 2) + 20, 20 + (i // 2) * (cell + 70)
        d.text((cx, cy), t, fill=(255, 255, 255), font=font(30))
        m = draw_mark(k, 300, st); ov.paste(m, (cx, cy + 44), m)
        a = adaptive(k, st, 48, "circle"); ov.paste(a, (cx + 320, cy + 60), a)
        a = adaptive(k, st, 48, "square"); ov.paste(a, (cx + 320, cy + 60 + 170), a)
    ov.save(os.path.join(OUT, "uebersicht.png"))
    print("fertig", OUT)
