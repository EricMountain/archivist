#!/usr/bin/env python3
"""Convert assets/vanishing-A-icon/vanishing-a.svg (from gen-vanishing-a.py) into the
Android adaptive-icon vector drawables.

Vector drawables can't hold <text>, so each "A" copy is turned into an outline path using
the font *embedded in the SVG* (not any font on disk). Writes ic_launcher_foreground.xml
(coloured) and ic_launcher_monochrome.xml (black, same alphas) under android/.../drawable.

Usage: tools/svg-to-android-icon.py [in.svg]
Needs fontTools: pip install fonttools
"""
import base64
import io
import pathlib
import re
import sys

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "assets/vanishing-A-icon/vanishing-a.svg"
OUT = ROOT / "android/app/src/main/res/drawable"
VIEWPORT_SCALE = 108 / 1080   # SVG canvas (1080) -> adaptive-icon viewport (108)

svg = SRC.read_text()
font = TTFont(io.BytesIO(base64.b64decode(re.search(r"base64,([A-Za-z0-9+/=]+)", svg).group(1))))
glyphs = font.getGlyphSet()
glyph = glyphs[font.getBestCmap()[ord("A")]]
size = float(re.search(r'font-size="([\d.]+)"', svg).group(1))
x0, y0 = map(float, re.search(r'<text x="([\d.]+)" y="([\d.]+)"', svg).groups())
vx, vy = map(float, re.search(r"translate\(([\d.]+) ([\d.]+)\) scale", svg).groups())
fs = size / font["head"].unitsPerEm

HEAD = ('<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n    android:height="108dp"\n'
        '    android:viewportWidth="108"\n    android:viewportHeight="108">\n')


def outline(k):
    """Glyph outline for the copy scaled by k about the vanishing point."""
    pen = SVGPathPen(glyphs, ntos=lambda v: ("%.2f" % v).rstrip("0").rstrip("."))
    a = fs * k * VIEWPORT_SCALE
    glyph.draw(TransformPen(pen, (a, 0, 0, -a, (vx + (x0 - vx) * k) * VIEWPORT_SCALE,
                                  (vy + (y0 - vy) * k) * VIEWPORT_SCALE)))
    return pen.getCommands()


fg, mono = [HEAD], [HEAD]
for k, fill, opacity in re.findall(
        r'scale\(([\d.]+)\) translate\([^)]*\)" fill="([^"]+)" opacity="([\d.]+)"', svg):
    d = outline(float(k))
    fill = "#000000" if fill == "black" else fill.upper()
    alpha = f"{float(opacity):.3f}"
    fg.append(f'    <path android:fillColor="{fill}" android:fillAlpha="{alpha}" android:pathData="{d}" />\n')
    mono.append(f'    <path android:fillColor="#000000" android:fillAlpha="{alpha}" android:pathData="{d}" />\n')
(OUT / "ic_launcher_foreground.xml").write_text("".join(fg) + "</vector>\n")
(OUT / "ic_launcher_monochrome.xml").write_text("".join(mono) + "</vector>\n")
print(f"wrote {len(fg) - 1} paths to {OUT}")
