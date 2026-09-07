package fr.enry.archivist.data.repo

import fr.enry.archivist.crypto.MasterKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the unlocked master key lives while the app runs — in memory only, per
 * `MasterKey`'s own contract, never in SharedPreferences or on disk. One active
 * instance's key at a time, matching the app's "one connected instance" reality today
 * (see [fr.enry.archivist.data.local.InstanceStore]'s per-host storage, kept for future
 * multi-instance support that doesn't exist yet either).
 *
 * **Deliberately not cleared just for the app being backgrounded** (see
 * [ArchivistApplication][fr.enry.archivist.ArchivistApplication]'s own doc for the full
 * account, and design.md's "Encryption" section for the threat model this follows: AWS/
 * the operator never seeing plaintext, not defending key material already resident in
 * memory against a local attacker holding an already-unlocked device). Only
 * [AuthRepository.signOut][fr.enry.archivist.data.repo.AuthRepository.signOut] and
 * [AccountRepository.deleteAccount][fr.enry.archivist.data.repo.AccountRepository.deleteAccount]
 * — deliberate, session-ending user actions — call [clear]. Re-unlocking after either
 * means running the enrolment repository's silent-unlock path again — cheap, since it's
 * usually just a Keystore ECDH unwrap with no visible prompt at all (see "Time-based
 * auth" in android.md) — and
 * [ui.timeline.TimelineScreen][fr.enry.archivist.ui.timeline.TimelineScreen] re-runs it
 * automatically as soon as it observes [current] go `null`, for whatever rare reason
 * that happens while it's on screen.
 */
@Singleton
class MasterKeyHolder
    @Inject
    constructor() {
        private val _current = MutableStateFlow<MasterKey?>(null)
        val current: StateFlow<MasterKey?> = _current.asStateFlow()

        fun set(masterKey: MasterKey) {
            _current.value?.clear()
            _current.value = masterKey
        }

        fun clear() {
            _current.value?.clear()
            _current.value = null
        }
    }
