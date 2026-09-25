package fr.enry.archivist.data.repo

import android.content.Context
import fr.enry.archivist.crypto.Aad
import fr.enry.archivist.crypto.EnvelopeCrypto
import fr.enry.archivist.crypto.MasterKey
import fr.enry.archivist.crypto.ObjectRef
import fr.enry.archivist.crypto.WholeObjectCipher
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class PreviewCacheTest {
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private lateinit var masterKeyHolder: MasterKeyHolder
    private lateinit var cache: PreviewCache
    private val requests = AtomicInteger()

    private val masterKey = MasterKey.of(ByteArray(32) { it.toByte() })
    private val dek = ByteArray(32) { (it + 1).toByte() }
    private val photoId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
    private val iv = ByteArray(12) { 3 }
    private val plaintext = ByteArray(5_000) { (it % 251).toByte() }

    private fun encode(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    /** Wrapped once, up front: [MasterKeyHolder.clear] zeroizes the very [MasterKey]
     * object it holds, after which that instance can't wrap anything. */
    private val wrappedDek = encode(masterKey.wrapDek(dek))

    private fun ciphertext(aadRef: ObjectRef = ObjectRef.Preview) =
        WholeObjectCipher.encrypt(dek, iv, Aad.of(photoId, aadRef), plaintext)

    private fun ref(path: String = "/thumbs/th/o/$photoId/preview") =
        PreviewRef(photoId, server.url(path).toString(), encode(iv), wrappedDek)

    private fun serve(body: () -> MockResponse) {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.incrementAndGet()
                    return body()
                }
            }
    }

    private fun ok(bytes: ByteArray) = MockResponse().setResponseCode(200).setBody(Buffer().write(bytes))

    @BeforeEach
    fun setUp() {
        server = MockWebServer().also { it.start() }
        tempDir = Files.createTempDirectory("preview-cache-test").toFile()
        masterKeyHolder = MasterKeyHolder().apply { set(masterKey) }
        val context = mock<Context>().also { whenever(it.cacheDir).thenReturn(tempDir) }
        cache = PreviewCache(context, OkHttpClient.Builder().build(), masterKeyHolder)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    @Test
    fun `downloads, decrypts under the preview AAD, and returns the plaintext clip`() =
        runTest {
            serve { ok(ciphertext()) }

            val file = cache.get(ref())

            assertNotNull(file)
            assertArrayEquals(plaintext, file!!.readBytes())
            assertTrue(file.absolutePath.startsWith(tempDir.absolutePath))
        }

    @Test
    fun `a second get is a cache hit -- no network and no master key needed`() =
        runTest {
            serve { ok(ciphertext()) }
            assertNotNull(cache.get(ref()))
            assertEquals(1, requests.get())

            masterKeyHolder.clear()
            val again = cache.get(ref())

            assertNotNull(again)
            assertEquals(1, requests.get())
        }

    @Test
    fun `a missing object is null, not an exception, and isn't re-requested`() =
        runTest {
            serve { MockResponse().setResponseCode(403) }

            assertNull(cache.get(ref()))
            assertNull(cache.get(ref()))

            assertEquals(1, requests.get())
        }

    @Test
    fun `ciphertext bound to a different object kind fails authentication`() =
        runTest {
            // A preview relabelled as the 256px still (conformance case 25) must not decrypt.
            serve { ok(ciphertext(ObjectRef.Thumbnail(256))) }

            assertNull(cache.get(ref()))
        }

    @Test
    fun `the wrong photoId in the ref fails authentication`() =
        runTest {
            serve { ok(ciphertext()) }

            assertNull(cache.get(ref().copy(photoId = "01ARZ3NDEKTSV4RRFFQ69G5FAW")))
        }

    @Test
    fun `a locked app returns null without a request, and works once unlocked`() =
        runTest {
            serve { ok(ciphertext()) }
            masterKeyHolder.clear()

            assertNull(cache.get(ref()))
            assertEquals(0, requests.get())

            masterKeyHolder.set(MasterKey.of(ByteArray(32) { it.toByte() }))
            assertNotNull(cache.get(ref())) // locked is not remembered as a failure
        }

    @Test
    fun `an oversized response is refused`() =
        runTest {
            serve { MockResponse().setResponseCode(200).setBody(Buffer().write(ByteArray((MAX_PREVIEW_DOWNLOAD_BYTES + 1).toInt()))) }

            assertNull(cache.get(ref()))
        }

    @Test
    fun `concurrent gets for the same preview download it once`() =
        runTest {
            serve { ok(ciphertext()) }

            val results = (1..5).map { async { cache.get(ref()) } }.awaitAll()

            assertTrue(results.all { it != null })
            assertEquals(1, requests.get())
        }

    @Test
    fun `clear deletes cached previews and forgets remembered failures`() =
        runTest {
            serve { MockResponse().setResponseCode(404) }
            assertNull(cache.get(ref()))
            serve { ok(ciphertext()) }
            assertNull(cache.get(ref())) // still remembered as failed

            cache.clear()

            val file = cache.get(ref())
            assertNotNull(file)
            cache.clear()
            assertFalse(file!!.exists())
        }

    private fun entry(name: String, bytes: Long, age: Long, now: Long = 1_000_000L) = CacheEntry(name, bytes, now - age)

    @Test
    fun `nothing is evicted while under budget`() {
        assertEquals(emptyList<String>(), selectEvictions(listOf(entry("a", 40, 999), entry("b", 40, 1)), 100, 1_000_000L, 60_000L))
    }

    @Test
    fun `eviction removes the least recently used first, only as many as needed`() {
        val entries = listOf(entry("new", 40, 100_000), entry("old", 40, 300_000), entry("mid", 40, 200_000))
        // 120 bytes against a budget of 100: dropping the single oldest (40) is enough.
        assertEquals(listOf("old"), selectEvictions(entries, 100, 1_000_000L, 60_000L))
        // Budget of 50 needs two gone.
        assertEquals(listOf("old", "mid"), selectEvictions(entries, 50, 1_000_000L, 60_000L))
    }

    @Test
    fun `a recently touched file is never evicted, even when that leaves the cache over budget`() {
        val entries = listOf(entry("playing", 80, 1_000), entry("stale", 80, 500_000))
        assertEquals(listOf("stale"), selectEvictions(entries, 10, 1_000_000L, 60_000L))
    }
}
