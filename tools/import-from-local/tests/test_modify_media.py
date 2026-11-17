from __future__ import annotations

import argparse
import base64
import contextlib
import io
import json
import os
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from PIL import Image

import crypto_format as cf
import library_cache
import modify_media as mm
from api_client import ApiError
from _heic import heic_bytes
from modify_media import _encode_image, parse_taken_at_local, parse_taken_at_utc


class ParseTakenAtLocal(unittest.TestCase):
    def test_positive_offset_subtracts_to_reach_utc(self):
        self.assertEqual(parse_taken_at_local("2019-06-15 14:00:00", 120), "2019-06-15T12:00:00.000Z")

    def test_negative_offset_adds_to_reach_utc(self):
        self.assertEqual(parse_taken_at_local("2019-06-15 14:00:00", -300), "2019-06-15T19:00:00.000Z")

    def test_zero_offset_is_already_utc(self):
        self.assertEqual(parse_taken_at_local("2019-06-15 14:00:00", 0), "2019-06-15T14:00:00.000Z")

    def test_accepts_a_t_separator_and_no_seconds(self):
        self.assertEqual(parse_taken_at_local("2019-06-15T14:00", 0), "2019-06-15T14:00:00.000Z")

    def test_rejects_unparseable_input(self):
        with self.assertRaises(ValueError):
            parse_taken_at_local("15 June 2019, 2pm", 0)

    def test_crosses_a_day_boundary(self):
        # 14:00 local at UTC+3 is 11:00 UTC the same day; a large enough offset
        # should still cross into the previous UTC day, not just wrap the hour.
        self.assertEqual(parse_taken_at_local("2019-06-15 01:00:00", 180), "2019-06-14T22:00:00.000Z")


class ParseTakenAtUtc(unittest.TestCase):
    def test_z_suffix(self):
        self.assertEqual(parse_taken_at_utc("2019-06-15T12:00:00.000Z"), "2019-06-15T12:00:00.000Z")

    def test_z_suffix_without_milliseconds(self):
        self.assertEqual(parse_taken_at_utc("2019-06-15T12:00:00Z"), "2019-06-15T12:00:00.000Z")

    def test_naive_input_is_treated_as_utc(self):
        self.assertEqual(parse_taken_at_utc("2019-06-15T12:00:00"), "2019-06-15T12:00:00.000Z")

    def test_explicit_offset_is_converted_to_utc(self):
        self.assertEqual(parse_taken_at_utc("2019-06-15T14:00:00+02:00"), "2019-06-15T12:00:00.000Z")

    def test_rejects_unparseable_input(self):
        with self.assertRaises(ValueError):
            parse_taken_at_utc("not-a-date")


class EncodeImage(unittest.TestCase):
    """The --rotate path's re-encode step -- quality is a lossy-format-only kwarg
    Pillow rejects outright for some formats (PNG), so this has to fall back
    rather than assume every format accepts it."""

    def test_jpeg_round_trips_with_quality(self):
        im = Image.new("RGB", (12, 8), (10, 20, 30))
        data = _encode_image(im, "JPEG")
        with Image.open(io.BytesIO(data)) as decoded:
            self.assertEqual(decoded.format, "JPEG")
            self.assertEqual(decoded.size, (12, 8))

    def test_png_falls_back_when_quality_is_rejected(self):
        im = Image.new("RGB", (12, 8), (10, 20, 30))
        data = _encode_image(im, "PNG")
        with Image.open(io.BytesIO(data)) as decoded:
            self.assertEqual(decoded.format, "PNG")
            self.assertEqual(decoded.size, (12, 8))


class RotateSemantics(unittest.TestCase):
    """--rotate is documented as clockwise; Pillow's Image.rotate() angle is
    counter-clockwise, so run_orientation negates it. Characterises that sign
    convention directly (not by importing run_orientation, which needs a live API)
    so a flipped sign regresses loudly rather than only being noticed by eye on a
    real photo."""

    def test_rotate_90_clockwise_swaps_dimensions_the_documented_direction(self):
        # A wide (100x60) image with a distinct top-left pixel: rotating 90
        # clockwise should move that corner's content to the top-right.
        im = Image.new("RGB", (100, 60), (0, 0, 0))
        im.putpixel((0, 0), (255, 0, 0))
        rotated = im.rotate(-90, expand=True)
        self.assertEqual(rotated.size, (60, 100))
        self.assertEqual(rotated.getpixel((59, 0)), (255, 0, 0))

    def test_rotate_180_does_not_change_dimensions(self):
        im = Image.new("RGB", (100, 60), (0, 0, 0))
        rotated = im.rotate(-180, expand=True)
        self.assertEqual(rotated.size, (100, 60))


# --- location (docs/plans/geolocation.md step 10) ---------------------------------

SRC = "geonames-cities500@2026-11-13"
OLD_SRC = "geonames-cities500@2026-01-01"
KYOTO = {
    "cc": "JP",
    "region": {"id": "JP.22", "name": "Kyoto"},
    "locality": {"id": "1857910", "name": "Kyoto"},
    "src": SRC,
}
KYOTO_GPS = {1: "N", 2: (35.0, 0.0, 41.796), 3: "E", 4: (135.0, 46.0, 4.944), 5: 0, 6: 12.5}
MASTER = bytes(range(32))
HASH_SECRET = bytes(range(32, 64))


def _b64(b: bytes) -> str:
    return base64.b64encode(b).decode("ascii")


def _jpeg(gps: dict | None = None, pad: int = 0, dto: str | None = None, oto: str | None = None) -> bytes:
    im = Image.new("RGB", (32, 24), color=(10, 120, 200))
    exif = im.getexif()
    exif[271] = "Canon"
    if gps:
        exif.get_ifd(0x8825).update(gps)
    if dto:
        exif.get_ifd(0x8769)[36867] = dto  # DateTimeOriginal
    if oto:
        exif.get_ifd(0x8769)[36881] = oto  # OffsetTimeOriginal
    buf = io.BytesIO()
    im.save(buf, format="JPEG", exif=exif)
    return buf.getvalue() + bytes(pad)  # trailing bytes after EOI are ignored by readers


def _box(box_type: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", 8 + len(payload)) + box_type + payload


def _video_xyz(value: str | None, creation: str | None = None, mdat: int = 64) -> bytes:
    """`creation`: the mvhd creation time as an ISO UTC instant (None: 0, i.e. unset)."""
    created = 0
    if creation is not None:
        created = int((mm._parse_utc(creation) - mm.datetime(1904, 1, 1, tzinfo=mm.timezone.utc)).total_seconds())
    udta = b""
    if value is not None:
        raw = value.encode("ascii")
        udta = _box(b"udta", _box(b"\xa9xyz", struct.pack(">HH", len(raw), 0x15C7) + raw))
    mvhd = _box(b"mvhd", bytes(4) + struct.pack(">III", created, created, 1000) + bytes(80))
    # mdat before moov: the "moov at the end" layout that needs the whole file.
    return _box(b"ftyp", b"isom" + bytes(12)) + _box(b"mdat", bytes(mdat)) + _box(b"moov", mvhd + udta)


class FakeGazetteer:
    src = SRC

    def __init__(self):
        self.lookups = []

    def lookup(self, lat, lon):
        self.lookups.append((lat, lon))
        return dict(KYOTO) if abs(lat - 35.0) < 1 and abs(lon - 135.8) < 1 else None


class FakeApi:
    def __init__(self, strip=False):
        self.settings = {"stripLocationOnUpload": strip}
        self.details: dict[str, dict] = {}
        self.media: dict[str, bytes] = {}
        self.deks: dict[str, bytes] = {}
        self.puts: list[tuple] = []
        self.detail_calls: list[str] = []
        self.fail_detail: set[str] = set()
        self.conflict_once: set[str] = set()

    # assets ------------------------------------------------------------------
    def add(self, photo_id, *, place=None, blob=None, original=b"", ext="jpg", chunked=False, trashed=False,
            path=None, taken_at="2025-02-17T06:16:41.000Z", tz_offset_min=0):
        dek = bytes([len(self.details) + 1]) * 32
        self.deks[photo_id] = dek
        rid = "R" + photo_id
        meta = {"photoId": photo_id, "encDek": _b64(cf.wrap_key(MASTER, dek)), "primaryRend": rid,
                "takenAt": taken_at, "tzOffsetMin": tz_offset_min}
        if trashed:
            meta["deletedAt"] = "2026-01-01T00:00:00.000Z"
        if place is not None:
            meta["place"] = place
        if blob is not None:
            enc = cf.encrypt_object(dek, cf.aad(photo_id, cf.object_ref_exif()), json.dumps(blob).encode(), 0)
            meta["exifEnc"], meta["exifIv"] = _b64(enc.ciphertext), _b64(enc.iv)
        enc = cf.encrypt_object(
            dek, cf.aad(photo_id, cf.object_ref_rendition(rid)), original, cf.SEGMENT if chunked else 0
        )
        rend = {
            "renditionId": rid, "path": path or f"dir/{photo_id}.{ext}", "ext": ext, "s3Key": f"raw/{photo_id}",
            "plainBytes": len(original), "bytes": len(enc.ciphertext), "encChunkSize": enc.chunk_size,
            "contentHash": cf.content_hash(HASH_SECRET, original),
        }
        if enc.iv is not None:
            rend["encIv"] = _b64(enc.iv)
        self.media[rend["s3Key"]] = enc.ciphertext
        self.details[photo_id] = {"meta": meta, "renditions": [rend], "facets": []}

    # api surface ---------------------------------------------------------------
    def get_settings(self):
        return self.settings

    def get_photo_detail(self, photo_id):
        self.detail_calls.append(photo_id)
        if photo_id in self.fail_detail:
            raise RuntimeError("boom")
        return json.loads(json.dumps(self.details.get(photo_id))) if photo_id in self.details else None

    def get_photo_by_path(self, path):
        for pid, d in self.details.items():
            if d["renditions"][0]["path"] == path:
                return {"photoId": pid, "renditionId": d["renditions"][0]["renditionId"]}
        return None

    def _page(self, trashed):
        items = [
            {"photoId": pid, **({"place": d["meta"]["place"]} if "place" in d["meta"] else {})}
            for pid, d in sorted(self.details.items())
            if bool(d["meta"].get("deletedAt")) == trashed
        ]
        return {"items": items}

    def get_photos_page(self, cursor=None, limit=200):
        return self._page(False)

    def get_trash_page(self, cursor=None, limit=200):
        return self._page(True)

    def put_photo_location(self, photo_id, place, exif_enc=None, exif_iv=None):
        if photo_id in self.conflict_once:
            self.conflict_once.discard(photo_id)
            raise ApiError(409, "changed")
        self.puts.append((photo_id, place, exif_enc, exif_iv))
        meta = self.details[photo_id]["meta"]
        if place is None:
            meta.pop("place", None)
        else:
            meta["place"] = place
        if exif_enc is not None:
            meta["exifEnc"], meta["exifIv"] = exif_enc, exif_iv
        return {"place": place}


class FakeDownloads:
    def __init__(self, api: FakeApi):
        self.api = api
        self.calls: list[tuple[str, tuple | None]] = []
        self.archived: set[str] = set()  # s3Keys S3 refuses with 403 InvalidObjectState

    def __call__(self, s3_key, dest, byte_range):
        self.calls.append((s3_key, byte_range))
        if s3_key in self.archived:
            raise ApiError(403, "<Error><Code>InvalidObjectState</Code><Message>The operation is not valid "
                                "for the object's access tier</Message></Error>")
        data = self.api.media[s3_key]
        if byte_range is not None:
            data = data[byte_range[0]:byte_range[1] + 1]
        with open(dest, "wb") as f:
            f.write(data)
        return len(data)


class _Enrolled:
    master_key = MASTER
    hash_secret = HASH_SECRET


class LocationTestBase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self._orig_cache_dir = library_cache.CACHE_DIR
        library_cache.CACHE_DIR = os.path.join(self._tmp.name, "cache")
        self.api = FakeApi()
        self.downloads = FakeDownloads(self.api)
        self.gaz = FakeGazetteer()
        self.enroll_calls = 0

    def tearDown(self):
        library_cache.CACHE_DIR = self._orig_cache_dir
        self._tmp.cleanup()

    def _enroll(self, api, code):
        self.enroll_calls += 1
        return _Enrolled()

    def run_location(self, *extra, photo_id=None, all_=True):
        argv = ["location", "--host", "photos.example.com", "--username", "someone@example.com", "--concurrency", "2"]
        argv += ["--photo-id", photo_id] if photo_id else (["--all"] if all_ else [])
        argv += list(extra)
        parser = argparse.ArgumentParser()
        sub = parser.add_subparsers(dest="command", required=True)
        mm.add_location_parser(sub)
        args = parser.parse_args(argv)
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            rc = mm.location_main(
                self.api, args, progress=lambda m: None, enroll_fn=self._enroll, gazetteer=self.gaz,
                download=self.downloads, read_recovery=lambda: "unused",
            )
        self.stdout, self.stderr = out.getvalue(), err.getvalue()
        return rc

    def put_for(self, photo_id):
        return [p for p in self.api.puts if p[0] == photo_id]


class LocationSkipRule(LocationTestBase):
    def test_current_src_is_skipped_without_even_a_detail_fetch(self):
        self.api.add("P1", place=KYOTO, blob={"gpsLat": 35.01, "gpsLon": 135.77})
        self.assertEqual(self.run_location("--execute"), 0)
        self.assertEqual(self.api.puts, [])
        self.assertEqual(self.api.detail_calls, [])
        self.assertIn("skipped (current src)  1", self.stdout)

    def test_current_src_skipped_for_a_single_photo_too(self):
        self.api.add("P1", place=KYOTO, blob={"gpsLat": 35.01, "gpsLon": 135.77})
        self.assertEqual(self.run_location("--execute", photo_id="P1"), 0)
        self.assertEqual(self.api.puts, [])

    def test_an_older_src_is_redone(self):
        self.api.add("P1", place={**KYOTO, "src": OLD_SRC}, blob={"gpsLat": 35.01, "gpsLon": 135.77})
        self.run_location("--execute")
        self.assertEqual(self.put_for("P1"), [("P1", KYOTO, None, None)])

    def test_immediate_rerun_is_all_skipped(self):
        self.api.add("P1", place={**KYOTO, "src": OLD_SRC}, blob={"gpsLat": 35.01, "gpsLon": 135.77})
        self.api.add("P2", place=KYOTO, original=_jpeg())  # no GPS: place gets cleared
        self.run_location("--execute")
        puts, downloads = len(self.api.puts), len(self.downloads.calls)
        self.api.detail_calls.clear()
        self.run_location("--execute")
        self.assertEqual((len(self.api.puts), len(self.downloads.calls)), (puts, downloads))
        self.assertEqual(self.api.detail_calls, [])
        self.assertIn("updated                0", self.stdout)


class LocationForce(LocationTestBase):
    def test_force_redoes_a_current_src_place(self):
        stale = {**KYOTO, "locality": {"id": "1", "name": "Somewhere else"}}
        self.api.add("P1", place=stale, blob={"gpsLat": 35.01, "gpsLon": 135.77})
        self.run_location("--execute", "--force")
        self.assertEqual(self.put_for("P1"), [("P1", KYOTO, None, None)])

    def test_force_with_an_identical_result_is_unchanged_and_sends_nothing(self):
        self.api.add("P1", place=KYOTO, blob={"gpsLat": 35.01, "gpsLon": 135.77})
        self.run_location("--execute", "--force")
        self.assertEqual(self.api.puts, [])
        self.assertIn("unchanged              1", self.stdout)

    def test_force_overrides_the_no_gps_memory(self):
        self.api.add("P1", original=_jpeg())
        self.run_location("--execute")
        self.assertEqual(len(self.downloads.calls), 1)
        self.run_location("--execute")
        self.assertEqual(len(self.downloads.calls), 1)  # remembered
        self.run_location("--execute", "--force")
        self.assertEqual(len(self.downloads.calls), 2)


class LocationStripSetting(LocationTestBase):
    def test_strip_on_refuses_before_asking_for_the_recovery_code(self):
        self.api.settings["stripLocationOnUpload"] = True
        self.api.add("P1", blob={"gpsLat": 35.01, "gpsLon": 135.77})
        self.assertEqual(self.run_location("--execute"), 1)
        self.assertIn("--ignore-strip-setting", self.stderr)
        self.assertEqual(self.enroll_calls, 0)
        self.assertEqual(self.api.puts, [])

    def test_ignore_strip_setting_overrides(self):
        self.api.settings["stripLocationOnUpload"] = True
        self.api.add("P1", blob={"gpsLat": 35.01, "gpsLon": 135.77})
        self.assertEqual(self.run_location("--execute", "--ignore-strip-setting"), 0)
        self.assertEqual(self.put_for("P1"), [("P1", KYOTO, None, None)])


class LocationNoGps(LocationTestBase):
    def test_no_gps_anywhere_sends_place_null(self):
        self.api.add("P1", place={**KYOTO, "src": OLD_SRC}, blob={"cameraMake": "Canon"}, original=_jpeg())
        self.run_location("--execute")
        self.assertEqual(self.put_for("P1"), [("P1", None, None, None)])
        self.assertIn("no GPS                 1", self.stdout)

    def test_no_gps_and_no_place_sends_nothing_but_is_remembered(self):
        self.api.add("P1", original=_jpeg())
        self.run_location("--execute")
        self.assertEqual(self.api.puts, [])
        self.assertIn("P1", library_cache.load_no_gps("photos.example.com", "someone@example.com"))

    def test_memory_is_ignored_once_the_primary_rendition_changes(self):
        self.api.add("P1", original=_jpeg())
        self.run_location("--execute")
        self.api.details["P1"]["renditions"][0]["contentHash"] = "hmac-sha256:other"
        self.run_location("--execute", photo_id="P1")
        self.assertEqual(len(self.downloads.calls), 2)

    def test_unreadable_raw_is_an_error_not_no_gps(self):
        self.api.add("P1", place={**KYOTO, "src": OLD_SRC}, original=b"not really a cr3", ext="cr3")
        self.assertEqual(self.run_location("--execute"), 1)
        self.assertEqual(self.api.puts, [])
        self.assertEqual(self.downloads.calls, [])
        self.assertIn("RAW", self.stdout)


class LocationCoordinateSources(LocationTestBase):
    def test_exif_blob_coordinates_are_used_without_downloading(self):
        self.api.add("P1", blob={"gpsLat": 35.01, "gpsLon": 135.77}, original=_jpeg())
        self.run_location("--execute")
        self.assertEqual(self.downloads.calls, [])
        self.assertEqual(self.put_for("P1"), [("P1", KYOTO, None, None)])
        self.assertIn("[coords: exif-blob]", self.stdout)

    def test_source_dir_is_used_before_downloading(self):
        original = _jpeg(KYOTO_GPS)
        self.api.add("P1", original=original)
        src = os.path.join(self._tmp.name, "src")
        os.makedirs(os.path.join(src, "dir"))
        with open(os.path.join(src, "dir", "P1.jpg"), "wb") as f:
            f.write(original)
        self.run_location("--execute", "--source-dir", src)
        self.assertEqual(self.downloads.calls, [])
        self.assertEqual(self.put_for("P1")[0][1], KYOTO)
        self.assertIn("[coords: source-dir]", self.stdout)

    def test_a_different_file_in_source_dir_falls_back_to_download(self):
        self.api.add("P1", original=_jpeg(KYOTO_GPS))
        src = os.path.join(self._tmp.name, "src")
        os.makedirs(os.path.join(src, "dir"))
        with open(os.path.join(src, "dir", "P1.jpg"), "wb") as f:
            f.write(_jpeg())  # same path, different bytes (e.g. GPS stripped)
        self.run_location("--execute", "--source-dir", src)
        self.assertEqual(self.downloads.calls, [("raw/P1", None)])
        self.assertEqual(self.put_for("P1")[0][1], KYOTO)
        self.assertIn("[coords: download]", self.stdout)

    def test_missing_from_source_dir_downloads(self):
        self.api.add("P1", original=_jpeg(KYOTO_GPS))
        self.run_location("--execute", "--source-dir", os.path.join(self._tmp.name, "empty"))
        self.assertEqual(self.downloads.calls, [("raw/P1", None)])

    def test_chunked_jpeg_downloads_only_its_first_segment(self):
        original = _jpeg(KYOTO_GPS, pad=cf.SEGMENT + 4096)
        self.api.add("P1", original=original, chunked=True)
        self.run_location("--execute")
        self.assertEqual(self.downloads.calls, [("raw/P1", (0, cf.SEGMENT - 1))])
        self.assertEqual(self.put_for("P1")[0][1], KYOTO)

    def test_heic_original_without_stored_coordinates_is_downloaded_and_read(self):
        blob = {"cameraMake": "Apple", "dateTimeOriginal": "2024:06:15 14:32:00"}
        self.api.add("P1", blob=blob, original=heic_bytes(KYOTO_GPS), ext="heic")
        self.assertEqual(self.run_location("--execute"), 0)
        self.assertEqual(self.downloads.calls, [("raw/P1", None)])
        (_pid, place, exif_enc, exif_iv), = self.put_for("P1")
        self.assertEqual(place, KYOTO)
        got = json.loads(cf.whole_object_decrypt(
            self.api.deks["P1"], base64.b64decode(exif_iv), cf.aad("P1", "x"), base64.b64decode(exif_enc)
        ))
        self.assertEqual({k: got[k] for k in blob}, blob)
        self.assertAlmostEqual(got["gpsLat"], 35 + 41.796 / 3600, places=9)
        self.assertAlmostEqual(got["gpsLon"], 135 + 46 / 60 + 4.944 / 3600, places=9)
        self.assertEqual(got["gpsAltM"], 12.5)
        self.assertIn("[coords: download]", self.stdout)

    def test_chunked_heic_downloads_the_whole_file(self):
        self.api.add("P1", original=heic_bytes(KYOTO_GPS) + bytes(cf.SEGMENT), ext="heic", chunked=True)
        self.run_location("--execute")
        self.assertEqual(self.downloads.calls, [("raw/P1", None)])
        self.assertEqual(self.put_for("P1")[0][1], KYOTO)

    def test_chunked_video_downloads_the_whole_file(self):
        original = _video_xyz("+35.0116+135.7681/") + bytes(cf.SEGMENT)
        self.api.add("P1", original=original, ext="mp4", chunked=True)
        self.run_location("--execute")
        self.assertEqual(self.downloads.calls, [("raw/P1", None)])
        self.assertEqual(self.put_for("P1")[0][1], KYOTO)


class LocationExifRewrite(LocationTestBase):
    def test_reencrypted_exif_round_trips_with_coordinates_added(self):
        blob = {"cameraMake": "Canon", "cameraModel": "EOS R5", "dateTimeOriginal": "2024:06:15 14:32:00",
                "someFutureField": [1, 2]}
        self.api.add("P1", blob=blob, original=_jpeg(KYOTO_GPS))
        self.run_location("--execute")
        (_pid, place, exif_enc, exif_iv), = self.put_for("P1")
        self.assertEqual(place, KYOTO)
        plain = cf.whole_object_decrypt(
            self.api.deks["P1"], base64.b64decode(exif_iv), cf.aad("P1", "x"), base64.b64decode(exif_enc)
        )
        got = json.loads(plain)
        self.assertEqual({k: got[k] for k in blob}, blob)
        self.assertAlmostEqual(got["gpsLat"], 35 + 41.796 / 3600, places=9)
        self.assertAlmostEqual(got["gpsLon"], 135 + 46 / 60 + 4.944 / 3600, places=9)
        self.assertEqual(got["gpsAltM"], 12.5)
        # And the next run reads them from the blob, never the original again.
        self.api.details["P1"]["meta"]["place"] = {**KYOTO, "src": OLD_SRC}
        self.run_location("--execute")
        self.assertEqual(len(self.downloads.calls), 1)
        self.assertIn("[coords: exif-blob]", self.stdout)

    def test_asset_with_no_exif_blob_gets_a_coordinates_only_one(self):
        self.api.add("P1", original=_jpeg(KYOTO_GPS))
        self.run_location("--execute")
        (_pid, _place, exif_enc, exif_iv), = self.put_for("P1")
        got = json.loads(cf.whole_object_decrypt(
            self.api.deks["P1"], base64.b64decode(exif_iv), cf.aad("P1", "x"), base64.b64decode(exif_enc)
        ))
        self.assertEqual(set(got), {"gpsLat", "gpsLon", "gpsAltM"})


class LocationRunBehaviour(LocationTestBase):
    def test_errors_do_not_abort_all_and_are_listed(self):
        for pid in ("P1", "P2", "P3"):
            self.api.add(pid, place={**KYOTO, "src": OLD_SRC}, blob={"gpsLat": 35.01, "gpsLon": 135.77})
        self.api.fail_detail.add("P2")
        self.assertEqual(self.run_location("--execute"), 1)
        self.assertEqual(sorted(p[0] for p in self.api.puts), ["P1", "P3"])
        self.assertIn("errors                 1", self.stdout)
        self.assertIn("    P2  RuntimeError: boom", self.stdout)

    def test_a_409_is_retried_once_from_a_fresh_read(self):
        self.api.add("P1", place={**KYOTO, "src": OLD_SRC}, blob={"gpsLat": 35.01, "gpsLon": 135.77})
        self.api.conflict_once.add("P1")
        self.assertEqual(self.run_location("--execute"), 0)
        self.assertEqual(len(self.put_for("P1")), 1)

    def test_dry_run_writes_nothing(self):
        self.api.add("P1", place={**KYOTO, "src": OLD_SRC}, blob={"gpsLat": 35.01, "gpsLon": 135.77})
        self.api.add("P2", original=_jpeg(KYOTO_GPS))
        self.api.add("P3", place=dict(KYOTO, src=OLD_SRC), original=_jpeg())
        self.assertEqual(self.run_location(), 0)
        self.assertEqual(self.api.puts, [])
        self.assertFalse(os.path.exists(library_cache.CACHE_DIR))
        self.assertIn("Dry run only", self.stdout)
        self.assertIn("[coords: download]  updated  +exifEnc", self.stdout)

    def test_include_trash_adds_trashed_assets(self):
        self.api.add("P1", blob={"gpsLat": 35.01, "gpsLon": 135.77})
        self.api.add("P2", blob={"gpsLat": 35.01, "gpsLon": 135.77}, trashed=True)
        self.run_location("--execute")
        self.assertEqual([p[0] for p in self.api.puts], ["P1"])
        self.run_location("--execute", "--include-trash")
        self.assertEqual(sorted(p[0] for p in self.api.puts), ["P1", "P2"])

    def test_include_trash_needs_all(self):
        self.assertEqual(self.run_location("--include-trash", photo_id="P1"), 2)


class _LocalCopiesBase(LocationTestBase):
    PHONE_PATH = "-1739773001/IMG_1234.jpg"

    def setUp(self):
        super().setUp()
        self.src = os.path.join(self._tmp.name, "src")
        os.makedirs(self.src)
        self.report = os.path.join(self._tmp.name, "missing.csv")

    def put_local(self, rel: str, data: bytes) -> str:
        path = os.path.join(self.src, *rel.split("/"))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(data)
        return path

    def read_report(self) -> list[dict]:
        import csv
        with open(self.report, newline="", encoding="utf-8") as f:
            return list(csv.DictReader(f))


class LocationLocalCopies(_LocalCopiesBase):
    """--source-dir by filename, --local-only, --missing-report and the originals
    statistics: local copies in a different layout from the server paths."""

    # matching ------------------------------------------------------------------
    def test_found_by_filename_in_a_different_layout(self):
        original = _jpeg(KYOTO_GPS)
        self.api.add("P1", original=original, path=self.PHONE_PATH)
        local = self.put_local("Photos/2025/02/IMG_1234.jpg", original)
        self.assertEqual(self.run_location("--execute", "--source-dir", self.src), 0)
        self.assertEqual(self.downloads.calls, [])
        self.assertEqual(self.put_for("P1")[0][1], KYOTO)
        self.assertIn("[coords: source-dir by filename]", self.stdout)
        self.assertIn(f"<- {local}", self.stdout)
        self.assertIn("local copy, by filename   1", self.stdout)

    def test_same_name_same_size_different_content_is_rejected(self):
        original = _jpeg(KYOTO_GPS, pad=16)
        impostor = original[:-1] + b"\x01"  # same size, different bytes: only the hash tells
        self.api.add("P1", original=original, path=self.PHONE_PATH)
        self.put_local("other-camera/IMG_1234.jpg", impostor)
        self.run_location("--execute", "--source-dir", self.src)
        self.assertEqual(self.downloads.calls, [("raw/P1", None)])
        self.assertIn("[coords: download]", self.stdout)

    def test_same_name_different_size_is_rejected_without_hashing(self):
        hashed = []
        real = cf.content_hash_of_file

        def counting(secret, path, *a, **kw):
            hashed.append(path)
            return real(secret, path, *a, **kw)

        self.api.add("P1", original=_jpeg(KYOTO_GPS), path=self.PHONE_PATH)
        self.put_local("other-camera/IMG_1234.jpg", _jpeg())
        orig = mm.cf.content_hash_of_file
        mm.cf.content_hash_of_file = counting
        try:
            self.run_location("--source-dir", self.src, "--local-only")
        finally:
            mm.cf.content_hash_of_file = orig
        self.assertEqual(hashed, [])
        self.assertEqual(self.downloads.calls, [])

    def test_duplicate_basenames_are_resolved_by_hash(self):
        original = _jpeg(KYOTO_GPS, pad=16)
        self.api.add("P1", original=original, path="-1739773001/IMG_0001.jpg")
        self.put_local("cameraA/IMG_0001.jpg", original[:-1] + b"\x01")  # same size, other photo
        self.put_local("cameraB/IMG_0001.jpg", original)
        self.put_local("cameraC/IMG_0001.jpg", _jpeg())  # other size
        self.run_location("--execute", "--source-dir", self.src, "--local-only")
        self.assertEqual(self.downloads.calls, [])
        self.assertEqual(self.put_for("P1")[0][1], KYOTO)
        self.assertIn(f"<- {os.path.join(self.src, 'cameraB', 'IMG_0001.jpg')}", self.stdout)

    def test_filename_match_ignores_case(self):
        original = _jpeg(KYOTO_GPS)
        self.api.add("P1", original=original, path=self.PHONE_PATH)
        self.put_local("backup/img_1234.JPG", original)
        self.run_location("--execute", "--source-dir", self.src, "--local-only")
        self.assertEqual(self.put_for("P1")[0][1], KYOTO)
        self.assertIn("[coords: source-dir by filename]", self.stdout)

    def test_filename_match_ignores_unicode_normalisation(self):
        import unicodedata
        original = _jpeg(KYOTO_GPS)
        self.api.add("P1", original=original, path=unicodedata.normalize("NFC", "-1739773001/Café.jpg"))
        self.put_local(unicodedata.normalize("NFD", "backup/Café.jpg"), original)
        self.run_location("--execute", "--source-dir", self.src, "--local-only")
        self.assertEqual(self.put_for("P1")[0][1], KYOTO)

    def test_exact_path_is_preferred_over_a_filename_match(self):
        original = _jpeg(KYOTO_GPS)
        self.api.add("P1", original=original, path=self.PHONE_PATH)
        self.put_local("-1739773001/IMG_1234.jpg", original)
        self.put_local("elsewhere/IMG_1234.jpg", original)
        self.run_location("--execute", "--source-dir", self.src)
        self.assertIn("[coords: source-dir]  updated", self.stdout)
        self.assertIn("local copy, exact path    1", self.stdout)
        self.assertIn("local copy, by filename   0", self.stdout)

    def test_a_wrong_file_at_the_exact_path_falls_through_to_a_filename_match(self):
        original = _jpeg(KYOTO_GPS)
        self.api.add("P1", original=original, path=self.PHONE_PATH)
        self.put_local("-1739773001/IMG_1234.jpg", _jpeg())  # edited since, say
        self.put_local("elsewhere/IMG_1234.jpg", original)
        self.run_location("--execute", "--source-dir", self.src, "--local-only")
        self.assertEqual(self.put_for("P1")[0][1], KYOTO)
        self.assertIn("[coords: source-dir by filename]", self.stdout)

    # --local-only ----------------------------------------------------------------
    def test_local_only_never_downloads_and_leaves_missing_assets_untouched(self):
        self.api.add("P1", place={**KYOTO, "src": OLD_SRC}, original=_jpeg(), path="-1/IMG_0001.jpg")  # no copy
        self.api.add("P2", original=_jpeg(KYOTO_GPS), path="-1/IMG_0002.jpg")
        self.put_local("x/IMG_0002.jpg", _jpeg(KYOTO_GPS))
        self.assertEqual(self.run_location("--execute", "--source-dir", self.src, "--local-only"), 0)
        self.assertEqual(self.downloads.calls, [])
        self.assertEqual(self.put_for("P1"), [])  # not cleared to place: null
        self.assertEqual(self.api.details["P1"]["meta"]["place"]["src"], OLD_SRC)
        self.assertEqual(self.put_for("P2")[0][1], KYOTO)
        self.assertNotIn("P1", library_cache.load_no_gps("photos.example.com", "someone@example.com"))
        self.assertIn("missing locally        1", self.stdout)
        self.assertIn("errors                 0", self.stdout)
        self.assertIn("P1  missing locally, left untouched: -1/IMG_0001.jpg", self.stdout)
        # And a later run without --local-only still does it.
        self.run_location("--execute", "--source-dir", self.src)
        self.assertEqual(self.downloads.calls, [("raw/P1", None)])
        self.assertEqual(self.put_for("P1"), [("P1", None, None, None)])

    def test_local_copy_without_gps_is_still_no_gps(self):
        self.api.add("P1", place={**KYOTO, "src": OLD_SRC}, original=_jpeg(), path="-1/IMG_0001.jpg")
        self.put_local("y/IMG_0001.jpg", _jpeg())
        self.run_location("--execute", "--source-dir", self.src, "--local-only")
        self.assertEqual(self.put_for("P1"), [("P1", None, None, None)])
        self.assertIn("P1", library_cache.load_no_gps("photos.example.com", "someone@example.com"))

    def test_local_only_and_missing_report_need_source_dir(self):
        self.assertEqual(self.run_location("--local-only"), 2)
        self.assertEqual(self.run_location("--missing-report", self.report), 2)
        self.assertEqual(self.run_location("--local-only", "--source-dir", os.path.join(self._tmp.name, "nope")), 2)
        self.assertEqual(self.enroll_calls, 0)

    def test_dry_run_with_local_only_downloads_and_writes_nothing_but_the_report(self):
        self.api.add("P1", place={**KYOTO, "src": OLD_SRC}, original=_jpeg(KYOTO_GPS), path="-1/IMG_0001.jpg")
        self.api.add("P2", original=_jpeg(KYOTO_GPS), path="-1/IMG_0002.jpg")
        self.api.add("P3", original=_jpeg(), path="-1/IMG_0003.jpg")
        self.put_local("a/IMG_0002.jpg", _jpeg(KYOTO_GPS))
        self.put_local("a/IMG_0003.jpg", _jpeg())  # no GPS: would be remembered on --execute
        rc = self.run_location("--source-dir", self.src, "--local-only", "--missing-report", self.report)
        self.assertEqual(rc, 0)
        self.assertEqual(self.downloads.calls, [])
        self.assertEqual(self.api.puts, [])
        self.assertFalse(os.path.exists(library_cache.CACHE_DIR))
        self.assertEqual([r["photoId"] for r in self.read_report()], ["P1"])
        self.assertIn("Dry run only", self.stdout)
        self.assertNotIn("note: a dry run still downloads", self.stdout)
        self.assertIn("local copies found for 2 of 3 originals needed (66.7%)", self.stdout)

    def test_plain_dry_run_warns_that_it_downloads(self):
        self.api.add("P1", original=_jpeg(KYOTO_GPS))
        self.run_location()
        self.assertIn("note: a dry run still downloads", self.stdout)
        self.assertEqual(len(self.downloads.calls), 1)

    # report and statistics -------------------------------------------------------
    def test_missing_report_contents(self):
        original = _jpeg(KYOTO_GPS, pad=16)
        self.api.add("P1", original=_jpeg(KYOTO_GPS), path="-1739773001/IMG_0001.jpg")  # nothing by that name
        self.api.add("P2", original=original, path="-1739773001/IMG_0002.jpg")
        wrong = self.put_local("cam/IMG_0002.jpg", original[:-1] + b"\x01")
        other = self.put_local("cam2/img_0002.jpg", _jpeg())
        self.api.add("P3", original=_jpeg(KYOTO_GPS), path="-1739773001/IMG_0003.jpg")
        self.put_local("ok/IMG_0003.jpg", _jpeg(KYOTO_GPS))
        self.run_location("--source-dir", self.src, "--local-only", "--missing-report", self.report)
        rows = {r["photoId"]: r for r in self.read_report()}
        self.assertEqual(set(rows), {"P1", "P2"})
        self.assertEqual(rows["P1"]["path"], "-1739773001/IMG_0001.jpg")
        self.assertEqual(rows["P1"]["filename"], "IMG_0001.jpg")
        self.assertEqual(rows["P1"]["reason"], "no-file-by-name")
        self.assertEqual(rows["P1"]["outcome"], "left untouched")
        self.assertEqual(rows["P1"]["takenAt"], "2025-02-17T06:16:41.000Z")
        self.assertEqual(int(rows["P1"]["plainBytes"]), len(_jpeg(KYOTO_GPS)))
        self.assertEqual(rows["P2"]["reason"], "name-found-content-differs")
        self.assertIn("1 with a different size", rows["P2"]["detail"])
        self.assertIn("1 same size but a different contentHash", rows["P2"]["detail"])
        self.assertEqual(rows["P2"]["candidates"], f"{wrong} | {other}")
        self.assertIn("Missing locally (2; photoId, server path, reason):", self.stdout)
        self.assertIn("P2  -1739773001/IMG_0002.jpg  name-found-content-differs", self.stdout)

    def test_missing_report_without_local_only_lists_the_downloaded_ones(self):
        self.api.add("P1", original=_jpeg(KYOTO_GPS), path="-1/IMG_0001.jpg")
        self.run_location("--execute", "--source-dir", self.src, "--missing-report", self.report)
        (row,) = self.read_report()
        self.assertEqual((row["photoId"], row["outcome"]), ("P1", "downloaded"))
        self.assertEqual(self.put_for("P1")[0][1], KYOTO)

    def test_statistics(self):
        a, b, c = _jpeg(KYOTO_GPS), _jpeg(KYOTO_GPS, pad=1), _jpeg(KYOTO_GPS, pad=2)
        self.api.add("P0", blob={"gpsLat": 35.01, "gpsLon": 135.77})  # needs no original
        self.api.add("P1", original=a, path="d/IMG_1.jpg")
        self.put_local("d/IMG_1.jpg", a)  # exact
        self.api.add("P2", original=b, path="d/IMG_2.jpg")
        self.put_local("e/IMG_2.jpg", b)  # by name
        self.api.add("P3", original=c, path="d/IMG_3.jpg")  # downloaded: nothing by that name
        self.api.add("P4", original=c, path="d/IMG_4.jpg")
        self.put_local("e/IMG_4.jpg", a)  # downloaded: name found, content differs
        self.assertEqual(self.run_location("--execute", "--source-dir", self.src), 0)
        out = self.stdout
        self.assertIn("Originals needed (no coordinates in exifEnc): 4", out)
        self.assertIn("local copy, exact path    1", out)
        self.assertIn("local copy, by filename   1", out)
        self.assertIn(f"downloaded                2  ({2 * len(self.api.media['raw/P3']):,} bytes)", out)
        self.assertIn("no verified local copy    2  (no file by that name: 1; name found but size/contentHash "
                      "differs: 1)", out)
        self.assertIn(f"bytes read locally        {len(a) + len(b):,}", out)
        self.assertIn("local copies found for 2 of 4 originals needed (50.0%)", out)

    # archive tier ----------------------------------------------------------------
    def test_an_archived_original_is_its_own_category_and_left_untouched(self):
        self.api.add("P1", place={**KYOTO, "src": OLD_SRC}, original=_jpeg(KYOTO_GPS))
        self.api.add("P2", original=_jpeg(KYOTO_GPS))
        self.downloads.archived.add("raw/P1")
        self.assertEqual(self.run_location("--execute"), 0)
        self.assertEqual(self.put_for("P1"), [])
        self.assertEqual(self.put_for("P2")[0][1], KYOTO)
        self.assertNotIn("P1", library_cache.load_no_gps("photos.example.com", "someone@example.com"))
        self.assertIn("archived               1", self.stdout)
        self.assertIn("errors                 0", self.stdout)
        self.assertIn("P1  ARCHIVED, left untouched", self.stdout)

    def test_other_403s_are_still_errors(self):
        self.api.add("P1", original=_jpeg(KYOTO_GPS))

        def denied(key, dest, rng):
            raise ApiError(403, "<Error><Code>AccessDenied</Code></Error>")

        self.downloads = denied
        self.assertEqual(self.run_location("--execute"), 1)
        self.assertIn("errors                 1", self.stdout)


# The asset's takenAt in FakeApi.add is 2025-02-17T06:16:41.000Z; at tzOffsetMin 60 its
# EXIF DateTimeOriginal reads 07:16:41.
TZ = 60
DTO = "2025:02:17 07:16:41"
TAKEN = "2025-02-17T06:16:41.000Z"


class LocationUnverifiedCopies(_LocalCopiesBase):
    """--allow-content-mismatch: a same-named local copy whose size/contentHash differ,
    accepted only on a matching capture time."""

    def run_unverified(self, *extra):
        return self.run_location("--execute", "--source-dir", self.src, "--allow-content-mismatch",
                                 "--missing-report", self.report, *extra)

    def no_gps_memory(self):
        return library_cache.load_no_gps("photos.example.com", "someone@example.com")

    def test_size_differs_accepted_with_matching_capture_time(self):
        self.api.add("P1", original=_jpeg(KYOTO_GPS, dto=DTO), path=self.PHONE_PATH, tz_offset_min=TZ)
        local = self.put_local("edited/IMG_1234.jpg", _jpeg(KYOTO_GPS, dto=DTO, pad=100))  # re-saved
        self.assertEqual(self.run_unverified("--local-only"), 0)
        self.assertEqual(self.downloads.calls, [])
        (_pid, place, exif_enc, exif_iv), = self.put_for("P1")
        self.assertEqual(place, KYOTO)
        got = json.loads(cf.whole_object_decrypt(
            self.api.deks["P1"], base64.b64decode(exif_iv), cf.aad("P1", "x"), base64.b64decode(exif_enc)
        ))
        self.assertAlmostEqual(got["gpsLat"], 35 + 41.796 / 3600, places=9)
        self.assertIn("[coords: source-dir UNVERIFIED (name + capture time)]", self.stdout)
        self.assertIn(f"<- {local}", self.stdout)
        (row,) = self.read_report()
        self.assertEqual(row["outcome"], "used unverified local copy")
        self.assertEqual(row["reason"], "name-found-content-differs")
        self.assertEqual(row["usedLocalCopy"], local)
        self.assertIn("used UNVERIFIED", row["detail"])

    def test_rejected_on_time_mismatch(self):
        self.api.add("P1", place={**KYOTO, "src": OLD_SRC}, original=_jpeg(KYOTO_GPS, dto=DTO),
                     path=self.PHONE_PATH, tz_offset_min=TZ)
        self.put_local("other/IMG_1234.jpg", _jpeg(KYOTO_GPS, dto="2019:06:15 14:00:00"))  # counter reset
        self.run_unverified("--local-only")
        self.assertEqual(self.api.puts, [])
        (row,) = self.read_report()
        self.assertEqual((row["reason"], row["outcome"], row["usedLocalCopy"]),
                         ("content-differs-time-mismatch", "left untouched", ""))
        self.assertIn("missing locally        1", self.stdout)

    def test_tolerance_is_two_seconds(self):
        self.api.add("P1", original=_jpeg(KYOTO_GPS, dto=DTO), path="-1/IMG_0001.jpg", tz_offset_min=TZ)
        self.put_local("a/IMG_0001.jpg", _jpeg(KYOTO_GPS, dto="2025:02:17 07:16:43"))
        self.api.add("P2", original=_jpeg(KYOTO_GPS, dto=DTO), path="-1/IMG_0002.jpg", tz_offset_min=TZ)
        self.put_local("a/IMG_0002.jpg", _jpeg(KYOTO_GPS, dto="2025:02:17 07:16:44"))
        self.run_unverified("--local-only")
        self.assertEqual([p[0] for p in self.api.puts], ["P1"])

    def test_naive_exif_is_read_at_the_assets_tz_offset(self):
        # Same wall clock, but the asset says UTC-5: 07:16:41 local is 12:16:41Z, not takenAt.
        self.api.add("P1", original=_jpeg(KYOTO_GPS, dto=DTO), path=self.PHONE_PATH, tz_offset_min=-300)
        self.put_local("a/IMG_1234.jpg", _jpeg(KYOTO_GPS, dto=DTO, pad=1))
        self.run_unverified("--local-only")
        self.assertEqual(self.api.puts, [])
        self.assertEqual(self.read_report()[0]["reason"], "content-differs-time-mismatch")

    def test_offset_time_original_is_honoured(self):
        # The asset's tzOffsetMin (0) doesn't explain the wall clock; the file's own +09:00 does.
        self.api.add("P1", original=_jpeg(KYOTO_GPS), path=self.PHONE_PATH, tz_offset_min=0)
        self.put_local("a/IMG_1234.jpg", _jpeg(KYOTO_GPS, dto="2025:02:17 15:16:41", oto="+09:00"))
        self.run_unverified("--local-only")
        self.assertEqual(self.put_for("P1")[0][1], KYOTO)

    def test_video_creation_time(self):
        self.api.add("P1", original=_video_xyz("+35.0116+135.7681/", creation=TAKEN), ext="mp4",
                     path="-1/VID_0001.mp4")
        self.put_local("v/VID_0001.mp4", _video_xyz("+35.0116+135.7681/", creation=TAKEN, mdat=999))
        self.api.add("P2", original=_video_xyz("+35.0116+135.7681/", creation=TAKEN), ext="mp4",
                     path="-1/VID_0002.mp4")
        self.put_local("v/VID_0002.mp4", _video_xyz("+35.0116+135.7681/", creation="2025-02-17T06:20:00Z", mdat=9))
        self.run_unverified("--local-only")
        self.assertEqual([(p[0], p[1]) for p in self.api.puts], [("P1", KYOTO)])
        rows = {r["photoId"]: r for r in self.read_report()}
        self.assertEqual(rows["P2"]["reason"], "content-differs-time-mismatch")

    def test_ambiguous_candidates_are_not_used(self):
        self.api.add("P1", original=_jpeg(KYOTO_GPS, dto=DTO), path=self.PHONE_PATH, tz_offset_min=TZ)
        self.put_local("a/IMG_1234.jpg", _jpeg(KYOTO_GPS, dto=DTO, pad=1))
        self.put_local("b/IMG_1234.jpg", _jpeg(KYOTO_GPS, dto=DTO, pad=2))
        self.run_unverified("--local-only")
        self.assertEqual(self.api.puts, [])
        self.assertEqual(self.read_report()[0]["reason"], "content-differs-ambiguous")

    def test_identical_copies_are_not_ambiguous(self):
        self.api.add("P1", original=_jpeg(KYOTO_GPS, dto=DTO), path=self.PHONE_PATH, tz_offset_min=TZ)
        copy = _jpeg(KYOTO_GPS, dto=DTO, pad=1)
        self.put_local("a/IMG_1234.jpg", copy)
        self.put_local("backup/a/IMG_1234.jpg", copy)
        self.run_unverified("--local-only")
        self.assertEqual(self.put_for("P1")[0][1], KYOTO)

    def test_no_capture_time_is_not_used(self):
        self.api.add("P1", original=_jpeg(KYOTO_GPS, dto=DTO), path=self.PHONE_PATH, tz_offset_min=TZ)
        self.put_local("a/IMG_1234.jpg", _jpeg(KYOTO_GPS, pad=1))  # DateTimeOriginal stripped
        self.put_local("b/IMG_1234.jpg", b"not an image at all")
        self.run_unverified("--local-only")
        self.assertEqual(self.api.puts, [])
        (row,) = self.read_report()
        self.assertEqual(row["reason"], "content-differs-no-capture-time")
        self.assertIn("none of 2 candidate(s)", row["detail"])

    def test_one_time_match_wins_over_timeless_and_mismatched_candidates(self):
        self.api.add("P1", original=_jpeg(KYOTO_GPS, dto=DTO), path=self.PHONE_PATH, tz_offset_min=TZ)
        good = self.put_local("a/IMG_1234.jpg", _jpeg(KYOTO_GPS, dto=DTO, pad=1))
        self.put_local("b/IMG_1234.jpg", _jpeg(KYOTO_GPS, pad=2))
        self.put_local("c/IMG_1234.jpg", _jpeg(KYOTO_GPS, dto="2019:06:15 14:00:00"))
        self.run_unverified("--local-only")
        self.assertEqual(self.read_report()[0]["usedLocalCopy"], good)

    def test_verified_copy_is_preferred(self):
        original = _jpeg(KYOTO_GPS, dto=DTO)
        self.api.add("P1", original=original, path=self.PHONE_PATH, tz_offset_min=TZ)
        self.put_local("a/IMG_1234.jpg", _jpeg(KYOTO_GPS, dto=DTO, pad=1))  # unverified, sorts first
        verified = self.put_local("b/IMG_1234.jpg", original)
        self.run_unverified("--local-only")
        self.assertIn(f"[coords: source-dir by filename]  updated  +exifEnc with coordinates  <- {verified}",
                      self.stdout)
        self.assertEqual(self.read_report(), [])
        self.assertIn("local copy, UNVERIFIED    0", self.stdout)

    def test_flag_off_is_the_old_behaviour(self):
        self.api.add("P1", original=_jpeg(KYOTO_GPS, dto=DTO), path=self.PHONE_PATH, tz_offset_min=TZ)
        self.put_local("a/IMG_1234.jpg", _jpeg(KYOTO_GPS, dto=DTO, pad=1))
        self.run_location("--execute", "--source-dir", self.src, "--missing-report", self.report)
        self.assertEqual(self.downloads.calls, [("raw/P1", None)])
        (row,) = self.read_report()
        self.assertEqual((row["reason"], row["outcome"]), ("name-found-content-differs", "downloaded"))
        self.assertNotIn("UNVERIFIED", self.stdout)

    def test_flag_needs_source_dir(self):
        self.assertEqual(self.run_location("--allow-content-mismatch"), 2)
        self.assertEqual(self.enroll_calls, 0)

    def test_unverified_copy_without_gps_falls_back_to_download(self):
        self.api.add("P1", place={**KYOTO, "src": OLD_SRC}, original=_jpeg(KYOTO_GPS, dto=DTO),
                     path=self.PHONE_PATH, tz_offset_min=TZ)
        self.put_local("a/IMG_1234.jpg", _jpeg(dto=DTO))  # re-saved, GPS lost
        self.run_unverified()
        self.assertEqual(self.downloads.calls, [("raw/P1", None)])
        self.assertEqual(self.put_for("P1")[0][1], KYOTO)
        self.assertNotIn("P1", self.no_gps_memory())
        (row,) = self.read_report()
        self.assertEqual((row["reason"], row["outcome"]), ("content-differs-no-gps", "downloaded"))

    def test_unverified_copy_without_gps_is_missing_under_local_only(self):
        self.api.add("P1", place={**KYOTO, "src": OLD_SRC}, original=_jpeg(KYOTO_GPS, dto=DTO),
                     path=self.PHONE_PATH, tz_offset_min=TZ)
        self.put_local("a/IMG_1234.jpg", _jpeg(dto=DTO))
        self.run_unverified("--local-only")
        self.assertEqual(self.api.puts, [])  # place not cleared
        self.assertEqual(self.api.details["P1"]["meta"]["place"]["src"], OLD_SRC)
        self.assertNotIn("P1", self.no_gps_memory())
        self.assertIn("missing locally        1", self.stdout)
        self.assertEqual(self.read_report()[0]["reason"], "content-differs-no-gps")

    def test_unverified_copy_that_does_not_parse_is_missing(self):
        self.api.add("P1", original=_jpeg(KYOTO_GPS, dto=DTO), path=self.PHONE_PATH, tz_offset_min=TZ)
        self.put_local("a/IMG_1234.jpg", _jpeg(KYOTO_GPS, dto=DTO, pad=1))
        real = mm.media_probe.read_coordinates

        def broken(path, ext):
            raise ValueError("truncated")

        mm.media_probe.read_coordinates = broken
        try:
            self.assertEqual(self.run_unverified("--local-only"), 0)
        finally:
            mm.media_probe.read_coordinates = real
        self.assertEqual(self.api.puts, [])
        self.assertNotIn("P1", self.no_gps_memory())
        self.assertEqual(self.read_report()[0]["reason"], "content-differs-unreadable")

    def test_dry_run_writes_nothing(self):
        self.api.add("P1", original=_jpeg(KYOTO_GPS, dto=DTO), path=self.PHONE_PATH, tz_offset_min=TZ)
        self.put_local("a/IMG_1234.jpg", _jpeg(KYOTO_GPS, dto=DTO, pad=1))
        self.run_location("--source-dir", self.src, "--allow-content-mismatch", "--local-only",
                          "--missing-report", self.report)
        self.assertEqual(self.api.puts, [])
        self.assertIn("UNVERIFIED (name + capture time)]  updated", self.stdout)
        self.assertEqual(self.read_report()[0]["outcome"], "used unverified local copy")

    def test_statistics(self):
        a = _jpeg(KYOTO_GPS, dto=DTO)
        self.api.add("P1", original=a, path="d/IMG_1.jpg", tz_offset_min=TZ)
        self.put_local("d/IMG_1.jpg", a)  # verified, exact
        self.api.add("P2", original=a, path="d/IMG_2.jpg", tz_offset_min=TZ)
        self.put_local("e/IMG_2.jpg", _jpeg(KYOTO_GPS, dto=DTO, pad=1))  # used unverified
        self.api.add("P3", original=a, path="d/IMG_3.jpg", tz_offset_min=TZ)
        self.put_local("e/IMG_3.jpg", _jpeg(KYOTO_GPS, dto="2019:06:15 14:00:00"))  # time mismatch
        self.api.add("P4", original=a, path="d/IMG_4.jpg", tz_offset_min=TZ)
        self.put_local("e/IMG_4.jpg", _jpeg(dto=DTO))  # no GPS in the copy
        self.api.add("P5", original=a, path="d/IMG_5.jpg", tz_offset_min=TZ)  # nothing by that name
        self.assertEqual(self.run_unverified("--local-only"), 0)
        out = self.stdout
        self.assertIn("local copy, exact path    1", out)
        self.assertIn("local copy, by filename   0", out)
        self.assertIn("local copy, UNVERIFIED    1", out)
        self.assertIn("no verified local copy    4  (no file by that name: 1; name found but size/contentHash "
                      "differs: 3)", out)
        self.assertIn("of the 3: used unverified 1; not used: capture time differs 1, no capture time 0, "
                      "ambiguous 0, copy has no GPS 1, copy unreadable 0", out)
        self.assertIn("local copies found for 2 of 5 originals needed (40.0%), 1 of them unverified", out)
        self.assertIn("missing locally        3", out)
        rows = {r["photoId"]: (r["reason"], r["outcome"]) for r in self.read_report()}
        self.assertEqual(rows, {
            "P2": ("name-found-content-differs", "used unverified local copy"),
            "P3": ("content-differs-time-mismatch", "left untouched"),
            "P4": ("content-differs-no-gps", "left untouched"),
            "P5": ("no-file-by-name", "left untouched"),
        })


class CaptureInstants(unittest.TestCase):
    def test_naive_exif_with_and_without_its_own_offset(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "a.jpg")
            with open(p, "wb") as f:
                f.write(_jpeg(dto="2025:02:17 15:16:41", oto="+09:00"))
            got = mm.capture_instants(p, "jpg", 60)
            self.assertEqual([mm._iso(t) for t in got], ["2025-02-17T14:16:41Z", "2025-02-17T06:16:41Z"])
            self.assertEqual([mm._iso(t) for t in mm.capture_instants(p, "jpg", None)], ["2025-02-17T06:16:41Z"])
            with open(p, "wb") as f:
                f.write(_jpeg())
            self.assertEqual(mm.capture_instants(p, "jpg", 60), [])


class DecryptFirstSegment(unittest.TestCase):
    def test_matches_the_start_of_a_tink_stream(self):
        dek, aad = os.urandom(32), cf.aad("P", cf.object_ref_rendition("R"))
        plain = os.urandom(cf.C0 + 1000)
        ct = cf.streaming_encrypt(dek, aad, plain)
        self.assertEqual(cf.decrypt_first_segment(dek, aad, ct[: cf.SEGMENT]), plain[: cf.C0])

    def test_wrong_aad_fails(self):
        dek = os.urandom(32)
        ct = cf.streaming_encrypt(dek, b"a", os.urandom(cf.C0 + 10))
        with self.assertRaises(Exception):
            cf.decrypt_first_segment(dek, b"b", ct[: cf.SEGMENT])

    def test_a_single_segment_object_is_refused(self):
        # Its only segment is flagged last, so decrypting it as non-final must fail.
        dek, aad = os.urandom(32), b"a"
        ct = cf.streaming_encrypt(dek, aad, os.urandom(cf.C0)) + bytes(cf.SEGMENT)
        with self.assertRaises(Exception):
            cf.decrypt_first_segment(dek, aad, ct[: cf.SEGMENT])


if __name__ == "__main__":
    unittest.main()

