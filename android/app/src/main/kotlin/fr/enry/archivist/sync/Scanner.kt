package fr.enry.archivist.sync

import fr.enry.archivist.crypto.ContentHash
import fr.enry.archivist.data.local.db.FolderSelectionDao
import fr.enry.archivist.data.local.db.LocalTombstoneDao
import fr.enry.archivist.data.local.db.RenditionDao
import fr.enry.archivist.data.local.db.UploadQueueDao
import fr.enry.archivist.data.local.db.UploadQueueEntity
import fr.enry.archivist.data.local.db.UploadState
import fr.enry.archivist.data.repo.HashSecretHolder
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Plan step 2.7: turns "these folders are selected" into "these specific files are
 * queued to upload" — the boundary between MediaStore (what exists on the device) and
 * `upload_queue` (what this app has decided to do about each of those files).
 *
 * A file's content hash is computed by reading the whole file exactly once
 * ("Compute the content HMAC while reading. Do it once and store it" — the plan's own
 * words); [UploadQueueDao.getByLocalUri] is checked first on every subsequent scan so a
 * file already seen (whatever became of it) is never re-read just to re-derive the same
 * hash. A file whose content turns out to be tombstoned or already uploaded still gets
 * a row — in a terminal, non-`PENDING` state, `photoId`/`renditionId` left null — purely
 * so that fast path recognises it next time too; see the state comments below.
 *
 * Requires [HashSecretHolder] to already be populated — call
 * [fr.enry.archivist.data.repo.EnrolmentRepository.ensureHashSecret] first. Scanner
 * itself doesn't depend on that repository: fetching-and-caching the hash secret is a
 * network+unwrap concern with its own error handling, orthogonal to "walk selected
 * folders and enqueue candidates," and keeping them separate is what makes this class
 * testable without a fake network stack.
 */
class Scanner
    @Inject
    constructor(
        private val mediaStoreSource: MediaStoreSource,
        private val folderSelectionDao: FolderSelectionDao,
        private val uploadQueueDao: UploadQueueDao,
        private val renditionDao: RenditionDao,
        private val localTombstoneDao: LocalTombstoneDao,
        private val hashSecretHolder: HashSecretHolder,
    ) {
        /** Returns how many new files were queued as `PENDING`, or a failure if
         * [HashSecretHolder] isn't populated yet (see the class doc — call
         * `ensureHashSecret()` first). A per-file read failure doesn't abort the whole
         * scan; it's swallowed and that one file is left for a future pass to retry,
         * since [UploadQueueDao.getByLocalUri] finding nothing is exactly what makes it
         * a candidate again. */
        suspend fun scan(): Result<Int> {
            val hashSecret =
                hashSecretHolder.current.value
                    ?: return Result.failure(
                        IllegalStateException("hash secret not available -- call ensureHashSecret() first"),
                    )
            val folders = folderSelectionDao.observeAll().first().filter { it.enabled }

            var queued = 0
            for (folder in folders) {
                for (file in mediaStoreSource.listFiles(folder.folderUri)) {
                    if (uploadQueueDao.getByLocalUri(file.contentUri) != null) continue

                    val contentHash = runCatching { hash(hashSecret, file) }.getOrNull() ?: continue

                    val state =
                        when {
                            localTombstoneDao.exists(contentHash) -> UploadState.DONE
                            renditionDao.existsByContentHash(contentHash) -> UploadState.DONE
                            else -> UploadState.PENDING
                        }

                    val now = nowIso()
                    val insertedId =
                        uploadQueueDao.insertIfNewContent(
                            UploadQueueEntity(
                                localUri = file.contentUri,
                                displayName = file.displayName,
                                folderUri = folder.folderUri,
                                contentHash = contentHash,
                                state = state,
                                // Free from the same MediaStore row the scan already
                                // read (AndroidMediaStoreSource.listFiles) — plan step
                                // 2.10's upload worker needs both before it can even
                                // start (plainBytes for the POST body, fileMtimeEpochSec
                                // as Timestamps.resolve's file-mtime fallback rung), and
                                // there's no cheaper time to capture them than now.
                                plainBytes = file.size,
                                fileMtimeEpochSec = file.dateModified,
                                takenAt = null,
                                tzOffsetMin = null,
                                takenAtSrc = null,
                                tzSrc = null,
                                mime = null,
                                width = null,
                                height = null,
                                photoId = null,
                                renditionId = null,
                                attempts = 0,
                                lastError = null,
                                createdAt = now,
                                updatedAt = now,
                            ),
                        )
                    // -1 means IGNORE dropped it (duplicate content under a different
                    // URI, already counted whenever that URI itself was scanned) --
                    // never count it twice.
                    if (insertedId != -1L && state == UploadState.PENDING) queued++
                }
            }
            return Result.success(queued)
        }

        private suspend fun hash(
            hashSecret: ByteArray,
            file: DeviceMediaFile,
        ): String =
            withContext(Dispatchers.IO) {
                mediaStoreSource.openInputStream(file.contentUri).use { ContentHash.of(hashSecret, it) }
            }
    }

private fun nowIso(): String = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()
