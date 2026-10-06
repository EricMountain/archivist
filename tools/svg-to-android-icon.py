#!/usr/bin/env python3
"""Convert an SVG made by gen-vanishing-a.py, gen-extruded-a.py or
gen-plain-a.py into the Android adaptive-icon vector
drawables.

Vector drawables can't hold <text>, so each "A" copy is turned into an outline path using
the font *embedded in the SVG* (not any font on disk). Writes ic_launcher_foreground.xml
(coloured) and ic_launcher_monochrome.xml (black, same alphas) under android/.../drawable.

Usage: tools/svg-to-android-icon.py in.svg [--center] [--dx DP] [--dy DP] [--out DIR]
                                [--preview FILE.svg]

--preview also writes a contact sheet (circle / rounded square / unmasked with the 66dp
safe zone / monochrome) to eyeball without an emulator; open it in a browser.

--center moves the artwork so its bounding box is centred in the 108dp viewport; --dx/--dy
then nudge it (dp, +right/+down; on their own they shift from where the SVG put it).
Needs fontTools: pip install -r tools/requirements.txt (direnv does this, see .envrc)
"""
import argparse
import base64
import io
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

from fontTools.misc.transform import Transform
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

ROOT = pathlib.Path(__file__).resolve().parent.parent
ap = argparse.ArgumentParser(description="SVG from gen-vanishing-a.py -> adaptive-icon drawables")
ap.add_argument("svg", type=pathlib.Path, help="input SVG (must embed its font; see --font-file)")
ap.add_argument("--center", action="store_true", help="centre the artwork's bounding box")
ap.add_argument("--dx", type=float, default=0, help="shift right, in dp (viewport is 108)")
ap.add_argument("--dy", type=float, default=0, help="shift down, in dp")
ap.add_argument("--preview", type=pathlib.Path, help="also write an SVG contact sheet of the icon")
ap.add_argument("--out", type=pathlib.Path, default=ROOT / "android/app/src/main/res/drawable",
                help="output directory (default: the app's drawable/)")
args = ap.parse_args()
SRC, OUT = args.svg, args.out
VIEWPORT_SCALE = 108 / 1080   # SVG canvas (1080) -> adaptive-icon viewport (108)

svg = SRC.read_text()
font = TTFont(io.BytesIO(base64.b64decode(re.search(r"base64,([A-Za-z0-9+/=]+)", svg).group(1))))
glyphs = font.getGlyphSet()
glyph = glyphs[font.getBestCmap()[ord("A")]]


def local(el):
    return el.tag.rsplit("}", 1)[-1]


root = ET.fromstring(svg)
text = next(el for el in root.iter() if local(el) == "text")
size = float(text.get("font-size"))
x0, y0 = float(text.get("x")), float(text.get("y"))
fs = size / font["head"].unitsPerEm

HEAD = ('<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n    android:height="108dp"\n'
        '    android:viewportWidth="108"\n    android:viewportHeight="108">\n')


def layers():
    """Yield (transform attr, fill, opacity) for each drawn copy of the A, in paint order.

    A copy is either a <use href="#..."> of the <text> (gen-extruded-a.py, gen-plain-a.py)
    or a <g> that directly contains the <text> (gen-vanishing-a.py).
    """
    for el in root.iter():
        if local(el) == "use" or (local(el) == "g" and any(local(c) == "text" for c in el)):
            yield el.get("transform", ""), el.get("fill", "black"), float(el.get("opacity", 1))


def placement(transform):
    """Glyph-space -> viewport Transform for an SVG transform of translate()/scale() terms."""
    t = Transform().scale(VIEWPORT_SCALE)
    for op, args_ in re.findall(r"(\w+)\(([^)]*)\)", transform):
        a = [float(v) for v in re.split(r"[\s,]+", args_.strip())]
        if op == "translate":
            t = t.translate(a[0], a[1] if len(a) > 1 else 0)
        elif op == "scale":
            t = t.scale(a[0], a[1] if len(a) > 1 else a[0])
        else:
            sys.exit(f"unsupported transform {op}() in {SRC}")
    return t.translate(x0, y0).scale(fs, -fs)


copies = [(placement(tr), fill, op) for tr, fill, op in layers()
          if not re.search(r"scale\(0(\.0+)?\)", tr)]  # drop copies collapsed onto the VP

shift = Transform().translate(args.dx, args.dy)
if args.center:
    bp = BoundsPen(glyphs)
    for t, _, _ in copies:
        glyph.draw(TransformPen(bp, t))
    x1, y1, x2, y2 = bp.bounds
    shift = Transform().translate(54 - (x1 + x2) / 2 + args.dx, 54 - (y1 + y2) / 2 + args.dy)


def outline(t):
    pen = SVGPathPen(glyphs, ntos=lambda v: ("%.2f" % v).rstrip("0").rstrip("."))
    glyph.draw(TransformPen(TransformPen(pen, shift), t))
    return pen.getCommands()


fg, mono, paths = [HEAD], [HEAD], []
for t, fill, opacity in copies:
    d = outline(t)
    fill = "#000000" if fill == "black" else fill.upper()
    alpha = f"{opacity:.3f}"
    paths.append((d, fill, alpha))
    fg.append(f'    <path android:fillColor="{fill}" android:fillAlpha="{alpha}" android:pathData="{d}" />\n')
    mono.append(f'    <path android:fillColor="#000000" android:fillAlpha="{alpha}" android:pathData="{d}" />\n')
(OUT / "ic_launcher_foreground.xml").write_text("".join(fg) + "</vector>\n")
(OUT / "ic_launcher_monochrome.xml").write_text("".join(mono) + "</vector>\n")
print(f"wrote {len(fg) - 1} paths to {OUT}")


if args.preview:
    bg = re.search(r'name="ic_launcher_background">(#\w+)<', (ROOT / "android/app/src/main/res/values/colors.xml").read_text())
    bg = bg.group(1) if bg else "#FFFFFF"
    tiles = [("circle", '<circle cx="54" cy="54" r="54"/>', bg, False),
             ("rounded", '<rect width="108" height="108" rx="24"/>', bg, False),
             ("unmasked", '<rect width="108" height="108"/>', bg, True),
             ("themed", '<circle cx="54" cy="54" r="54"/>', "#2b3a55", False)]
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {len(tiles) * 118 + 10} 128" '
           f'width="{(len(tiles) * 118 + 10) * 4}" height="512">\n<rect width="100%" height="100%" fill="#8a8a8a"/>\n']
    for i, (name, clip, fill_bg, guide) in enumerate(tiles):
        out.append(f'<g transform="translate({10 + i * 118} 10)"><clipPath id="c{i}">{clip}</clipPath>'
                   f'<g clip-path="url(#c{i})"><rect width="108" height="108" fill="{fill_bg}"/>')
        for d, fill, alpha in paths:
            f = ("#d8e2ff" if name == "themed" else fill)
            out.append(f'<path d="{d}" fill="{f}" fill-opacity="{alpha}"/>')
        out.append('</g>')
        if guide:  # 66dp safe zone: launchers may crop outside it
            out.append('<circle cx="54" cy="54" r="33" fill="none" stroke="red" stroke-width=".4"/>')
        out.append('</g>')
    args.preview.write_text("\n".join(out) + "\n</svg>\n")
    print(f"wrote preview {args.preview}")
