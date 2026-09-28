package fr.enry.archivist.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.enry.archivist.data.metrics.ImageLoadMetrics
import fr.enry.archivist.data.metrics.MetricsSnapshot
import fr.enry.archivist.data.repo.CacheUsage
import fr.enry.archivist.data.repo.StorageRepository
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class StatsUiState(
    val cache: CacheUsage? = null,
    val metrics: MetricsSnapshot? = null,
)

/** Settings > Stats: thumbnail cache occupancy plus [ImageLoadMetrics], refreshed every
 * [REFRESH_MS] while the page is open so it can be watched while using the timeline in
 * another window, or read straight after coming back from it. */
@HiltViewModel
class StatsViewModel
    @Inject
    constructor(
        private val storageRepository: StorageRepository,
        private val metrics: ImageLoadMetrics,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(StatsUiState())
        val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                while (isActive) {
                    refresh()
                    delay(REFRESH_MS)
                }
            }
        }

        private suspend fun refresh() {
            _uiState.value = StatsUiState(cache = storageRepository.cacheUsage(), metrics = metrics.snapshot())
        }

        fun reset() {
            metrics.reset()
            viewModelScope.launch { refresh() }
        }

        private companion object {
            const val REFRESH_MS = 1000L
        }
    }
