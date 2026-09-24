package fr.enry.archivist.ui.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import fr.enry.archivist.data.repo.PhotoDetail

/**
 * "Details" menu action — a plain-text dump of everything [PhotoDetail] carries
 * (photoId, `stem`, dedupe/trash bookkeeping, every rendition's contentHash/path),
 * deliberately in the same shape `inspect_photo.py`'s own `print_asset` prints
 * server-side, so this dialog's output can be matched directly against that tool's or
 * against a support request. Selectable text (long-press to copy a line) plus a
 * one-tap "Copy all" for the whole dump.
 */
@Composable
fun PhotoDetailsDialog(
    detail: PhotoDetail,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var copied by remember(detail.photoId) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Details") },
        text = {
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        detailsText(detail),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                copyToClipboard(context, detailsText(detail))
                copied = true
            }) { Text(if (copied) "Copied" else "Copy all") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private fun detailsText(detail: PhotoDetail): String {
    val lines =
        buildList {
            add("photoId       ${detail.photoId}")
            add("stem          ${detail.stem}")
            add("status        ${detail.status}")
            if (detail.deletedAt != null) {
                add("deletedAt     ${detail.deletedAt}")
                add("deletedBy     ${detail.deletedBy}")
            }
            add("takenAt       ${detail.takenAt}  (src=${detail.takenAtSrc})")
            add("tzOffsetMin   ${detail.tzOffsetMin}  (src=${detail.tzSrc})")
            add("dimensions    ${detail.width}x${detail.height}")
            add("mime          ${detail.mime}")
            add("primaryRend   ${detail.primaryRend}")
            add("renditions    ${detail.renditionsCount}")
            add("groupSrc      ${detail.groupSrc}")
            if (detail.deviceKey != null) add("deviceKey     ${detail.deviceKey}")
            if (detail.cameraMake != null || detail.cameraModel != null) {
                add("camera        ${detail.cameraMake ?: "?"} ${detail.cameraModel ?: "?"}")
            }
            add("uploadedAt    ${detail.uploadedAt}")
            add("")
            add("renditions:")
            for (r in detail.renditions) {
                add("  ${r.renditionId}  role=${r.role}")
                add("    path=${r.path}")
                add(
                    "    ${r.width}x${r.height}  plainBytes=${r.plainBytes}  bytes=${r.bytes}  " +
                        "encChunkSize=${r.encChunkSize}",
                )
                add("    contentHash=${r.contentHash}")
                add("    addedAt=${r.addedAt}")
            }
        }
    return lines.joinToString("\n")
}

private fun copyToClipboard(
    context: Context,
    text: String,
) {
    val clipboardManager = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboardManager.setPrimaryClip(ClipData.newPlainText("Archivist photo details", text))
}
