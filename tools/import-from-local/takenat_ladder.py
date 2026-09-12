"""The `takenAt`/`tzOffsetMin` resolution ladders from design.md's "Establishing
`takenAt`" and "Resolving the UTC offset at ingest", run client-side -- mirrors
android/app/.../domain/Timestamps.kt, with one addition: a `filename` rung between
`exif` and `file-mtime` (see src/core/items.ts's `TakenAtSrc` and this change's
own commit message for why file-mtime is useless for a bulk import and a filename
timestamp usually isn't).

Pure functions throughout -- every input, including the clock, is a parameter --
so this is testable with plain fixtures and no real file on disk.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

MIN_TAKEN_AT = datetime(1990, 1, 1, tzinfo=timezone.utc)

_EXIF_DATETIME_RE = re.compile(r"^(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})$")
_OFFSET_RE = re.compile(r"^([+-])(\d{2}):(\d{2})$")


@dataclass(frozen=True)
class ResolvedTimestamp:
    taken_at: datetime  # timezone-aware, UTC
    taken_at_src: str  # "exif" | "filename" | "file-mtime"
    tz_offset_min: int
    tz_src: str


@dataclass(frozen=True)
class UploadOffsetHint:
    tz_offset_min: int
    force: bool  # True: offsetMode=force. False: offsetMode=fallback.


def parse_exif_naive_local(raw: str | None) -> datetime | None:
    if not raw:
        return None
    m = _EXIF_DATETIME_RE.match(raw.strip())
    if not m:
        return None
    y, mo, d, h, mi, s = (int(g) for g in m.groups())
    try:
        return datetime(y, mo, d, h, mi, s)
    except ValueError:
        return None


def parse_exif_offset_minutes(raw: str | None) -> int | None:
    if not raw:
        return None
    if raw == "Z":
        return 0
    m = _OFFSET_RE.match(raw)
    if not m:
        return None
    sign, hh, mm = m.groups()
    minutes = int(hh) * 60 + int(mm)
    return -minutes if sign == "-" else minutes


def _is_plausible(instant: datetime, now: datetime) -> bool:
    return MIN_TAKEN_AT <= instant <= now


def _gps_delta_minutes(naive_local: datetime, gps_utc: datetime) -> int:
    """design.md "GPS delta": the naive-local value read as if it were UTC, minus
    the real UTC GPS instant, recovers the local zone's offset -- rounded to the
    nearest 15 minutes."""
    naive_as_utc = naive_local.replace(tzinfo=timezone.utc)
    raw_minutes = (naive_as_utc - gps_utc).total_seconds() / 60
    return round(raw_minutes / 15) * 15


@dataclass(frozen=True)
class _Offset:
    tz_offset_min: int
    tz_src: str


def _resolve_offset(
    naive_local: datetime | None,
    exif_offset_raw: str | None,
    gps_utc: datetime | None,
    reference_instant: datetime,
    upload_offset: UploadOffsetHint | None,
    device_default_offset_min: int | None,
    home_tz: str | None,
) -> _Offset:
    if upload_offset is not None and upload_offset.force:
        return _Offset(upload_offset.tz_offset_min, "upload-forced")
    if naive_local is not None:
        exif_offset = parse_exif_offset_minutes(exif_offset_raw)
        if exif_offset is not None:
            return _Offset(exif_offset, "exif-offset")
        if gps_utc is not None:
            return _Offset(_gps_delta_minutes(naive_local, gps_utc), "gps")
    if upload_offset is not None:
        return _Offset(upload_offset.tz_offset_min, "upload")
    if device_default_offset_min is not None:
        return _Offset(device_default_offset_min, "device")
    if home_tz:
        offset = ZoneInfo(home_tz).utcoffset(reference_instant.replace(tzinfo=None))
        if offset is not None:
            return _Offset(int(offset.total_seconds() // 60), "owner-default")
    return _Offset(0, "assumed-utc")


def resolve(
    *,
    exif_date_time_original: str | None = None,
    exif_offset_time_original: str | None = None,
    exif_gps_utc: datetime | None = None,
    filename_local: datetime | None = None,
    file_mtime: datetime | None = None,
    upload_offset: UploadOffsetHint | None = None,
    device_default_offset_min: int | None = None,
    home_tz: str | None = None,
    now: datetime | None = None,
) -> ResolvedTimestamp | None:
    """None only when every rung -- exif, filename, and a plausible file mtime --
    comes up empty. Callers should fall back to `now`/"upload"/`assumed-utc` in
    that case, the same convention Android's own UploadRepository uses when its
    own (narrower) `Timestamps.resolve` returns null."""
    now = now or datetime.now(timezone.utc)

    naive_local = parse_exif_naive_local(exif_date_time_original)
    if naive_local is not None:
        reference = naive_local.replace(tzinfo=timezone.utc)
        offset = _resolve_offset(
            naive_local, exif_offset_time_original, exif_gps_utc, reference,
            upload_offset, device_default_offset_min, home_tz,
        )
        taken_at = naive_local.replace(tzinfo=timezone(timedelta(minutes=offset.tz_offset_min))).astimezone(timezone.utc)
        if _is_plausible(taken_at, now):
            return ResolvedTimestamp(taken_at, "exif", offset.tz_offset_min, offset.tz_src)
        # Implausible EXIF timestamp (garbled camera clock): fall through as if
        # there had been no DateTimeOriginal at all, same as Android's Timestamps.

    if filename_local is not None:
        reference = filename_local.replace(tzinfo=timezone.utc)
        # No exif_offset/gps for a filename-derived naive-local time by
        # definition (if EXIF had a usable DateTimeOriginal we wouldn't be here),
        # but design.md's generalised GPS-delta rung still applies when the file
        # carries GPS tags without a DateTimeOriginal to pair them with -- see
        # this change's own commit message (49 real files in this tool's corpus).
        offset = _resolve_offset(
            filename_local, None, exif_gps_utc, reference,
            upload_offset, device_default_offset_min, home_tz,
        )
        taken_at = filename_local.replace(tzinfo=timezone(timedelta(minutes=offset.tz_offset_min))).astimezone(timezone.utc)
        if _is_plausible(taken_at, now):
            return ResolvedTimestamp(taken_at, "filename", offset.tz_offset_min, offset.tz_src)

    if file_mtime is not None and _is_plausible(file_mtime, now):
        offset = _resolve_offset(None, None, None, file_mtime, upload_offset, device_default_offset_min, home_tz)
        return ResolvedTimestamp(file_mtime, "file-mtime", offset.tz_offset_min, offset.tz_src)

    return None
