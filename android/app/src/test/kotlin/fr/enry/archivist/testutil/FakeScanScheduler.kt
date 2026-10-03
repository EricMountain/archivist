package fr.enry.archivist.testutil

import fr.enry.archivist.sync.ScanScheduler

/** Stands in for [fr.enry.archivist.sync.WorkManagerScanScheduler] -- no JVM unit test
 * environment has a real WorkManager. */
class FakeScanScheduler : ScanScheduler {
    var armCallCount = 0
    var scanNowCallCount = 0

    override suspend fun arm() {
        armCallCount++
    }

    override suspend fun scanNow() {
        scanNowCallCount++
    }
}
