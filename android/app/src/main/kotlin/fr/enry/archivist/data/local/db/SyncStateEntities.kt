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
