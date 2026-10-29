#!/usr/bin/env python3
"""Draws Soleanna Hub's launcher icon (a clock tower and a stage gate at sunset over the sea)
into app/src/soleanna/res/mipmap-*/ic_launcher.png. Opaque and full-bleed, so it reads the same
on light and dark home screens. Usage: make_icon.py <res folder> [preview.png]"""
import math, os, sys
from PIL import Image, ImageDraw, ImageFilter

S = 1024

def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

def draw():
    img = Image.new("RGB", (S, S))
    px = img.load()
    top, mid, low = (28, 52, 120), (238, 128, 64), (252, 206, 120)
    for y in range(S):
        t = y / (S * 0.62)
        c = lerp(top, mid, min(t, 1) ** 1.2) if t < 1 else low
        if 0.75 < t < 1:
            c = lerp(c, low, (t - 0.75) / 0.25)
        for x in range(S):
            px[x, y] = c
    d = ImageDraw.Draw(img, "RGBA")
    # Sun
    sun = (S * 0.70, S * 0.58)
    for r, a in ((260, 40), (190, 70), (130, 255)):
        d.ellipse([sun[0] - r, sun[1] - r, sun[0] + r, sun[1] + r], fill=(255, 236, 170, a))
    # Sea
    sea_y = int(S * 0.64)
    for y in range(sea_y, S):
        t = (y - sea_y) / (S - sea_y)
        d.line([(0, y), (S, y)], fill=lerp((40, 92, 170), (14, 34, 82), t))
    for i in range(9):
        y = sea_y + 18 + i * 34
        w = 260 - i * 14
        d.rounded_rectangle([sun[0] - w / 2, y, sun[0] + w / 2, y + 9], 4, fill=(255, 220, 150, 150 - i * 14))
    # City skyline and clock tower (dark silhouette)
    ink = (22, 26, 48)
    base = sea_y + 6
    for x0, w, h in ((40, 120, 150), (150, 90, 210), (430, 110, 170), (520, 140, 120), (650, 80, 190), (820, 160, 140)):
        d.rectangle([x0, base - h, x0 + w, base], fill=ink)
        d.polygon([(x0 - 6, base - h), (x0 + w / 2, base - h - 50), (x0 + w + 6, base - h)], fill=ink)
    tx, tw = 300, 120
    d.rectangle([tx, base - 520, tx + tw, base], fill=ink)
    d.rectangle([tx - 18, base - 540, tx + tw + 18, base - 500], fill=ink)
    d.polygon([(tx - 18, base - 540), (tx + tw / 2, base - 700), (tx + tw + 18, base - 540)], fill=ink)
    c = (tx + tw / 2, base - 430)
    d.ellipse([c[0] - 42, c[1] - 42, c[0] + 42, c[1] + 42], fill=(252, 232, 180))
    d.line([c, (c[0], c[1] - 30)], fill=ink, width=8)
    d.line([c, (c[0] + 22, c[1] + 8)], fill=ink, width=8)
    # Stage gate (glowing blue ring) in front
    g = (S * 0.66, S * 0.80)
    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse([g[0] - 175, g[1] - 205, g[0] + 175, g[1] + 145], outline=(90, 200, 255, 255), width=46)
    glow = glow.filter(ImageFilter.GaussianBlur(22))
    img.paste(glow, (0, 0), glow)
    d = ImageDraw.Draw(img, "RGBA")
    d.ellipse([g[0] - 165, g[1] - 195, g[0] + 165, g[1] + 135], outline=(150, 230, 255), width=30)
    d.ellipse([g[0] - 135, g[1] - 165, g[0] + 135, g[1] + 105], fill=(110, 210, 255, 90))
    d.rectangle([g[0] - 200, g[1] + 120, g[0] + 200, g[1] + 160], fill=ink)
    return img

def main():
    res = sys.argv[1]
    img = draw()
    for folder, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        out = os.path.join(res, "mipmap-" + folder)
        os.makedirs(out, exist_ok=True)
        icon = img.resize((size, size), Image.LANCZOS)
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], int(size * 0.22), fill=255)
        rgba = icon.convert("RGBA")
        rgba.putalpha(mask)
        rgba.save(os.path.join(out, "ic_launcher.png"))
    if len(sys.argv) > 2:
        img.resize((512, 512), Image.LANCZOS).save(sys.argv[2])

if __name__ == "__main__":
    main()
