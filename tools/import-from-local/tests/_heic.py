"""A tiny real HEIC, generated in-test with pillow-heif (no committed binary, so no
third-party image rights): 64x32 stored, left half red and right half blue, EXIF
Orientation 6 (rotate 90 CW to display, so it displays 32x64, red on top), camera
make/model, DateTimeOriginal + offset, and GPS for Kyoto with altitude.

pillow-heif turns Orientation 6 into the container's own `irot` transform on save
and applies it on open (reporting Orientation 1 afterwards), the same shape a phone's
HEIC has; see media_probe.py's registration comment.
"""

from __future__ import annotations

import io

from PIL import Image

import media_probe  # noqa: F401 -- registers the HEIC/HEIF opener with Pillow

# Kyoto, 35°0'41.8" N 135°46'5.0" E, 12.5 m.
KYOTO_GPS = {1: "N", 2: (35.0, 0.0, 41.796), 3: "E", 4: (135.0, 46.0, 4.944), 5: 0, 6: 12.5}

RED, BLUE = (220, 20, 20), (20, 20, 220)


def heic_bytes(gps: dict | None = None, orientation: int = 6) -> bytes:
    im = Image.new("RGB", (64, 32), BLUE)
    im.paste(RED, (0, 0, 32, 32))
    exif = Image.Exif()
    exif[271] = "Apple"
    exif[272] = "iPhone 15"
    exif[0x0112] = orientation
    ifd = exif.get_ifd(0x8769)
    ifd[36867] = "2024:06:15 14:32:00"
    ifd[36881] = "+09:00"
    if gps:
        exif.get_ifd(0x8825).update(gps)
    buf = io.BytesIO()
    im.save(buf, format="HEIF", exif=exif.tobytes(), quality=90)
    return buf.getvalue()


def write_heic(path: str, gps: dict | None = None, orientation: int = 6) -> None:
    with open(path, "wb") as f:
        f.write(heic_bytes(gps, orientation))
