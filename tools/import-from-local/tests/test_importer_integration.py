"""Exercises `Importer` end to end in dry-run mode (no network calls, so no live
instance needed) against a small synthetic directory -- catches orchestration
bugs the per-module unit tests can't (e.g. a file type that crashes the pipeline
before it ever reaches `POST /uploads`, or `-edited` grouping wiring)."""

from __future__ import annotations

import os
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from PIL import Image

from importer import Enrollment, Importer


def _fake_enrollment(**overrides) -> Enrollment:
    defaults = dict(
        api=None,
        master_key=b"\x00" * 32,
        master_key_ver="mk-1",
        hash_secret=b"\x01" * 32,
        home_tz="Europe/Paris",
        strip_location=False,
        device_defaults={},
    )
    defaults.update(overrides)
    return Enrollment(**defaults)


def _make_jpeg(path: str, size=(64, 48)) -> None:
    Image.new("RGB", size, color=(200, 100, 50)).save(path, format="JPEG")


class DryRunAgainstSyntheticCorpus(unittest.TestCase):
    def test_mixed_file_types_do_not_crash(self):
        with tempfile.TemporaryDirectory() as d:
            _make_jpeg(os.path.join(d, "IMG_20180713_140225.jpg"))
            _make_jpeg(os.path.join(d, "IMG_20180713_140225-edited.jpg"))
            # A RAW file Pillow cannot decode -- must not crash the run (see
            # media_probe.is_raw / the regression this guards against).
            with open(os.path.join(d, "IMG_9999.CR3"), "wb") as f:
                f.write(b"not actually a real CR3 file, just garbage bytes")
            # A tiny, real (if minimal) MP4 container.
            with open(os.path.join(d, "VID_20190101_120000.mp4"), "wb") as f:
                f.write(_minimal_mp4())
            with open(os.path.join(d, ".DS_Store"), "wb") as f:
                f.write(b"ignored")

            logs = []
            importer = Importer(_fake_enrollment(), d, dry_run=True, progress=logs.append)
            stats = importer.run()

            self.assertEqual(stats.errors, [])
            # 4 real files processed (DS_Store skipped).
            processed = [l for l in logs if l.startswith("[")]
            self.assertEqual(len(processed), 4)

    def test_edited_file_groups_with_its_base(self):
        with tempfile.TemporaryDirectory() as d:
            _make_jpeg(os.path.join(d, "IMG_1234.jpg"))
            _make_jpeg(os.path.join(d, "IMG_1234-edited.jpg"))

            importer = Importer(_fake_enrollment(), d, dry_run=True, progress=lambda m: None)

            # Patch _upload_one to capture the group_with argument each file was
            # actually called with, while still exercising the real _process_one/
            # _group_with_for wiring around it.
            calls = []
            original = importer._upload_one

            def spy(rel_path, group_with):
                calls.append((rel_path, group_with))
                # Pretend every upload created a fresh asset, so the base's
                # photoId becomes resolvable for the edited file's lookup.
                return f"photo-for-{rel_path}"

            importer._upload_one = spy
            importer.run()

            by_path = dict(calls)
            self.assertIsNone(by_path["IMG_1234.jpg"])
            self.assertEqual(by_path["IMG_1234-edited.jpg"], "photo-for-IMG_1234.jpg")

    def test_orphaned_edit_suffix_falls_back_to_no_group(self):
        with tempfile.TemporaryDirectory() as d:
            _make_jpeg(os.path.join(d, "IMG_5555-edited.jpg"))  # no base present

            importer = Importer(_fake_enrollment(), d, dry_run=True, progress=lambda m: None)
            calls = []
            importer._upload_one = lambda rel_path, group_with: calls.append((rel_path, group_with)) or None
            importer.run()

            self.assertEqual(calls, [("IMG_5555-edited.jpg", None)])

    def test_strip_location_on_upload_runs_for_real_gps_bearing_jpeg(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "IMG_20220101_120000.jpg")
            im = Image.new("RGB", (32, 32))
            exif = im.getexif()
            gps = {1: "N", 2: (48.0, 51.0, 0.0), 3: "E", 4: (2.0, 21.0, 0.0)}
            exif_ifd = exif.get_ifd(0x8825)
            for k, v in gps.items():
                exif_ifd[k] = v
            im.save(path, format="JPEG", exif=exif)

            importer = Importer(
                _fake_enrollment(strip_location=True), d, dry_run=True, progress=lambda m: None
            )
            stats = importer.run()
            self.assertEqual(stats.errors, [])
            self.assertEqual(stats.location_stripped, 1)


def _minimal_mp4() -> bytes:
    def box(t: bytes, payload: bytes) -> bytes:
        return struct.pack(">I", 8 + len(payload)) + t + payload

    mvhd = box(b"mvhd", bytes(4) + struct.pack(">III", 0, 0, 1000) + bytes(80))
    moov = box(b"moov", mvhd)
    ftyp = box(b"ftyp", b"isom" + bytes(12))
    mdat = box(b"mdat", b"\x00" * 64)
    return ftyp + moov + mdat


if __name__ == "__main__":
    unittest.main()
