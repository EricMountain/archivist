package fr.enry.archivist.sync

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.enry.archivist.TestEntryPoint
import fr.enry.archivist.data.local.db.FolderSelectionEntity
import fr.enry.archivist.data.local.db.UploadState
import fr.enry.archivist.data.repo.EnrolmentStep
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "ArchivistLoadTest"
private const val BUCKET_NAME = "ArchivistLoadTest"

/**
 * Not part of the ordinary plan-step test suite — an ad hoc harness for populating a
 * real `dev` account with many real photos, to exercise plan step 2.11's own literal
 * "Done when" ("1,000 photos scroll smoothly...") against real data rather than a fake.
 * See STATUS.md's 2.11 row for why that couldn't be tested any other way: seeding
 * DynamoDB/S3 directly would need the account's master key, which never leaves the
 * device's memory and this session never had.
 *
 * **Deliberately does not touch [fr.enry.archivist.data.local.InstanceStore]/
 * [fr.enry.archivist.data.repo.MasterKeyHolder]** — unlike `UploadWorkerInstrumentedTest`,
 * which fakes a throwaway session specifically to avoid touching a live one, this test's
 * entire point is to use whatever's *actually* signed in right now, so the photos land
 * in the real account and show up in the real running app afterward. Refuses to run at
 * all if nothing is signed in, rather than silently doing nothing useful.
 *
 * **Never run this via `./gradlew :app:connectedDebugAndroidTest`** — that Gradle task
 * uninstalls the app-under-test when it finishes regardless of which test ran, which
 * previously wiped a real enrolled session (see STATUS.md's "Last audit" for 2026-08-31).
 * Build and run it directly instead, bypassing Gradle's install/uninstall lifecycle:
 *
 * ```sh
 * ./gradlew :app:assembleDebugAndroidTest
 * adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb shell am instrument -w \
 *   -e class fr.enry.archivist.sync.LoadTestInstrumentedTest \
 *   -e photoCount 50 \
 *   fr.enry.archivist.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * `photoCount` (an `-e` instrumentation argument, not a recompile) controls how many
 * photos to generate — start small, then scale up once the harness itself is confirmed
 * working. Images are generated procedurally on-device (random shapes on a random
 * background, `EXIF DateTimeOriginal` spread over the past year) rather than real
 * photos, since the point is exercising the pipeline and the timeline grid, not image
 * content.
 *
 * **Nothing here is cleaned up afterward** — unlike every other instrumented test in
 * this module, the inserted `MediaStore` rows and the resulting real server-side photos
 * are the entire point, meant to persist so the running app's own timeline has
 * something to scroll. Delete them by hand (device gallery, or `DELETE /photos/{id}`
 * per photo) when done testing.
 */
@RunWith(AndroidJUnit4::class)
class LoadTestInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val entryPoint by lazy { TestEntryPoint.from(context) }

    @Test
    fun populateTheRealSignedInAccountWithSyntheticPhotos() =
        runBlocking {
            assumeTrue(
                "refusing to run: no instance is connected in this process",
                entryPoint.instanceStore().current.first() != null,
            )
            // `am instrument` always starts a *fresh* process for the test -- it never
            // inherits whatever's in memory in the UI process you were just looking at
            // (MasterKeyHolder is deliberately in-memory-only, so it starts empty here
            // regardless). Silently re-unlock the same way the real app does on launch:
            // the device's own Keystore auth window is gated by *when the screen was
            // last unlocked*, not by which process asks, so this succeeds as long as a
            // real unlock (PIN/biometric) happened in roughly the last few minutes.
            val step = entryPoint.enrolmentRepository().determineStep()
            assumeTrue(
                "refusing to run: couldn't silently unlock in this fresh instrumentation " +
                    "process (step=$step) -- unlock the device (PIN/biometric) within the last few minutes and retry",
                step == EnrolmentStep.Unlocked,
            )

            val photoCount =
                InstrumentationRegistry.getArguments().getString("photoCount")?.toIntOrNull() ?: 50
            Log.i(TAG, "Generating $photoCount synthetic photos into MediaStore bucket \"$BUCKET_NAME\"...")

            var bucketId: String? = null
            var insertFailures = 0
            for (i in 1..photoCount) {
                runCatching { insertSyntheticPhoto(context, i) }
                    .onSuccess { bucketId = it }
                    .onFailure { insertFailures++; Log.w(TAG, "failed to insert synthetic photo $i: ${it.message}") }
                if (i % 50 == 0 || i == photoCount) Log.i(TAG, "Inserted $i/$photoCount ($insertFailures failed)")
            }
            requireNotNull(bucketId) { "every synthetic photo insert failed -- see logcat above" }

            entryPoint.folderSelectionDao().upsert(
                FolderSelectionEntity(
                    folderUri = bucketId,
                    displayName = BUCKET_NAME,
                    enabled = true,
                    addedAt = Instant.now().toString(),
                ),
            )

            val hashSecretReady = entryPoint.enrolmentRepository().ensureHashSecret()
            assertTrue("couldn't fetch/unwrap the owner's hash secret: ${hashSecretReady.exceptionOrNull()}", hashSecretReady.isSuccess)

            val queued = entryPoint.scanner().scan().getOrThrow()
            Log.i(TAG, "Scanner queued $queued new file(s)")

            val activeIds = entryPoint.uploadQueueDao().getActiveIds()
            UploadWorker.enqueueAll(context, activeIds)

            val timeoutMs = maxOf(5 * 60_000L, photoCount * 5_000L)
            awaitQueueDrained(timeoutMs)

            val all = entryPoint.uploadQueueDao().observeAll().first()
            val done = all.count { it.state == UploadState.DONE }
            val failed = all.count { it.state == UploadState.FAILED }
            Log.i(TAG, "Finished: $done done, $failed failed, ${all.size} total rows in upload_queue")
            assertTrue(
                "too many uploads failed: $failed failed out of ${all.size} -- check adb logcat for the real errors",
                failed <= all.size / 10,
            )
        }

    private suspend fun awaitQueueDrained(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastLog = 0L
        while (System.currentTimeMillis() < deadline) {
            val active = entryPoint.uploadQueueDao().getActiveIds()
            if (active.isEmpty()) return
            if (System.currentTimeMillis() - lastLog > 10_000) {
                Log.i(TAG, "${active.size} still in flight...")
                lastLog = System.currentTimeMillis()
            }
            delay(2_000)
        }
        Log.w(TAG, "timed out after ${timeoutMs}ms waiting for the queue to drain -- continuing to the final tally anyway")
    }
}

private val EXIF_DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss").withZone(ZoneOffset.UTC)
private val PLAUSIBLE_OFFSETS_MIN = listOf(-480, -420, -300, -60, 0, 60, 120, 330, 540)

/** Random shapes on a random background — cheap to generate, and pixel content is
 * irrelevant to what this harness is testing (pagination, decrypt, scroll perf). ~15%
 * of photos are generated oversized (up to 4000px) to exercise
 * [Thumbnailer]'s real downsampling path, matching what a real camera sensor's output
 * would hit; the rest are ordinary phone-photo-ish sizes. */
private fun insertSyntheticPhoto(
    context: Context,
    index: Int,
): String {
    val random = Random(index)
    val large = random.nextInt(100) < 15
    val width = if (large) random.nextInt(2400, 4001) else random.nextInt(800, 1601)
    val height = if (large) random.nextInt(1800, 3001) else random.nextInt(600, 1201)

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(randomColor(random))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    repeat(6) {
        paint.color = randomColor(random)
        val cx = random.nextFloat() * width
        val cy = random.nextFloat() * height
        val r = random.nextFloat() * (width.coerceAtMost(height) / 3f) + 20f
        if (random.nextBoolean()) {
            canvas.drawCircle(cx, cy, r, paint)
        } else {
            canvas.drawRect(RectF(cx - r, cy - r, cx + r, cy + r), paint)
        }
    }

    val displayName = "load_test_%04d.jpg".format(index)
    val resolver = context.contentResolver
    val values =
        ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/$BUCKET_NAME")
        }
    val uri =
        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed for $displayName")

    resolver.openOutputStream(uri)?.use { out ->
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        out.write(bytes)
    } ?: error("no output stream for $uri")
    bitmap.recycle()

    // A random moment in the past year, plus a plausible UTC offset -- gives the
    // timeline grid a real spread of date headers and tzOffsetMin values to group by,
    // not everything piled under a single "today" header.
    val takenAt = Instant.now().minusSeconds(random.nextLong(0, 365L * 24 * 3600))
    val offsetMin = PLAUSIBLE_OFFSETS_MIN[random.nextInt(PLAUSIBLE_OFFSETS_MIN.size)]
    val offset = ZoneOffset.ofTotalSeconds(offsetMin * 60)
    resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
        val exif = ExifInterface(pfd.fileDescriptor)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, EXIF_DATETIME_FORMAT.format(takenAt))
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, formatOffset(offset))
        exif.saveAttributes()
    }

    val id = ContentUris.parseId(uri)
    val bucketId =
        resolver.query(uri, arrayOf(MediaStore.Images.Media.BUCKET_ID), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) error("inserted row $id vanished before it could be read back")
            cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID))
        } ?: error("query against $uri returned nothing")

    return bucketId
}

private fun randomColor(random: Random): Int =
    Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))

private fun formatOffset(offset: ZoneOffset): String {
    val totalMinutes = offset.totalSeconds / 60
    val sign = if (totalMinutes < 0) "-" else "+"
    val abs = kotlin.math.abs(totalMinutes)
    return "%s%02d:%02d".format(sign, abs / 60, abs % 60)
}
