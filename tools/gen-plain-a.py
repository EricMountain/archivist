#!/usr/bin/env python3
"""Generate an SVG of a capital A "extruded" towards a vanishing point: a solid block of
letter rather than the separate, fading copies of gen-vanishing-a.py.

Usage: tools/gen-extruded-a.py [out.svg] [--depth D] [--steps N] [--near COLOR]
                               [--far COLOR] [--font NAME] [--font-file PATH] [--guide]

The extrusion is the A swept from full size down to `depth` times its size about the
vanishing point, drawn as `steps` overlapping copies, back to front. Each copy is a
tiny shift from its neighbour, so together they fill the swept volume; the colour runs
from --near (next to the front face) to --far (the back), which gives the sides their
shading. The front face is black. Canvas, font, A position and vanishing point are
shared with gen-vanishing-a.py. Like it, the A is <text>, so the font must be installed
or embedded with --font-file.
"""
import argparse
import importlib.util
import pathlib

_spec = importlib.util.spec_from_file_location(
    "gen_vanishing_a", pathlib.Path(__file__).with_name("gen-vanishing-a.py"))
base = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(base)


# def hex_rgb(c):
#     c = c.lstrip("#")
#     return tuple(int(c[i:i + 2], 16) for i in (0, 2, 4))


# def lerp_colour(a, b, t):
#     return "#" + "".join(f"{round(x + (y - x) * t):02x}" for x, y in zip(hex_rgb(a), hex_rgb(b)))


def build(font=base.FONT, font_file=None, guide=False):
    # vx, vy = base.VP
    # uses = []
    # for k in range(steps, 0, -1):  # back -> front; k=0 is the front face, added last
    #     s = 1 - (1 - depth) * k / steps
    #     colour = lerp_colour(near, far, k / steps)
    #     uses.append(
    #         f'    <use href="#a" fill="{colour}" transform="translate({vx} {vy}) '
    #         f'scale({s:.5f}) translate({-vx} {-vy})"/>'
    #     )
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {base.W} {base.H}" '
        f'width="{base.W}" height="{base.H}">\n'
        + (base.font_face(font, font_file) if font_file else "")
        + f'  <defs><text id="a" x="{base.BASE[0]}" y="{base.BASE[1]}" '
        f'font-size="{base.FONT_SIZE}" font-family="\'{font}\', serif">A</text></defs>\n'
        f'  <rect width="100%" height="100%" fill="white"/>\n'
        + '  <use href="#a" fill="black"/>\n'
        + (f'  <circle cx="{base.W/2}" cy="{base.H/2}" r="330" fill="none" stroke="red"/>\n'
           if guide else "")
        + "</svg>\n"
    )


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("out", nargs="?", default="extruded-a.svg")
    p.add_argument("--font", default=base.FONT, help="font-family name")
    p.add_argument("--font-file", help="TTF/OTF/WOFF file to embed in the SVG")
    p.add_argument("--guide", action="store_true", help="draw the 66dp safe-zone circle")
    a = p.parse_args()
    with open(a.out, "w") as f:
        f.write(build(a.font, a.font_file, a.guide))
    print(f"wrote {a.out}")
