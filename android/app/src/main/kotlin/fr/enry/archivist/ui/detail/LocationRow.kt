package fr.enry.archivist.ui.detail

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import fr.enry.archivist.data.remote.PlaceDto
import fr.enry.archivist.domain.ExifBlob
import fr.enry.archivist.domain.GeoPoint
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLEncoder
import java.util.IllformedLocaleException
import java.util.Locale

/**
 * Geolocation plan step 7: what the detail panel's location row shows. [primary] is the
 * place label, or the coordinates when there is no place; [secondary] is the coordinates
 * under a label. [point] is the precise position from the decrypted EXIF blob, and is
 * null when the row isn't tappable (a place with no coordinates: EXIF that didn't
 * decrypt, or an upload from before coordinates were recorded).
 */
internal data class LocationRowModel(
    val primary: String,
    val secondary: String?,
    val point: GeoPoint?,
    val label: String?,
    /** Protected areas and the natural feature from a region pack, " · "-separated. */
    val extras: String? = null,
)

/** Null when there is neither a place nor coordinates: no row at all. */
internal fun locationRow(
    place: PlaceDto?,
    exif: ExifBlob?,
    locale: Locale = Locale.getDefault(),
): LocationRowModel? {
    val point = exif?.let { e -> if (e.gpsLat != null && e.gpsLon != null) GeoPoint.of(e.gpsLat, e.gpsLon) else null }
    val label = place?.let { placeLabel(it, locale) }
    return when {
        label != null -> LocationRowModel(label, point?.let(::formatCoordinates), point, label, place.let(::placeExtras))
        point != null -> LocationRowModel(formatCoordinates(point), null, point, null)
        else -> null
    }
}

/**
 * "Spot, Sub, Locality, Region, Country", most specific first: "Le Planet, Argentière,
 * Chamonix-Mont-Blanc, Auvergne-Rhône-Alpes, France" with a region pack, "Kyoto, Japan" without.
 * A name equal to the one before it is left out ("Kyoto, Kyoto, Japan" says nothing
 * extra), and so is an absent region (about 460 GeoNames towns have no admin1, e.g.
 * Stanley in the Falklands). The country is never stored as a name (design.md
 * "Location"): it's rendered from `cc` in [locale], so it follows the viewer's language.
 */
internal fun placeLabel(
    place: PlaceDto,
    locale: Locale = Locale.getDefault(),
): String {
    val names = mutableListOf<String>()
    for (name in place.names + listOfNotNull(place.region?.name)) {
        if (names.none { it.equals(name, ignoreCase = true) }) names.add(name)
    }
    return (names + countryName(place.cc, locale)).joinToString(", ")
}

/** The region-pack levels that aren't part of the "where" chain: protected areas, then
 * the natural feature. Null when there are none. */
internal fun placeExtras(place: PlaceDto): String? =
    (place.areas.orEmpty().map { it.name } + listOfNotNull(place.feature?.name))
        .joinToString(" · ")
        .ifEmpty { null }

/** `Locale.Builder` rather than the `Locale(String, String)` constructor (deprecated
 * since JDK 19) or `Locale.of` (Android API 36+). Falls back to the bare code for a
 * value the JDK doesn't know, which `getDisplayCountry` itself also does. */
internal fun countryName(
    cc: String,
    locale: Locale = Locale.getDefault(),
): String =
    try {
        Locale.Builder().setRegion(cc).build().getDisplayCountry(locale).ifBlank { cc }
    } catch (_: IllformedLocaleException) {
        cc
    }

/**
 * `35.01161, 135.76804`: signed decimal degrees, latitude first, five decimals (about
 * 1 m), a `.` decimal point and `", "` between them whatever the device locale. Chosen
 * over a localized or hemisphere-letter form (`35.01161° N`) because it's what every
 * maps app's search box, `geo:` URIs and the laptop tools accept as-is, so the copied
 * text pastes anywhere; a `,` decimal separator would make the pair ambiguous.
 * Rounded half-up from the shortest decimal form of the double (`BigDecimal.valueOf`),
 * so `-0.000001` reads `0.00000`, never `-0.00000`.
 */
internal fun formatCoordinates(point: GeoPoint): String = "${fixed5(point.lat)}, ${fixed5(point.lon)}"

private fun fixed5(value: Double): String = BigDecimal.valueOf(value).setScale(5, RoundingMode.HALF_UP).toPlainString()

/**
 * `geo:0,0?q=<lat>,<lon>(<label>)`: the form Google Maps, OsmAnd and Organic Maps all
 * read as "drop a pin here, called this". Full precision, never rounded and never the
 * place's own centroid. The label is percent-encoded as UTF-8, with a space as `%20`
 * (not form-encoding's `+`, which some apps show literally) and `(`, `)` and `,`
 * escaped so they can't end the label or split the query early.
 */
internal fun geoUri(
    point: GeoPoint,
    label: String?,
): String {
    val q = "${plain(point.lat)},${plain(point.lon)}"
    val suffix = label?.let { "(" + URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20") + ")" } ?: ""
    return "geo:0,0?q=$q$suffix"
}

private fun plain(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

/**
 * The detail panel's location row. Tap opens the point in whatever maps app handles
 * `geo:`; with none installed, the coordinates go to the clipboard instead and a line
 * under the row says so. There is deliberately no web-map fallback: that would send the
 * photo's precise position to a third party. A long press copies the coordinates.
 */
@Composable
internal fun LocationRow(
    model: LocationRowModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var message by remember(model) { mutableStateOf<String?>(null) }
    val point = model.point
    val clickable =
        if (point == null) {
            Modifier
        } else {
            Modifier.combinedClickable(
                onClickLabel = "Open in maps app",
                onLongClickLabel = "Copy coordinates",
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(geoUri(point, model.label)))
                    message =
                        try {
                            context.startActivity(intent)
                            null
                        } catch (_: ActivityNotFoundException) {
                            copyCoordinates(context, point)
                            "No maps app installed. Coordinates copied."
                        }
                },
                onLongClick = {
                    copyCoordinates(context, point)
                    message = "Coordinates copied."
                },
            )
        }
    Column(modifier.fillMaxWidth().then(clickable)) {
        Text(
            model.primary,
            style = MaterialTheme.typography.bodySmall,
            color = if (point != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        model.extras?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        model.secondary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
    }
}

private fun copyCoordinates(
    context: Context,
    point: GeoPoint,
) {
    val clipboardManager = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboardManager.setPrimaryClip(ClipData.newPlainText("Coordinates", formatCoordinates(point)))
}
