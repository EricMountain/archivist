from __future__ import annotations

import datetime
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import filename_time as ft
from _real_corpus import ENV_VAR, real_corpus_dir


class ParsePatterns(unittest.TestCase):
    def test_img_underscore_form(self):
        self.assertEqual(ft.parse("IMG_20180713_140225.jpg"), datetime.datetime(2018, 7, 13, 14, 2, 25))

    def test_vid_form(self):
        self.assertEqual(ft.parse("VID_20190628_195032.mp4"), datetime.datetime(2019, 6, 28, 19, 50, 32))

    def test_bare_underscore_form(self):
        self.assertEqual(ft.parse("20150408_123456.jpg"), datetime.datetime(2015, 4, 8, 12, 34, 56))

    def test_edited_suffix_does_not_break_parsing(self):
        self.assertEqual(
            ft.parse("20200124_123456-edited.jpg"), datetime.datetime(2020, 1, 24, 12, 34, 56)
        )

    def test_google_photos_export_form(self):
        self.assertEqual(ft.parse("2018_0302_20492800.jpg"), datetime.datetime(2018, 3, 2, 20, 49, 28))

    def test_download_prefix(self):
        self.assertEqual(ft.parse("download_20200122_190327.jpg"), datetime.datetime(2020, 1, 22, 19, 3, 27))

    def test_screenshot_hyphen_time(self):
        self.assertEqual(
            ft.parse("Screenshot_20200215-125144.jpg"), datetime.datetime(2020, 2, 15, 12, 51, 44)
        )

    def test_whatsapp_form_defaults_to_noon(self):
        self.assertEqual(ft.parse("IMG-20180101-WA0001.jpg"), datetime.datetime(2018, 1, 1, 12, 0, 0))

    def test_bare_date_form(self):
        self.assertEqual(ft.parse("2019-03-05.jpeg"), datetime.datetime(2019, 3, 5, 12, 0, 0))

    def test_iso_with_offset_naive_part(self):
        self.assertEqual(
            ft.parse("2019-08-01T12_00_00+02_00.JPEG"), datetime.datetime(2019, 8, 1, 12, 0, 0)
        )

    def test_offset_extraction(self):
        self.assertEqual(ft.parse_offset_minutes("2019-08-01T12_00_00+02_00.JPEG"), 120)
        self.assertEqual(ft.parse_offset_minutes("2019-08-01T12_00_00-05_30.JPEG"), -330)
        self.assertIsNone(ft.parse_offset_minutes("IMG_20180713_140225.jpg"))

    def test_no_recognisable_pattern(self):
        self.assertIsNone(ft.parse("1e3148cf-11da-4c48-922e-3dcdcdb594b7.jpg"))
        self.assertIsNone(ft.parse("Rachel prise au CIV.mp4"))

    def test_implausible_date_shape_is_rejected_not_raised(self):
        # Looks like the pattern but month 99 isn't a real month -- must not raise.
        self.assertIsNone(ft.parse("29999_9999_99999999.jpg"))


class RealCorpusAgreement(unittest.TestCase):
    """Reproduces the exploration survey: for every real file that has both a
    filename timestamp and a real EXIF DateTimeOriginal, the two must agree
    closely almost all the time. Skipped unless ARCHIVIST_IMPORT_TEST_CORPUS is
    set -- see tests/_real_corpus.py."""

    def test_agrees_with_exif_on_real_corpus(self):
        try:
            from PIL import Image
        except ImportError:
            self.skipTest("Pillow not installed in this interpreter")
        corpus = real_corpus_dir()
        if corpus is None:
            self.skipTest(f"set {ENV_VAR} to a real photo directory to run this")

        compared = 0
        close = 0
        for name in os.listdir(corpus):
            if name.rsplit(".", 1)[-1].lower() in ("mp4", "mov", "m4v"):
                continue
            fn_dt = ft.parse(name)
            if fn_dt is None:
                continue
            try:
                with Image.open(os.path.join(corpus, name)) as im:
                    ifd = im.getexif().get_ifd(0x8769) or {}
                    raw = ifd.get(36867)
            except Exception:
                continue
            if not raw:
                continue
            try:
                exif_dt = datetime.datetime.strptime(raw.strip(), "%Y:%m:%d %H:%M:%S")
            except ValueError:
                continue
            compared += 1
            if abs((exif_dt - fn_dt).total_seconds()) <= 120:
                close += 1

        self.assertGreater(compared, 1000)  # sanity: the comparison actually ran
        self.assertGreater(close / compared, 0.9)


if __name__ == "__main__":
    unittest.main()
