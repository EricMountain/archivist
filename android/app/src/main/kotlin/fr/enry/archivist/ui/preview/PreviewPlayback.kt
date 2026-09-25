package fr.enry.archivist.ui.preview

/** How long the preview takes to fade to black at the end of each loop. */
const val PREVIEW_FADE_MS = 500L

/** How many previews the grid may play at once. Devices expose a small number of
 * concurrent hardware video decoders, and every playing cell also costs battery and a
 * download; the rest of the visible cells keep showing their still. */
const val MAX_CONCURRENT_GRID_PREVIEWS = 4

/** How long the grid must sit still before any preview starts, so a fling never spins up
 * (and immediately tears down) players for cells that scroll straight past. */
const val GRID_PREVIEW_SETTLE_MS = 300L

/**
 * The opacity (0..1) of the black overlay drawn over the preview at [positionMs] into a
 * clip of [durationMs]: 0 until the last [fadeMs], then a linear ramp to 1 at the very end.
 * The player then loops back to the start, which is a hard cut from black to frame 0 (the
 * design's "fade out, then restart" — see design.md, "Video preview clip").
 *
 * A clip shorter than twice [fadeMs] fades over half its length instead, so a very short
 * clip still shows some picture. An unknown or non-positive duration (ExoPlayer reports a
 * negative `C.TIME_UNSET` until prepared) never fades. Pure and Android-free so it's
 * covered by a plain JVM test.
 */
fun fadeOutAlpha(
    positionMs: Long,
    durationMs: Long,
    fadeMs: Long = PREVIEW_FADE_MS,
): Float {
    if (durationMs <= 0L) return 0f
    val fade = minOf(fadeMs, durationMs / 2).coerceAtLeast(1L)
    val start = durationMs - fade
    if (positionMs <= start) return 0f
    return ((positionMs - start).toFloat() / fade).coerceIn(0f, 1f)
}

/**
 * Which visible grid items may hold a player: the first [max] (in grid order) that have a
 * preview at all. [hasPreview] is asked per index of [visibleIndices]; `false` for a
 * placeholder that hasn't loaded, a header, or a still. Empty while [scrolling] — playing
 * only ever starts once the grid settles, and stops the instant it moves again. Pure and
 * Android-free.
 */
fun selectPlayingIndices(
    visibleIndices: List<Int>,
    hasPreview: (Int) -> Boolean,
    scrolling: Boolean,
    max: Int = MAX_CONCURRENT_GRID_PREVIEWS,
): Set<Int> {
    if (scrolling) return emptySet()
    return visibleIndices.filter(hasPreview).take(max).toSet()
}
