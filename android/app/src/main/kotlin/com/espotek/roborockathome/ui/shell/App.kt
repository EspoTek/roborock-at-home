package com.espotek.roborockathome.ui.shell

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import com.espotek.roborockathome.core.model.Look
import com.espotek.roborockathome.core.state.Home
import com.espotek.roborockathome.core.state.Link
import com.espotek.roborockathome.ui.common.LocalMessages
import com.espotek.roborockathome.ui.common.rememberMessages
import com.espotek.roborockathome.ui.common.rememberRunner
import com.espotek.roborockathome.ui.signin.SignInScreen
import com.espotek.roborockathome.ui.theme.RahTheme
import java.time.ZoneId

/** The whole app: the sign-in form until someone is signed in, then their session. */
@Composable
fun App(model: AppModel) {
    when (val session = model.session) {
        // Before anyone is signed in, the device decides light or dark.
        null -> RahTheme(dark = isSystemInDarkTheme()) {
            // The form shows every refusal itself; the runner it uses needs messages to exist.
            CompositionLocalProvider(LocalMessages provides rememberMessages()) {
                SignInScreen(model.form, connect = model::connect, onSignedIn = model::signedIn)
            }
        }
        else -> key(session) { Session(model, session) }
    }
}

@Composable
private fun Session(model: AppModel, session: SignedIn) {
    val home by session.store.home.collectAsState()
    val dark = when (home.session?.look ?: model.lastLook ?: Look.System) {
        Look.System -> isSystemInDarkTheme()
        Look.Light -> false
        Look.Dark -> true
    }
    val messages = rememberMessages()
    RahTheme(dark) {
        CompositionLocalProvider(LocalMessages provides messages) {
            SetTimeZoneOnce(session, home)
            if (home.link == Link.Unreadable) {
                UnreadableScreen(
                    problem = checkNotNull(home.problem) { "An unreadable home says what could not be read" },
                    onTryAgain = session.store::start,
                    onSignOut = { model.signOut(session) },
                )
            } else {
                Shell(
                    home,
                    session.store,
                    session.serverUrl,
                    onSignOut = { model.signOut(session) },
                    messages = messages,
                )
            }
        }
    }
}

/** The page's tzTried: the first time a home has no time zone, it gets the phone's, once a session. */
@Composable
private fun SetTimeZoneOnce(session: SignedIn, home: Home) {
    val runner = rememberRunner()
    val app = home.app
    val unset = home.link == Link.Live && app != null && app.tz == null
    LaunchedEffect(unset) {
        if (unset && !session.timeZoneTried) {
            session.timeZoneTried = true
            runner.launch { session.store.api.setTimeZone(ZoneId.systemDefault().id) }
        }
    }
}
