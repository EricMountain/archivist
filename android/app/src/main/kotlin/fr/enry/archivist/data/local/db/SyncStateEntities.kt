package fr.enry.archivist.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Plan step 2.6 describes one table, "`sync_state` (folder selections, cursors)" — two
 * distinct kinds of state that happen to share a name in the plan's prose. They're
 * split into two physical tables here since they have unrelated shapes and lifecycles
 * (one row per folder vs. one row total), but both exist to answer "what has this
 * device already synced, and from where".
 */
@Entity(tableName = "sync_state")
data class FolderSelectionEntity(
    @PrimaryKey val folderUri: String,
    val displayName: String,
    val enabled: Boolean,
    val addedAt: String,
)

@Dao
interface FolderSelectionDao {
    /** Not `@Upsert` — see the note on `PhotoDao.upsertOne`. */
    @Query(
        """
        INSERT INTO sync_state (folderUri, displayName, enabled, addedAt)
        VALUES (:folderUri, :displayName, :enabled, :addedAt)
        ON CONFLICT(folderUri) DO UPDATE SET
            displayName = excluded.displayName,
            enabled = excluded.enabled,
            addedAt = excluded.addedAt
        """,
    )
    suspend fun upsertOne(
        folderUri: String,
        displayName: String,
        enabled: Boolean,
        addedAt: String,
    )

    suspend fun upsert(folder: FolderSelectionEntity) =
        upsertOne(folder.folderUri, folder.displayName, folder.enabled, folder.addedAt)

    @Query("SELECT * FROM sync_state ORDER BY addedAt ASC")
    fun observeAll(): Flow<List<FolderSelectionEntity>>

    /** So a caller toggling an already-selected folder can preserve its original
     * `addedAt` through [upsert] rather than bumping it on every enable/disable. */
    @Query("SELECT * FROM sync_state WHERE folderUri = :folderUri")
    suspend fun getByFolderUri(folderUri: String): FolderSelectionEntity?

    /** Selections live in Room and are re-evaluated on each scan (android.md), so
     * disabling a folder here — not deleting the row — is what "deselecting stops
     * future uploads" (plan step 2.7's "Done when") actually means: the scanner just
     * stops proposing candidates from it. */
    @Query("UPDATE sync_state SET enabled = :enabled WHERE folderUri = :folderUri")
    suspend fun setEnabled(
        folderUri: String,
        enabled: Boolean,
    )

    @Query("DELETE FROM sync_state WHERE folderUri = :folderUri")
    suspend fun delete(folderUri: String)
}

/** One row: the opaque cursor `RemoteMediator` (plan step 2.11) resumes `GET /photos`
 * pagination from. `id` is always 0 — a singleton row rather than a bare key-value
 * table, so Room's own conflict-resolution (`@Upsert`) does the "insert or replace"
 * work instead of hand-written SQL. */
@Entity(tableName = "timeline_cursor")
data class TimelineCursorEntity(
    @PrimaryKey val id: Int = 0,
    val cursor: String?,
    val updatedAt: String,
)

@Dao
interface TimelineCursorDao {
    /** Not `@Upsert` — see the note on `PhotoDao.upsertOne`. */
    @Query(
        """
        INSERT INTO timeline_cursor (id, cursor, updatedAt)
        VALUES (0, :cursor, :updatedAt)
        ON CONFLICT(id) DO UPDATE SET cursor = excluded.cursor, updatedAt = excluded.updatedAt
        """,
    )
    suspend fun setOne(
        cursor: String?,
        updatedAt: String,
    )

    suspend fun set(cursor: TimelineCursorEntity) = setOne(cursor.cursor, cursor.updatedAt)

    @Query("SELECT * FROM timeline_cursor WHERE id = 0")
    fun observe(): Flow<TimelineCursorEntity?>

    @Query("DELETE FROM timeline_cursor")
    suspend fun clear()
}

/**
 * What the cached `photos` table currently *is*, as a span of the library — the fact
 * every read that wants to trust it without asking the server depends on.
 *
 * The invariant this records is that the cache is always **one contiguous span** of the
 * timeline: every write either replaces it (a jump, a refresh) or extends it at an edge
 * (`APPEND` older, `PREPEND` and [fr.enry.archivist.data.repo.PhotoRepository.refreshLatest]
 * newer). Contiguous means every live photo between the oldest cached row and
 * [completeThrough] is present, which is what lets a jump to a day inside the span be
 * answered from Room with no request at all. Keyset paging relies on it too: a hole
 * would be paged straight across, silently skipping everything missing from it.
 *
 * [completeThrough] is the upper edge, as a fixed-width UTC instant: every live photo
 * taken at or before it (and no older than the oldest cached row) is cached. `null`
 * means the cache reaches the present. It is an instant rather than "the newest cached
 * row" because a jump fetches everything up to a bound that is usually *later* than the
 * newest photo it finds — knowing there is nothing in that gap is information, and it is
 * the information that makes the next scrub to the same day free.
 *
 * One row. Absent on a fresh install, which reads as `null`: the first thing a fresh
 * install does is a `REFRESH` from the present.
 */
@Entity(tableName = "timeline_window")
data class TimelineWindowEntity(
    @PrimaryKey val id: Int = 0,
    val completeThrough: String?,
)

@Dao
interface TimelineWindowDao {
    @Query(
        """
        INSERT INTO timeline_window (id, completeThrough) VALUES (0, :completeThrough)
        ON CONFLICT(id) DO UPDATE SET completeThrough = excluded.completeThrough
        """,
    )
    suspend fun setCompleteThrough(completeThrough: String?)

    @Query("SELECT completeThrough FROM timeline_window WHERE id = 0")
    suspend fun completeThrough(): String?
}
