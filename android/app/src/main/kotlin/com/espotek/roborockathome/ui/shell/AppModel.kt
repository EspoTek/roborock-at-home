package com.espotek.roborockathome.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.autoSaver
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi
import androidx.lifecycle.viewmodel.compose.saveable
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.espotek.roborockathome.core.api.RoborockApi
import com.espotek.roborockathome.core.model.Look
import com.espotek.roborockathome.core.state.HomeStore
import com.espotek.roborockathome.core.state.Link
import com.espotek.roborockathome.data.Preferences
import com.espotek.roborockathome.ui.signin.SignInForm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** A signed-in session: the server at [serverUrl] as the person typed it, normalised, and the store that talks to it. */
class SignedIn internal constructor(val serverUrl: String, val store: HomeStore) {
    // The page's tzTried: the server's time zone is set from the phone's at most once a session.
    internal var timeZoneTried = false
    internal lateinit var watching: Job
}

/**
 * The app's state between launches and across rotation: who is signed in, and the sign-in form.
 * The session's store streams only while the app is visible, since a phone should not hold the
 * stream open in a pocket; stopped, it keeps what it held, so the screens are never blank on return.
 */
@OptIn(SavedStateHandleSaveableApi::class)
class AppModel(private val prefs: Preferences, saved: SavedStateHandle, private val http: OkHttpClient) : ViewModel() {
    /** The sign-in form, starting from the address and name last signed in with. */
    val form = SignInForm(
        saved.saveable("address", stateSaver = autoSaver()) { mutableStateOf(prefs.serverUrl ?: "") },
        saved.saveable("name", stateSaver = autoSaver()) { mutableStateOf(prefs.name ?: "") },
    )

    /** Null while nobody is signed in on this phone. */
    var session: SignedIn? by mutableStateOf(null)
        private set

    /** The look last used on this phone, for the moment before the server says; null until it first has. */
    val lastLook: Look? get() = prefs.look

    private val app = ProcessLifecycleOwner.get().lifecycle
    private val visibility = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> session?.store?.start()
            Lifecycle.Event.ON_STOP -> session?.store?.stop()
            else -> {}
        }
    }

    init {
        app.addObserver(visibility)
        val serverUrl = prefs.serverUrl
        if (serverUrl != null && prefs.cookie != null) open(serverUrl, connect(serverUrl))
    }

    /** A client for the server at [serverUrl] that keeps its session cookie on the phone. */
    fun connect(serverUrl: String): RoborockApi = RoborockApi(serverUrl, prefs, http)

    /** Someone has signed in at [serverUrl] as [name] through [api]: both are remembered for next time and the session starts. */
    fun signedIn(serverUrl: String, name: String, api: RoborockApi) {
        prefs.serverUrl = serverUrl
        prefs.name = name
        open(serverUrl, api)
    }

    /**
     * Forgets the cookie and returns to the sign-in form. The server keeps no record of who is signed
     * in (a session is only the signed cookie), so there is nothing to tell it, and signing out works
     * just as well when the server has moved or is switched off.
     */
    fun signOut(session: SignedIn) = end(session)

    private fun open(serverUrl: String, api: RoborockApi) {
        // Reading the stream's JSON is kept off the main thread.
        val store = HomeStore(api, CoroutineScope(viewModelScope.coroutineContext + Dispatchers.Default))
        val signedIn = SignedIn(serverUrl, store)
        signedIn.watching = viewModelScope.launch {
            store.home.collect { home ->
                val look = home.session?.look
                if (look != null && look != prefs.look) prefs.look = look
                if (home.link == Link.SignedOut) end(signedIn)
            }
        }
        session = signedIn
        if (app.currentState.isAtLeast(Lifecycle.State.STARTED)) store.start()
    }

    private fun end(signedIn: SignedIn) {
        // A session can end twice: by Sign out, and by the store hearing that the server no longer
        // accepts the cookie.
        if (session !== signedIn) return
        session = null
        signedIn.store.stop()
        signedIn.watching.cancel()
        prefs.cookie = null
    }

    override fun onCleared() {
        app.removeObserver(visibility)
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(this[APPLICATION_KEY]) { "An activity's view models have the application" }
                AppModel(Preferences(application), createSavedStateHandle(), OkHttpClient())
            }
        }
    }
}
