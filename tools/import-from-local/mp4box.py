"""A narrow ISO-BMFF ("MP4 box") reader/patcher, covering exactly what this
importer needs and nothing else: a video's creation time (`mvhd`, feeding
`takenAtSrc`'s absolute-instant rungs when neither EXIF nor a filename timestamp is
available -- videos carry no EXIF at all), its display dimensions (`tkhd` of the
first video track), and the two location-metadata schemes docs/design/design.md's
"Stripping location on upload" names for video: `moov/udta/loci`, `moov/udta/©xyz`,
and the `moov/meta` `keys`+`ilst` scheme keyed by
`com.apple.quicktime.location.ISO6709`.

Box headers only are read into memory; large payloads (`mdat`, thumbnails baked
into a container, etc.) are skipped by seeking past their declared size, so this
works the same whether `moov` sits before or after a 700 MB `mdat` -- confirmed
against this tool's own real source corpus, where both orderings occur.

Deliberately narrow, matching design.md's own stated scope for the stripping
feature: "This covers the two schemes real-world phone encoders actually use, not
every conceivable one... An encoder that hides location a fourth way passes
through unstripped, silently."
"""

from __future__ import annotations

import shutil
import struct
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

# mvhd/tkhd timestamps are seconds since 1904-01-01 UTC (the classic QuickTime/MP4
# epoch), not Unix epoch.
_MP4_EPOCH = datetime(1904, 1, 1, tzinfo=timezone.utc)
_PLAUSIBLE_RANGE = (datetime(2000, 1, 1, tzinfo=timezone.utc), datetime(2035, 1, 1, tzinfo=timezone.utc))

_CONTAINER_TYPES = {b"moov", b"trak", b"mdia", b"udta"}


@dataclass(frozen=True)
class Box:
    pos: int  # file offset of the box's own header (not its payload)
    header_len: int  # 8 (32-bit size) or 16 (64-bit "largesize")
    box_type: bytes  # 4 bytes; for an `ilst` child this may be a 1-based index, not a fourcc
    size: int  # total box size, header included


def iter_boxes(f, start: int, end: int):
    """Direct children of the byte range [start, end) -- not recursive. Reads only
    each child's header, then seeks past its payload, so a container never needs to
    be read into memory just to walk it."""
    pos = start
    while pos + 8 <= end:
        f.seek(pos)
        header = f.read(8)
        if len(header) < 8:
            return
        size = struct.unpack(">I", header[:4])[0]
        box_type = header[4:8]
        header_len = 8
        if size == 1:
            largesize = f.read(8)
            if len(largesize) < 8:
                return
            size = struct.unpack(">Q", largesize)[0]
            header_len = 16
        elif size == 0:
            size = end - pos  # box extends to the end of its parent (or file)
        if size < header_len:
            return  # malformed -- stop rather than looping or seeking backwards
        yield Box(pos, header_len, box_type, size)
        pos += size


def _find(f, start: int, end: int, box_type: bytes) -> Box | None:
    for box in iter_boxes(f, start, end):
        if box.box_type == box_type:
            return box
    return None


def _payload_range(box: Box) -> tuple[int, int]:
    return box.pos + box.header_len, box.pos + box.size


@dataclass(frozen=True)
class VideoProbe:
    creation_time: datetime | None
    width: int | None
    height: int | None


def probe_video(path: str) -> VideoProbe:
    with open(path, "rb") as f:
        size = _file_size(f)
        moov = _find(f, 0, size, b"moov")
        if moov is None:
            return VideoProbe(None, None, None)
        moov_start, moov_end = _payload_range(moov)

        creation_time = _read_mvhd_creation_time(f, moov_start, moov_end)
        width, height = _read_first_video_track_dims(f, moov_start, moov_end)
        return VideoProbe(creation_time, width, height)


def _file_size(f) -> int:
    pos = f.tell()
    f.seek(0, 2)
    size = f.tell()
    f.seek(pos)
    return size


def _mp4_time_to_datetime(raw: int) -> datetime | None:
    dt = _MP4_EPOCH + timedelta(seconds=raw)
    if not (_PLAUSIBLE_RANGE[0] <= dt <= _PLAUSIBLE_RANGE[1]):
        return None
    return dt


def _read_mvhd_creation_time(f, start: int, end: int) -> datetime | None:
    mvhd = _find(f, start, end, b"mvhd")
    if mvhd is None:
        return None
    payload_start, _ = _payload_range(mvhd)
    f.seek(payload_start)
    version = f.read(1)
    if len(version) < 1:
        return None
    f.seek(payload_start + 4)  # skip version(1) + flags(3)
    if version[0] == 1:
        raw = f.read(8)
        if len(raw) < 8:
            return None
        creation = struct.unpack(">Q", raw)[0]
    else:
        raw = f.read(4)
        if len(raw) < 4:
            return None
        creation = struct.unpack(">I", raw)[0]
    return _mp4_time_to_datetime(creation)


def _read_first_video_track_dims(f, moov_start: int, moov_end: int) -> tuple[int | None, int | None]:
    for trak in iter_boxes(f, moov_start, moov_end):
        if trak.box_type != b"trak":
            continue
        trak_start, trak_end = _payload_range(trak)
        if not _is_video_track(f, trak_start, trak_end):
            continue
        tkhd = _find(f, trak_start, trak_end, b"tkhd")
        if tkhd is None:
            continue
        dims = _read_tkhd_dims(f, tkhd)
        if dims is not None:
            return dims
    return None, None


def _is_video_track(f, trak_start: int, trak_end: int) -> bool:
    mdia = _find(f, trak_start, trak_end, b"mdia")
    if mdia is None:
        return False
    mdia_start, mdia_end = _payload_range(mdia)
    hdlr = _find(f, mdia_start, mdia_end, b"hdlr")
    if hdlr is None:
        return False
    payload_start, payload_end = _payload_range(hdlr)
    f.seek(payload_start + 8)  # version(1)+flags(3)+pre_defined(4)
    handler_type = f.read(4)
    return handler_type == b"vide"


def _read_tkhd_dims(f, tkhd: Box) -> tuple[int, int] | None:
    payload_start, payload_end = _payload_range(tkhd)
    f.seek(payload_start)
    version_byte = f.read(1)
    if len(version_byte) < 1:
        return None
    # version 0: four 4-byte time/track/reserved fields before duration (4 bytes);
    # version 1: those same fields are 8 bytes each except track_id/reserved (4
    # bytes). Width/height sit at a fixed offset from the end of a fixed-size
    # tail (reserved[2] + layer + alternate_group + volume + reserved + matrix[9]
    # + width + height), so it's easiest to locate them from the box's own end.
    box_end = tkhd.pos + tkhd.size
    # width/height are the last 8 bytes of the box, each a 16.16 fixed-point value.
    f.seek(box_end - 8)
    raw = f.read(8)
    if len(raw) < 8:
        return None
    width_fixed, height_fixed = struct.unpack(">II", raw)
    width = width_fixed >> 16
    height = height_fixed >> 16
    if width <= 0 or height <= 0:
        return None
    return width, height


# --- Location stripping (design.md "Stripping location on upload", video) -------


def strip_video_location(src_path: str, dst_path: str) -> bool:
    """Copies `src_path` to `dst_path` byte-for-byte, then in-place zeroes and
    retypes (to `free`) any location box found, per design.md's algorithm: this
    never shifts an offset or changes a box's declared size, so `stco`/`co64`
    sample tables never need recomputing regardless of `moov`/`mdat` order.

    Returns whether anything was actually found and stripped -- a caller should
    still treat `dst_path` as the file to upload either way (it's a safe copy
    either way), but a `False` return is worth logging: an owner with
    `stripLocationOnUpload` on and a video that carries no location data this tool
    recognises gets no protection from this pass, silently, same as design.md's own
    "an encoder that hides location a fourth way passes through unstripped" caveat.
    """
    shutil.copyfile(src_path, dst_path)
    stripped_any = False
    with open(dst_path, "r+b") as f:
        size = _file_size(f)
        moov = _find(f, 0, size, b"moov")
        if moov is None:
            return False
        moov_start, moov_end = _payload_range(moov)

        udta = _find(f, moov_start, moov_end, b"udta")
        if udta is not None:
            udta_start, udta_end = _payload_range(udta)
            for box in list(iter_boxes(f, udta_start, udta_end)):
                if box.box_type in (b"loci", b"\xa9xyz"):
                    _zero_and_retype(f, box)
                    stripped_any = True

        meta = _find(f, moov_start, moov_end, b"meta")
        if meta is not None and _strip_quicktime_meta_location(f, meta):
            stripped_any = True

    return stripped_any


def _zero_and_retype(f, box: Box) -> None:
    payload_start, payload_end = _payload_range(box)
    f.seek(payload_start)
    f.write(b"\x00" * (payload_end - payload_start))
    f.seek(box.pos + (box.header_len - 4))  # the type field is the header's last 4 bytes
    f.write(b"free")


def _strip_quicktime_meta_location(f, meta: Box) -> bool:
    """`moov/meta`'s `keys`+`ilst` scheme. `meta` may or may not carry the 4-byte
    version/flags prefix ISO/IEC 14496-12 gives every FullBox -- real encoders
    disagree (this tool's own real corpus has `moov/meta` boxes that *do* carry it,
    Android's `mdta` handler among them), so both offsets are tried and whichever
    yields a plausible child box (a 4-letter type after four bytes that parse as a
    sane size) wins."""
    payload_start, payload_end = _payload_range(meta)
    children_start = _pick_meta_children_start(f, payload_start, payload_end)
    if children_start is None:
        return False

    keys_box = _find(f, children_start, payload_end, b"keys")
    ilst_box = _find(f, children_start, payload_end, b"ilst")
    if keys_box is None or ilst_box is None:
        return False

    target_index = _find_location_key_index(f, keys_box)
    if target_index is None:
        return False

    ilst_start, ilst_end = _payload_range(ilst_box)
    for i, child in enumerate(iter_boxes(f, ilst_start, ilst_end), start=1):
        if i == target_index:
            _zero_and_retype(f, child)
            return True
    return False


def _pick_meta_children_start(f, payload_start: int, payload_end: int) -> int | None:
    for candidate in (payload_start + 4, payload_start):  # FullBox-prefixed, then bare
        first = next(iter_boxes(f, candidate, payload_end), None)
        if first is not None and first.box_type in (b"hdlr", b"keys", b"ilst"):
            return candidate
    return None


_LOCATION_KEY = b"com.apple.quicktime.location.ISO6709"


def _find_location_key_index(f, keys_box: Box) -> int | None:
    """1-based position of the entry named `_LOCATION_KEY` in a `keys` box, or
    None. `keys` is a FullBox: version+flags(4), entry_count(4), then entries of
    {size(4), namespace(4), name(size-8 bytes)} -- confirmed against this tool's
    own real corpus (an Android `mdta`/`com.android.version` entry, structurally
    identical, just a different namespace and key)."""
    payload_start, payload_end = _payload_range(keys_box)
    f.seek(payload_start + 4)  # version+flags
    count_raw = f.read(4)
    if len(count_raw) < 4:
        return None
    entry_count = struct.unpack(">I", count_raw)[0]
    pos = payload_start + 8
    for index in range(1, entry_count + 1):
        if pos + 8 > payload_end:
            return None
        f.seek(pos)
        header = f.read(8)
        entry_size = struct.unpack(">I", header[:4])[0]
        if entry_size < 8 or pos + entry_size > payload_end:
            return None
        name = f.read(entry_size - 8)
        if name == _LOCATION_KEY:
            return index
        pos += entry_size
    return None
