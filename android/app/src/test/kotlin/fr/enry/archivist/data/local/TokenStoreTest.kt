package fr.enry.archivist.data.local

import fr.enry.archivist.testutil.FakeSharedPreferences
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TokenStoreTest {
    private val preferences = FakeSharedPreferences()
    private val store = TokenStore(preferences, Json { ignoreUnknownKeys = true })

    private val session =
        AuthSession(
            username = "a@example.com",
            accessToken = "access",
            idToken = "id",
            refreshToken = "refresh",
            accessTokenExpiresAt = 1_000_000L,
        )

    @Test
    fun `no session saved yet returns null`() {
        assertNull(store.get("photos.example.com"))
    }

    @Test
    fun `a saved session round-trips exactly`() {
        store.save("photos.example.com", session)

        assertEquals(session, store.get("photos.example.com"))
    }

    @Test
    fun `sessions for different hosts don't collide`() {
        store.save("photos.example.com", session)
        val second = session.copy(username = "b@example.com", accessToken = "access-2")
        store.save("photos2.example.com", second)

        assertEquals(session, store.get("photos.example.com"))
        assertEquals(second, store.get("photos2.example.com"))
    }

    @Test
    fun `clear removes only that host's session`() {
        store.save("photos.example.com", session)
        store.save("photos2.example.com", session.copy(username = "b@example.com"))

        store.clear("photos.example.com")

        assertNull(store.get("photos.example.com"))
        assertEquals("b@example.com", store.get("photos2.example.com")?.username)
    }

    @Test
    fun `saving again for the same host overwrites the old session`() {
        store.save("photos.example.com", session)
        val refreshed = session.copy(accessToken = "new-access", accessTokenExpiresAt = 2_000_000L)

        store.save("photos.example.com", refreshed)

        assertEquals(refreshed, store.get("photos.example.com"))
    }
}
