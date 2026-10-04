#!/usr/bin/env python3
"""Generate an SVG of a black capital A repeated and fading
towards a vanishing point ("point de fuite") to the right and slightly up.

Usage: tools/gen-vanishing-a.py [out.svg] [--copies N] [--ratio R] [--fade F]
                                [--colors MODE] [--font NAME]
                                [--font-file PATH] [--guide]

Copy k is the base A scaled by ratio**k about the vanishing point, so copies shrink and
bunch up with perspective-correct spacing; opacity is fade**k. Far copies are drawn first
so nearer ones overlap them. Text is emitted as <text>, so the font must be installed
wherever the SVG is rendered.

The front A is always black. --colors picks how the copies behind it are filled: each
mode sweeps hue linearly from the first copy to the last at a fixed saturation and
lightness (see MODES), so the colour drifts as the A recedes.
"""
import argparse
import base64
import colorsys
import pathlib

# Square canvas for an Android adaptive icon (108dp -> 1080). Launcher masks can crop
# everything outside the central 66dp circle (diameter 660, radius 330), so the whole
# composition is kept inside it; --guide draws that circle to check.
W, H = 1080, 1080
FONT_SIZE = 720
FONT = "UnifrakturMaguntia"   # default family; override with --font (OFL, on Google Fonts)
BASE = (270, 750)           # baseline-left of the front (black) A
VP = (800, 450)             # vanishing point: close to the A, to its right and a little up


# mode -> (hue_start, hue_end in degrees, saturation, lightness); None = all black.
# Hue may exceed 360 to sweep across the red wrap-around (sunset).
MODES = {
    "black": None,
    "rainbow": (0, 300, 0.85, 0.50),
    "pastel": (0, 300, 0.70, 0.80),
    "neon": (120, 330, 1.00, 0.55),
    "sunset": (350, 410, 0.90, 0.55),
    "ocean": (170, 270, 0.75, 0.45),
    "mono": (215, 215, 0.60, 0.50),
}


def colour(mode, k, copies):
    """Fill for copy k (0 = front, always black)."""
    if k == 0 or MODES[mode] is None:
        return "black"
    h0, h1, sat, light = MODES[mode]
    t = (k - 1) / max(copies - 1, 1)
    r, g, b = colorsys.hls_to_rgb(((h0 + (h1 - h0) * t) % 360) / 360, light, sat)
    return f"#{round(r * 255):02x}{round(g * 255):02x}{round(b * 255):02x}"


def font_face(font, path):
    """@font-face rule embedding the font file as a data: URI, so the SVG is
    self-contained and the font needn't be installed."""
    mime = {".ttf": "font/ttf", ".otf": "font/otf", ".woff": "font/woff", ".woff2": "font/woff2"}
    data = base64.b64encode(pathlib.Path(path).read_bytes()).decode()
    mt = mime[pathlib.Path(path).suffix.lower()]
    return (f"  <style>@font-face {{ font-family: '{font}'; "
            f"src: url(data:{mt};base64,{data}); }}</style>\n")


def build(copies, ratio, fade, mode="black", font=FONT, font_file=None, guide=False):
    layers = []
    for k in range(copies, -1, -1):  # far -> near
        s = ratio ** k
        layers.append(
            f'  <g transform="translate({VP[0]} {VP[1]}) scale({s:.5f}) '
            f'translate({-VP[0]} {-VP[1]})" '
            f'fill="{colour(mode, k, copies)}" opacity="{fade ** k:.4f}">\n'
            f'    <text x="{BASE[0]}" y="{BASE[1]}" font-size="{FONT_SIZE}">A</text>\n'
            f'  </g>'
        )
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}">\n'
        + (font_face(font, font_file) if font_file else "")
        + f'  <rect width="100%" height="100%" fill="white"/>\n'
        f'  <g fill="black" font-family="\'{font}\', serif">\n'
        + "\n".join(layers)
        + "\n  </g>\n"
        + (f'  <circle cx="{W/2}" cy="{H/2}" r="330" fill="none" stroke="red"/>\n' if guide else "")
        + "</svg>\n"
    )


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("out", nargs="?", default="vanishing-a.svg")
    p.add_argument("--copies", type=int, default=15)
    p.add_argument("--ratio", type=float, default=0.78, help="scale step per copy")
    p.add_argument("--fade", type=float, default=0.75, help="opacity step per copy")
    p.add_argument("--colors", choices=MODES, default="black", help="fill mode for the copies")
    p.add_argument("--font", default=FONT, help="font-family name (must be installed to render)")
    p.add_argument("--font-file", help="TTF/OTF/WOFF file to embed in the SVG (no install needed)")
    p.add_argument("--guide", action="store_true", help="draw the 66dp safe-zone circle")
    a = p.parse_args()
    with open(a.out, "w") as f:
        f.write(build(a.copies, a.ratio, a.fade, a.colors, a.font, a.font_file, a.guide))
    print(f"wrote {a.out}")
