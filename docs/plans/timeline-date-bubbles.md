# Timeline: immersive grid, continuous flow, floating date bubbles

A self-contained UI change to plan step 2.11's timeline screen. It doesn't touch the
data model, the paging/mediator layer, or the server.

Read `CLAUDE.md`, `android/AGENTS.md` (in full, before debugging any `./gradlew`
failure) and the 2.11 row of `STATUS.md` before starting.

## What the user asked for

1. **No date header rows.** While the grid is scrolling, and for 2 s after it stops, a
   semi-transparent date bubble floats over the top-left of certain rows (rules below).
2. **Photos flow continuously.** A row isn't tied to a date. Every row is filled.
3. **The grid fills the whole screen**, in immersive mode (system bars hidden, swipe from
   an edge to reveal them for a moment).
4. **The top bar goes.** The row that holds only the `⋮` menu is replaced by a floating
   `⋮` button.

## Decisions already made (don't reopen)

| Question | Decision |
| --- | --- |
| Which date labels a row | The **latest local date** among the row's photos (`max(PhotoEntity.localDate())`), not the first cell's. The list is sorted by UTC `takenAt` but dates are local (`tzOffsetMin`), so the two orders can disagree. |
| Which rows get a bubble | Row 0 always. Any other row whose date differs from the date of the row above it. |
| Top of the viewport | The topmost visible row **always** has a bubble, pinned to the top edge (sticky), even if its date matches the row above. The next labelled row pushes it up and off, like a sticky header handing over. |
| Rail drag | Bubbles show during rail scrubs/commits too. They're triggered by any change in scroll position, not only by `isScrollInProgress`. |
| Full screen | Immersive: `WindowInsetsControllerCompat.hide(systemBars())` with `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, **only while the grid itself is showing**. Detail, Settings, Enrolment, the spinner, and the Error/Empty states keep their normal bars and padding. |
| Menu position | Top-end, floating over the grid. The rail's touch track starts below it, so the button never covers the rail's "present" end. |
| Date format | `DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)`. |

**Known, accepted consequence.** A rail jump used to land on a day that started its own
row. Now the landing photo can sit mid-row, with newer photos (inserted by PREPEND) to
its left, so that row's bubble may show the newer date. That's fine. Mention it in
STATUS.md; don't try to fix it.

## Files

All paths are under `android/app/src/`.

* `main/kotlin/fr/enry/archivist/ui/timeline/TimelineViewModel.kt`
* `main/kotlin/fr/enry/archivist/ui/timeline/TimelineScreen.kt`
* `main/kotlin/fr/enry/archivist/ui/timeline/TimelineScrollbar.kt`
* `main/kotlin/fr/enry/archivist/ui/timeline/DateBubbles.kt` (new)
* `main/kotlin/fr/enry/archivist/MainActivity.kt`
* `main/kotlin/fr/enry/archivist/ui/trash/TrashViewModel.kt` (KDoc reference only)
* `test/kotlin/fr/enry/archivist/ui/timeline/TimelineViewModelTest.kt`
* `test/kotlin/fr/enry/archivist/ui/timeline/DateBubblesTest.kt` (new)
* `test/kotlin/fr/enry/archivist/ui/detail/DetailFormattingTest.kt` (KDoc reference only)
* `docs/design/android.md`, `docs/plans/STATUS.md`

## Steps

Do them in order. Each ends in a state that builds and passes tests.

### 1. Remove the header items

* `TimelineViewModel.kt`: delete `TimelineItem.Header`, `toTimelineItems()` and the
  `insertSeparators` import. Collapse `TimelineItem` entirely: with one variant it has no
  purpose. `timeline` becomes `Flow<PagingData<PhotoEntity>>`
  (`pagerGeneration.flatMapLatest { … }.cachedIn(viewModelScope)`).
* Change every `LazyPagingItems<TimelineItem>` to `LazyPagingItems<PhotoEntity>`:
  `TimelineScreen.kt` (`landingIndex`, `TimelineGrid`, `TimelineItemGrid`) and
  `TimelineScrollbar.kt` (`TimelineScrollbar`'s parameter, `nearestPhoto`).
  * `nearestPhoto` becomes `items.peek(i)` for the first non-null in its 3-item window.
    Update its KDoc: there are no more headers to skip.
  * `landingIndex`: drop the "land on the header instead" branch. It returns the photo's
    own index. Update its KDoc.
  * In the grid's `items(...)` call: `key = items.itemKey { it.photoId }`,
    `contentType = items.itemContentType { "photo" }`, and no `span` (every cell spans 1).
  * Delete `DateHeader` and `HEADER_FORMATTER`.
  * The `jumpCompleted` collector: the comment about "the header arrives and takes 0"
    no longer applies. Keep the `(index, item)` distinct (harmless), but rewrite that
    comment so it doesn't describe headers.
  * `TimelineItemGrid`'s preview selection: `items.peek(index)?.takeIf { it.preview != null }`.
* Fix the two KDoc-only references (`TrashViewModel.kt:23`, `DetailFormattingTest.kt:11`)
  so they don't name removed symbols.
* `TimelineViewModelTest.kt`: keep `localDate uses the offset, not UTC`. Delete the four
  header-insertion tests and their now-unused helpers (`snapshotOf`,
  `FixedPagingSource`, if nothing else uses them). The behaviour they covered moves into
  step 2's tests.

**Done when:** `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes, and
`grep -rn "TimelineItem\|DateHeader\|toTimelineItems" android/app/src` finds nothing.

### 2. Bubble placement as a pure function

Create `DateBubbles.kt`. Following this screen's existing convention
(`timelineContentState`, `TimelineScale`), keep the logic out of Compose so it can be
unit-tested. This repo has no Compose UI test harness.

Suggested shape (adjust names freely, not semantics):

```kotlin
/** One laid-out row as the grid currently has it. */
internal data class RowGeom(val row: Int, val topPx: Int, val dates: List<LocalDate?>)
// `dates`: one entry per cell, null = paging placeholder not loaded yet.

internal data class Bubble(val date: LocalDate, val yPx: Int, val row: Int)

internal fun placeBubbles(
    rows: List<RowGeom>,              // visible rows, ascending by row, from layoutInfo
    rowAboveFirst: List<LocalDate?>?, // cells of the row just above rows.first(), or null if rows.first().row == 0
    stickyTopPx: Int,                 // top inset + 8dp margin
    bubbleHeightPx: Int,
    gapPx: Int,                       // 4dp
    marginPx: Int,                    // 8dp, the offset from a row's top edge
): List<Bubble>
```

Rules:

* `rowDate(cells)` = `max` of the non-null dates. If **any** cell is null, the row has no
  known date: no bubble for it, and it doesn't count as a "difference" for the row
  below. Better to show nothing than a wrong date.
* A row other than the topmost is labelled iff `row == 0`, or its date differs from the
  previous row's date (the previous visible row, or `rowAboveFirst` for the first).
  Labelled rows get `yPx = topPx + marginPx`.
* **Sticky:** the topmost visible row (`rows.first()`) always gets a bubble with its own
  `rowDate`, at `y = min(max(topPx + marginPx, stickyTopPx), nextLabelledY - bubbleHeightPx - gapPx)`,
  where `nextLabelledY` is the `yPx` of the next labelled row below it (no constraint if
  there is none). It **replaces** that row's own bubble; the same row never gets two.
  If it's pushed wholly above the screen (`y + bubbleHeightPx <= 0`), drop it.
* Only emit rows whose bubble is at least partly on screen. The caller clips, so this
  is an optimisation, not a requirement.

`DateBubblesTest.kt`, at minimum:

* Row 0 is always labelled, even with nothing above it.
* Row date is the max, not the first cell: cells `[3 Mar, 5 Mar(+14:00 offset), 3 Mar]`
  get labelled 5 Mar. Build dates with `PhotoEntity.localDate()`-style offsets if you
  go through entities.
* Same date as the row above: no bubble. A different date: a bubble at `topPx + margin`.
* Sticky: when the top row is partly scrolled off (`topPx < 0`), its bubble sits at
  `stickyTopPx`.
* Sticky push-up: when the next labelled row's bubble is closer than
  `bubbleHeight + gap`, the sticky bubble's `y` is pushed up by exactly that overlap.
* Sticky with an unlabelled top row (same date as the row above): a bubble still shows,
  with that date.
* A placeholder in a row: that row gets no bubble, and the row after it isn't
  "different" just because of it.
* A repeated date across non-adjacent rows (A, B, A) labels all three. This is the
  non-monotonic tz case that used to crash on duplicate header keys.

### 3. Render the bubbles

In `TimelineGrid`'s `CONTENT` branch, add an overlay `Box(Modifier.fillMaxSize())`
**above** `TimelineItemGrid`, inside `TimelineScrollbar`'s content slot. The rail must
draw above the bubbles.

* Geometry comes from a `derivedStateOf` over `gridState.layoutInfo.visibleItemsInfo`.
  Group by `info.row`, `topPx = info.offset.y` of any cell in that row, and cell dates
  from `items.peek(info.index)?.localDate()`. Columns come from
  `visibleItemsInfo.maxOf { it.column } + 1`. `rowAboveFirst` is the `peek` of indices
  `[firstIndexOfTopRow - columns, firstIndexOfTopRow)`, clamped at 0. **Only ever
  `peek`**, never `items[i]`, which would trigger page loads from a drawing path.
* The bubble composable is a pill: `RoundedCornerShape(50)`,
  `MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.7f)` background, `labelMedium`
  in `inverseOnSurface`, horizontal padding 10dp, vertical 4dp. Place it with
  `Modifier.offset { IntOffset(marginPx, bubble.yPx) }`. Measure `bubbleHeightPx` once,
  with `onSizeChanged` on any bubble, or compute it from the text style. A fixed height
  modifier (e.g. `height(24.dp)`) is the simplest way to keep the placement math honest.
* Key each bubble on `row` (`key(bubble.row) { … }`) so a sticky bubble doesn't flicker
  on recomposition.
* No `clickable`/`pointerInput` anywhere in the overlay. Taps must fall through to
  photos. Verify this on the device.
* **Visibility:** `var bubblesVisible by remember { mutableStateOf(false) }`, driven by a
  `LaunchedEffect(gridState)` running
  `snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }.drop(1).collectLatest { bubblesVisible = true; delay(BUBBLE_LINGER_MS); bubblesVisible = false }`.
  Also keep them visible while `gridState.isScrollInProgress`, so a finger resting
  mid-drag doesn't hide them. `BUBBLE_LINGER_MS = 2000L`, as a named constant with a
  one-line KDoc. Use `drop(1)` so they don't flash on first composition. They *should*
  show after a jump's `scrollToItem`, since that's a position change.
* Fade with `AnimatedVisibility(visible, enter = fadeIn(tween(150)), exit = fadeOut(tween(300)))`
  around the whole overlay.

### 4. Floating menu button, and the rail inset

* `TimelineScreen.kt`: replace the `Column { Row { ⋮ } ; TimelineGrid(weight 1f) }` with
  a `Box(Modifier.fillMaxSize())`. It holds `TimelineGrid(Modifier.fillMaxSize())`, then
  the menu `Box` aligned `TopEnd`, padded by the top inset (step 5) + 8dp and 8dp from
  the end.
* The button: `Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)).clickable { showMenu = true }, contentAlignment = Center) { Text("⋮", style = titleLarge) }`.
  Keep the text glyph: the project has no material-icons dependency, and adding one
  isn't worth it here. The `DropdownMenu` stays anchored in the same `Box`.
  Content description: "More options".
* `TimelineScrollbar.kt`: add a `trackTopInset: Dp = 0.dp` parameter. The track `Box`
  (the one with `onSizeChanged { trackHeightPx = … }`, around line 405) and the
  `TimelineRail` `Box` get `padding(top = trackTopInset)`. The gesture is detected on
  the **outer** Box, whose coordinates include the inset, so:
  * `inStrip` must also require `it.y >= trackTopPx`, so touches beside the menu button
    fall through to the grid and the button.
  * Every `fractionAt(y, trackHeightPx)` call inside the gesture callbacks must use
    `y - trackTopPx`. Grep for `fractionAt(`: there were 3 call sites plus the definition
    when this plan was written. Check each one. Missing one shows
    up as the thumb sitting offset from the finger.
  * Pass `trackTopInset = topInset + 8.dp + 40.dp + 8.dp` (button bottom plus a gap)
    from `TimelineGrid`.

### 5. Immersive mode

* `MainActivity.kt`: `ArchivistApp` currently applies `Modifier.padding(innerPadding)`
  to `TimelineScreen`. Pass the padding in instead:
  `TimelineScreen(onSessionEnded = …, contentPadding = innerPadding)`. Inside
  `TimelineScreen`, every branch **except** the grid's `CONTENT` state keeps
  `Modifier.padding(contentPadding)`: EnrolmentScreen, spinner, DetailScreen,
  SettingsScreen, and TimelineGrid's ERROR/EMPTY branches. Find each current use of
  `modifier` in `TimelineScreen` and decide one by one.
* In `TimelineGrid`'s `CONTENT` branch, hide the bars:

  ```kotlin
  val view = LocalView.current
  DisposableEffect(view) {
      val window = (view.context as Activity).window // or findActivity() if context is wrapped
      val controller = WindowCompat.getInsetsController(window, view)
      controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      controller.hide(WindowInsetsCompat.Type.systemBars())
      onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
  }
  ```

  That branch leaves composition whenever Detail or Settings opens (they `return` before
  `TimelineGrid` is reached), so `onDispose` restores the bars for them automatically.
  Check this on the device; don't assume it.
* Insets while immersive: `WindowInsets.systemBars` reads 0 when the bars are hidden,
  which is what we want. Use `WindowInsets.displayCutout` for the top inset, so the
  first row, the sticky bubble, and the menu button clear a punch-hole camera. Pass it
  as `LazyVerticalGrid(contentPadding = PaddingValues(top = cutoutTop))`, and into
  `stickyTopPx` and the menu/rail offsets above. When the transient bars appear on a
  swipe they overlay the content and nothing re-lays out. That's the intended behaviour.
* The `LaunchedEffect(view)` that disables the View's own scrollbar stays as it is.

### 6. Docs and status

* `docs/design/android.md`, the Screens → Timeline bullet: replace "date headers by
  *local* day" with a short description of the immersive, continuously-flowing grid, its
  per-row floating date bubbles (latest *local* date in the row, sticky at the top), and
  the floating menu. Keep the "local day via `tzOffsetMin`, not UTC" point.
* `docs/plans/STATUS.md`, row 2.11: add a dated note saying what changed, what you ran
  (build, unit tests, which on-device checks), and anything you didn't verify. Include
  the jump-landing consequence from "Decisions" above.

## Verification (the whole change is done when all of these hold)

1. `./gradlew :app:assembleDebug :app:testDebugUnitTest` is green, including the new
   `DateBubblesTest`.
2. On the dev AVD that has a real session (see `android/AGENTS.md` for which one, and
   how to install over it without wiping the session: `adb install -r -t`, never
   `connectedDebugAndroidTest` there; always target it with `-s`):
   * The grid shows with no status or nav bar. Swiping from the top edge reveals the
     bars for a moment and nothing jumps.
   * Opening a photo and backing out: the bars are back in Detail and hidden again on
     return. The same for Settings.
   * Rows are fully packed across day boundaries.
   * Fling the grid: bubbles fade in, the top one is pinned at the top and gets pushed
     off by the next one, and they fade out about 2 s after the fling stops.
   * Drag the rail: bubbles show on the left while the rail's own pill shows on the
     right.
   * Taps on photos under a bubble still open the photo.
   * `⋮` opens the menu, and Settings works from it. A long press just below the button
     still engages the rail, and a touch right beside the button doesn't.
   * Screenshots at rest, mid-fling, and during a rail drag, attached to or described
     in the STATUS note.
3. `grep` for the maintainer's name, domain and hostnames finds nothing new (see
   CLAUDE.md, "Nothing personal in the committed tree").

## Out of scope

* Justified (aspect-ratio) rows. The grid stays `GridCells.Adaptive(96.dp)` squares.
* Immersive mode in DetailScreen.
* Any change to the paging, jump, or mediator logic beyond the header removal in
  step 1.
