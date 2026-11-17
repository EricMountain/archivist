package fr.enry.archivist.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** The same table as `tools/import-from-local/tests/test_location.py`'s `Iso6709`. */
class Iso6709Test {
    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @MethodSource("table")
    fun `parses decimal-degree ISO 6709 and rejects everything else`(
        value: String?,
        expected: GeoPoint?,
    ) {
        assertEquals(expected, Iso6709.parse(value))
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("implausible")
    fun `GeoPoint rejects non-finite, out-of-range and exactly-zero positions`(
        lat: Double,
        lon: Double,
    ) {
        assertNull(GeoPoint.of(lat, lon))
    }

    companion object {
        @JvmStatic
        fun table() =
            listOf(
                Arguments.of("+35.0116+135.7681/", GeoPoint(35.0116, 135.7681)),
                Arguments.of("+35.0116+135.7681+012.5/", GeoPoint(35.0116, 135.7681, 12.5)),
                Arguments.of("+35.0116+135.7681", GeoPoint(35.0116, 135.7681)),
                Arguments.of("-33.8688-070.6693-005.250/", GeoPoint(-33.8688, -70.6693, -5.25)),
                Arguments.of("+48.8566+002.3522CRSWGS_84/", GeoPoint(48.8566, 2.3522)),
                Arguments.of("  +35.0116+135.7681/ ", GeoPoint(35.0116, 135.7681)),
                Arguments.of("+90.0000-180.0000/", GeoPoint(90.0, -180.0)),
                Arguments.of("+35+135/", GeoPoint(35.0, 135.0)),
                Arguments.of("", null),
                Arguments.of(null, null),
                Arguments.of("garbage", null),
                Arguments.of("35.0116+135.7681/", null), // no sign
                Arguments.of("+35.0116/", null), // no longitude
                Arguments.of("+3501.16+13546.08/", null), // degree-minute form: not supported
                Arguments.of("+95.0000+135.7681/", null), // latitude out of range
                Arguments.of("+35.0116+185.0000/", null), // longitude out of range
                Arguments.of("+35.0116+135.7681//", null),
                Arguments.of("+00.0000+000.0000/", null), // a placeholder / redacted fix
            )

        @JvmStatic
        fun implausible() =
            listOf(
                Arguments.of(Double.NaN, 1.0),
                Arguments.of(1.0, Double.POSITIVE_INFINITY),
                Arguments.of(90.5, 1.0),
                Arguments.of(1.0, -180.5),
                Arguments.of(0.0, 0.0),
            )
    }
}
