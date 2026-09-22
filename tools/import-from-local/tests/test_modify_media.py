from __future__ import annotations

import io
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from PIL import Image

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


if __name__ == "__main__":
    unittest.main()
