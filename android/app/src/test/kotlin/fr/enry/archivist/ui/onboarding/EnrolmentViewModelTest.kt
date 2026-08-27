package fr.enry.archivist.ui.onboarding

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.enry.archivist.crypto.DeviceKeystoreUnsupportedException
import fr.enry.archivist.data.local.EnrolmentStore
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.TokenStore
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.CognitoAuthClient
import fr.enry.archivist.data.remote.DiscoveryDocument
import fr.enry.archivist.data.remote.KeyWrapDto
import fr.enry.archivist.data.remote.KeysResponse
import fr.enry.archivist.data.repo.EnrolmentRepository
import fr.enry.archivist.data.repo.MasterKeyHolder
import fr.enry.archivist.testutil.FakeCognitoAuthApi
import fr.enry.archivist.testutil.FakeDeviceKeyProvider
import fr.enry.archivist.testutil.FakeSharedPreferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EnrolmentViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var deviceKeyProvider: FakeDeviceKeyProvider
    private lateinit var viewModel: EnrolmentViewModel
    private val json = Json { ignoreUnknownKeys = true }

    private val host = "photos.example.com"

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("enrolment-viewmodel-test").toFile()

        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(dispatcher),
                produceFile = { File(tempDir, "instances.preferences_pb") },
            )
        val instanceStore = InstanceStore(dataStore, json)
        deviceKeyProvider = FakeDeviceKeyProvider()
        val archivistApiFactory =
            ArchivistApiFactory(
                baseOkHttpClient = OkHttpClient.Builder().build(),
                json = json,
                tokenStore = TokenStore(FakeSharedPreferences(), json),
                cognitoAuthClient = CognitoAuthClient(FakeCognitoAuthApi(), json),
            )
        val repository =
            EnrolmentRepository(
                instanceStore = instanceStore,
                archivistApiFactory = archivistApiFactory,
                enrolmentStore = EnrolmentStore(FakeSharedPreferences()),
                deviceKeystore = deviceKeyProvider,
                masterKeyHolder = MasterKeyHolder(),
            )

        runTest(dispatcher) {
            instanceStore.save(
                host,
                DiscoveryDocument(
                    apiBase = server.url("/api").toString().trimEnd('/'),
                    region = "eu-west-1",
                    cognito =
                        DiscoveryDocument.CognitoConfig(userPoolId = "eu-west-1_XXXXXXXXX", clientId = "client-id"),
                    cryptoVersion = 1,
                    instanceName = "Home photos",
                ),
            )
        }

        viewModel = EnrolmentViewModel(repository)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
        tempDir.deleteRecursively()
    }

    /** See `SignInViewModelTest`'s identical helper: `advanceUntilIdle()` alone can't
     * wait for a real OkHttp/MockWebServer round trip, which resumes the coroutine
     * from a real thread at a real wall-clock moment. */
    private fun awaitState(
        timeoutMs: Long = 2000,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            dispatcher.scheduler.advanceUntilIdle()
            if (predicate()) return
            Thread.sleep(5)
        }
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `an empty wrap list leads straight to a generated recovery code`() =
        runTest {
            server.enqueue(MockResponse().setBody(json.encodeToString(KeysResponse.serializer(), KeysResponse(emptyList()))))

            awaitState { viewModel.uiState.value is EnrolmentUiState.ShowRecoveryCode }

            val state = viewModel.uiState.value
            assertTrue(state is EnrolmentUiState.ShowRecoveryCode)
            assertEquals(30, (state as EnrolmentUiState.ShowRecoveryCode).formattedCode.length) // 26 chars + 4 hyphens
        }

    @Test
    fun `a device below API 31 is told plainly, not left generating forever`() =
        runTest {
            deviceKeyProvider.unsupportedException = DeviceKeystoreUnsupportedException(28)
            server.enqueue(MockResponse().setBody(json.encodeToString(KeysResponse.serializer(), KeysResponse(emptyList()))))

            awaitState { viewModel.uiState.value is EnrolmentUiState.DeviceKeystoreUnsupported }

            assertEquals(28, (viewModel.uiState.value as EnrolmentUiState.DeviceKeystoreUnsupported).sdkInt)
        }

    @Test
    fun `saving the code moves to confirmation, and a mismatched retype is rejected`() =
        runTest {
            server.enqueue(MockResponse().setBody(json.encodeToString(KeysResponse.serializer(), KeysResponse(emptyList()))))
            awaitState { viewModel.uiState.value is EnrolmentUiState.ShowRecoveryCode }

            viewModel.proceedToConfirmation()
            assertTrue(viewModel.uiState.value is EnrolmentUiState.ConfirmRecoveryCode)

            viewModel.confirmTypedCode("0000000000000000000000000")

            val state = viewModel.uiState.value
            assertTrue(state is EnrolmentUiState.ConfirmRecoveryCode && state.error != null)
        }

    @Test
    fun `confirming the exact code finishes enrolment and unlocks`() =
        runTest {
            server.enqueue(MockResponse().setBody(json.encodeToString(KeysResponse.serializer(), KeysResponse(emptyList()))))
            awaitState { viewModel.uiState.value is EnrolmentUiState.ShowRecoveryCode }
            val shown = viewModel.uiState.value as EnrolmentUiState.ShowRecoveryCode
            viewModel.proceedToConfirmation()

            server.enqueue(MockResponse().setBody("""{"masterKeyVer":"mk-1","rotatedAt":"2026-01-01T00:00:00.000Z"}"""))
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"wrapId":"w-device","masterKeyVer":"mk-1"}"""))
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"wrapId":"w-recovery","masterKeyVer":"mk-1"}"""))
            server.enqueue(MockResponse().setResponseCode(204))

            // Strip the code's formatting hyphens back out before typing it "back in".
            viewModel.confirmTypedCode(shown.formattedCode.replace("-", ""))
            awaitState { viewModel.uiState.value == EnrolmentUiState.Unlocked }

            assertEquals(EnrolmentUiState.Unlocked, viewModel.uiState.value)
        }

    @Test
    fun `a returning owner is asked for a recovery code, and a mistyped one is rejected locally`() =
        runTest {
            server.enqueue(
                MockResponse().setBody(
                    json.encodeToString(
                        KeysResponse.serializer(),
                        KeysResponse(listOf(KeyWrapDto("rec1", "recovery", "Recovery code", "mk-1"))),
                    ),
                ),
            )

            awaitState { viewModel.uiState.value is EnrolmentUiState.EnterRecoveryCode }
            assertEquals(false, (viewModel.uiState.value as EnrolmentUiState.EnterRecoveryCode).reenrolling)

            viewModel.submitRecoveryCode("0000000000000000000000000")
            awaitState {
                val s = viewModel.uiState.value
                s is EnrolmentUiState.EnterRecoveryCode && s.error != null
            }

            assertEquals(1, server.requestCount) // the mistyped attempt made no further request
        }
}
