from __future__ import annotations

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from PIL import Image

import thumbnails


def _jpeg_with_orientation(width: int, height: int, orientation: int | None) -> str:
    im = Image.new("RGB", (width, height), (255, 0, 0))
    exif = None
    if orientation is not None:
        exif = Image.Exif()
        exif[274] = orientation  # EXIF Orientation tag
    fd, path = tempfile.mkstemp(suffix=".jpg")
    os.close(fd)
    if exif is not None:
        im.save(path, format="JPEG", exif=exif)
    else:
        im.save(path, format="JPEG")
    return path


class GenerateForImage(unittest.TestCase):
    """generate_for_image must bake the source's own EXIF Orientation into the
    thumbnail pixels (via ImageOps.exif_transpose) -- WEBP export doesn't carry
    EXIF forward, so nothing downstream can correct for it otherwise. Width/height
    swapping is used as the observable proxy for "the pixels were actually
    rotated", since asserting on pixel content directly would be brittle."""

    def test_no_orientation_tag_leaves_dimensions_as_is(self):
        path = _jpeg_with_orientation(100, 60, orientation=None)
        try:
            thumbs = thumbnails.generate_for_image(path)
        finally:
            os.unlink(path)
        self.assertTrue(all(t.width >= t.height for t in thumbs))

    def test_orientation_1_normal_leaves_dimensions_as_is(self):
        path = _jpeg_with_orientation(100, 60, orientation=1)
        try:
            thumbs = thumbnails.generate_for_image(path)
        finally:
            os.unlink(path)
        self.assertTrue(all(t.width >= t.height for t in thumbs))

    def test_orientation_6_rotate_90_cw_swaps_dimensions(self):
        path = _jpeg_with_orientation(100, 60, orientation=6)
        try:
            thumbs = thumbnails.generate_for_image(path)
        finally:
            os.unlink(path)
        # Source is landscape (100x60); rotated 90 CW for display, every rung
        # should come out portrait.
        self.assertTrue(all(t.height >= t.width for t in thumbs))

    def test_orientation_8_rotate_90_ccw_swaps_dimensions(self):
        path = _jpeg_with_orientation(100, 60, orientation=8)
        try:
            thumbs = thumbnails.generate_for_image(path)
        finally:
            os.unlink(path)
        self.assertTrue(all(t.height >= t.width for t in thumbs))

    def test_orientation_3_upside_down_leaves_dimensions_as_is(self):
        path = _jpeg_with_orientation(100, 60, orientation=3)
        try:
            thumbs = thumbnails.generate_for_image(path)
        finally:
            os.unlink(path)
        # 180 degrees doesn't swap width/height, just flips both axes.
        self.assertTrue(all(t.width >= t.height for t in thumbs))


if __name__ == "__main__":
    unittest.main()
