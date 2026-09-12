"""Per-file classification: extension -> role/mime (mirroring src/core/paths.ts,
since the server derives role from extension the same way and this tool wants its
own preview/logging to agree with what the server will actually decide), EXIF
extraction for images (mirroring android's domain/ExifExtractor.kt/ExifBlob), video
probing via mp4box.py, and detection of Google Photos' "-edited"-style sibling
files so they can be uploaded as an explicit second rendition of the same asset
via `groupWith` rather than landing as their own, disconnected timeline entries.
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass, field
from datetime import datetime, timezone

from PIL import Image

import mp4box

# --- Extension classification, mirroring src/core/paths.ts ----------------------
# Kept in sync by hand -- these are small, closed, rarely-changing sets on the
# server side too (see design.md's "Rendition grouping").

RAW_EXTS = {"cr2", "cr3", "nef", "arw", "dng", "raf", "orf", "rw2"}
VIDEO_EXTS = {"mov", "mp4", "m4v"}
SIDECAR_EXTS = {"xmp"}

# Mirrors android/app/.../domain/ExifExtractor.kt's `extensionMime` table.
_EXTENSION_MIME = {
    "jpg": "image/jpeg",
    "jpeg": "image/jpeg",
    "png": "image/png",
    "webp": "image/webp",
    "heic": "image/heic",
    "heif": "image/heif",
    "gif": "image/gif",
    "mp4": "video/mp4",
    "mov": "video/quicktime",
    "m4v": "video/x-m4v",
    "dng": "image/x-adobe-dng",
    "cr2": "image/x-canon-cr2",
    "cr3": "image/x-canon-cr3",
    "arw": "image/x-sony-arw",
    "nef": "image/x-nikon-nef",
    "orf": "image/x-olympus-orf",
    "raf": "image/x-fuji-raf",
    "rw2": "image/x-panasonic-rw2",
    "xmp": "application/rdf+xml",
}


def mime_for_ext(ext: str) -> str:
    return _EXTENSION_MIME.get(ext.lower(), "application/octet-stream")


def is_video(ext: str) -> bool:
    return ext.lower() in VIDEO_EXTS


def is_raw(ext: str) -> bool:
    return ext.lower() in RAW_EXTS


def is_image(ext: str) -> bool:
    """A format Pillow can actually decode -- excludes RAW (Pillow has no CR2/CR3/
    NEF/ARW/DNG/RAF/ORF/RW2 decoder; `Image.open` on one of these raises, same gap
    design.md already names for the Android client: "the phone will not handle
    RAW"). A RAW file still uploads correctly as its own rendition -- see
    `importer.py`'s `_upload_one` -- just with no EXIF read and no thumbnails."""
    e = ext.lower()
    return e not in VIDEO_EXTS and e not in SIDECAR_EXTS and e not in RAW_EXTS


# --- EXIF (images) ----------------------------------------------------------------

_TAG_DATETIME_ORIGINAL = 36867
_TAG_OFFSET_TIME_ORIGINAL = 36881
_TAG_MAKE = 271
_TAG_MODEL = 272
_TAG_LENS_MODEL = 42036
_TAG_BODY_SERIAL = 42033
_EXIF_IFD_TAG = 0x8769
_GPS_IFD_TAG = 0x8825
_GPS_LATITUDE_REF, _GPS_LATITUDE = 1, 2
_GPS_LONGITUDE_REF, _GPS_LONGITUDE = 3, 4
_GPS_DATE_STAMP = 29
_GPS_TIME_STAMP = 7


@dataclass
class ExifData:
    width: int | None = None
    height: int | None = None
    camera_make: str | None = None
    camera_model: str | None = None
    camera_serial: str | None = None
    lens: str | None = None
    date_time_original: str | None = None  # raw "yyyy:MM:dd HH:mm:ss", naive local
    offset_time_original: str | None = None  # raw "+02:00" etc.
    gps_date_time_utc: datetime | None = None
    has_gps: bool = False


def _clean(v) -> str | None:
    if v is None:
        return None
    s = str(v).strip()
    return s or None


def extract_exif(path: str) -> ExifData:
    with Image.open(path) as im:
        width, height = im.size
        exif = im.getexif()
        ifd = exif.get_ifd(_EXIF_IFD_TAG) if exif else {}
        gps = exif.get_ifd(_GPS_IFD_TAG) if exif else {}

    data = ExifData(width=width, height=height)
    data.camera_make = _clean(exif.get(_TAG_MAKE))
    data.camera_model = _clean(exif.get(_TAG_MODEL))
    data.camera_serial = _clean(ifd.get(_TAG_BODY_SERIAL))
    data.lens = _clean(ifd.get(_TAG_LENS_MODEL))
    data.date_time_original = _clean(ifd.get(_TAG_DATETIME_ORIGINAL))
    data.offset_time_original = _clean(ifd.get(_TAG_OFFSET_TIME_ORIGINAL))

    if gps:
        data.has_gps = bool(gps.get(_GPS_LATITUDE) and gps.get(_GPS_LONGITUDE))
        date_stamp = gps.get(_GPS_DATE_STAMP)
        time_stamp = gps.get(_GPS_TIME_STAMP)
        if date_stamp and time_stamp:
            data.gps_date_time_utc = _combine_gps(date_stamp, time_stamp)
    return data


def _combine_gps(date_stamp: str, time_stamp) -> datetime | None:
    """`GPSDateStamp` is "YYYY:MM:DD"; `GPSTimeStamp` is three RATIONALs (h, m, s),
    exactly as crypto-format.md's Android counterpart notes -- not the "HH:MM:SS"
    string its name suggests."""
    try:
        year, month, day = (int(p) for p in date_stamp.strip().split(":"))
        h, m, s = (float(r) for r in time_stamp)
        return datetime(year, month, day, tzinfo=timezone.utc) + _hms_delta(h, m, s)
    except (ValueError, TypeError):
        return None


def _hms_delta(h: float, m: float, s: float):
    from datetime import timedelta

    return timedelta(hours=h, minutes=m, seconds=s)


def strip_gps_from_image(src_path: str, dst_path: str) -> None:
    """design.md "Stripping location on upload", images: a copy with the GPS IFD
    removed, decoded and re-saved rather than a byte-level patch -- unlike video's
    box-shaped location metadata, EXIF's GPS IFD isn't a fixed-size, offset-stable
    region a patch can zero in place without walking the whole TIFF structure, and
    Pillow already gives us a clean "drop this IFD, re-encode" primitive.

    `quality="keep"` for JPEG specifically: without it, `Image.save` fully
    decodes and re-compresses the pixel data (real generation loss on top of
    whatever compression the source already carries), just to change a metadata
    segment. `quality="keep"` instead asks libjpeg to re-use the source's own
    DCT coefficients unchanged -- confirmed against a real GPS-bearing photo from
    this tool's own corpus: output differs from the input by only the bytes the
    dropped GPS IFD itself accounted for."""
    with Image.open(src_path) as im:
        exif = im.getexif()
        if _GPS_IFD_TAG in exif:
            del exif[_GPS_IFD_TAG]
        save_kwargs = {"exif": exif}
        if (im.format or "").upper() == "JPEG":
            save_kwargs["quality"] = "keep"
        im.save(dst_path, **save_kwargs)


# --- Google Photos "-edited" family detection ------------------------------------
#
# design.md's rendition grouping already covers "two files that are the same
# logical photo" via `groupWith` -- this just recognises the specific naming
# convention Google Takeout uses for an edited copy, so the pair can be handed to
# `groupWith` instead of landing as two disconnected assets (stems differ because
# the suffix is part of the filename, so the server's own stem-based auto-grouping
# never catches this on its own).

_EDIT_SUFFIXES = ("-edited", "-EFFECTS", "-SMILE", "-PANO", "-COLLAGE", "-MIX")
_SUFFIX_PATTERN = re.compile(
    "(" + "|".join(re.escape(s) for s in _EDIT_SUFFIXES) + r")(?:\(\d+\))?$"
)


def strip_one_edit_suffix(stem: str) -> str | None:
    """Removes exactly one trailing suffix (and an optional `(n)` Google Photos
    appends to disambiguate a duplicate export), or None if `stem` doesn't end in
    a recognised one. Suffixes can chain (`...-EFFECTS-edited`), so a caller
    walking toward the ultimate base calls this repeatedly."""
    m = _SUFFIX_PATTERN.search(stem)
    if not m:
        return None
    return stem[: m.start()]


def base_stem_chain(stem: str) -> list[str]:
    """[stem itself, ..., the fully-unwound base], stopping the first time a
    suffix strip no longer applies. A plain file with no edit suffix returns just
    `[stem]`."""
    chain = [stem]
    current = stem
    while True:
        stripped = strip_one_edit_suffix(current)
        if stripped is None:
            return chain
        chain.append(stripped)
        current = stripped


@dataclass
class VideoProbeResult:
    creation_time_utc: datetime | None
    width: int | None
    height: int | None


def probe_video(path: str) -> VideoProbeResult:
    p = mp4box.probe_video(path)
    return VideoProbeResult(p.creation_time, p.width, p.height)
