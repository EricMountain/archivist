from __future__ import annotations

import os
import sys
import unittest
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import takenat_ladder as ladder

NOW = datetime(2026, 9, 12, tzinfo=timezone.utc)


class ExifWithOffset(unittest.TestCase):
    def test_exif_offset_wins_over_gps(self):
        r = ladder.resolve(
            exif_date_time_original="2018:07:13 14:02:25",
            exif_offset_time_original="+09:00",
            exif_gps_utc=datetime(2018, 7, 13, 4, 0, 0, tzinfo=timezone.utc),  # would imply +10:00 via gps
            now=NOW,
        )
        self.assertEqual(r.taken_at_src, "exif")
        self.assertEqual(r.tz_src, "exif-offset")
        self.assertEqual(r.tz_offset_min, 540)
        self.assertEqual(r.taken_at, datetime(2018, 7, 13, 5, 2, 25, tzinfo=timezone.utc))


class ExifWithGpsOnly(unittest.TestCase):
    def test_gps_delta_rounds_to_15_minutes(self):
        r = ladder.resolve(
            exif_date_time_original="2018:07:13 14:02:25",
            exif_gps_utc=datetime(2018, 7, 13, 4, 32, 10, tzinfo=timezone.utc),
            now=NOW,
        )
        self.assertEqual(r.tz_src, "gps")
        # naive-as-utc(14:02:25) - gps(04:32:10) = 9h30m15s -> 570.25min -> rounds to 570
        self.assertEqual(r.tz_offset_min, 570)


class ExifWithNeither(unittest.TestCase):
    def test_falls_to_device_then_owner_default(self):
        r = ladder.resolve(exif_date_time_original="2018:07:13 14:02:25", now=NOW)
        self.assertEqual(r.tz_src, "assumed-utc")
        self.assertEqual(r.tz_offset_min, 0)

        r2 = ladder.resolve(
            exif_date_time_original="2018:07:13 14:02:25", device_default_offset_min=60, now=NOW
        )
        self.assertEqual(r2.tz_src, "device")
        self.assertEqual(r2.tz_offset_min, 60)

        r3 = ladder.resolve(
            exif_date_time_original="2018:07:13 14:02:25", home_tz="Europe/Paris", now=NOW
        )
        self.assertEqual(r3.tz_src, "owner-default")
        self.assertEqual(r3.tz_offset_min, 120)  # CEST in July


class NoExifAtAll(unittest.TestCase):
    def test_filename_rung_used_when_no_exif(self):
        r = ladder.resolve(
            filename_local=datetime(2015, 4, 8, 12, 34, 56),
            file_mtime=datetime(2026, 9, 10, tzinfo=timezone.utc),  # extraction-date mtime, must lose
            home_tz="Europe/Paris",
            now=NOW,
        )
        self.assertEqual(r.taken_at_src, "filename")
        self.assertEqual(r.tz_src, "owner-default")

    def test_filename_then_gps_delta_generalisation(self):
        # A file with GPS tags but no DateTimeOriginal (measured: 49 such files in
        # this tool's real corpus) still resolves rung 3 (gps) against the
        # filename-derived naive-local time.
        r = ladder.resolve(
            filename_local=datetime(2022, 2, 10, 16, 12, 9),
            exif_gps_utc=datetime(2022, 2, 10, 15, 12, 9, tzinfo=timezone.utc),
            now=NOW,
        )
        self.assertEqual(r.taken_at_src, "filename")
        self.assertEqual(r.tz_src, "gps")
        self.assertEqual(r.tz_offset_min, 60)

    def test_file_mtime_last_resort(self):
        r = ladder.resolve(file_mtime=datetime(2011, 3, 2, 19, 44, 10, tzinfo=timezone.utc), now=NOW)
        self.assertEqual(r.taken_at_src, "file-mtime")
        self.assertEqual(r.taken_at, datetime(2011, 3, 2, 19, 44, 10, tzinfo=timezone.utc))

    def test_nothing_at_all_returns_none(self):
        self.assertIsNone(ladder.resolve(now=NOW))

    def test_implausible_file_mtime_is_rejected(self):
        self.assertIsNone(ladder.resolve(file_mtime=datetime(1975, 1, 1, tzinfo=timezone.utc), now=NOW))
        self.assertIsNone(ladder.resolve(file_mtime=datetime(2030, 1, 1, tzinfo=timezone.utc), now=NOW))


class UploadForced(unittest.TestCase):
    def test_force_beats_exif_offset(self):
        r = ladder.resolve(
            exif_date_time_original="2018:07:13 14:02:25",
            exif_offset_time_original="+09:00",
            upload_offset=ladder.UploadOffsetHint(tz_offset_min=120, force=True),
            now=NOW,
        )
        self.assertEqual(r.tz_src, "upload-forced")
        self.assertEqual(r.tz_offset_min, 120)

    def test_fallback_mode_loses_to_exif_offset(self):
        r = ladder.resolve(
            exif_date_time_original="2018:07:13 14:02:25",
            exif_offset_time_original="+09:00",
            upload_offset=ladder.UploadOffsetHint(tz_offset_min=120, force=False),
            now=NOW,
        )
        self.assertEqual(r.tz_src, "exif-offset")

    def test_fallback_mode_wins_with_no_exif_evidence(self):
        r = ladder.resolve(
            file_mtime=datetime(2020, 1, 1, tzinfo=timezone.utc),
            upload_offset=ladder.UploadOffsetHint(tz_offset_min=-300, force=False),
            now=NOW,
        )
        self.assertEqual(r.tz_src, "upload")
        self.assertEqual(r.tz_offset_min, -300)


class ImplausibleExifFallsThrough(unittest.TestCase):
    def test_future_exif_date_falls_through_to_filename(self):
        r = ladder.resolve(
            exif_date_time_original="2099:01:01 00:00:00",  # implausible: garbled clock
            filename_local=datetime(2018, 7, 13, 14, 2, 25),
            now=NOW,
        )
        self.assertEqual(r.taken_at_src, "filename")


if __name__ == "__main__":
    unittest.main()
