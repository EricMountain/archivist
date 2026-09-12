from __future__ import annotations

import os
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import mp4box
from _real_corpus import ENV_VAR, real_corpus_dir


def _box(box_type: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", 8 + len(payload)) + box_type + payload


def _mvhd(creation: int, version: int = 0) -> bytes:
    if version == 0:
        payload = bytes([0, 0, 0, 0]) + struct.pack(">III", creation, creation, 1000)
        payload += b"\x00" * 80  # rest of mvhd, contents irrelevant here
    else:
        payload = bytes([1, 0, 0, 0]) + struct.pack(">QQI", creation, creation, 1000)
        payload += b"\x00" * 80
    return _box(b"mvhd", payload)


def _tkhd(width: int, height: int, version: int = 0) -> bytes:
    if version == 0:
        payload = bytes([0, 0, 0, 0]) + b"\x00" * (4 * 4)  # ctime/mtime/track_id/reserved
        payload += struct.pack(">I", 0)  # duration
    else:
        payload = bytes([1, 0, 0, 0]) + b"\x00" * (8 + 8 + 4 + 4)
        payload += struct.pack(">Q", 0)
    payload += b"\x00" * 8  # reserved[2]
    payload += b"\x00" * (2 + 2 + 2 + 2)  # layer, alternate_group, volume, reserved
    payload += b"\x00" * 36  # matrix[9] * 4 bytes
    payload += struct.pack(">II", width << 16, height << 16)
    return _box(b"tkhd", payload)


def _hdlr(handler_type: bytes) -> bytes:
    payload = b"\x00" * 8 + handler_type + b"\x00" * 12
    return _box(b"hdlr", payload)


def _mdia(handler_type: bytes) -> bytes:
    return _box(b"mdia", _hdlr(handler_type))


def _trak(handler_type: bytes, width: int, height: int) -> bytes:
    return _box(b"trak", _tkhd(width, height) + _mdia(handler_type))


def _keys_entry(namespace: bytes, name: bytes) -> bytes:
    return struct.pack(">I", 8 + len(name)) + namespace + name


def _keys(entries: list[bytes]) -> bytes:
    payload = b"\x00\x00\x00\x00" + struct.pack(">I", len(entries)) + b"".join(entries)
    return _box(b"keys", payload)


def _ilst_index_entry(index: int, value: bytes) -> bytes:
    data_box = _box(b"data", struct.pack(">II", 1, 0) + value)
    return struct.pack(">I", 8 + len(data_box)) + struct.pack(">I", index) + data_box


def _ilst(entries: list[bytes]) -> bytes:
    return _box(b"ilst", b"".join(entries))


def _write(tmp_dir: str, name: str, data: bytes) -> str:
    path = os.path.join(tmp_dir, name)
    with open(path, "wb") as f:
        f.write(data)
    return path


class MvhdAndDims(unittest.TestCase):
    def test_creation_time_and_dims(self):
        # 2026-07-14T09:22:05Z, computed from the MP4 (1904) epoch.
        import datetime

        target = datetime.datetime(2026, 7, 14, 9, 22, 5, tzinfo=datetime.timezone.utc)
        seconds_since_1904 = int((target - mp4box._MP4_EPOCH).total_seconds())

        moov = _box(
            b"moov",
            _mvhd(seconds_since_1904) + _trak(b"soun", 0, 0) + _trak(b"vide", 1920, 1080),
        )
        data = _box(b"ftyp", b"isom" + b"\x00" * 12) + moov + _box(b"mdat", b"\x00" * 100)

        with tempfile.TemporaryDirectory() as d:
            path = _write(d, "t.mp4", data)
            probe = mp4box.probe_video(path)
            self.assertEqual(probe.creation_time, target)
            self.assertEqual((probe.width, probe.height), (1920, 1080))

    def test_moov_after_large_mdat(self):
        """The real corpus this tool imports has files with moov after mdat --
        confirm the walker doesn't assume the opposite ordering."""
        import datetime

        target = datetime.datetime(2020, 1, 2, 3, 4, 5, tzinfo=datetime.timezone.utc)
        seconds_since_1904 = int((target - mp4box._MP4_EPOCH).total_seconds())
        moov = _box(b"moov", _mvhd(seconds_since_1904) + _trak(b"vide", 640, 480))
        data = _box(b"ftyp", b"isom" + b"\x00" * 12) + _box(b"mdat", b"\x00" * 500_000) + moov

        with tempfile.TemporaryDirectory() as d:
            path = _write(d, "t.mp4", data)
            probe = mp4box.probe_video(path)
            self.assertEqual(probe.creation_time, target)
            self.assertEqual((probe.width, probe.height), (640, 480))

    def test_no_moov_returns_none(self):
        with tempfile.TemporaryDirectory() as d:
            path = _write(d, "t.mp4", _box(b"ftyp", b"isom"))
            probe = mp4box.probe_video(path)
            self.assertIsNone(probe.creation_time)
            self.assertIsNone(probe.width)


class LocationStripping(unittest.TestCase):
    def test_strips_loci(self):
        udta = _box(b"udta", _box(b"loci", b"\x01\x02\x03\x04GB"))
        moov = _box(b"moov", _mvhd(0) + udta)
        data = _box(b"ftyp", b"isom") + moov + _box(b"mdat", b"hello world")

        with tempfile.TemporaryDirectory() as d:
            src = _write(d, "src.mp4", data)
            dst = os.path.join(d, "dst.mp4")
            stripped = mp4box.strip_video_location(src, dst)
            self.assertTrue(stripped)
            self.assertEqual(os.path.getsize(src), os.path.getsize(dst))
            with open(dst, "rb") as f:
                out = f.read()
            self.assertNotIn(b"loci", out)
            self.assertIn(b"free", out)
            # mdat payload untouched, and at the same offset -- no shifting.
            self.assertIn(b"hello world", out)
            # Original file is never modified.
            with open(src, "rb") as f:
                self.assertIn(b"loci", f.read())

    def test_strips_xyz(self):
        udta = _box(b"udta", _box(b"\xa9xyz", b"+37.3349-122.0090/"))
        moov = _box(b"moov", _mvhd(0) + udta)
        data = _box(b"ftyp", b"isom") + moov + _box(b"mdat", b"payload")

        with tempfile.TemporaryDirectory() as d:
            src = _write(d, "src.mov", data)
            dst = os.path.join(d, "dst.mov")
            self.assertTrue(mp4box.strip_video_location(src, dst))
            with open(dst, "rb") as f:
                out = f.read()
            self.assertNotIn(b"\xa9xyz", out)

    def test_strips_quicktime_keys_ilst_matching_by_name_not_position(self):
        # Two keys entries; the location key is second, so its ilst sibling (also
        # second) must be the one zeroed -- proves this matches by name, not by
        # always picking the first entry.
        keys = _keys(
            [
                _keys_entry(b"mdta", b"com.android.version"),
                _keys_entry(b"mdta", mp4box._LOCATION_KEY),
            ]
        )
        ilst = _ilst(
            [
                _ilst_index_entry(1, b"9"),
                _ilst_index_entry(2, b"+51.5074-000.1278/"),
            ]
        )
        meta = _box(b"meta", b"\x00\x00\x00\x00" + _hdlr(b"mdta") + keys + ilst)
        moov = _box(b"moov", _mvhd(0) + meta)
        data = _box(b"ftyp", b"isom") + moov + _box(b"mdat", b"x")

        with tempfile.TemporaryDirectory() as d:
            src = _write(d, "src.mov", data)
            dst = os.path.join(d, "dst.mov")
            self.assertTrue(mp4box.strip_video_location(src, dst))
            with open(dst, "rb") as f:
                out = f.read()
            self.assertIn(b"com.android.version", out)  # unrelated key untouched
            self.assertIn(b"9", out)  # its value untouched
            self.assertNotIn(b"51.5074", out)  # the location value is gone

    def test_meta_without_fullbox_prefix(self):
        """QuickTime's own `meta` atom (as opposed to ISO/IEC 14496-12's) has no
        version/flags prefix -- children start immediately."""
        keys = _keys([_keys_entry(b"mdta", mp4box._LOCATION_KEY)])
        ilst = _ilst([_ilst_index_entry(1, b"+0.0000+0.0000/")])
        meta = _box(b"meta", _hdlr(b"mdta") + keys + ilst)  # no 4-byte prefix
        moov = _box(b"moov", _mvhd(0) + meta)
        data = _box(b"ftyp", b"isom") + moov + _box(b"mdat", b"x")

        with tempfile.TemporaryDirectory() as d:
            src = _write(d, "src.mov", data)
            dst = os.path.join(d, "dst.mov")
            self.assertTrue(mp4box.strip_video_location(src, dst))
            with open(dst, "rb") as f:
                out = f.read()
            self.assertNotIn(b"0.0000", out)

    def test_no_location_data_returns_false_and_copies_unchanged(self):
        moov = _box(b"moov", _mvhd(0))
        data = _box(b"ftyp", b"isom") + moov + _box(b"mdat", b"unrelated bytes")

        with tempfile.TemporaryDirectory() as d:
            src = _write(d, "src.mp4", data)
            dst = os.path.join(d, "dst.mp4")
            self.assertFalse(mp4box.strip_video_location(src, dst))
            with open(src, "rb") as fsrc, open(dst, "rb") as fdst:
                self.assertEqual(fsrc.read(), fdst.read())


class RealCorpusSmoke(unittest.TestCase):
    """Best-effort: exercises the real reader against a real source corpus, when
    ARCHIVIST_IMPORT_TEST_CORPUS points at one, purely as an extra confidence
    check -- skipped everywhere else (CI, another contributor's machine) rather
    than failing. See tests/_real_corpus.py."""

    def test_probe_every_real_video_without_crashing(self):
        corpus = real_corpus_dir()
        if corpus is None:
            self.skipTest(f"set {ENV_VAR} to a real photo directory to run this")
        videos = [
            os.path.join(corpus, n)
            for n in os.listdir(corpus)
            if n.rsplit(".", 1)[-1].lower() in ("mp4", "mov", "m4v")
        ]
        self.assertGreater(len(videos), 0)
        ok = 0
        for path in videos:
            probe = mp4box.probe_video(path)  # must not raise
            if probe.creation_time is not None:
                ok += 1
        # Measured in exploration: mvhd resolves cleanly for the large majority.
        self.assertGreater(ok, len(videos) * 0.5)


if __name__ == "__main__":
    unittest.main()
