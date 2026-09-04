package fr.enry.archivist.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Plan step 2.14. A local cache of the owner's `D#<deviceKey>` items (`GET /devices` in
 * api.md) — two independent readers need it: the Devices settings screen (list, edit,
 * remove) and [fr.enry.archivist.data.repo.UploadRepository] (the real per-camera
 * `tzOffsetMin` default for rung 5 of design.md's UTC-offset ladder, replacing the
 * previous stand-in — see that class's own note on what it used before this existed).
 * Refreshed from the server by [fr.enry.archivist.data.repo.DeviceRepository.refresh];
 * never written to speculatively for a `deviceKey` this device hasn't fetched — a device
 * this cache has never heard of correctly falls through to a lower rung rather than
 * guessing.
 */
@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val deviceKey: String,
    val label: String,
    val tzOffsetMin: Int?,
    val photoCount: Int,
    val firstSeenAt: String,
)

@Dao
interface DeviceDao {
    /** Not `@Upsert` — see `android/AGENTS.md`'s note (`PhotoDao.upsertOne` is the
     * canonical example) on why every upsert-shaped DAO method here is a raw query. */
    @Query(
        """
        INSERT INTO devices (deviceKey, label, tzOffsetMin, photoCount, firstSeenAt)
        VALUES (:deviceKey, :label, :tzOffsetMin, :photoCount, :firstSeenAt)
        ON CONFLICT(deviceKey) DO UPDATE SET
            label = excluded.label,
            tzOffsetMin = excluded.tzOffsetMin,
            photoCount = excluded.photoCount,
            firstSeenAt = excluded.firstSeenAt
        """,
    )
    suspend fun upsertOne(
        deviceKey: String,
        label: String,
        tzOffsetMin: Int?,
        photoCount: Int,
        firstSeenAt: String,
    )

    suspend fun upsert(device: DeviceEntity) =
        upsertOne(device.deviceKey, device.label, device.tzOffsetMin, device.photoCount, device.firstSeenAt)

    /** A full refresh replaces the cache wholesale — a device removed server-side
     * (via `DELETE /devices/{deviceKey}`, e.g. from another signed-in browser) should
     * disappear from here too, which a plain per-row upsert would never notice. */
    @Query("DELETE FROM devices")
    suspend fun clear()

    @Query("SELECT * FROM devices ORDER BY label")
    suspend fun getAll(): List<DeviceEntity>

    @Query("SELECT * FROM devices WHERE deviceKey = :deviceKey")
    suspend fun getByDeviceKey(deviceKey: String): DeviceEntity?

    @Query("DELETE FROM devices WHERE deviceKey = :deviceKey")
    suspend fun deleteByDeviceKey(deviceKey: String)
}
