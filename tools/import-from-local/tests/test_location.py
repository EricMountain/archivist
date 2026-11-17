"""Geolocation plan step 5, importer side: GPS extraction (EXIF for stills, ISO-6709 /
`©xyz` / `loci` / keys+ilst for video), and the importer sending `place` plus
`gpsLat`/`gpsLon`/`gpsAltM` in its encrypted ExifBlob -- and neither for a stripped
copy or with `stripLocationOnUpload` on."""

from __future__ import annotations

import base64
import json
import os
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from PIL import Image

import crypto_format as cf
import media_probe as mp
import mp4box
from importer import Enrollment, Importer, exif_blob
from _heic import write_heic

KYOTO_PLACE = {
    "cc": "JP",
    "region": {"id": "JP.22", "name": "Kyoto"},
    "locality": {"id": "1857910", "name": "Kyoto"},
    "src": "geonames-cities500@2026-11-13",
}


def _box(box_type: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", 8 + len(payload)) + box_type + payload


def _gps_jpeg(path: str, gps: dict) -> None:
    im = Image.new("RGB", (32, 24), color=(10, 120, 200))
    exif = im.getexif()
    exif[271] = "Canon"
    exif[272] = "EOS R5"
    ifd = exif.get_ifd(0x8825)
    ifd.update(gps)
    im.save(path, format="JPEG", exif=exif)


# Kyoto, 35°0'41.8" N 135°46'5.0" E, 12.5 m.
KYOTO_GPS = {
    1: "N", 2: (35.0, 0.0, 41.796), 3: "E", 4: (135.0, 46.0, 4.944), 5: 0, 6: 12.5,
    29: "2024:06:15", 7: (5.0, 32.0, 0.0),
}


def _video(udta_children: bytes = b"", meta: bytes = b"") -> bytes:
    mvhd = _box(b"mvhd", bytes(4) + struct.pack(">III", 0, 0, 1000) + bytes(80))
    udta = _box(b"udta", udta_children) if udta_children else b""
    moov = _box(b"moov", mvhd + udta + meta)
    return _box(b"ftyp", b"isom" + bytes(12)) + moov + _box(b"mdat", bytes(32))


def _xyz(value: str) -> bytes:
    raw = value.encode("ascii")
    return _box(b"\xa9xyz", struct.pack(">HH", len(raw), 0x15C7) + raw)


def _apple_meta(value: str) -> bytes:
    name = b"com.apple.quicktime.location.ISO6709"
    other = b"com.apple.quicktime.make"
    keys = _box(
        b"keys",
        bytes(4) + struct.pack(">I", 2)
        + struct.pack(">I", 8 + len(other)) + b"mdta" + other
        + struct.pack(">I", 8 + len(name)) + b"mdta" + name,
    )

    def item(index: int, v: bytes) -> bytes:
        data = _box(b"data", struct.pack(">II", 1, 0) + v)
        return struct.pack(">I", 8 + len(data)) + struct.pack(">I", index) + data

    ilst = _box(b"ilst", item(1, b"Apple") + item(2, value.encode("ascii")))
    hdlr = _box(b"hdlr", bytes(8) + b"mdta" + bytes(12))
    return _box(b"meta", bytes(4) + hdlr + keys + ilst)


def _loci(lat: float, lon: float, alt: float, name: bytes = b"Home\x00") -> bytes:
    fixed = lambda v: int(round(v * 65536))  # noqa: E731
    payload = bytes(4) + struct.pack(">H", 0x15C7) + name + b"\x00" + struct.pack(">iii", fixed(lon), fixed(lat), fixed(alt))
    payload += b"\x00\x00"  # empty astronomical body + notes
    return _box(b"loci", payload)


class Iso6709(unittest.TestCase):
    """The same table as android's Iso6709Test."""

    def test_table(self):
        cases = [
            ("+35.0116+135.7681/", (35.0116, 135.7681, None)),
            ("+35.0116+135.7681+012.5/", (35.0116, 135.7681, 12.5)),
            ("+35.0116+135.7681", (35.0116, 135.7681, None)),
            ("-33.8688-070.6693-005.250/", (-33.8688, -70.6693, -5.25)),
            ("+48.8566+002.3522CRSWGS_84/", (48.8566, 2.3522, None)),
            ("  +35.0116+135.7681/ ", (35.0116, 135.7681, None)),
            ("+90.0000-180.0000/", (90.0, -180.0, None)),
            ("+35+135/", (35.0, 135.0, None)),
            ("", None),
            (None, None),
            ("garbage", None),
            ("35.0116+135.7681/", None),  # no sign
            ("+35.0116/", None),  # no longitude
            ("+3501.16+13546.08/", None),  # degree-minute form: not supported
            ("+95.0000+135.7681/", None),  # latitude out of range
            ("+35.0116+185.0000/", None),  # longitude out of range
            ("+35.0116+135.7681//", None),
            ("+00.0000+000.0000/", None),  # a placeholder / redacted fix, not null island
        ]
        for value, want in cases:
            with self.subTest(value=value):
                self.assertEqual(mp4box.parse_iso6709(value), want)


class VideoLocation(unittest.TestCase):
    def _probe(self, data: bytes):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "v.mp4")
            with open(path, "wb") as f:
                f.write(data)
            return mp.probe_video_location(path)

    def test_xyz(self):
        self.assertEqual(self._probe(_video(_xyz("+35.0116+135.7681/"))), (35.0116, 135.7681, None))

    def test_apple_keys_ilst_with_altitude(self):
        got = self._probe(_video(meta=_apple_meta("+35.0116+135.7681+012.500/")))
        self.assertEqual(got, (35.0116, 135.7681, 12.5))

    def test_loci(self):
        lat, lon, alt = self._probe(_video(_loci(35.0116, 135.7681, 12.5)))
        self.assertAlmostEqual(lat, 35.0116, places=4)
        self.assertAlmostEqual(lon, 135.7681, places=4)
        self.assertAlmostEqual(alt, 12.5, places=4)

    def test_loci_with_utf16_name(self):
        name = b"\xfe\xff" + "Kyō".encode("utf-16-be") + b"\x00\x00"
        lat, lon, _ = self._probe(_video(_loci(-33.5, -70.25, 0.0, name=name)))
        self.assertAlmostEqual(lat, -33.5, places=4)
        self.assertAlmostEqual(lon, -70.25, places=4)

    def test_apple_meta_preferred_over_xyz(self):
        data = _video(_xyz("+10.0000+010.0000/"), meta=_apple_meta("+35.0116+135.7681/"))
        self.assertEqual(self._probe(data), (35.0116, 135.7681, None))

    def test_no_location(self):
        self.assertIsNone(self._probe(_video()))

    def test_stripped_copy_has_no_location(self):
        with tempfile.TemporaryDirectory() as d:
            src, dst = os.path.join(d, "a.mp4"), os.path.join(d, "b.mp4")
            with open(src, "wb") as f:
                f.write(_video(_xyz("+35.0116+135.7681/") + _loci(1.0, 2.0, 3.0), meta=_apple_meta("+35.0+135.0/")))
            self.assertIsNotNone(mp.probe_video_location(src))
            self.assertTrue(mp4box.strip_video_location(src, dst))
            self.assertIsNone(mp.probe_video_location(dst))


class StillGps(unittest.TestCase):
    def test_gps_tagged_jpeg(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "a.jpg")
            _gps_jpeg(path, KYOTO_GPS)
            e = mp.extract_exif(path)
        self.assertAlmostEqual(e.gps_lat, 35.01161, places=5)
        self.assertAlmostEqual(e.gps_lon, 135.76804, places=5)
        self.assertEqual(e.gps_alt_m, 12.5)

    def test_south_west_and_below_sea_level(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "a.jpg")
            _gps_jpeg(path, {1: "S", 2: (34.0, 36.0, 0.0), 3: "W", 4: (58.0, 22.5, 0.0), 5: 1, 6: 3.0})
            e = mp.extract_exif(path)
        self.assertAlmostEqual(e.gps_lat, -34.6, places=9)
        self.assertAlmostEqual(e.gps_lon, -58.375, places=9)
        self.assertEqual(e.gps_alt_m, -3.0)

    def test_missing_ref_or_longitude_gives_no_coordinates(self):
        for gps in ({2: (35.0, 0.0, 0.0), 3: "E", 4: (135.0, 0.0, 0.0)}, {1: "N", 2: (35.0, 0.0, 0.0)}):
            with self.subTest(gps=gps), tempfile.TemporaryDirectory() as d:
                path = os.path.join(d, "a.jpg")
                _gps_jpeg(path, gps)
                e = mp.extract_exif(path)
                self.assertIsNone(e.gps_lat)
                self.assertIsNone(e.gps_lon)

    def test_zeroed_gps_block_gives_no_coordinates(self):
        # What a read without ACCESS_MEDIA_LOCATION, or a placeholder, decodes to.
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "a.jpg")
            _gps_jpeg(path, {1: "N", 2: (0.0, 0.0, 0.0), 3: "E", 4: (0.0, 0.0, 0.0)})
            e = mp.extract_exif(path)
        self.assertIsNone(e.gps_lat)
        self.assertIsNone(e.gps_lon)

    def test_altitude_needs_its_ref(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "a.jpg")
            _gps_jpeg(path, {1: "N", 2: (35.0, 0.0, 0.0), 3: "E", 4: (135.0, 0.0, 0.0), 6: 12.5})
            e = mp.extract_exif(path)
        self.assertIsNotNone(e.gps_lat)
        self.assertIsNone(e.gps_alt_m)

    def test_stripped_copy_has_no_coordinates(self):
        with tempfile.TemporaryDirectory() as d:
            src, dst = os.path.join(d, "a.jpg"), os.path.join(d, "b.jpg")
            _gps_jpeg(src, KYOTO_GPS)
            mp.strip_gps_from_image(src, dst)
            e = mp.extract_exif(dst)
        self.assertIsNone(e.gps_lat)
        self.assertIsNone(e.gps_lon)
        self.assertIsNone(e.gps_alt_m)


class HeicGps(unittest.TestCase):
    """HEIC via pillow-heif: same EXIF GPS rules as a JPEG."""

    def test_read_coordinates_on_heic(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "IMG_0001.HEIC")
            write_heic(path, KYOTO_GPS)
            self.assertTrue(mp.coordinates_readable("HEIC"))
            lat, lon, alt = mp.read_coordinates(path, "heic")
        self.assertAlmostEqual(lat, 35.01161, places=5)
        self.assertAlmostEqual(lon, 135.76804, places=5)
        self.assertEqual(alt, 12.5)

    def test_heic_without_gps_gives_none(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "a.heic")
            write_heic(path)
            self.assertIsNone(mp.read_coordinates(path, "heic"))

    def test_stripped_heic_has_no_coordinates_but_keeps_the_rest(self):
        with tempfile.TemporaryDirectory() as d:
            src, dst = os.path.join(d, "a.heic"), os.path.join(d, "b.heic")
            write_heic(src, KYOTO_GPS)
            mp.strip_gps_from_image(src, dst)
            e = mp.extract_exif(dst)
        self.assertIsNone(e.gps_lat)
        self.assertEqual((e.camera_make, e.date_time_original), ("Apple", "2024:06:15 14:32:00"))
        self.assertEqual((e.width, e.height), (32, 64))

    def test_raw_stays_unreadable(self):
        self.assertFalse(mp.coordinates_readable("cr3"))
        self.assertFalse(mp.coordinates_readable("DNG"))


class _RecordingApi:
    def __init__(self):
        self.bodies = []

    def post_upload(self, body):
        self.bodies.append(body)
        return {"skipped": True}  # nothing further to PUT


MASTER_KEY = bytes(range(32))


def _enrollment(api, strip_location=False) -> Enrollment:
    return Enrollment(
        api=api, master_key=MASTER_KEY, master_key_ver="mk-1", hash_secret=b"\x01" * 32,
        home_tz="Europe/Paris", strip_location=strip_location, device_defaults={},
    )


def _decrypt_exif(body) -> dict:
    dek = cf.unwrap_key(MASTER_KEY, base64.b64decode(body["encDek"]))
    plaintext = cf.whole_object_decrypt(
        dek, base64.b64decode(body["exifIv"]), cf.aad(body["photoId"], cf.object_ref_exif()),
        base64.b64decode(body["exifEnc"]),
    )
    return json.loads(plaintext)


class ImporterSendsPlace(unittest.TestCase):
    def _run(self, files: dict, strip_location=False):
        api = _RecordingApi()
        with tempfile.TemporaryDirectory() as d:
            for name, writer in files.items():
                writer(os.path.join(d, name))
            importer = Importer(_enrollment(api, strip_location), d, progress=lambda m: None)
            stats = importer.run()
        self.assertEqual(stats.errors, [])
        return {b["path"]: b for b in api.bodies}, stats

    def test_gps_tagged_jpeg_sends_place_and_coordinates(self):
        bodies, stats = self._run({"IMG_1.jpg": lambda p: _gps_jpeg(p, KYOTO_GPS)})
        body = bodies["IMG_1.jpg"]
        self.assertEqual(body["place"], KYOTO_PLACE)
        blob = _decrypt_exif(body)
        self.assertAlmostEqual(blob["gpsLat"], 35.01161, places=5)
        self.assertAlmostEqual(blob["gpsLon"], 135.76804, places=5)
        self.assertEqual(blob["gpsAltM"], 12.5)
        self.assertEqual(blob["cameraMake"], "Canon")
        self.assertEqual((stats.with_coordinates, stats.geocoded), (1, 1))

    def test_gps_tagged_heic_sends_place_exif_and_thumbnails(self):
        bodies, stats = self._run({"IMG_0001.HEIC": lambda p: write_heic(p, KYOTO_GPS)})
        body = bodies["IMG_0001.HEIC"]
        self.assertEqual(body["mime"], "image/heic")
        self.assertEqual(body["place"], KYOTO_PLACE)
        self.assertEqual((body["takenAt"][:19], body["takenAtSrc"], body["tzOffsetMin"]),
                         ("2024-06-15T05:32:00", "exif", 540))
        self.assertEqual((body["width"], body["height"]), (32, 64))
        self.assertEqual(sorted(body["thumbs"]), ["1024", "2048", "256"])
        blob = _decrypt_exif(body)
        self.assertEqual((blob["cameraMake"], blob["cameraModel"]), ("Apple", "iPhone 15"))
        self.assertAlmostEqual(blob["gpsLat"], 35.01161, places=5)
        self.assertEqual(blob["gpsAltM"], 12.5)
        self.assertEqual((stats.with_coordinates, stats.geocoded), (1, 1))

    def test_heic_with_strip_on_is_stripped_and_sends_neither(self):
        bodies, stats = self._run({"IMG_0001.HEIC": lambda p: write_heic(p, KYOTO_GPS)}, strip_location=True)
        body = bodies["IMG_0001.HEIC"]
        self.assertEqual(stats.location_stripped, 1)
        self.assertNotIn("place", body)
        self.assertFalse({"gpsLat", "gpsLon", "gpsAltM"} & set(_decrypt_exif(body)))
        self.assertIn("thumbs", body)

    def test_stripped_jpeg_sends_neither(self):
        def stripped(p):
            src = p + ".src.jpg"
            _gps_jpeg(src, KYOTO_GPS)
            mp.strip_gps_from_image(src, p)
            os.remove(src)

        body = self._run({"IMG_1.jpg": stripped})[0]["IMG_1.jpg"]
        self.assertNotIn("place", body)
        blob = _decrypt_exif(body)
        self.assertNotIn("gpsLat", blob)
        self.assertNotIn("gpsLon", blob)
        self.assertNotIn("gpsAltM", blob)

    def test_strip_location_on_sends_neither(self):
        body = self._run({"IMG_1.jpg": lambda p: _gps_jpeg(p, KYOTO_GPS)}, strip_location=True)[0]["IMG_1.jpg"]
        self.assertNotIn("place", body)
        self.assertFalse({"gpsLat", "gpsLon", "gpsAltM", "gpsDateTimeUtc"} & set(_decrypt_exif(body)))

    def test_video_with_location_sends_place_and_a_coordinates_only_blob(self):
        def video(p):
            with open(p, "wb") as f:
                f.write(_video(_xyz("+35.0116+135.7681/")))

        body = self._run({"VID_1.mp4": video})[0]["VID_1.mp4"]
        self.assertEqual(body["place"], KYOTO_PLACE)
        self.assertEqual(_decrypt_exif(body), {"gpsLat": 35.0116, "gpsLon": 135.7681})

    def test_video_with_location_and_strip_on_sends_neither(self):
        def video(p):
            with open(p, "wb") as f:
                f.write(_video(_xyz("+35.0116+135.7681/")))

        body = self._run({"VID_1.mp4": video}, strip_location=True)[0]["VID_1.mp4"]
        self.assertNotIn("place", body)
        self.assertNotIn("exifEnc", body)

    def test_open_sea_sends_coordinates_but_no_place(self):
        gps = {1: "N", 2: (30.0, 0.0, 0.0), 3: "W", 4: (40.0, 0.0, 0.0)}
        body = self._run({"IMG_1.jpg": lambda p: _gps_jpeg(p, gps)})[0]["IMG_1.jpg"]
        self.assertNotIn("place", body)
        blob = _decrypt_exif(body)
        self.assertEqual((blob["gpsLat"], blob["gpsLon"]), (30.0, -40.0))
        self.assertNotIn("gpsAltM", blob)

    def test_locality_without_region_is_sent_with_the_town(self):
        # Stanley, Falkland Islands: GeoNames has no admin1 region for it.
        gps = {1: "S", 2: (51.0, 41.0, 37.75), 3: "W", 4: (57.0, 51.0, 25.24)}
        body = self._run({"IMG_1.jpg": lambda p: _gps_jpeg(p, gps)})[0]["IMG_1.jpg"]
        self.assertEqual(
            body["place"],
            {"cc": "FK", "locality": {"id": "3426691", "name": "Stanley"}, "src": KYOTO_PLACE["src"]},
        )


class ExifBlobShape(unittest.TestCase):
    def test_field_names_match_android(self):
        e = mp.ExifData(camera_make="Canon")
        self.assertEqual(
            exif_blob(e, (1.5, -2.5, None)), {"cameraMake": "Canon", "gpsLat": 1.5, "gpsLon": -2.5}
        )
        self.assertEqual(exif_blob(None, (1.5, -2.5, 3.0)), {"gpsLat": 1.5, "gpsLon": -2.5, "gpsAltM": 3.0})
        self.assertIsNone(exif_blob(None, None))
        self.assertIsNone(exif_blob(mp.ExifData(), None))


if __name__ == "__main__":
    unittest.main()
