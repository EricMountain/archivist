package fr.enry.archivist.data.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import fr.enry.archivist.crypto.Aad
import fr.enry.archivist.crypto.MasterKey
import fr.enry.archivist.crypto.ObjectRef
import fr.enry.archivist.crypto.WholeObjectCipher
import fr.enry.archivist.data.local.InstanceStore
import fr.enry.archivist.data.local.TokenStore
import fr.enry.archivist.data.remote.ArchivistApiFactory
import fr.enry.archivist.data.remote.CognitoAuthClient
import fr.enry.archivist.data.remote.DiscoveryDocument
import fr.enry.archivist.domain.ExifBlob
import fr.enry.archivist.testutil.FakeCognitoAuthApi
import fr.enry.archivist.testutil.FakeSharedPreferences
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Plan step 2.12: [PhotoDetailRepository.fetchDetail] against a real Retrofit stack
 * (MockWebServer standing in for the instance), following [UploadRepositoryTest]'s own
 * pattern of decrypting what actually came back rather than only checking the request
 * shape.
 */
class PhotoDetailRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var instanceStore: InstanceStore
    private lateinit var masterKeyHolder: MasterKeyHolder
    private lateinit var repository: PhotoDetailRepository

    private val json = Json { ignoreUnknownKeys = true }
    private val exifJson = Json { ignoreUnknownKeys = true }
    private val host = "photos.example.com"
    private val photoId = "01K5A2Q8ZCV1000000000000"
    private val rawMasterKey = ByteArray(32) { it.toByte() }
    private val masterKey = MasterKey.of(rawMasterKey)
    private val dek = ByteArray(32) { (it + 1).toByte() }

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("photo-detail-repository-test").toFile()

        val dataStore = PreferenceDataStoreFactory.create(produceFile = { File(tempDir, "instances.preferences_pb") })
        instanceStore = InstanceStore(dataStore, json)
        masterKeyHolder = MasterKeyHolder()

        val archivistApiFactory =
            ArchivistApiFactory(
                baseOkHttpClient = OkHttpClient.Builder().build(),
                json = json,
                tokenStore = TokenStore(FakeSharedPreferences(), json),
                cognitoAuthClient = CognitoAuthClient(FakeCognitoAuthApi(), json),
            )

        repository =
            PhotoDetailRepository(
                instanceStore = instanceStore,
                archivistApiFactory = archivistApiFactory,
                masterKeyHolder = masterKeyHolder,
                okHttpClient = OkHttpClient.Builder().build(),
            )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    private suspend fun connectInstance() {
        instanceStore.save(
            host,
            DiscoveryDocument(
                apiBase = server.url("/api").toString().trimEnd('/'),
                region = "eu-west-1",
                cognito = DiscoveryDocument.CognitoConfig(userPoolId = "eu-west-1_XXXXXXXXX", clientId = "client-id"),
                cryptoVersion = 1,
                instanceName = "Home photos",
            ),
        )
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun encryptExif(blob: ExifBlob): Pair<String, String> {
        val iv = ByteArray(WholeObjectCipher.IV_LEN) { (it + 2).toByte() }
        val ciphertext =
            WholeObjectCipher.encrypt(
                dek,
                iv,
                Aad.of(photoId, ObjectRef.Exif),
                exifJson.encodeToString(blob).toByteArray(Charsets.UTF_8),
            )
        return encode(ciphertext) to encode(iv)
    }

    private fun metaJson(
        exifEnc: String? = null,
        exifIv: String? = null,
        primaryRend: String? = "r1",
    ): String =
        """
        {
          "pk": "OWNER#owner#PHOTO#$photoId",
          "sk": "#META",
          "ownerId": "owner",
          "photoId": "$photoId",
          "stem": "IMG_1",
          "primaryRend": ${primaryRend?.let { "\"$it\"" }},
          "renditions": 1,
          "mime": "image/jpeg",
          "width": 4000,
          "height": 3000,
          "enc": "AES-256-GCM",
          "encDek": "${encode(masterKey.wrapDek(dek))}",
          "encKeyId": "mk-1",
          "takenAt": "2026-08-30T10:00:00.000Z",
          "tzOffsetMin": 60,
          "tzSrc": "exif-offset",
          "takenAtSrc": "exif",
          "uploadedAt": "2026-08-30T10:05:00.000Z",
          "thumbs": {},
          ${exifEnc?.let { "\"exifEnc\": \"$it\"," } ?: ""}
          ${exifIv?.let { "\"exifIv\": \"$it\"," } ?: ""}
          "groupSrc": "stem",
          "status": "ready"
        }
        """.trimIndent()

    private fun detailResponseJson(
        exifEnc: String? = null,
        exifIv: String? = null,
        primaryRend: String? = "r1",
    ): String =
        """
        {
          "meta": ${metaJson(exifEnc, exifIv, primaryRend)},
          "renditions": [
            {
              "pk": "OWNER#owner#PHOTO#$photoId",
              "sk": "R#r1",
              "renditionId": "r1",
              "role": "display",
              "path": "Camera/IMG_1.jpg",
              "ext": "jpg",
              "mime": "image/jpeg",
              "s3Bucket": "archivist-originals",
              "s3Key": "raw/owner/$photoId/r1",
              "contentHash": "hmac-sha256:test",
              "bytes": 1040,
              "plainBytes": 1024,
              "width": 4000,
              "height": 3000,
              "encIv": "${encode(ByteArray(WholeObjectCipher.IV_LEN))}",
              "encChunkSize": 0,
              "addedAt": "2026-08-30T10:05:00.000Z"
            }
          ],
          "facets": []
        }
        """.trimIndent()

    @Test
    fun `fetchDetail decrypts exifEnc into camera fields when the master key is available`() =
        runTest {
            connectInstance()
            masterKeyHolder.set(masterKey)
            val blob = ExifBlob(cameraMake = "Canon", cameraModel = "EOS R5")
            val (exifEnc, exifIv) = encryptExif(blob)
            server.enqueue(MockResponse().setResponseCode(200).setBody(detailResponseJson(exifEnc, exifIv)))

            val detail = repository.fetchDetail(photoId)

            assertEquals("Canon", detail.cameraMake)
            assertEquals("EOS R5", detail.cameraModel)
            assertFalse(detail.exifDecryptFailed)
            assertEquals("exif", detail.takenAtSrc)
            assertEquals("r1", detail.primaryRend)
            assertEquals(1, detail.renditions.size)
            assertEquals("r1", detail.renditions[0].renditionId)
            assertEquals(1024L, detail.renditions[0].plainBytes)
        }

    @Test
    fun `fetchDetail leaves camera fields null when the asset has no EXIF at all`() =
        runTest {
            connectInstance()
            masterKeyHolder.set(masterKey)
            server.enqueue(MockResponse().setResponseCode(200).setBody(detailResponseJson()))

            val detail = repository.fetchDetail(photoId)

            assertNull(detail.cameraMake)
            assertNull(detail.cameraModel)
            assertFalse(detail.exifDecryptFailed)
        }

    @Test
    fun `fetchDetail reports exifDecryptFailed rather than silently null when EXIF exists but the key can't decrypt it`() =
        runTest {
            connectInstance()
            // Master key never set -- locked, same as a photo opened right after
            // ArchivistApplication.onTrimMemory cleared it.
            val blob = ExifBlob(cameraMake = "Canon")
            val (exifEnc, exifIv) = encryptExif(blob)
            server.enqueue(MockResponse().setResponseCode(200).setBody(detailResponseJson(exifEnc, exifIv)))

            val detail = repository.fetchDetail(photoId)

            assertNull(detail.cameraMake)
            assertTrue(detail.exifDecryptFailed)
        }

    // downloadOriginal itself -- fetch over a hardcoded `https://` CloudFront URL, then
    // decrypt -- is deliberately not covered here: it hardcodes `https://`
    // (`fr.enry.archivist.crypto.EncryptedThumbRef.url`'s callers do the same, and real
    // CloudFront always terminates TLS), and MockWebServer has no TLS listener without
    // extra certificate bootstrapping this test doesn't set up. Same gap as
    // `fr.enry.archivist.crypto.EncryptedImageFetcher`, which has no JVM test either --
    // see this step's STATUS.md note.
}
