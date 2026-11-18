# Timeline place filter: search from the `⋮` menu, filter the main timeline

Replaces geolocation step 8's separate search screen. Place search moves into the timeline's
floating `⋮` menu, and picking places **filters the main timeline in place**. The grid, the
date bubbles, the rail and the detail screen all stay; they just show only the photos taken
in the selected places. The filter applies **live** while you type and tick.

Read `CLAUDE.md`, `android/AGENTS.md` (in full, before debugging any `./gradlew` failure), the
2.11 row and the Geolocation section (steps 6 and 8) of `STATUS.md`, `timeline-date-bubbles.md`
and `geolocation.md` (D5, D6, "Search: options and trade-offs") before starting.

**No server change.** Everything here runs on endpoints that already exist (`GET /places`,
`GET /facets/{type}/{value}`) and on new client-side Room tables. `design.md`, `api.md` and
`sample-data.md` don't change. If an implementer finds they need a new endpoint or a new
server attribute, stop and ask (README ground rules, and F14 below).

## What the owner asked for

1. Search is triggered by tapping the `⋮` button. It expands to show the menu with Settings,
   plus a text field to search by location. Once the user taps the field, the menu fades out
   and the search results (place suggestions) appear as an overlay.
2. Photos are filtered **on the main timeline** according to the active search filter, not in
   a separate view.
3. **Several results can be selected** (GeoNames has "Paris 01 Louvre" … "Paris 16 Passy" as
   separate localities). By default every entry is selected.
4. One tap clears the filter.
5. The search bar disappears while scrolling, by swipe or by the rail. The rail should
   adjust to the filtered set of photos if possible.

**Owner's answers, 2026-11-17** (to the first draft's questions):

* The filter applies **live as you type and tick**, not on a "Show N photos" button. Debounce,
  cancel superseded loads, and the overlay still needs a way to dismiss to the filtered
  timeline.
* Back on a filtered timeline **clears the filter**.
* **Always load as you scroll.** Never bulk-download a selection (not even an id list for the
  rail), whatever its size or the network.
* **No facet data-model change**: facet items keep no `preview` and no `place`.
* `⋮` itself does **not** hide while scrolling. An empty query lists the top places, unticked,
  as quick picks.

## What exists today (verified 2026-11-17 by reading the code)

* `TimelineScreen.kt`: the `⋮` button is a 40 dp circle at top-end with a `DropdownMenu`
  holding **Search** and **Settings**. Search toggles `showSearch`, which replaces the whole
  timeline with `SearchScreen` (a plain local toggle, like Settings and Detail).
* `ui/search/`:
  * `PlaceSearch.kt`: pure `searchPlaces`, a folded substring match ranked prefix first, then
    by count; capped at 50; an empty query lists the top places.
  * `SearchViewModel.kt`: vocabulary status, the suggestions, and one `selected` place →
    `PlaceRepository.facetPhotos`.
  * `SearchScreen.kt`: the text field, the suggestion list, a results grid of `PhotoCell`, and
    its own `DetailScreen`, whose `swipeOrder` is a snapshot of the loaded results.
* `PlaceRepository`:
  * the vocabulary: `GET /places`, ETag, a one-row `place_vocabulary` table, so suggestions
    work offline;
  * `FacetPagingSource`: one facet stream, server cursor, no Room, page size 60 (server cap
    200), newest first. Facets accept `from`/`to` but have **no ascending order**.
* **Facet entries are thinner than timeline entries.** `facet_gsi` keeps `grid_projection`:
  no `preview` and no `place` (design.md "timeline_gsi"). On dev they have no `mime`/`status`
  either; `FacetEntryDto` defaults them.
* **Room does not hold the whole timeline.** `photos` is a *window*: a rail jump replaces the
  cache (`TimelineRemoteMediator.reseedAt`, `timeline_window.completeThrough`). `PhotoEntity`
  has `placeCc` and `placeKey` (the locality id, else the region id), but **no region id when
  a locality is present**.
* The rail:
  * `TimelineScrollbar` takes `bounds` and `histogram` and hides itself when
    `railScale(histogram, bounds)` is null.
  * The scale is a `RailScale` interface (`DensityScale` from a histogram, `TimeScale` from
    bounds).
  * The grid's leading cells (`leadingCells`/`HistogramRanks`/`LeadTracker`) read the
    histogram too.
  * A rail jump goes `TimelineViewModel.applyJump` → `PhotoRepository.jumpTo` (which can
    reseed `photos` over the network) → a `pagerGeneration` rebuild → `jumpCompleted` →
    `PinGridTo`.
* `DateBubbleOverlay` keeps its own "position changed in the last 2 s" state
  (`BUBBLE_LINGER_MS`), and the rail has its own peek/hold state. Nothing outside either one
  knows whether the grid is being scrolled.
* `DetailScreen` swipes over `DetailViewModel.photos` (all of `photos`, as a list) unless it
  is given a `swipeOrder` list.
* The repositories that change a cached photo: `TakenAtRepository`, `RotateRepository`,
  `RepairRepository` (`photoDao.upsertAll`), `LocationUpdateRepository` (the place columns)
  and `DeleteRepository` (`deleteByPhotoId`).

## Decisions

Numbered so STATUS notes and code comments can refer to them. Rows marked **owner
2026-11-17** record the owner's answers. The rest are this plan's proposals, consistent with
those answers.

| # | Question | Decision | Why |
| --- | --- | --- | --- |
| F1 | Data path | **Per-place facet streams, merged on demand, cached in Room.** One stream per selected place over `GET /facets/{COUNTRY\|REGION\|PLACE}/{id}`. Their pages go into a session cache in Room (`facet_streams` + `facet_stream_rows`). The grid pages over a merged query of the selected streams, cut off at a **watermark**. A `RemoteMediator` fetches the next page of whichever stream holds the watermark, **only when the grid asks** (APPEND). Details under "Data path". **Owner 2026-11-17: load as you scroll, never bulk.** | It's the k-way merge, so nothing loads ahead of the scroll. Room gives stable item-keyed paging, a detail swipe list, row patching, and reuse of streams between keystrokes: typing `par` → `pari` → `paris` refetches nothing already loaded. |
| F2 | Rail in filter mode | **A partial rail.** See "Rail: what's achievable". **Owner 2026-11-17: no bulk download for the rail.** | It's the best we can derive from what's loaded plus the vocabulary's exact per-place totals. |
| F3 | How the selection combines | The filter is the **union** of the selected entries' photos, deduplicated by `photoId`. There are no exclusions. | Each photo has exactly one country, at most one region and at most one town. Union is the only combination that needs no server help. |
| F4 | A place ticked along with places inside it | A ticked region **implies** its matched towns, and a ticked country its matched regions and towns. Those show as ticked and disabled, captioned "included". Only **maximal** entries (with no ticked ancestor) become streams. | A `REGION` facet already holds every photo of its towns. Maximal entries are pairwise disjoint, so the total N = Σ `n` is exact, which the rail needs (F2). |
| F5 | Town → region link | By **`(cc, regionName)`** against the `R#` rows' `(cc, name)`. Localities carry a `regionName` but no region id. An ambiguous or missing match groups the town under its country only. | Avoids an API change. A wrong link costs one redundant stream (dedup keeps the result right) and an odd grouping. |
| F6 | When the filter applies | **Live** (**owner 2026-11-17**). The selection is re-derived on every keystroke and tick; after **350 ms** of no change it becomes the active filter. The filtered pager is rebuilt with `flatMapLatest`, which cancels the superseded one. In-flight page fetches **for streams the new selection still uses keep running**, since they fill the shared cache; fetches for streams it dropped are cancelled, whether already sent or still queued. **Live apply needs at least 2 folded characters** (or a tick). Below that the suggestions update but the previous filter stands. The IME Search key applies at once, even for 1 character. **Coordinator amendment, 2026-11-17:** facet requests are capped at **6 in flight** across all streams (one semaphore in `PlaceFilterRepository`; a broad query's other first pages queue behind it), and a superseded selection must cancel the in-flight and queued requests of every stream it dropped. | Matches the owner's choice. The 2-character floor stops a 1-letter query fanning out to ~200 first-page requests per keystroke (see "Awkward consequences"); the cap keeps a broad query from opening dozens of connections at once. |
| F7 | What a query ticks by default | **Every visible match** is ticked. Unticks are remembered per entry while the overlay is open (an *excluded* set), so matches that appear as you keep typing arrive ticked. A place that doesn't match the current query is never in the filter. The overlay shows up to **200** matches (was 50); a footer says "Showing 200 of 340 — type more to narrow", and the rest are not included. An **empty query lists the top places unticked** as quick picks (owner 2026-11-17). An empty query with nothing ticked means **no filter** (the unfiltered timeline). **Coordinator amendment, 2026-11-17:** a non-empty query whose selection is empty (it matches no place, or every match is unticked) is **an active filter with no places**: the timeline shows nothing, never the unfiltered timeline. | Owner's point 3. What you see ticked is what you get. Falling back to everything on a typo, labelled "No filter", was misleading. |
| F8 | Active-filter indicator | A **filter chip** in the top band, left of `⋮`: "Kyoto · 4 ✕" for one place, "paris · 21 places · 1,912 ✕" for several (the query, the number of maximal entries, Σ `n`). **✕ clears.** Tapping the chip's body reopens the overlay with that query and selection. | One tap to clear, one tap to edit. The chip is the "search bar" that hides while scrolling. |
| F9 | Hiding while scrolling | The chip hides while the grid's position is changing, while the rail is held, and while the bubbles linger afterwards. It fades back when they fade. One shared scroll-activity state drives both. **`⋮` stays** (owner 2026-11-17). | The chip and the sticky date bubble share the top-left band, so they cross-fade rather than overlap. |
| F10 | Scroll position | Every change of the filter starts the filtered grid at its **newest** photo. Clearing returns to **exactly** the unfiltered position: the unfiltered pager, its `LazyGridState` and the `photos` cache are not touched in filter mode. Filtered rail jumps never call `PhotoRepository.jumpTo`. | Restoring the old position costs nothing that way. |
| F11 | Back | A filtered timeline in state A **clears the filter** (owner 2026-11-17). For the overlay, see "Back". | Same as "back out of search" in other gallery apps. |
| F12 | Bubble place names, previews | A cached row's `placeName`/`placeKey` come from the **most specific selected stream** that returned it: a town gives its name and id, a region its name and id, a country nothing (the bubble shows the date only). **No grid video previews** in filter mode; detail still plays the video. **Owner 2026-11-17: no facet data-model change.** | Bubbles still tell "Paris 04" from "Paris 05" without any server help. |
| F13 | Freshness | The stream cache lasts **one filter session**: it is emptied when the filter is cleared and on a cold start. While a filter is active, uploads and changes made elsewhere aren't seen until it is cleared and set again. This device's own edits (date, rotate, repair, location, delete) patch or remove the cached rows in place. | There's no per-facet ETag to revalidate against. A session cache is also what makes live typing cheap (F1). |
| F14 | Persistence and API | The filter **does not survive the process**; the app always opens unfiltered at the present. No new endpoint. | As `TimelineViewModel.init` does today. API changes need the owner. |
| F15 | The old search screen | **Removed.** `SearchScreen.kt` goes. `searchPlaces`, `PlaceSuggestion`, `PlaceLevel`, `PlaceFold`, the vocabulary sync and `FacetEntryDto.toEntity()` are reused. `SearchViewModel`'s vocabulary-status logic moves to the overlay's state holder. The `⋮` menu loses its "Search" item. | One way to search. |

## Interaction design

### States

| State | On screen | Reached by |
| --- | --- | --- |
| **A. Collapsed** | `⋮` at top-end. With a filter active, the chip in the same band, left of it. | Default; dismissing B or C. |
| **B. Expanded** | The button grows into a card anchored top-end, about `min(width − 16dp, 360dp)` wide: a "Search places" field (not focused, no keyboard), then the menu entries (**Settings**, and any future ones). The chip hides. | Tapping `⋮`. |
| **C. Searching** | The field takes focus and the keyboard opens. The menu entries **fade out** (150 ms) and the card widens to the full top band. Below it, the suggestion panel: grouped rows with checkboxes, at most **45 % of the height above the keyboard**, scrollable. **The live-filtered grid stays visible below the panel**, with no scrim, so the effect of each keystroke shows. The field has a clear (×) button; clearing the query unticks everything, so the grid goes back to unfiltered. | Tapping the field in B, or the chip's body in A (restores its query and selection). |

When there's a filter, A *is* the filtered timeline. There's no apply step to reach it.

Transitions use `AnimatedContent`/`animateContentSize` from the button's corner. There's no
navigation library; this follows the same plain-state pattern as the rest of `TimelineScreen`.

### The suggestion panel

* **Matching and ranking:** `searchPlaces`, unchanged apart from the cap (F7).
* **Grouping:** country → region → town, as far as the vocabulary allows (F5 for linking a
  town to its region). Groups are ordered by their best-ranked member, and members by rank.
  * An ancestor that matched is a real row with a checkbox.
  * One that didn't match is a plain header with an "All · None" control.
  * A region header appears only when a matched town sits under it.

  ```text
  France                                   All · None
    Île-de-France                          All · None
      ☑ Paris                       412
      ☑ Paris 04 Hôtel-de-Ville      96
      ☑ Paris 16 Passy               31
  United States
    Texas
      ☑ Paris                         3
  ```

  For `kyoto`: `☑ Kyoto (region) · 4`, with `☑ Kyoto · 4` under it, disabled and marked
  "included" (F4).
* **Tapping a row** toggles it, and the change applies after the debounce (F6).
* **A status line** above the list reads "1,912 photos in 21 places". During a load it shows
  a small progress indicator.
* **States:**
  * vocabulary loading;
  * "Couldn't load your places" with Retry, only when nothing is cached;
  * "No located photos yet";
  * "No places match." — the filter is then **active with an empty selection** (coordinator
    amendment, 2026-11-17; F7): the grid behind the panel is empty, with a short empty-state
    message, rather than falling back to the unfiltered timeline. Unticking every match
    gives the same state.

### Back and dismissing

| Where | Back / dismiss |
| --- | --- |
| C, keyboard open | Back closes the keyboard (system). The **IME Search** action applies at once and goes to A. |
| C, keyboard closed | Back goes to A, **keeping** the live filter. Tapping the grid below the panel does the same, and that tap doesn't open a photo. |
| B | Back or an outside tap collapses to A. The touch is consumed, as `DropdownMenu` does today. |
| A, filter active | Back **clears the filter** (F11), restoring the unfiltered position (F10). |
| A, no filter | As today: leaves the app. |
| Detail from the filtered grid | Back returns to the filtered grid at the same position. |

There's nothing to discard in C. Every change is already live, so undoing means ✕ or
editing the query.

### The filtered grid

* The same `TimelineGrid`, fed the filtered `LazyPagingItems`, its own `LazyGridState` and
  `LeadTracker`, and the partial rail scale (F2).
* **First load** (the selected streams' first pages, F1): the grid area shows a spinner, the
  chip is up, and in state C the status line shows progress.
* **End of loaded rows**: Paging's append fetches the next page as you scroll. An append
  error shows a Retry cell at the end.
* **Empty** (every selected stream came back empty because the vocabulary was stale): "No
  photos in these places any more." and a **Clear filter** button.
* **Empty selection** (the query matches no place, or every match is unticked; F7's
  coordinator amendment): no request at all; a short message ("No places selected.") and a
  **Clear filter** button.
* **Error before any row**: "Couldn't load photos for these places." with Retry and Clear
  filter.
* **Offline**: suggestions still work from the cached vocabulary. Streams already cached in
  this session still show what they have, up to their watermark. A new stream can't load, so
  that's the error state. There's no fallback to the `photos` cache: it's a window, and it
  can't answer a region filter.

## Data path

### How it works

* **A stream** is one maximal selected entry `(level, id)`. Its `facet_streams` row stores
  `cursor`, `oldestFetched` (`takenAt#photoId` of its last row), `done`, and `fetchedAt`.
* **Rows**: `facet_stream_rows(streamKey, photoId, …PhotoEntity display columns…, localDay)`,
  primary key `(streamKey, photoId)`. A photo can sit in a town stream and a region stream
  that F5 failed to link; the merged query collapses it.
* **The merged view** for a selection `S`:

  ```sql
  SELECT … FROM facet_stream_rows WHERE streamKey IN (:S) AND sortKey >= :watermark
  GROUP BY photoId ORDER BY takenAt DESC, photoId DESC
  ```

  The watermark is the **highest** `oldestFetched` among the unfinished streams in `S`, and
  "no limit" once all of them are `done`. A stream with no page yet blocks everything: rows
  show only once every selected stream has its first page. Above the watermark the merge is
  complete, so the grid never shows a gap that fills in later. New rows only ever appear
  below the last visible one, so there are no scroll jumps.
* **Paging**: `TimelinePagingSource`, generalised over a small DAO interface (F1). It is keyed
  on items (`takenAt#photoId`) and invalidated by Room on table writes.
* **`FacetMergeMediator`** (a `RemoteMediator`):
  * REFRESH fetches the first page of each selected stream that has none, at most **6 in
    flight** (coordinator amendment, F6: one cap across all streams, the rest queued), page
    size **60**. That's the grid's page and keeps the first paint cheap; a
    stream already cached from an earlier keystroke costs nothing.
  * APPEND fetches the next page of the stream holding the watermark. If several streams
    share the watermark it fetches each of them; if the watermark doesn't move it fetches
    again.
  * PREPEND reports end of pagination at once: newest-first streams have nothing above
    their first page.

  The latch problem the timeline's mediator has with jumps (see `pagerGeneration`) doesn't
  arise here: a filtered jump never goes to the network (F2).
* **Cancelling**: a new selection rebuilds the pager. Fetches for streams that are no longer
  selected are cancelled through the coroutine scope (Retrofit cancels the call), including
  those still queued for the in-flight cap. Fetches for streams still selected finish and
  land in the cache; they run in the repository's own scope, one per stream, so the new
  pager joins them instead of refetching.
* **Sizes**, to be measured in step 2: about **0.75 KB** of JSON per facet entry (three
  `thumbs` rungs, `encDek`, ids, dimensions), so a 60-entry page is about 45 KB. The first
  paint of a k-stream selection costs k requests (about k × 45 KB, less for small places).
  Scrolling costs about one request per 60 photos scrolled past, whatever the selection's
  size. DynamoDB cost is negligible (about 4 RCU per page).

### Options considered

| Option | Verdict |
| --- | --- |
| Live k-way merge with no Room (the first draft's (a)) | Works, but the paging key is a vector of cursors, nothing survives a restart, the detail swipe list is a snapshot, and every keystroke refetches. Room costs one migration and buys all of that back. |
| Bulk-download the selection into Room (the first draft's recommendation) | **Ruled out** by the owner, 2026-11-17. |
| Filter the cached timeline (`photos`) | The cache is a window, not the library, and has no region id beside a locality. **Rejected.** |
| New multi-facet endpoint | Not needed. It would also be an API decision for the owner. |

## Rail: what's achievable

What the client knows in filter mode:
* the **exact total N** = Σ `n` of the maximal entries, from the vocabulary;
* the **loaded rows** L above the watermark, each with its local day.

What it doesn't know:
* any date or density **below** the watermark. Facets have no ascending order and no
  per-month counts, and fetching them is the bulk download the owner ruled out.

| Option | What you get | What you lose |
| --- | --- | --- |
| Hide the rail | Nothing to build. | All fast scrolling in filter mode. |
| Time-linear rail over the library's bounds, jumping by reseeding each stream with `to=<date>` | Jumps to any date. | Most of the track is empty years: the filtered set's own span is unknown. A jump costs k requests and leaves gaps in the cache, which breaks the watermark model, so the timeline's window bookkeeping would have to be rebuilt per stream. **Rejected.** |
| **Partial rail** (recommended) | Count-weighted like the unfiltered rail. | See below. |

**The partial rail** works like this:

* **Track**: the top `L/N` of the track is the loaded part, drawn exactly as today: a
  `DensityScale` over the loaded rows' histogram, compressed into that fraction, with month
  ticks, day labels and the magnifier. The remaining `1 − L/N` is a plain, unlabelled
  segment.
* **Thumb**: at `topIndex / N`. That's exact, because N is exact and position is by photo
  count, as in the unfiltered `DensityScale`.
* **Dragging within the loaded part** jumps locally, in Room (`cachedLanding`'s rule; the
  filtered pager is rebuilt at that key, so `jumpCompleted`/`PinGridTo` work unchanged).
* **Dragging into the unloaded segment** holds the grid at the oldest loaded photo, and the
  pill reads "Loading older…". While the finger stays there it keeps fetching one page at a
  time, paced like today's scrubs. That is still loading as you scroll, just faster.
* **Not shown** until the first pages are in, or when N is unknown (no cached vocabulary).

What the user loses against the unfiltered rail:
* no jumping straight to a date that isn't loaded yet (e.g. from 2024 straight to 2015 in a
  big selection);
* no month labels or density over the unloaded part;
* reaching the oldest photos of a big selection still means paging through everything newer.

If N is stale (a photo was trashed or re-located elsewhere since the vocabulary was fetched),
the thumb can be slightly off. When every stream is done, the rail uses the loaded count for
N.

## Awkward consequences of live apply

* **Screen space.** The keyboard, the bar and a 45 % suggestion panel leave only a strip of
  grid on a phone, about two rows on a Pixel 8a. That's enough to see the filter change, not
  to browse. Browsing starts once the overlay is dismissed.
* **Request fan-out per keystroke.** Each settled query needs a first page from every newly
  selected stream. The floors: the 2-character minimum and the 350 ms debounce (F6), at most
  6 in flight, cancelling dropped streams, and the session cache, so narrowing a query costs
  nothing and widening it pays only for new places. A 2-letter query that matches 150 places
  still costs about 150 requests, mostly tiny ones. That's accepted, and recorded in STATUS
  with a measurement (step 2).
* **Ticks default to on, and the grid follows.** Typing `pa` briefly filters to "Paris, Pau,
  Palermo…" before `paris` narrows it. That is the live behaviour working as intended; it
  only looks odd.
* **Position resets on every change** (F10). It can't be helped: the filtered set itself
  changed.
* **"No match" shows an empty timeline** (F7, coordinator amendment 2026-11-17), so a typo
  shows nothing rather than everything; the panel says "No places match." The first draft's
  fallback to the unfiltered timeline, labelled "No filter", was judged misleading.
* **Dismissing can't be a button.** IME Search, Back or a tap on the visible grid all keep the
  filter; ✕ on the chip is the only "undo".

## Files

All paths are under `android/app/src/` unless stated.

* `main/kotlin/fr/enry/archivist/data/local/db/`:
  * `FacetStreamEntities.kt` (new: streams, rows, DAOs, the merged query);
  * `AppDatabase.kt` (v9, `MIGRATION_8_9`), plus the exported `9.json` schema.
* `main/kotlin/fr/enry/archivist/data/repo/`:
  * `PlaceFilterRepository.kt` (new: selection → pager, cache, clear, loaded histogram, local
    jumps, row patching);
  * `FacetMergeMediator.kt` (new);
  * `PlaceRepository.kt` (shared `facetUrl`, `toEntity`);
  * `TimelinePagingSource.kt` (generalised);
  * `TakenAtRepository.kt`, `RotateRepository.kt`, `RepairRepository.kt`,
    `LocationUpdateRepository.kt`, `DeleteRepository.kt` (patch or remove cached rows).
* `main/kotlin/fr/enry/archivist/ui/search/`:
  * `PlaceSearch.kt` (the cap);
  * `PlaceSelection.kt` (new, pure);
  * `PlaceSearchState.kt` (new, absorbs `SearchViewModel`);
  * `SearchOverlay.kt` (new);
  * `SearchScreen.kt` and `SearchViewModel.kt` (deleted).
* `main/kotlin/fr/enry/archivist/ui/timeline/`:
  * `TimelineViewModel.kt` (filter mode, debounce);
  * `TimelineScreen.kt`;
  * `MenuPanel.kt` (new);
  * `DateBubbles.kt` (hoisted scroll activity);
  * `TimelineScale.kt` (`PartialScale`);
  * `TimelineScrollbar.kt` (reports when it's held; the unloaded segment).
* `main/kotlin/fr/enry/archivist/ui/detail/DetailScreen.kt` and `DetailViewModel.kt` (the
  swipe source).
* The tests listed per step, under `test/` and `androidTest/`.
* `docs/design/android.md`, `docs/plans/STATUS.md`.

## Steps

Do them in order. Each ends building and passing
`./gradlew :app:assembleDebug :app:testDebugUnitTest`.

### 1. Selection model (pure)

**Goal:** everything the overlay decides, with no Compose and no network.

**Files:** `ui/search/PlaceSelection.kt` (new), `PlaceSearch.kt`,
`test/…/ui/search/PlaceSelectionTest.kt` (new), `PlaceSearchTest.kt`.

**Details:**
* `searchPlaces` takes the overlay cap (200) as its `limit` and reports the uncapped match
  count.
* `buildSelectionTree(vocabulary, matches, locale)`: country → region → town groups, as in
  "The suggestion panel", with F5 linking.
* `SelectionState(query, excluded, quickPicks)` gives `ticked`, `implied` (F4), `maximal`,
  `photoCount` (Σ `n` over `maximal`) and `liveApplicable` (F6: ≥ 2 folded characters, or a
  tick). It also has pure toggle and All/None functions.
* `toFilter()`: null (no filter) only for an empty query with nothing ticked; a non-empty
  query with an empty selection is a filter with no places (coordinator amendment,
  2026-11-17, F7).

**Done when** `PlaceSelectionTest` covers:
* a query matching nothing gives an active, empty filter, not "no filter" (amendment);
* `paris` with three French arrondissements and Paris, Texas: two country groups, an
  Île-de-France header, everything ticked, N = the sum;
* `kyoto`: the region ticked, the town implied and disabled, `maximal` = the region only;
* unticking the region leaves the town ticked and enabled;
* typing on keeps an exclusion, and a new match arrives ticked;
* an empty query ticks nothing, and a quick pick ticks one;
* All/None on an unmatched header;
* Stanley (a town with no region) sits under its country;
* an ambiguous region name falls back to the country;
* more than 200 matches: 200 shown, the footer count right, `maximal` limited to the shown
  entries;
* `liveApplicable` false for `p`, true for `pa` and for any tick.

`PlaceSearchTest` still passes.

### 2. Stream cache and merged paging

**Goal:** a selection pages correctly, newest first, loading only on demand, and reuses
streams across selections.

**Files:** `data/local/db/FacetStreamEntities.kt`, `AppDatabase.kt` (v9) + `9.json`,
`data/repo/FacetMergeMediator.kt`, `PlaceFilterRepository.kt`, `TimelinePagingSource.kt`,
`test/…/data/repo/FacetMergeMediatorTest.kt` and `PlaceFilterRepositoryTest.kt` (new),
`TimelinePagingSourceTest.kt`, `AppDatabaseMigrationTest.kt`.

**Details:**
* Implement "Data path / How it works", F12's place columns, and F13's clear (on clear, and
  on a cold start).
* Extract `TimelinePagingSource`'s queries (`pageFromKey`, `pageFromStart`, `pageAfter`,
  `pageBefore`, and the invalidation table) behind an interface. The timeline's existing
  tests are the guard that its behaviour doesn't change.
* `PlaceFilterRepository.pager(selection, initialKey)` gets its own `TimelineJumpCoordinator`
  instance.

**Done when** the tests (MockWebServer and the JVM Room driver, as `PlaceRepositoryTest`
uses) cover:
* two streams interleaved by date come out newest first, and no row is exposed before a
  later page would precede it;
* REFRESH fetches only first pages, and nothing more without an APPEND;
* APPEND fetches only the stream holding the watermark;
* a photo in two streams appears once, with the more specific place;
* reselecting a subset fetches nothing, and a superset fetches only the new streams;
* a cancelled selection's fetch for a dropped stream is cancelled, while one for a kept
  stream completes;
* at most 6 fetches are in flight at once (amendment);
* empty streams → an empty result;
* a 500 → `LoadResult.Error`, and a retry resumes from the stored cursor;
* `MIGRATION_8_9`: builds v8 from `8.json`, migrates, keeps `photos`, the histogram and the
  vocabulary, and round-trips a stream row;
* `TimelinePagingSourceTest` is unchanged and green.

**Measured and recorded in STATUS:** bytes per facet entry, ms per page, and request count
and time to first paint for a multi-stream selection on dev. Extrapolate where dev is too
small, and say so.

### 3. Timeline view model: filter mode, live

**Goal:** one view model switches the screen between unfiltered and filtered, applies
selection changes live, and leaves the unfiltered timeline alone.

**Files:** `ui/timeline/TimelineViewModel.kt`, `test/…/ui/timeline/TimelineViewModelTest.kt`.

**Details:**
* `selection: MutableStateFlow<SelectionState?>` →
  `debounce(350)` + `liveApplicable` → `activeFilter` → `flatMapLatest` to the filtered pager.
  `applyNow()` (IME Search) skips the debounce. `clearFilter()` empties the selection and the
  cache.
* `filteredTimeline` and `filteredRail` (N, the loaded histogram) sit alongside the existing
  flows. The unfiltered `timeline` keeps collecting (F10).
* `onScrubTo`/`onJumpCommitted` dispatch on the mode. The filtered path never reaches
  `PhotoRepository.jumpTo`. `jumpCompleted` is tagged with the mode.
* An empty selection (amendment, F7) is an active filter whose filtered timeline is empty and
  builds no pager.

**Done when** `TimelineViewModelTest` (virtual time) covers:
* three keystrokes within 350 ms → one pager build;
* `p` doesn't apply, `pa` does;
* `applyNow` applies at once;
* a superseded selection's pager is cancelled;
* the `photos` table and the unfiltered pager generation stay untouched throughout;
* a filtered jump never calls `PhotoRepository.jumpTo` (a fake);
* clear → no filter and an empty cache;
* `jumpCompleted` is tagged by mode.

### 4. Menu panel and suggestion overlay

**Goal:** the owner's points 1 and 3: states A–C, the panel, live updates, and Back.

**Files:** `ui/timeline/TimelineScreen.kt`, `MenuPanel.kt` (new),
`ui/search/SearchOverlay.kt` (new), `PlaceSearchState.kt` (new),
`test/…/ui/search/PlaceSearchStateTest.kt` (new),
`androidTest/…/ui/search/SearchOverlayUiTest.kt` (new).

**Details:**
* The states and the Back table are a pure reducer, unit-tested without Compose (the same
  convention as `timelineContentState`/`TimelineScale`).
* `PlaceSearchState` takes over the vocabulary status, `hasPlaces`, and revalidation on
  entering C.
* Immersive mode stays on. The panel's height is measured above `WindowInsets.ime`. Check on
  the device that the keyboard doesn't bring the bars back or shift the grid.
* Remove the "Search" menu item and the `showSearch` branch.

**Done when:**
* `PlaceSearchStateTest` covers every row of "Back and dismissing", including that a tap on
  the grid in C dismisses and doesn't open a photo, and that reopening from the chip restores
  the query and ticks.
* `SearchOverlayUiTest` (`createComposeRule`, a fake vocabulary) passes on the managed device
  with `./gradlew :app:pixel2api30DebugAndroidTest`. The local AVDs can't run Compose UI
  tests; see `android/AGENTS.md`. It checks that `paris` shows grouped, ticked rows and the
  right status-line count, unticking lowers it, and an implied town's box is disabled.

### 5. Filtered grid, chip, hide-on-scroll

**Goal:** the owner's points 2, 4 and 5 (the rail part comes in step 6).

**Files:** `ui/timeline/TimelineScreen.kt`, `DateBubbles.kt`, `TimelineScrollbar.kt`, and the
tests under `test/…/ui/timeline/`.

**Details:**
* Two `LazyPagingItems`, a second `LazyGridState` and a second `LeadTracker`. `TimelineGrid`
  renders whichever mode is active, with the states in "The filtered grid". For the lead in
  filter mode, use the loaded histogram's ranks, or no lead.
* Hoist the bubbles' linger into `rememberScrollActivity(gridState)`, shared with the chip.
  `TimelineScrollbar` reports `held`. The chip is visible when a filter is active, the state
  is A, and the grid isn't scrolling, held or lingering. Fade in 150 ms, out 300 ms.
* The chip sits at `topInset + 8dp`, from the start margin to `⋮`. The rail's
  `trackTopInset` is unchanged.

**Done when** a pure test pins the chip-visibility rule, `DateBubblesTest` is still green, and
device checks 3–7 below pass.

### 6. Partial rail

**Goal:** F2.

**Files:** `ui/timeline/TimelineScale.kt`, `TimelineScrollbar.kt`, `TimelineViewModel.kt`,
`PlaceFilterRepository.kt`, `test/…/ui/timeline/TimelineScaleTest.kt` (or the existing scale
tests' file), `TimelineScrollbarTest.kt`.

**Details:**
* `PartialScale(loaded: TimelineHistogram, total: Int) : RailScale`:
  * the loaded part maps through a `DensityScale` into `[0, L/N]`;
  * `dayAt`/`positionAt` beyond `L/N` return the oldest loaded day, flagged as "beyond";
  * ticks only in the loaded part.
* `TimelineScrollbar` draws the unloaded segment plain. While held in it, the pill reads
  "Loading older…" and it calls an `onHoldBeyond` that appends one page at a time, conflated
  like scrubs.
* Filtered jumps are answered in Room.

**Done when** the scale tests cover:
* fractions inside the loaded part match `DensityScale`'s, scaled;
* the beyond region;
* ticks only above `L/N`;
* N = L (everything loaded) behaves exactly like `DensityScale`;
* `TimelineScrollbarTest` is green, with the unfiltered rail's behaviour unchanged.

The device check confirms the hold-at-the-end loading. If the scale change can't be kept from
disturbing the unfiltered rail, ship filter mode with the rail **hidden**, and record that as
`partial` in STATUS rather than forcing it.

### 7. Detail swipe scope and row patching

**Goal:** F11's detail behaviour and F13's in-place edits.

**Files:** `ui/detail/DetailScreen.kt`, `DetailViewModel.kt`, and the five repositories named
in "Files", with their tests.

**Details:**
* `DetailScreen` takes a swipe *source* (timeline or filter) instead of a `swipeOrder` list.
* The filter source observes the merged query's loaded rows. Swiping to the last loaded photo
  asks the repository to append one page (on demand, as when scrolling).
* `TakenAt`/`Rotate`/`Repair`/`LocationUpdate` update the cached rows by `photoId`,
  recomputing `localDay`. `DeleteRepository` deletes them.

**Done when:**
* each repository test asserts the cached rows are patched or removed;
* a `DetailViewModel` test shows the filter source swiping only within the filter, and
  appending at its end.

### 8. Retire the old screen; docs and status

**Files:** `ui/search/SearchScreen.kt` and `SearchViewModel.kt` (deleted),
`docs/design/android.md`, `docs/plans/STATUS.md`.

* `grep -rn "SearchScreen\|SearchViewModel\|showSearch" android/app/src` finds nothing.
* `android.md`:
  * rewrite **Search (places)** as the menu panel, the live overlay and the filtered
    timeline: states, grouping, implied ancestors, live apply with debounce and the
    2-character floor, the chip, Back, offline;
  * amend **Timeline**: the chip, hide-on-scroll, the partial rail and what it can't do, and
    F12;
  * amend **Photo detail**: the swipe scope;
  * note under Architecture that the facet-stream tables are a client-only session cache,
    not a mirror of any server item.
* `design.md`, `api.md` and `sample-data.md`: no change. Re-check that this is still true.
* `STATUS.md`: each step's row says what was run and what wasn't.

## Verification on a device

Use the `Pixel_8a` AVD, which has the real dev session. Check it isn't sitting on "Sign in"
first (`android/AGENTS.md`).
* Install with `adb -s <serial> install -r -t`, and pass `-s` on every `adb` call.
* **Never** run `connectedDebugAndroidTest` there: it uninstalls the app and loses the
  session.
* Compose UI tests run on `pixel2api30` instead.

1. `⋮` expands to the card with the field and Settings. Settings still works, and Back or an
   outside tap collapses the card.
2. Tap the field: the menu fades, the keyboard opens, the top places are listed unticked, and
   the timeline still shows below. The bars stay hidden and nothing jumps.
3. Type `k`: nothing filters. Type `kyo`: shortly afterwards the strip of grid below shows
   only the Kyoto photos; the status line reads "4 photos", and Kyoto (region) is ticked with
   Kyoto implied.
4. Edit to `osa`: the grid swaps to Osaka. Change it quickly back and forth: logcat shows the
   dropped streams' requests cancelled and no stale grid. IME Search dismisses to the
   filtered timeline, and the bubbles read "… · Osaka".
5. Chip reads right. Scroll by swipe: the chip fades out, the bubbles show, and the chip
   returns after they fade. Hold the rail: the chip stays hidden, and the rail's loaded part
   has labels only for loaded months. Hold in the plain segment: "Loading older…" and the
   grid advances page by page.
6. Open a photo and swipe: it stays within the filter and loads more at the end. Back returns
   to the same filtered position.
7. ✕ on the chip returns to the unfiltered timeline at exactly the earlier position. Repeat
   with Back instead of ✕.
8. In airplane mode the overlay still suggests, and a previously loaded selection still shows
   its cached rows. A new place shows the error state with Retry and Clear. Reconnect and
   Retry: it loads.
9. Delete a photo from the filtered detail: it's gone from the filtered grid and, after
   clearing, from the timeline.
10. Take screenshots of B, of C with the live strip, of A filtered at rest, mid-scroll and
    with the rail held, and describe them in the STATUS notes.

Then `grep` for the maintainer's name, domain and hostnames; it should find nothing new
(CLAUDE.md).

## Open questions for the owner

None open. The first draft's five were answered on 2026-11-17 (see "Owner's answers" above).
The two live-apply numbers, the 350 ms debounce and the 2-character floor (F6), are
proposals. Tune them on the device in step 4 and record the values chosen in STATUS.

## Out of scope

* Filtering by anything but place, and AND across facet types.
* Excluding a sub-place.
* Persisting filters, saved searches, search history.
* Any server endpoint or data-model change: facet `preview`/`place` (owner, 2026-11-17: not
  now), or a multi-facet or keys-only listing.
* Date jumps beyond the loaded part of a filtered selection (F2).
