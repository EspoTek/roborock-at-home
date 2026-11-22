package com.espotek.roborockathome.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.espotek.roborockathome.core.api.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs one control's requests, the page's `run()`. Give the control `busy = runner.busy` and
 * `onClick = { runner.launch { store.api.command(robot.id, Command.Dock) } }`.
 */
@Stable
class Runner internal constructor(private val scope: CoroutineScope, private val messages: Messages) {
    /** True from the press until the request has answered or failed. */
    var busy: Boolean by mutableStateOf(false)
        private set

    /**
     * Starts [request] unless one is already running, in which case the press is ignored. When the
     * server refuses it or cannot be reached, its sentence goes to [onError], for a form that shows
     * it inline, or without one appears as a failure message. Anything else is a bug and is thrown.
     */
    fun launch(onError: ((String) -> Unit)? = null, request: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                request()
            } catch (e: ApiException) {
                val sentence = checkNotNull(e.message) { "An ApiException always has a sentence" }
                if (onError != null) onError(sentence) else messages.failure(sentence)
            } finally {
                busy = false
            }
        }
    }
}

/** A [Runner] for one control. Its requests end when the control leaves the screen. */
@Composable
fun rememberRunner(): Runner {
    val scope = rememberCoroutineScope()
    val messages = LocalMessages.current
    return remember(scope, messages) { Runner(scope, messages) }
}
