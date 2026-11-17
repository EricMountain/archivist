package fr.enry.archivist.ui.search

import fr.enry.archivist.data.remote.PlacesResponse
import fr.enry.archivist.domain.PlaceFold
import fr.enry.archivist.ui.detail.countryName
import java.text.NumberFormat
import java.util.Locale

/** The three place levels, named by their facet type (`GET /facets/{type}/{value}`). */
enum class PlaceLevel(val facetType: String) {
    COUNTRY("COUNTRY"),
    REGION("REGION"),
    LOCALITY("PLACE"),
}

/**
 * One row of the search screen's suggestion list. [facetValue] is what
 * `GET /facets/{level.facetType}/{facetValue}` takes: the `cc`, the admin1 key or the
 * geonameid. [title] is the place's own name ("Kyoto (region)" for a region); [context]
 * is what tells two same-named places apart (region and country for a town, country for
 * a region; null for a country).
 */
data class PlaceSuggestion(
    val level: PlaceLevel,
    val facetValue: String,
    val title: String,
    val context: String?,
    val n: Int,
) {
    /** The second line: context and count, "Kyoto, Japan · 2". */
    fun subtitle(locale: Locale = Locale.getDefault()): String =
        listOfNotNull(context, NumberFormat.getIntegerInstance(locale).format(n)).joinToString(" · ")

    /** The whole row as one line, "Kyoto · Japan · 2", for tests and accessibility. */
    fun text(locale: Locale = Locale.getDefault()): String = "$title · ${subtitle(locale)}"
}

/** The cap on suggestions shown: enough to scroll through for a short query like "a",
 * few enough that a few-thousand-place vocabulary never builds a long list per keystroke. */
const val MAX_PLACE_SUGGESTIONS = 50

/**
 * Geolocation plan step 8 (D6): the place vocabulary matched against [query] on the
 * device.
 *
 * **Matching.** Both sides go through [PlaceFold.fold] (case, accents, whitespace), and
 * the query must be a substring of one of the entry's names: a town's name, a region's
 * name, or a country's name in [locale] *or* in English, so "japan" still finds `JP` on
 * a French phone, and "allemagne" finds `DE` there. A town is not matched on its
 * region's or country's name: "japan" suggests Japan, not every town in it.
 *
 * **Ranking.** Prefix matches first, where a prefix is a match at the start of the name
 * or of any word in it ("kyo" in "Kyoto", "york" in "New York"); then by photo count,
 * descending; then by folded title, then level and id, so the order is total.
 *
 * **Empty query** (blank after folding): the top places by count, all levels mixed, in
 * the same order. The screen then opens on the places with the most photos rather than
 * an empty list.
 *
 * At most [limit] suggestions.
 */
fun searchPlaces(
    vocabulary: PlacesResponse,
    query: String,
    locale: Locale = Locale.getDefault(),
    limit: Int = MAX_PLACE_SUGGESTIONS,
): List<PlaceSuggestion> {
    val q = PlaceFold.fold(query)
    val candidates = ArrayList<Pair<PlaceSuggestion, Boolean>>()

    fun consider(
        suggestion: PlaceSuggestion,
        names: List<String>,
    ) {
        if (q.isEmpty()) {
            candidates += suggestion to false
            return
        }
        var matched = false
        var prefix = false
        for (name in names) {
            val folded = PlaceFold.fold(name)
            var at = folded.indexOf(q)
            while (at >= 0) {
                matched = true
                if (at == 0 || !folded[at - 1].isLetterOrDigit()) {
                    prefix = true
                    break
                }
                at = folded.indexOf(q, at + 1)
            }
            if (prefix) break
        }
        if (matched) candidates += suggestion to prefix
    }

    for (c in vocabulary.countries) {
        val local = countryName(c.cc, locale)
        val english = countryName(c.cc, Locale.ENGLISH)
        consider(PlaceSuggestion(PlaceLevel.COUNTRY, c.cc, local, null, c.n), listOf(local, english).distinct())
    }
    for (r in vocabulary.regions) {
        consider(
            PlaceSuggestion(PlaceLevel.REGION, r.id, "${r.name} (region)", countryName(r.cc, locale), r.n),
            listOf(r.name),
        )
    }
    for (l in vocabulary.localities) {
        val region = l.regionName?.takeIf { !it.equals(l.name, ignoreCase = true) }
        val context = listOfNotNull(region, countryName(l.cc, locale)).joinToString(", ")
        consider(PlaceSuggestion(PlaceLevel.LOCALITY, l.id, l.name, context, l.n), listOf(l.name))
    }

    return candidates
        .sortedWith(
            compareByDescending<Pair<PlaceSuggestion, Boolean>> { it.second }
                .thenByDescending { it.first.n }
                .thenBy { PlaceFold.fold(it.first.title) }
                .thenBy { it.first.level.ordinal }
                .thenBy { it.first.facetValue },
        ).take(limit)
        .map { it.first }
}
