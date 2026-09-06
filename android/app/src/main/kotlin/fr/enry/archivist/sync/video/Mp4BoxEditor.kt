package fr.enry.archivist.sync.video

import java.io.File
import java.io.RandomAccessFile

/**
 * Plan step 2.18 — strips location from an MP4/MOV *in place*, for [LocationStripper][
 * fr.enry.archivist.sync.LocationStripper]'s temporary copy, never the original. See
 * "Mechanism, video" in design.md for the two schemes this targets and why zeroing a
 * box's payload and relabelling it `free`, rather than removing or re-muxing anything,
 * needs no `stco`/`co64` offset recomputation: every box's declared size — and
 * therefore every other box's offset and the file's total length — is left bit-for-bit
 * unchanged. This is deliberately not a general MP4 metadata scrubber: an encoder
 * hiding location a fourth way passes through unstripped, a named residual limit, not
 * something this class tries to close by guessing at other vendor-specific boxes.
 *
 * Pure JVM, no Android framework dependency — testable with a hand-built synthetic MP4,
 * no real device or codec involved.
 */
object Mp4BoxEditor {
    private const val QUICKTIME_LOCATION_ATOM = "©xyz"
    private const val THREE_GPP_LOCATION_ATOM = "loci"

    /** No-op if [file] carries no recognised location box — that's the ordinary case,
     * not an error. */
    fun stripLocation(file: File) {
        RandomAccessFile(file, "rw").use { raf ->
            val moov = topLevelBoxes(raf, file.length()).find { it.type == "moov" } ?: return
            for (child in children(raf, moov)) {
                when (child.type) {
                    "udta" -> stripUdta(raf, child)
                    "meta" -> stripMeta(raf, child)
                }
            }
        }
    }

    private fun stripUdta(
        raf: RandomAccessFile,
        udta: Box,
    ) {
        for (child in children(raf, udta)) {
            if (child.type == THREE_GPP_LOCATION_ATOM || child.type == QUICKTIME_LOCATION_ATOM) {
                zeroAndRelabel(raf, child)
            }
        }
    }

    /** QuickTime's own `meta` box is a plain container (children start immediately);
     * ISO/IEC 14496-12's `meta` is a FullBox (4 bytes of version+flags first). Real
     * `.mov` files use the plain form, real ISO-family `.mp4` muxers use the FullBox
     * form — detected, not assumed, since guessing wrong misparses every child. */
    private fun stripMeta(
        raf: RandomAccessFile,
        meta: Box,
    ) {
        val childrenHeaderLen = (metaChildrenStart(raf, meta) - meta.start).toInt()
        val asContainer = Box(meta.start, meta.size, meta.type, childrenHeaderLen)
        val metaChildren = children(raf, asContainer)
        val keys = metaChildren.find { it.type == "keys" } ?: return
        val ilst = metaChildren.find { it.type == "ilst" } ?: return
        val locationKeyIndex = locationKeyIndex(raf, keys)
        if (locationKeyIndex > 0) {
            ilstChildByIndex(raf, ilst, locationKeyIndex)?.let { zeroAndRelabel(raf, it) }
        }
    }

    private fun metaChildrenStart(
        raf: RandomAccessFile,
        meta: Box,
    ): Long {
        val plainStart = meta.payloadStart
        val fullBoxStart = meta.payloadStart + 4
        if (fullBoxStart >= meta.end) return plainStart
        raf.seek(meta.payloadStart)
        val versionAndFlags = raf.readInt()
        if (versionAndFlags != 0) return plainStart
        val candidate = readBoxAt(raf, fullBoxStart, meta.end) ?: return plainStart
        val remaining = meta.end - fullBoxStart
        return if (candidate.size in 8..remaining && candidate.type.isPlausibleFourCc()) fullBoxStart else plainStart
    }

    /** 1-based index of the `keys` entry whose name mentions "location" (case
     * insensitive — covers `com.apple.quicktime.location.ISO6709` and any sibling key
     * a different encoder names slightly differently), or -1. Per QuickTime's metadata
     * keyspace: FullBox header, `entry_count`, then that many entries of
     * `[key_size(4)][key_namespace(4)][key_value(key_size-8 bytes)]`. */
    private fun locationKeyIndex(
        raf: RandomAccessFile,
        keys: Box,
    ): Int {
        var offset = keys.payloadStart + 4 // FullBox version+flags
        if (offset + 4 > keys.end) return -1
        raf.seek(offset)
        val entryCount = raf.readInt()
        offset += 4
        for (index in 1..entryCount) {
            if (offset + 8 > keys.end) break
            raf.seek(offset)
            val keySize = raf.readInt().toLong() and 0xFFFFFFFFL
            if (keySize < 8 || offset + keySize > keys.end) break
            raf.skipBytes(4) // namespace, unused — the value alone is enough to match on
            val valueBytes = ByteArray((keySize - 8).toInt())
            raf.readFully(valueBytes)
            if (String(valueBytes, Charsets.UTF_8).contains("location", ignoreCase = true)) return index
            offset += keySize
        }
        return -1
    }

    /** `ilst`'s children are anonymous, each one's *type field* holding the raw
     * 1-based numeric index of the `keys` entry it corresponds to (not a FourCC) — so
     * matching means comparing that field as an integer, not as text. */
    private fun ilstChildByIndex(
        raf: RandomAccessFile,
        ilst: Box,
        index: Int,
    ): Box? {
        var offset = ilst.payloadStart
        while (offset < ilst.end) {
            val box = readBoxAt(raf, offset, ilst.end) ?: break
            if (box.size <= 0) break
            raf.seek(box.start + 4)
            if (raf.readInt() == index) return box
            offset += box.size
        }
        return null
    }

    private fun zeroAndRelabel(
        raf: RandomAccessFile,
        box: Box,
    ) {
        raf.seek(box.start + 4)
        raf.write(FREE_TYPE)
        var remaining = box.size - box.headerLen
        if (remaining <= 0) return
        raf.seek(box.payloadStart)
        val zeros = ByteArray(minOf(remaining, ZERO_CHUNK_SIZE).toInt())
        while (remaining > 0) {
            val chunk = minOf(remaining, zeros.size.toLong()).toInt()
            raf.write(zeros, 0, chunk)
            remaining -= chunk
        }
    }

    private fun topLevelBoxes(
        raf: RandomAccessFile,
        fileLength: Long,
    ): List<Box> = children(raf, Box(0, fileLength, "", 0))

    private fun children(
        raf: RandomAccessFile,
        container: Box,
    ): List<Box> {
        val boxes = mutableListOf<Box>()
        var offset = container.payloadStart
        while (offset < container.end) {
            val box = readBoxAt(raf, offset, container.end) ?: break
            if (box.size <= 0) break
            boxes.add(box)
            offset += box.size
        }
        return boxes
    }

    /** `[size(4)][type(4)][largesize(8) if size==1]` — ISO/IEC 14496-12's box header,
     * `size == 0` meaning "extends to the end of [containerEnd]" (legitimate for a
     * streaming-written top-level `mdat`, harmless to support generically elsewhere). */
    private fun readBoxAt(
        raf: RandomAccessFile,
        offset: Long,
        containerEnd: Long,
    ): Box? {
        if (offset + 8 > containerEnd) return null
        raf.seek(offset)
        val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
        val typeBytes = ByteArray(4)
        raf.readFully(typeBytes)
        val type = String(typeBytes, Charsets.ISO_8859_1)
        return when (size32) {
            0L -> Box(offset, containerEnd - offset, type, 8)
            1L -> Box(offset, raf.readLong(), type, 16)
            else -> Box(offset, size32, type, 8)
        }
    }

    private fun String.isPlausibleFourCc(): Boolean = length == 4 && all { it.code in 0x20..0x7e || it.code == 0xA9 }

    private val FREE_TYPE = "free".toByteArray(Charsets.US_ASCII)
    private const val ZERO_CHUNK_SIZE = 8192L

    /** [type] is read as ISO-8859-1 so a high-bit byte (QuickTime's `©` in `©xyz`)
     * round-trips as one code point rather than being mangled — it is not meant to be
     * displayed, only compared against the known atom names above. An `ilst` child's
     * type is numeric instead (see [ilstChildByIndex]) and is never read through this
     * field as text. */
    private data class Box(val start: Long, val size: Long, val type: String, val headerLen: Int) {
        val payloadStart: Long get() = start + headerLen
        val end: Long get() = start + size
    }
}
