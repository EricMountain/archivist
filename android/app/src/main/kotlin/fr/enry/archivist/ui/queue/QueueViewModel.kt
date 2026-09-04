package fr.enry.archivist.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.enry.archivist.data.local.SyncSettingsStore
import fr.enry.archivist.data.local.db.UploadQueueDao
import fr.enry.archivist.data.local.db.UploadQueueEntity
import fr.enry.archivist.data.local.db.UploadState
import fr.enry.archivist.sync.DeviceStateMonitor
import fr.enry.archivist.sync.QueueIdleReason
import fr.enry.archivist.sync.UploadScheduler
import fr.enry.archivist.sync.queueIdleReason
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One [UploadQueueEntity] row, trimmed to what [QueueScreen] shows. */
data class QueueItem(
    val id: Long,
    val displayName: String,
    val state: UploadState,
    val attempts: Int,
    val lastError: String?,
)

data class QueueUiState(
    val items: List<QueueItem>,
    val idleReason: QueueIdleReason,
)

/**
 * Plan step 2.15: "visible progress" over `upload_queue`, plus `android.md`'s "a silent
 * queue that has stalled on a constraint is a support nightmare of one's own making".
 * [UploadState.DONE] rows are excluded -- the plan's own "Details" names only pending,
 * in-progress and failed. [QueueUiState.idleReason] is computed from
 * [DeviceStateMonitor]/[SyncSettingsStore] rather than read off WorkManager directly,
 * since a constrained `WorkInfo` is just `ENQUEUED` with no reasoning attached -- and
 * it's suppressed once every remaining row is [UploadState.FAILED]: a permanently
 * failed row isn't "waiting" on anything, it's waiting on [retry].
 */
@HiltViewModel
class QueueViewModel
    @Inject
    constructor(
        private val uploadQueueDao: UploadQueueDao,
        syncSettingsStore: SyncSettingsStore,
        deviceStateMonitor: DeviceStateMonitor,
        private val uploadScheduler: UploadScheduler,
    ) : ViewModel() {
        val uiState: StateFlow<QueueUiState> =
            combine(
                uploadQueueDao.observePending(UploadState.DONE),
                syncSettingsStore.settings,
                deviceStateMonitor.state,
            ) { rows, settings, deviceState ->
                val active = rows.any { it.state != UploadState.FAILED }
                QueueUiState(
                    items = rows.map { it.toQueueItem() },
                    idleReason = if (active) queueIdleReason(settings, deviceState) else QueueIdleReason.NONE,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueUiState(emptyList(), QueueIdleReason.NONE))

        fun retry(id: Long) {
            viewModelScope.launch {
                uploadQueueDao.resetForRetry(id, nowIso())
                uploadScheduler.enqueueAll(listOf(id))
            }
        }

        fun cancel(id: Long) {
            viewModelScope.launch {
                uploadScheduler.cancel(id)
                uploadQueueDao.deleteById(id)
            }
        }
    }

private fun UploadQueueEntity.toQueueItem() =
    QueueItem(id = id, displayName = displayName, state = state, attempts = attempts, lastError = lastError)

private fun nowIso(): String = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()
