package fr.enry.archivist.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

private const val LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"
private const val REPO_URL = "https://github.com/EricMountain/archivist"

/**
 * Plan step 2.17's follow-up: an About pane, the "about box" GPLv3 itself suggests for
 * displaying Appropriate Legal Notices. Deliberately a plain composable with no
 * ViewModel and no injected repository — `LocalContext`/`LocalUriHandler` are Compose
 * ambients, not network-capable dependencies — so it's safe to reuse verbatim from both
 * the real `SettingsScreen` and the network-free `ReviewerSettingsScreen`.
 *
 * `REPO_URL` is one of exactly two hardcoded personal/identifying strings this repo
 * allows — see CLAUDE.md's "Nothing personal in the committed tree": unlike a domain or
 * AWS account, this project's own GitHub repository isn't deployment-specific, so it
 * belongs alongside the application ID rather than being replaced with a placeholder.
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val versionName =
        remember {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        }

    Column(modifier.padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text("About", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 8.dp))
        Text(
            versionName?.let { "Archivist $it" } ?: "Archivist",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "A self-hosted photo library. Every instance is your own — see \"Preview " +
                "without an account\" and Settings > Sync for what that means.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )
        Text("License", style = MaterialTheme.typography.titleSmall)
        Text(
            "GNU General Public License v3.0 or later",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        TextButton(onClick = { uriHandler.openUri(LICENSE_URL) }) { Text("View the license") }
        Text("Source code", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
        Text(
            "Archivist is free software — the app, the Terraform and the design docs " +
                "are all here.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        TextButton(onClick = { uriHandler.openUri(REPO_URL) }) { Text(REPO_URL) }
    }
}
