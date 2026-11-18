package fr.enry.archivist.ui.search

import fr.enry.archivist.data.remote.PlacesResponse
import fr.enry.archivist.data.repo.FacetStreamSpec
import fr.enry.archivist.domain.PlaceFold
import fr.enry.archivist.ui.detail.countryName
import java.util.Locale

/**
 * Timeline place filter step 1: everything the suggestion overlay decides, with no
 * Compose and no network. See `docs/plans/timeline-place-filter.md` ("The suggestion
 * panel", F3–F7).
 *
 * A [SelectionTree] is the matches of one query grouped country → region → town. A
 * [SelectionState] is that tree plus what the user ticked, and derives the filter: the
 * **maximal** ticked entries (no ticked ancestor, F4), whose union is the filtered
 * timeline (F3). Maximal entries are pairwise disjoint, so their photo counts add up
 * exactly ([SelectionState.photoCount]).
 */

/** A town under its country or region. */
sealed interface CountryChild

/** A town: always a matched entry with a checkbox. */
data class TownRow(val entry: PlaceSuggestion) : CountryChild

/**
 * A region under its country. [entry] is the region's own row when the region itself
 * matched; null makes it a plain header ([title] is then the vocabulary's region name)
 * with "All · None" over [towns]. A header only exists because a matched town sits under
 * it (F5 links the town by `(cc, regionName)`).
 */
data class RegionGroup(
    val id: String,
    val title: String,
    val entry: PlaceSuggestion?,
    val towns: List<PlaceSuggestion>,
) : CountryChild {
    /** The key "All · None" and [SelectionState.setAll] take: the region's facet key. */
    val key: String get() = "${PlaceLevel.REGION.facetType}#$id"
}

/** A country and everything matched in it. [entry] null: a plain header, as for
 * [RegionGroup]. [children] are region groups and towns with no linked region, in rank
 * order (a group ranks as its best-ranked member). */
data class CountryGroup(
    val cc: String,
    val title: String,
    val entry: PlaceSuggestion?,
    val children: List<CountryChild>,
) {
    val key: String get() = "${PlaceLevel.COUNTRY.facetType}#$cc"
}

/**
 * The matches of one query, grouped. [shown] is the capped match list in rank order
 * (every entry with a checkbox appears in it exactly once); [total] is the uncapped
 * count, for the "Showing 200 of 340 — type more to narrow" footer.
 */
data class SelectionTree(
    val countries: List<CountryGroup>,
    val shown: List<PlaceSuggestion>,
    val total: Int,
) {
    /** For each shown entry's key, the keys of its *matched* ancestors (region, then
     * country). An unmatched ancestor is a header and can't be ticked, so it never
     * implies anything. */
    val ancestors: Map<String, List<String>> by lazy {
        buildMap {
            for (country in countries) {
                val countryKey = listOfNotNull(country.entry?.key)
                country.entry?.let { put(it.key, emptyList()) }
                for (child in country.children) {
                    when (child) {
                        is TownRow -> put(child.entry.key, countryKey)
                        is RegionGroup -> {
                            child.entry?.let { put(it.key, countryKey) }
                            val above = listOfNotNull(child.entry?.key) + countryKey
                            for (town in child.towns) put(town.key, above)
                        }
                    }
                }
            }
        }
    }

    /** Whether the overlay should show the "type more to narrow" footer. */
    val truncated: Boolean get() = total > shown.size

    /**
     * The shown entries under the group [groupKey] (a [CountryGroup.key] or
     * [RegionGroup.key]), not counting the group's own entry. What "All · None" on a
     * header acts on. Empty for a key that names no group.
     */
    fun descendantsOf(groupKey: String): List<PlaceSuggestion> {
        for (country in countries) {
            if (country.key == groupKey) {
                return country.children.flatMap { child ->
                    when (child) {
                        is TownRow -> listOf(child.entry)
                        is RegionGroup -> listOfNotNull(child.entry) + child.towns
                    }
                }
            }
            for (child in country.children) {
                if (child is RegionGroup && child.key == groupKey) return child.towns
            }
        }
        return emptyList()
    }

    companion object {
        val EMPTY = SelectionTree(emptyList(), emptyList(), 0)
    }
}

/**
 * Groups [matches] country → region → town ("The suggestion panel"), keeping the match
 * order as the rank: groups are ordered by their best-ranked member, members by rank.
 *
 * A town is linked to its region by **`(cc, regionName)`** against the vocabulary's
 * regions' `(cc, name)` (F5): localities carry a region *name*, not an id. No region of
 * that name in that country, or more than one, puts the town directly under its country.
 * A wrong link costs only an odd grouping and, if both are ticked, one redundant stream
 * (the merge dedups by `photoId`).
 */
fun buildSelectionTree(
    vocabulary: PlacesResponse,
    matches: PlaceMatches,
    locale: Locale = Locale.getDefault(),
): SelectionTree {
    val regionsByName = vocabulary.regions.groupBy { it.cc to it.name }
    val regionNames = vocabulary.regions.associate { it.id to it.name }
    val townRegion = vocabulary.localities.associate { l -> l.id to l.regionName?.let { regionsByName[l.cc to it] }?.singleOrNull()?.id }

    class RegionBuilder(val id: String, val rank: Int) {
        var entry: PlaceSuggestion? = null
        val towns = ArrayList<PlaceSuggestion>()
    }

    class CountryBuilder(val cc: String, val rank: Int) {
        var entry: PlaceSuggestion? = null
        val regions = LinkedHashMap<String, RegionBuilder>()

        /** Region groups and loose towns, each with the rank it first appeared at. */
        val children = ArrayList<Pair<Int, Any>>()
    }

    val countries = LinkedHashMap<String, CountryBuilder>()

    fun country(
        cc: String,
        rank: Int,
    ) = countries.getOrPut(cc) { CountryBuilder(cc, rank) }

    fun CountryBuilder.region(
        id: String,
        rank: Int,
    ) = regions.getOrPut(id) { RegionBuilder(id, rank).also { children += rank to it } }

    // `matches.shown` is in rank order, so the first time a group is touched is its best
    // rank, and appending keeps members in rank order too.
    matches.shown.forEachIndexed { rank, entry ->
        val c = country(entry.cc, rank)
        when (entry.level) {
            PlaceLevel.COUNTRY -> c.entry = entry
            PlaceLevel.REGION -> c.region(entry.facetValue, rank).entry = entry
            PlaceLevel.LOCALITY -> {
                val regionId = townRegion[entry.facetValue]
                if (regionId != null) {
                    c.region(regionId, rank).towns += entry
                } else {
                    c.children += rank to entry
                }
            }
        }
    }

    val groups =
        countries.values.map { c ->
            CountryGroup(
                cc = c.cc,
                title = c.entry?.title ?: countryName(c.cc, locale),
                entry = c.entry,
                children =
                    c.children.sortedBy { it.first }.map { (_, child) ->
                        when (child) {
                            is RegionBuilder ->
                                RegionGroup(
                                    id = child.id,
                                    title = child.entry?.title ?: regionNames[child.id] ?: child.id,
                                    entry = child.entry,
                                    towns = child.towns.toList(),
                                )
                            else -> TownRow(child as PlaceSuggestion)
                        }
                    },
            )
        }
    return SelectionTree(groups, matches.shown, matches.total)
}

/** [buildSelectionTree] for [query]: [matchPlaces] with the overlay's cap (F7), grouped. */
fun selectionTreeFor(
    vocabulary: PlacesResponse,
    query: String,
    locale: Locale = Locale.getDefault(),
    limit: Int = MAX_PLACE_SUGGESTIONS,
): SelectionTree = buildSelectionTree(vocabulary, matchPlaces(vocabulary, query, locale, limit), locale)

/** Live apply's floor (F6): fewer folded characters than this don't apply on their own,
 * so a one-letter query doesn't fan out to a first-page request per matching place. */
const val LIVE_APPLY_MIN_CHARS = 2

/**
 * What the overlay has ticked, over [tree] (the matches of [query]).
 *
 * **Defaults (F7).** Under a non-empty query every shown match is ticked except those in
 * [excluded], the entries the user unticked. The set outlives keystrokes (it's carried
 * by [withQuery]), so a match that appears as you keep typing arrives ticked, while one
 * you unticked stays unticked. Under an empty query the top places are listed as quick
 * picks, **unticked**, and [quickPicks] holds the ones the user ticked.
 *
 * **Implied (F4).** A ticked entry implies the matched entries under it: a region its
 * towns, a country its regions and towns. They show ticked and disabled ("included") and
 * aren't toggleable; only [maximal] entries become streams.
 *
 * [touched] is whether the latest change was a tick (toggle or All/None) rather than a
 * keystroke: a tick applies live even under the 2-character floor ([liveApplicable]).
 */
data class SelectionState(
    val query: String,
    val excluded: Set<String> = emptySet(),
    val quickPicks: Set<String> = emptySet(),
    val tree: SelectionTree = SelectionTree.EMPTY,
    val touched: Boolean = false,
) {
    private val foldedQuery: String by lazy { PlaceFold.fold(query) }

    /** Blank after folding: the quick-picks mode. */
    val isEmptyQuery: Boolean get() = foldedQuery.isEmpty()

    private fun directlyTicked(key: String): Boolean = if (isEmptyQuery) key in quickPicks else key !in excluded

    /** Entries ticked because an ancestor is ticked (F4): drawn ticked and disabled. */
    val implied: Set<String> by lazy {
        tree.shown
            .map { it.key }
            .filter { key -> tree.ancestors[key].orEmpty().any(::directlyTicked) }
            .toSet()
    }

    /** Every shown entry drawn with a tick, implied ones included. */
    val ticked: Set<String> by lazy { tree.shown.map { it.key }.filter { directlyTicked(it) || it in implied }.toSet() }

    /** The ticked entries with no ticked ancestor, in rank order: one facet stream each. */
    val maximal: List<PlaceSuggestion> by lazy { tree.shown.filter { directlyTicked(it.key) && it.key !in implied } }

    /** Σ `n` over [maximal]: exact, since maximal entries are disjoint (F4). */
    val photoCount: Int by lazy { maximal.sumOf { it.n } }

    /**
     * Whether this state may become the active filter after the debounce (F6): at least
     * [LIVE_APPLY_MIN_CHARS] folded characters, or a tick. An empty query also applies:
     * it has no fan-out, and clearing the field is how the grid goes back to unfiltered
     * (or to the quick picks). Below the floor the suggestions still update, but the
     * previous filter stands.
     */
    val liveApplicable: Boolean get() = touched || isEmptyQuery || foldedQuery.length >= LIVE_APPLY_MIN_CHARS

    /** Whether [key]'s checkbox can be toggled: an implied entry can't (F4). */
    fun isEnabled(key: String): Boolean = key !in implied

    /** Ticks or unticks one entry. A no-op for an implied entry or a key not shown. */
    fun toggle(key: String): SelectionState {
        if (key in implied || tree.shown.none { it.key == key }) return this
        return if (isEmptyQuery) {
            copy(quickPicks = quickPicks.toggled(key), touched = true)
        } else {
            copy(excluded = excluded.toggled(key), touched = true)
        }
    }

    /** "All" ([ticked] true) or "None" on the header [groupKey]: every shown entry under
     * it. */
    fun setAll(
        groupKey: String,
        ticked: Boolean,
    ): SelectionState {
        val keys = tree.descendantsOf(groupKey).map { it.key }.toSet()
        if (keys.isEmpty()) return this
        return if (isEmptyQuery) {
            copy(quickPicks = if (ticked) quickPicks + keys else quickPicks - keys, touched = true)
        } else {
            copy(excluded = if (ticked) excluded - keys else excluded + keys, touched = true)
        }
    }

    /**
     * The same selection over a new [query] and its [tree]. [excluded] carries over (F7);
     * [quickPicks] don't survive typing, and clearing the query unticks everything, so the
     * grid goes back to unfiltered. A keystroke is not a tick, so [touched] resets.
     */
    fun withQuery(
        query: String,
        tree: SelectionTree,
    ): SelectionState {
        val next = SelectionState(query, excluded, quickPicks, tree, touched = false)
        return if (next.isEmptyQuery && isEmptyQuery) next else next.copy(quickPicks = emptySet())
    }

    /**
     * The filter this state stands for, or null for **no filter**: an empty query with
     * nothing ticked (F7). A non-empty query whose selection is empty (nothing matched, or
     * everything unticked) is **an active filter with no places**: the timeline shows
     * nothing rather than falling back to everything (coordinator amendment, 2026-11-17).
     */
    fun toFilter(): PlaceFilter? =
        if (isEmptyQuery && maximal.isEmpty()) {
            null
        } else {
            PlaceFilter(query = query.trim(), places = maximal, photoCount = photoCount)
        }

    private fun Set<String>.toggled(key: String) = if (key in this) this - key else this + key

    companion object {
        /** A fresh overlay: nothing unticked, nothing picked. */
        fun initial(
            query: String,
            tree: SelectionTree,
        ) = SelectionState(query = query, tree = tree)
    }
}

/**
 * An applied place filter: the [places] (maximal entries) whose union the timeline
 * shows, the [query] the chip names, and [photoCount] (Σ `n`, F8). [places] empty is a
 * real filter that matches nothing (see [SelectionState.toFilter]).
 */
data class PlaceFilter(
    val query: String,
    val places: List<PlaceSuggestion>,
    val photoCount: Int,
) {
    /** The facet streams, as a set: two filters with the same streams page the same
     * rows, whatever their query. */
    val streamKeys: Set<String> get() = places.map { it.key }.toSet()

    val isEmptySelection: Boolean get() = places.isEmpty()

    /** One facet stream per place, for [fr.enry.archivist.data.repo.PlaceFilterRepository]. */
    val streams: List<FacetStreamSpec> get() = places.map { it.toStreamSpec() }
}

/** This place as a facet stream, carrying the place columns its rows show (F12): a town
 * its name and id, a region its name and id, a country neither. */
fun PlaceSuggestion.toStreamSpec(): FacetStreamSpec =
    FacetStreamSpec(
        facetType = level.facetType,
        value = facetValue,
        cc = cc.ifEmpty { null },
        placeName = if (level == PlaceLevel.COUNTRY) null else name,
        placeKey = if (level == PlaceLevel.COUNTRY) null else facetValue,
    )
