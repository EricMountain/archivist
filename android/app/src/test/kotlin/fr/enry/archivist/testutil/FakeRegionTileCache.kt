package fr.enry.archivist.testutil

import fr.enry.archivist.data.repo.RegionTileCache

/** Counts clears; reports [size] until cleared. */
class FakeRegionTileCache(var size: Long = 0L) : RegionTileCache {
    var clears = 0
        private set

    override suspend fun sizeBytes(): Long = size

    override suspend fun clear() {
        clears++
        size = 0L
    }
}
