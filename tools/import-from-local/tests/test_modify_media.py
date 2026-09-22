from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from modify_media import parse_taken_at_local, parse_taken_at_utc


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


if __name__ == "__main__":
    unittest.main()
