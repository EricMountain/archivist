package fr.enry.archivist.domain

/**
 * A photo or video's recorded position: decimal degrees (WGS84) and metres. What
 * [ExifExtractor] reads off a still and [Iso6709] off a video, what [ExifBlob] carries as
 * `gpsLat`/`gpsLon`/`gpsAltM`, and what [ReverseGeocoder] turns into a [Place].
 */
data class GeoPoint(
    val lat: Double,
    val lon: Double,
    val altM: Double? = null,
) {
    companion object {
        /**
         * Null unless the coordinates are finite, in range and not exactly (0, 0). Exactly
         * zero is what a placeholder or a redacted GPS block decodes to (the platform
         * zeroes location in a file read without `ACCESS_MEDIA_LOCATION`), never a real fix
         * in practice. A non-finite altitude is dropped, keeping the position. Same rule as
         * `tools/import-from-local/mp4box.py`'s `plausible_coordinates`.
         */
        fun of(
            lat: Double,
            lon: Double,
            altM: Double? = null,
        ): GeoPoint? {
            if (!lat.isFinite() || !lon.isFinite()) return null
            if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
            if (lat == 0.0 && lon == 0.0) return null
            return GeoPoint(lat, lon, altM?.takeIf { it.isFinite() })
        }
    }
}

/**
 * The ISO 6709 location string a video container carries, as
 * `MediaMetadataRetriever.METADATA_KEY_LOCATION` returns it: `+35.0116+135.7681/`, or with
 * an altitude, `+35.0116+135.7681+012.5/`.
 */
object Iso6709 {
    private val PATTERN =
        Regex("""^([+-]\d{2}(?:\.\d+)?)([+-]\d{3}(?:\.\d+)?)([+-]\d+(?:\.\d+)?)?(?:CRS[^/]*)?/?$""")

    /**
     * Decimal degrees only (`±DD.D±DDD.D`), which is what phone encoders write. The
     * trailing `/` and a `CRS…` suffix are optional. The degree-minute forms ISO 6709 also
     * allows, an implausible value ([GeoPoint.of]), or anything else gives null rather than
     * a guess. Mirrors `tools/import-from-local/mp4box.py`'s `parse_iso6709`; both have the
     * same test table.
     */
    fun parse(value: String?): GeoPoint? {
        val match = PATTERN.matchEntire(value?.trim() ?: return null) ?: return null
        val (lat, lon, alt) = match.destructured
        return GeoPoint.of(lat.toDouble(), lon.toDouble(), alt.ifEmpty { null }?.toDouble())
    }
}
