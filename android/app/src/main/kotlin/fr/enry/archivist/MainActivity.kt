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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import fr.enry.archivist.ui.onboarding.ConnectScreen
import fr.enry.archivist.ui.onboarding.ConnectUiState
import fr.enry.archivist.ui.onboarding.ConnectViewModel
import fr.enry.archivist.ui.onboarding.EnrolmentScreen
import fr.enry.archivist.ui.onboarding.SignInScreen
import fr.enry.archivist.ui.theme.ArchivistTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ArchivistTheme {
                ArchivistApp()
            }
        }
    }
}

@Composable
private fun ArchivistApp(connectViewModel: ConnectViewModel = hiltViewModel()) {
    val connectState by connectViewModel.uiState.collectAsStateWithLifecycle()
    var signedIn by remember { mutableStateOf(false) }
    var unlocked by remember { mutableStateOf(false) }

    Scaffold { innerPadding ->
        when (val s = connectState) {
            ConnectUiState.CheckingStoredInstance ->
                Centered(Modifier.padding(innerPadding)) { CircularProgressIndicator() }

            is ConnectUiState.Connected ->
                when {
                    unlocked ->
                        // Placeholder until 2.6+ adds a real screen to land on.
                        Centered(Modifier.padding(innerPadding)) { Text("Unlocked on ${s.instanceName}") }

                    signedIn ->
                        EnrolmentScreen(onUnlocked = { unlocked = true }, modifier = Modifier.padding(innerPadding))

                    else ->
                        SignInScreen(
                            onSignedIn = { signedIn = true },
                            onChangeServer = connectViewModel::changeInstance,
                            modifier = Modifier.padding(innerPadding),
                        )
                }

            is ConnectUiState.NeedsConnection ->
                ConnectScreen(state = s, onConnect = connectViewModel::connect, modifier = Modifier.padding(innerPadding))
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
