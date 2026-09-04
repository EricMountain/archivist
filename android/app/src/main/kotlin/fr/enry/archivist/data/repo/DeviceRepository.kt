package fr.enry.archivist.data.repo

import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.db.DeviceDao
import fr.enry.archivist.data.local.db.DeviceEntity
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.PatchDeviceRequest
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import retrofit2.HttpException

/**
 * Plan step 2.14's Settings > Devices section (the camera-`deviceKey` config items —
 * "Device config items" in design.md — not the Keys section's enrolled-device
 * wrappings, a different `device` entirely; see [EnrolmentRepository] for those).
 *
 * [DeviceDao]'s `devices` table is a plain server-backed cache: [refresh] replaces it
 * wholesale from `GET /devices`, and [fr.enry.archivist.data.repo.UploadRepository]
 * reads it locally (no network) to resolve rung 5 of design.md's UTC-offset ladder —
 * see that class's own note on what it did before this repository/cache existed.
 */
@Singleton
class DeviceRepository
    @Inject
    constructor(
        private val instanceStore: InstanceStore,
        private val archivistApiFactory: ArchivistApiFactory,
        private val deviceDao: DeviceDao,
    ) {
        /** Fetches the current server-side list and replaces the local cache with it —
         * called by the Devices settings screen on load, and after every edit/remove
         * so the list it shows is never stale relative to what it just did. */
        suspend fun refresh(): Result<List<DeviceEntity>> {
            val instance = instanceStore.current.first() ?: return Result.failure(IllegalStateException("no connected instance"))
            val api = archivistApiFactory.create(instance.host, instance.document.region, instance.document.cognito.clientId)
            return try {
                val devices =
                    api.getDevices("${instance.document.apiBase}/devices").devices.map {
                        DeviceEntity(it.deviceKey, it.label, it.tzOffsetMin, it.photoCount, it.firstSeenAt)
                    }
                deviceDao.clear()
                devices.forEach { deviceDao.upsert(it) }
                Result.success(devices)
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: HttpException) {
                Result.failure(e)
            }
        }

        /** Whatever [refresh] last fetched, with no network call — the Devices
         * screen's fallback when a fresh [refresh] fails (offline, a 5xx), so it shows
         * last-known state plus an error rather than an empty list. */
        suspend fun cached(): List<DeviceEntity> = deviceDao.getAll()

        /** Local-only, no network — [fr.enry.archivist.data.repo.UploadRepository]'s
         * read of a specific camera's configured default. Absent (`null`) both when
         * this device has never seen this `deviceKey` at all and when it has but no
         * default is set — [Timestamps.resolve][fr.enry.archivist.domain.Timestamps.resolve]
         * treats the two identically (fall through to a lower rung). */
        suspend fun tzOffsetMinFor(deviceKey: String): Int? = deviceDao.getByDeviceKey(deviceKey)?.tzOffsetMin

        /** [tzOffsetMin] of `null` clears a previously-set default — see
         * [fr.enry.archivist.data.remote.ArchivistApi.patchDevice]'s doc for why both
         * fields are always sent, never a partial patch. */
        suspend fun update(
            deviceKey: String,
            label: String,
            tzOffsetMin: Int?,
        ): Result<Unit> {
            val instance = instanceStore.current.first() ?: return Result.failure(IllegalStateException("no connected instance"))
            val api = archivistApiFactory.create(instance.host, instance.document.region, instance.document.cognito.clientId)
            return try {
                val response =
                    api.patchDevice(
                        "${instance.document.apiBase}/devices/$deviceKey",
                        PatchDeviceRequest(label = label, tzOffsetMin = tzOffsetMin),
                    )
                if (!response.isSuccessful) return Result.failure(HttpException(response))
                deviceDao.upsert(
                    deviceDao.getByDeviceKey(deviceKey)?.copy(label = label, tzOffsetMin = tzOffsetMin)
                        ?: DeviceEntity(deviceKey, label, tzOffsetMin, photoCount = 0, firstSeenAt = ""),
                )
                Result.success(Unit)
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: HttpException) {
                Result.failure(e)
            }
        }

        suspend fun remove(deviceKey: String): Result<Unit> {
            val instance = instanceStore.current.first() ?: return Result.failure(IllegalStateException("no connected instance"))
            val api = archivistApiFactory.create(instance.host, instance.document.region, instance.document.cognito.clientId)
            return try {
                val response = api.deleteDevice("${instance.document.apiBase}/devices/$deviceKey")
                if (!response.isSuccessful) return Result.failure(HttpException(response))
                deviceDao.deleteByDeviceKey(deviceKey)
                Result.success(Unit)
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: HttpException) {
                Result.failure(e)
            }
        }
    }
