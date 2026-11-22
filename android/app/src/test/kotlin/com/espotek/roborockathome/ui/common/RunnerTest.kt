package com.espotek.roborockathome.ui.common

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import com.espotek.roborockathome.core.api.ApiException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RunnerTest {
    @get:Rule
    val compose = createComposeRule()

    private val messages = Messages()
    private lateinit var runner: Runner

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalMessages provides messages) { runner = rememberRunner() }
        }
        compose.waitForIdle()
    }

    private val refused = ApiException(503, "robot_unreachable", "Downstairs did not answer", null)

    @Test
    fun busyAtOnceAndASecondPressIsIgnored() {
        show()
        val answer = CompletableDeferred<Unit>()
        var second = false
        compose.runOnIdle {
            runner.launch { answer.await() }
            assertTrue(runner.busy)
            runner.launch { second = true }
        }
        compose.waitForIdle()
        assertTrue(runner.busy)
        compose.runOnIdle { answer.complete(Unit) }
        compose.waitForIdle()
        assertFalse(runner.busy)
        assertFalse(second)
    }

    @Test
    fun aRefusalWithoutAFormIsAFailureMessage() {
        show()
        compose.runOnIdle { runner.launch { throw refused } }
        compose.waitForIdle()
        assertFalse(runner.busy)
        val shown = checkNotNull(messages.current)
        assertEquals("Downstairs did not answer", shown.text)
        assertTrue(shown.failure)
    }

    @Test
    fun aRefusalForAFormGoesToTheForm() {
        show()
        var inline: String? = null
        compose.runOnIdle { runner.launch(onError = { inline = it }) { throw refused } }
        compose.waitForIdle()
        assertEquals("Downstairs did not answer", inline)
        assertNull(messages.current)
    }
}
