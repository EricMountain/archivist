package fr.enry.archivist.ui.detail

import androidx.exifinterface.media.ExifInterface
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** [exifTransform]'s mapping pulled out to a pure function so it's testable with no
 * Bitmap/Matrix (Android framework classes) involved -- same convention as
 * [DetailFormattingTest]. */
class ExifTransformTest {
    @Test
    fun `normal and undefined orientation need no correction`() {
        assertNull(exifTransform(ExifInterface.ORIENTATION_NORMAL))
        assertNull(exifTransform(ExifInterface.ORIENTATION_UNDEFINED))
    }

    @Test
    fun `plain rotations carry no flip`() {
        assertEquals(ExifTransform(rotationDegrees = 90f), exifTransform(ExifInterface.ORIENTATION_ROTATE_90))
        assertEquals(ExifTransform(rotationDegrees = 180f), exifTransform(ExifInterface.ORIENTATION_ROTATE_180))
        assertEquals(ExifTransform(rotationDegrees = 270f), exifTransform(ExifInterface.ORIENTATION_ROTATE_270))
    }

    @Test
    fun `mirror orientations rotate by zero`() {
        assertEquals(
            ExifTransform(rotationDegrees = 0f, flipHorizontal = true),
            exifTransform(ExifInterface.ORIENTATION_FLIP_HORIZONTAL),
        )
        assertEquals(
            ExifTransform(rotationDegrees = 0f, flipVertical = true),
            exifTransform(ExifInterface.ORIENTATION_FLIP_VERTICAL),
        )
    }

    @Test
    fun `transpose and transverse combine a rotation with a horizontal flip`() {
        assertEquals(
            ExifTransform(rotationDegrees = 90f, flipHorizontal = true),
            exifTransform(ExifInterface.ORIENTATION_TRANSPOSE),
        )
        assertEquals(
            ExifTransform(rotationDegrees = 270f, flipHorizontal = true),
            exifTransform(ExifInterface.ORIENTATION_TRANSVERSE),
        )
    }
}
