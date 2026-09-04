package fr.enry.archivist.sync

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.enry.archivist.TestEntryPoint
import fr.enry.archivist.data.local.SyncSettings
import fr.enry.archivist.data.local.db.FolderSelectionEntity
import fr.enry.archivist.data.repo.EnrolmentStep
import java.io.File
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "ArchivistCrossDeviceReupload"
private const val BUCKET_NAME = "ArchivistCrossDeviceReupload"

/**
 * Ad hoc harness, not part of the ordinary plan-step suite — verifies plan step 2.13's
 * "a trashed entry that another source keeps re-offering shows the attempt warning" for
 * real, on a *second* device signed into the same account as whichever device originally
 * uploaded (then archive-only-trashed) the file. Same "never via
 * `./gradlew :app:connectedDebugAndroidTest`" caveat as `LoadTestInstrumentedTest` — that
 * task uninstalls the app-under-test when it finishes, which would wipe this device's own
 * enrolled session. Build and run directly:
 *
 * ```sh
 * ./gradlew :app:assembleDebugAndroidTest
 * adb -s <this device> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb -s <this device> push <exact-bytes-of-the-already-trashed-file>.jpg /sdcard/reupload_test.jpg
 * adb -s <this device> shell am instrument -w \
 *   -e class fr.enry.archivist.sync.CrossDeviceReuploadInstrumentedTest \
 *   -e sourcePath /sdcard/reupload_test.jpg \
 *   fr.enry.archivist.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * The pushed file must be the *exact* bytes of a file already uploaded (then archive-only
 * deleted — so its local tombstone lives only on the *original* device, not this one) from
 * another device on the same account: `ContentHash.of(hashSecret, ...)` is an HMAC over the
 * raw bytes with the owner's own `hashSecret` (shared across every device on the account),
 * so identical bytes here reproduce the identical `contentHash` and hit the same server-side
 * `HASH` pointer — `uploads.ts`'s `postUpload` records a blocked attempt against it
 * (`recordBlockedHashAttempt`) precisely because that pointer's asset is live-but-trashed
 * and this call doesn't set `reAddDeleted`. This device has no local tombstone or
 * `upload_queue` row for the file at all (it never scanned or uploaded it), which is the
 * whole point: nothing here defends against re-offering it, the way `design.md`'s "another
 * source keeps re-offering it" describes.
 */
@RunWith(AndroidJUnit4::class)
class CrossDeviceReuploadInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val entryPoint by lazy { TestEntryPoint.from(context) }

    // Block body, not `= runBlocking { ... }` -- the block's last statement is a
    // Log.i(...) call, which returns Int, not Unit; a JUnit4 @Test method must return
    // void, and an expression body would infer Int here and fail with
    // InvalidTestClassError at test-collection time (same class of gotcha AGENTS.md
    // documents for @Before/@After, just hitting @Test instead). A block body's
    // inferred return type is always Unit regardless of the last expression inside.
    @Test
    fun reuploadingAnAlreadyTrashedFileRecordsABlockedAttempt() {
        runBlocking {
            assumeTrue(
                "refusing to run: no instance is connected in this process",
                entryPoint.instanceStore().current.first() != null,
            )
            val step = entryPoint.enrolmentRepository().determineStep()
            assumeTrue(
                "refusing to run: couldn't silently unlock in this fresh instrumentation " +
                    "process (step=$step) -- unlock the device (PIN/biometric) within the last few minutes and retry",
                step == EnrolmentStep.Unlocked,
            )

            val sourcePath =
                InstrumentationRegistry.getArguments().getString("sourcePath")
                    ?: error("pass -e sourcePath <on-device path to the exact already-uploaded bytes>")
            val bytes = File(sourcePath).readBytes()
            Log.i(TAG, "Read ${bytes.size} bytes from $sourcePath")

            val resolver = context.contentResolver
            val values =
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "reupload_test_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/$BUCKET_NAME")
                }
            val uri =
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore insert failed")
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no output stream for $uri")

            val id = ContentUris.parseId(uri)
            val bucketId =
                resolver.query(uri, arrayOf(MediaStore.Images.Media.BUCKET_ID), null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) error("inserted row $id vanished before it could be read back")
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID))
                } ?: error("query against $uri returned nothing")
            Log.i(TAG, "Inserted as $uri in bucket $bucketId")

            entryPoint.folderSelectionDao().upsert(
                FolderSelectionEntity(
                    folderUri = bucketId,
                    displayName = BUCKET_NAME,
                    enabled = true,
                    addedAt = Instant.now().toString(),
                ),
            )

            val hashSecretReady = entryPoint.enrolmentRepository().ensureHashSecret()
            check(hashSecretReady.isSuccess) { "couldn't fetch/unwrap the owner's hash secret: ${hashSecretReady.exceptionOrNull()}" }

            val queued = entryPoint.scanner().scan().getOrThrow()
            Log.i(TAG, "Scanner queued $queued new file(s)")

            val activeIds = entryPoint.uploadQueueDao().getActiveIds()
            Log.i(TAG, "Enqueuing ${activeIds.size} active row(s) to UploadWorker")
            UploadWorker.enqueueAll(context, activeIds, SyncSettings())

            awaitQueueDrained(60_000)

            val row = entryPoint.uploadQueueDao().observeAll().first().find { it.localUri == uri.toString() }
            Log.i(TAG, "Final row: state=${row?.state} contentHash=${row?.contentHash} lastError=${row?.lastError}")
        }
    }

    private suspend fun awaitQueueDrained(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val active = entryPoint.uploadQueueDao().getActiveIds()
            if (active.isEmpty()) return
            delay(1_000)
        }
        Log.w(TAG, "timed out after ${timeoutMs}ms waiting for the queue to drain")
    }
}
