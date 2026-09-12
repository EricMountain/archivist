from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import media_probe as mp
from _real_corpus import ENV_VAR, real_corpus_dir


class SuffixStripping(unittest.TestCase):
    def test_single_suffix(self):
        self.assertEqual(mp.strip_one_edit_suffix("04_12_2014 - 1-edited"), "04_12_2014 - 1")

    def test_no_suffix(self):
        self.assertIsNone(mp.strip_one_edit_suffix("IMG_1234"))

    def test_suffix_with_disambiguating_number(self):
        self.assertEqual(mp.strip_one_edit_suffix("IMG_1234-edited(1)"), "IMG_1234")

    def test_chained_suffixes(self):
        chain = mp.base_stem_chain("2015 - 3-EFFECTS-edited")
        self.assertEqual(chain, ["2015 - 3-EFFECTS-edited", "2015 - 3-EFFECTS", "2015 - 3"])

    def test_plain_stem_chain_is_itself_only(self):
        self.assertEqual(mp.base_stem_chain("IMG_1234"), ["IMG_1234"])

    def test_does_not_strip_a_suffix_mid_string(self):
        # "-edited" must anchor at the end, not match a coincidental substring earlier
        # in the name.
        self.assertIsNone(mp.strip_one_edit_suffix("edited-report"))


class RoleAndMime(unittest.TestCase):
    def test_raw_and_video_classification(self):
        self.assertIn("cr3", mp.RAW_EXTS)
        self.assertTrue(mp.is_video("MOV"))
        self.assertFalse(mp.is_video("jpg"))
        self.assertTrue(mp.is_image("jpg"))
        self.assertFalse(mp.is_image("xmp"))

    def test_raw_is_never_also_an_image(self):
        # Regression: is_image used to have no RAW exclusion at all, so a RAW
        # file would be handed to Pillow's Image.open (which can't decode it)
        # instead of being skipped for EXIF/thumbnails the way RAW is supposed
        # to be.
        for ext in mp.RAW_EXTS:
            with self.subTest(ext=ext):
                self.assertTrue(mp.is_raw(ext))
                self.assertFalse(mp.is_image(ext))
                self.assertFalse(mp.is_video(ext))

    def test_mime_table(self):
        self.assertEqual(mp.mime_for_ext("JPG"), "image/jpeg")
        self.assertEqual(mp.mime_for_ext("mp4"), "video/mp4")
        self.assertEqual(mp.mime_for_ext("totally-unknown"), "application/octet-stream")


class RealCorpusPairing(unittest.TestCase):
    """Reproduces the exploration survey: of every file matching a known edit
    suffix, (measured) 653/655 have a same-directory base sibling under some
    extension. Skipped unless ARCHIVIST_IMPORT_TEST_CORPUS is set -- see
    tests/_real_corpus.py."""

    def test_pairing_rate_on_real_corpus(self):
        corpus = real_corpus_dir()
        if corpus is None:
            self.skipTest(f"set {ENV_VAR} to a real photo directory to run this")

        names = [n for n in os.listdir(corpus) if not n.startswith(".")]
        stems_present = {n.rsplit(".", 1)[0] for n in names}

        suffixed = 0
        has_base = 0
        for n in names:
            stem, _, ext = n.rpartition(".")
            if not stem:
                continue
            base = mp.strip_one_edit_suffix(stem)
            if base is None:
                continue
            suffixed += 1
            if base in stems_present:
                has_base += 1

        self.assertGreater(suffixed, 600)
        self.assertGreater(has_base / suffixed, 0.99)


if __name__ == "__main__":
    unittest.main()
