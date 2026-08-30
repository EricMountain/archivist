package fr.enry.archivist.data.local.db

import android.content.Context
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.mockito.kotlin.mock

/** A synchronous, JVM-safe stand-in for `ArchTaskExecutor`'s default delegate, which
 * posts to a real `Handler(Looper.getMainLooper())` — nonexistent on a bare JVM. Only
 * bites when something collects a Room `Flow`-returning query (`observeXxx()`) from a
 * coroutine context where `Dispatchers.Main` is *also* resolvable (e.g. inside
 * `viewModelScope`, once a test has called `Dispatchers.setMain(...)`) — Room's
 * `TriggerBasedInvalidationTracker` only routes through `ArchTaskExecutor` on that
 * path, which is why plain DAO tests (no `Dispatchers.Main` involved) never hit this,
 * but a `ViewModel` test collecting the same query does, with
 * `RuntimeException("Method getMainLooper in android.os.Looper not mocked")`
 * surfacing as `MissingMainCoroutineDispatcher`/`CoroutinesInternalError`. See
 * `android/AGENTS.md`. Installing this before building the database (idempotent, safe
 * to call every time) sidesteps it for every test that uses [buildTestDatabase],
 * whether or not it happens to need it. */
private val jvmSafeTaskExecutor =
    object : TaskExecutor() {
        override fun executeOnDiskIO(runnable: Runnable) = runnable.run()

        override fun postToMainThread(runnable: Runnable) = runnable.run()

        override fun isMainThread(): Boolean = true
    }

/**
 * Builds an in-memory [AppDatabase] for DAO tests as a plain JVM unit test — no
 * Robolectric, no emulator (this build environment has none — see STATUS.md). Room's
 * `inMemoryDatabaseBuilder` still takes a [Context] parameter even with
 * [BundledSQLiteDriver] doing the actual work, but a decompiled read of
 * `RoomDatabase.Builder` (2.8.4) shows the only calls it makes on that context are a
 * null-check and, for the default `AUTOMATIC` journal mode, one `getSystemService`
 * lookup that's safe to return null from — so an unstubbed Mockito mock is enough; nothing
 * here needs a real Android framework.
 */
fun buildTestDatabase(): AppDatabase {
    ArchTaskExecutor.getInstance().setDelegate(jvmSafeTaskExecutor)
    return Room.inMemoryDatabaseBuilder(mock<Context>(), AppDatabase::class.java)
        .setDriver(BundledSQLiteDriver())
        .build()
}
