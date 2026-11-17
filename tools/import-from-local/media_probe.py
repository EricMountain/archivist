"""Per-file classification: extension -> role/mime (mirroring src/core/paths.ts,
since the server derives role from extension the same way and this tool wants its
own preview/logging to agree with what the server will actually decide), EXIF
extraction for images (mirroring android's domain/ExifExtractor.kt/ExifBlob), video
probing via mp4box.py, and detection of Google Photos' "-edited"-style sibling
files so they can be uploaded as an explicit second rendition of the same asset
via `groupWith` rather than landing as their own, disconnected timeline entries.
"""

from __future__ import annotations

import math
import os
import re
from dataclasses import dataclass, field
from datetime import datetime, timezone

import pillow_heif
from PIL import Image

import mp4box

# HEIC/HEIF: Pillow has no decoder of its own; pillow-heif plugs one into
# `Image.open`/`Image.save` for every module in this tool (thumbnails.py imports
# this module for exactly this). It applies the container's own rotation/mirror
# (`irot`/`imir`) on decode and resets the EXIF Orientation it reports to match,
# so `ImageOps.exif_transpose` never rotates a HEIC twice.
pillow_heif.register_heif_opener()

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
    """A format Pillow can actually decode (HEIC/HEIF via pillow-heif, registered
    above) -- excludes RAW (Pillow has no CR2/CR3/
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
_GPS_ALTITUDE_REF, _GPS_ALTITUDE = 5, 6
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
    # Decimal degrees (WGS84) and metres; the ExifBlob's gpsLat/gpsLon/gpsAltM.
    gps_lat: float | None = None
    gps_lon: float | None = None
    gps_alt_m: float | None = None


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
        coords = _gps_lat_lon(gps)
        if coords is not None:
            data.gps_lat, data.gps_lon = coords
            data.gps_alt_m = _gps_altitude(gps)
    return data


def _gps_lat_lon(gps) -> tuple[float, float] | None:
    """Same rule as androidx `ExifInterface.getLatLong()`, which the app uses: all four
    of latitude, its ref, longitude and its ref must be present, the ref must be N/S
    (E/W), and the value is degrees + minutes/60 + seconds/3600, negated for S (W).
    Anything unparseable, out of range, or exactly (0, 0) gives None (see
    `mp4box.plausible_coordinates`)."""
    try:
        lat = _dms(gps.get(_GPS_LATITUDE), gps.get(_GPS_LATITUDE_REF), "N", "S")
        lon = _dms(gps.get(_GPS_LONGITUDE), gps.get(_GPS_LONGITUDE_REF), "E", "W")
    except (TypeError, ValueError, ZeroDivisionError):
        return None
    if lat is None or lon is None:
        return None
    if not mp4box.plausible_coordinates(lat, lon):
        return None
    return lat, lon


def _dms(value, ref, positive: str, negative: str) -> float | None:
    if value is None or ref is None:
        return None
    ref = str(ref).strip().upper()
    if ref not in (positive, negative):
        return None
    d, m, s = (float(v) for v in value)
    degrees = d + m / 60.0 + s / 3600.0
    return -degrees if ref == negative else degrees


def _gps_altitude(gps) -> float | None:
    """`ExifInterface.getAltitude()`'s rule: both GPSAltitude and GPSAltitudeRef must be
    present; ref 1 means below sea level."""
    alt, ref = gps.get(_GPS_ALTITUDE), gps.get(_GPS_ALTITUDE_REF)
    if alt is None or ref is None:
        return None
    try:
        value = float(alt)
        ref_int = ref[0] if isinstance(ref, (bytes, bytearray)) else int(ref)
    except (TypeError, ValueError, ZeroDivisionError, IndexError):
        return None
    if not math.isfinite(value) or value < 0:
        return None
    return -value if ref_int == 1 else value


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


HEIF_STRIP_QUALITY = 90


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
    dropped GPS IFD itself accounted for.

    HEIC/HEIF has no such shortcut: pillow-heif re-encodes the primary image (at a
    high quality, rather than libheif's much lower default) and keeps its EXIF,
    but not auxiliary images such as depth maps or an HDR gain map. A real cost,
    paid only when `stripLocationOnUpload` is on and the file actually has GPS."""
    with Image.open(src_path) as im:
        exif = im.getexif()
        if _GPS_IFD_TAG in exif:
            del exif[_GPS_IFD_TAG]
        save_kwargs = {"exif": exif}
        if (im.format or "").upper() == "JPEG":
            save_kwargs["quality"] = "keep"
        elif (im.format or "").upper() == "HEIF":
            save_kwargs["quality"] = HEIF_STRIP_QUALITY
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


def coordinates_readable(ext: str) -> bool:
    """Whether `read_coordinates` can read this kind of file at all -- a video, or an
    image Pillow decodes (HEIC/HEIF included, via pillow-heif). RAW is not."""
    return is_video(ext) or is_image(ext)


def read_coordinates(path: str, ext: str) -> tuple[float, float, float | None] | None:
    """(lat, lon, alt_m or None) from a file's own metadata, by the importer's rules:
    EXIF for stills, the container's location for video; (0, 0) and out-of-range
    values are none. A JPEG truncated after its EXIF segment still works: only the
    header is parsed."""
    if is_video(ext):
        return probe_video_location(path)
    exif = extract_exif(path)
    if exif.gps_lat is None or exif.gps_lon is None:
        return None
    return exif.gps_lat, exif.gps_lon, exif.gps_alt_m


def probe_video_location(path: str) -> tuple[float, float, float | None] | None:
    """(lat, lon, alt_m or None) from the container's location metadata, or None.
    Call it on the file actually being uploaded (the stripped copy when
    `stripLocationOnUpload` is on), so a stripped video has no location by
    construction."""
    return mp4box.probe_location(path)
