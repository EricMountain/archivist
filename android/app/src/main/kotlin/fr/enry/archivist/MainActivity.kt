package fr.enry.archivist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.CompositionLocalProvider
import dagger.hilt.android.AndroidEntryPoint
import fr.enry.archivist.data.repo.PreviewCache
import fr.enry.archivist.ui.preview.LocalPreviewCache
import javax.inject.Inject
import fr.enry.archivist.ui.onboarding.ConnectScreen
import fr.enry.archivist.ui.onboarding.ConnectUiState
import fr.enry.archivist.ui.onboarding.ConnectViewModel
import fr.enry.archivist.ui.onboarding.PermissionOnboardingScreen
import fr.enry.archivist.ui.onboarding.SignInScreen
import fr.enry.archivist.ui.reviewer.ReviewerPreviewScreen
import fr.enry.archivist.ui.theme.ArchivistTheme
import fr.enry.archivist.ui.timeline.TimelineScreen

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Handed to the composition via [LocalPreviewCache] rather than through the
     * ViewModels — see its own doc. */
    @Inject
    lateinit var previewCache: PreviewCache

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalPreviewCache provides previewCache) {
                ArchivistTheme {
                    ArchivistApp()
                }
            }
        }
    }
}

@Composable
private fun ArchivistApp(connectViewModel: ConnectViewModel = hiltViewModel()) {
    val connectState by connectViewModel.uiState.collectAsStateWithLifecycle()
    var signedIn by remember { mutableStateOf(false) }

    Scaffold { innerPadding ->
        when (val s = connectState) {
            ConnectUiState.CheckingStoredInstance ->
                Centered(Modifier.padding(innerPadding)) { CircularProgressIndicator() }

            is ConnectUiState.Connected ->
                if (signedIn) {
                    // TimelineScreen owns the entire signed-in experience end to end now
                    // -- locked (silent-unlock attempt, or a real recovery-code/device-
                    // unlock form if silent unlock fails), unlocked-but-still-loading,
                    // and the real grid, including its own `PermissionOnboardingScreen`
                    // gate once genuinely past the locked forms (see its own top-of-file
                    // doc). This used to be split here instead: an `unlocked` boolean
                    // gated a *second*, separately-mounted `EnrolmentScreen` call (and,
                    // behind it, a *second* `PermissionOnboardingScreen`) before ever
                    // reaching `TimelineScreen` at all. That meant a fresh sign-in always
                    // showed two distinct, separately-composed `CircularProgressIndicator`
                    // spinners in sequence -- TimelineScreen's own unification (see its
                    // doc) only ever covered a *relock* while already mounted, not this
                    // very first mount -- reported live 2026-09-26 as "still 2 distinct
                    // spinners" even after that first fix. Removing the split here is
                    // what actually closes the gap: TimelineScreen mounts exactly once,
                    // right after sign-in, and never again for the rest of the process.
                    TimelineScreen(
                        onSessionEnded = { signedIn = false },
                        modifier = Modifier.padding(innerPadding),
                    )
                } else {
                    SignInScreen(
                        onSignedIn = { signedIn = true },
                        onChangeServer = connectViewModel::changeInstance,
                        modifier = Modifier.padding(innerPadding),
                    )
                }

            is ConnectUiState.NeedsConnection ->
                ConnectScreen(
                    state = s,
                    onConnect = connectViewModel::connect,
                    onPreviewWithoutAccount = connectViewModel::enterReviewerPreview,
                    modifier = Modifier.padding(innerPadding),
                )

            // Plan step 2.17: structurally parallel to Connected above, not a branch of
            // it — this path touches neither `signedIn` here nor anything inside
            // `TimelineScreen`'s own lock state, and nothing reachable from
            // ReviewerPreviewScreen can construct a network client at all.
            //
            // Plan step 2.19: preview mode needs the media-library permission just as
            // much as a real session does -- MediaStoreSource can't see anything beyond
            // this app's own files without it. All three steps show here, notifications
            // included, even though preview mode itself never fires one -- these screens
            // exist so a Play reviewer sees the complete, real set of prompts this app
            // can ever show, not just the subset a given mode happens to use.
            ConnectUiState.ReviewerPreview ->
                PermissionOnboardingScreen(modifier = Modifier.padding(innerPadding)) {
                    ReviewerPreviewScreen(onExit = connectViewModel::exitReviewerPreview, modifier = Modifier.padding(innerPadding))
                }
        }
    }
}

@Composable
private fun Centered(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        content()
    }
}
