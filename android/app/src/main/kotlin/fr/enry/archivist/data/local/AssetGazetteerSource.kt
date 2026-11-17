package fr.enry.archivist.data.local

import android.content.Context
import fr.enry.archivist.domain.GazetteerSource
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Memory-maps the bundled `assets/gazetteer.bin` for [fr.enry.archivist.domain.ReverseGeocoder].
 * `openFd` only works because `app/build.gradle.kts` stores `.bin` assets uncompressed
 * (`androidResources.noCompress`); a compressed asset would throw here. The mapping
 * outlives the descriptor, which is closed straight away.
 */
class AssetGazetteerSource(
    private val context: Context,
    private val assetName: String = "gazetteer.bin",
) : GazetteerSource {
    override fun open(): ByteBuffer =
        context.assets.openFd(assetName).use { afd ->
            FileInputStream(afd.fileDescriptor).use { input ->
                input.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)
            }
        }
}
