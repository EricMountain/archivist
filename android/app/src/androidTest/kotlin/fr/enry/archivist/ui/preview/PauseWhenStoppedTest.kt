package fr.enry.archivist.ui.preview

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * [PauseWhenStopped] against a real [ExoPlayer] and a hand-driven lifecycle -- stands in for
 * "press Home with a preview looping", which on a real phone left a player decoding in the
 * background until Android killed the process (see the composable's own doc). No touch
 * input, so it runs on the local preview-image AVDs too (`android/AGENTS.md`).
 */
class PauseWhenStoppedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val owner = TestOwner()
    private lateinit var player: ExoPlayer

    private fun setUp(
        initialState: Lifecycle.State,
        playWhenReady: Boolean,
    ) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            owner.registry.currentState = initialState
            player =
                ExoPlayer.Builder(InstrumentationRegistry.getInstrumentation().targetContext).build().also {
                    it.playWhenReady = playWhenReady
                }
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { PauseWhenStopped(player) }
        }
    }

    private fun moveTo(state: Lifecycle.State) = composeRule.runOnIdle { owner.registry.currentState = state }

    private fun playWhenReady() = composeRule.runOnIdle { player.playWhenReady }

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { player.release() }
    }

    @Test
    fun pausesWhenStoppedAndResumesWhenStarted() {
        setUp(Lifecycle.State.RESUMED, playWhenReady = true)
        assertTrue(playWhenReady())

        moveTo(Lifecycle.State.CREATED)
        assertFalse(playWhenReady())

        moveTo(Lifecycle.State.RESUMED)
        assertTrue(playWhenReady())
    }

    @Test
    fun userPauseSurvivesStopAndStart() {
        setUp(Lifecycle.State.RESUMED, playWhenReady = false)

        moveTo(Lifecycle.State.CREATED)
        moveTo(Lifecycle.State.RESUMED)
        assertFalse(playWhenReady())
    }

    @Test
    fun createdWhileStoppedWaitsForStart() {
        setUp(Lifecycle.State.CREATED, playWhenReady = true)
        assertFalse(playWhenReady())

        moveTo(Lifecycle.State.STARTED)
        assertTrue(playWhenReady())
    }
}
