package fr.enry.archivist.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.mockito.kotlin.mock

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
fun buildTestDatabase(): AppDatabase =
    Room.inMemoryDatabaseBuilder(mock<Context>(), AppDatabase::class.java)
        .setDriver(BundledSQLiteDriver())
        .build()
