package fr.enry.archivist.data.repo

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the unwrapped `hashSecret` lives while the app runs — in memory only, same
 * policy as [MasterKeyHolder] and for the same reason: it's wrapped by the master key
 * exactly like a DEK (design.md, "`contentHash` is HMAC'd"), so it gets the same
 * custody. Never written to disk — recomputing it costs one unwrap, not worth the risk
 * of a second at-rest copy of key material.
 *
 * Populated by [EnrolmentRepository.ensureHashSecret], not set directly by callers —
 * plan step 2.7's scanner (and eventually 2.10's upload worker) call that and read
 * [current] afterward.
 */
@Singleton
class HashSecretHolder
    @Inject
    constructor() {
        private val _current = MutableStateFlow<ByteArray?>(null)
        val current: StateFlow<ByteArray?> = _current.asStateFlow()

        fun set(hashSecret: ByteArray) {
            _current.value?.fill(0)
            _current.value = hashSecret
        }

        fun clear() {
            _current.value?.fill(0)
            _current.value = null
        }
    }
