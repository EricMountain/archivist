package fr.enry.archivist.testutil

import java.io.ByteArrayOutputStream

/**
 * Minimal, byte-correct ISO-BMFF box builders for exercising
 * [fr.enry.archivist.sync.video.Mp4BoxEditor] against a synthetic file — no real
 * video/audio codec involved, just box framing. Only the fields that box actually
 * reads are spec-correct (the `keys`/`ilst` pair, since it walks their internals);
 * `loci`/`©xyz`/`mdat`/`ftyp` payloads are arbitrary bytes, since the editor matches
 * those purely by box type and zeroes the whole payload without reading it.
 */
object SyntheticMp4 {
    fun box(
        type: String,
        payload: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream(8 + payload.size)
        out.write(intToBytes(8 + payload.size))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(payload)
        return out.toByteArray()
    }

    private fun fullBoxPayload(body: ByteArray): ByteArray = byteArrayOf(0, 0, 0, 0) + body

    fun lociBox(payload: ByteArray = ByteArray(20) { 1 }): ByteArray = box("loci", fullBoxPayload(payload))

    fun quicktimeXyzBox(iso6709: String = "+35.1234+139.5678/"): ByteArray = box("©xyz", iso6709.toByteArray(Charsets.UTF_8))

    /** `keys`' real structure — `Mp4BoxEditor` parses this one for real, matching a
     * key name against "location" (case-insensitive). */
    fun keysBox(vararg keyNames: String): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(intToBytes(keyNames.size))
        for (name in keyNames) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            body.write(intToBytes(8 + nameBytes.size))
            body.write("mdta".toByteArray(Charsets.US_ASCII))
            body.write(nameBytes)
        }
        return box("keys", fullBoxPayload(body.toByteArray()))
    }

    /** One `ilst` child. Its *type field* is the raw 1-based key index, not a FourCC
     * — [index] is written as the box's type bytes directly, matching what
     * `Mp4BoxEditor.ilstChildByIndex` reads back. */
    fun ilstItem(
        index: Int,
        payload: ByteArray = ByteArray(8) { 9 },
    ): ByteArray = box(String(intToBytes(index), Charsets.ISO_8859_1), payload)

    fun ilstBox(vararg items: ByteArray): ByteArray = box("ilst", items.concat())

    fun udtaBox(vararg children: ByteArray): ByteArray = box("udta", children.concat())

    /** ISO/IEC 14496-12's `meta`: a FullBox (4 bytes of version+flags before the
     * children). */
    fun isoMetaBox(vararg children: ByteArray): ByteArray = box("meta", fullBoxPayload(children.concat()))

    /** QuickTime's own `meta`: a plain container, children start immediately. */
    fun quicktimeMetaBox(vararg children: ByteArray): ByteArray = box("meta", children.concat())

    fun moovBox(vararg children: ByteArray): ByteArray = box("moov", children.concat())

    fun mdatBox(size: Int = 16): ByteArray = box("mdat", ByteArray(size) { 0x5A })

    fun ftypBox(): ByteArray = box("ftyp", "isomisom".toByteArray(Charsets.US_ASCII))

    /** A full synthetic file — `moovFirst = false` exercises the "mdat before moov"
     * layout real fast-start-avoiding encoders sometimes use; the editor must not
     * care either way, since it never changes any box's size. */
    fun file(
        moov: ByteArray,
        moovFirst: Boolean = true,
        mdat: ByteArray = mdatBox(),
    ): ByteArray {
        val ftyp = ftypBox()
        return if (moovFirst) ftyp + moov + mdat else ftyp + mdat + moov
    }

    private fun intToBytes(value: Int): ByteArray =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private fun Array<out ByteArray>.concat(): ByteArray = fold(ByteArray(0)) { acc, b -> acc + b }
}
