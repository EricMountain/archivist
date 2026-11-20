"""geocode.py: coordinate parsing, and that its lookup is the importer's own."""

from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import gazetteer
import geocode


class ParseTest(unittest.TestCase):
    def test_forms(self):
        self.assertEqual(geocode.parse_pairs(["45.7,4.8", "-33.86, 151.2", "1 2", "3", "-4"]),
                         [(45.7, 4.8), (-33.86, 151.2), (1.0, 2.0), (3.0, -4.0)])

    def test_rejects(self):
        for bad in (["12"], ["foo"], ["91,0"], ["0,181"]):
            with self.assertRaises(ValueError):
                geocode.parse_pairs(bad)


class LookupTest(unittest.TestCase):
    def test_matches_gazetteer(self):
        gaz = gazetteer.load()
        place, dist = geocode.lookup(gaz, 35.0116, 135.7681)
        self.assertEqual(place, gaz.lookup(35.0116, 135.7681))
        self.assertEqual(place["locality"]["id"], "1857910")
        self.assertLess(dist, 25)
        self.assertEqual(geocode.lookup(gaz, 0.0, -150.0), (None, None))


if __name__ == "__main__":
    unittest.main()
