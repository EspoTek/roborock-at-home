package com.espotek.roborockathome.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.espotek.roborockathome.core.model.AppInfo
import com.espotek.roborockathome.core.state.Home
import com.espotek.roborockathome.core.state.HomeStore
import com.espotek.roborockathome.ui.common.rememberTimeWords
import com.espotek.roborockathome.ui.theme.Rah

/** The pages the Settings index opens, in its order. */
internal enum class SettingsPage(val title: String) {
    Account("Roborock account"),
    TimeZone("Time zone"),
    RemoteAccess("Remote access"),
    People("People"),
    Tokens("API tokens"),
    Password("Password"),
}

/**
 * The Settings destination: the index, with Appearance on it, and the page each of its rows opens,
 * drawn here with its own Back while the bottom bar stays. It shows [home], acts through [store],
 * shows [serverUrl] (the address signed in to, as the person sees it) and calls [onSignOut] for Sign
 * out, which the app carries out.
 *
 * Which page is open and what is typed on it survive rotation and the process being killed, except
 * for [Secrets]. People, tokens and the server's figures are fetched when a page that shows them
 * opens, and never saved.
 */
@Composable
fun SettingsScreen(home: Home, store: HomeStore, serverUrl: String, onSignOut: () -> Unit) {
    val words = rememberTimeWords()
    var open by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    val drafts = rememberSaveableStateHolder()
    val secrets: Secrets = viewModel()
    val users = rememberFetched { store.api.users() }
    val tokens = rememberFetched { store.api.tokens() }
    val stats = rememberFetched { store.api.stats() }
    val zones = remember { platformZones() }

    // A page opens empty and leaves nothing behind when it closes, as Back does elsewhere on Android.
    fun show(page: SettingsPage?) {
        open?.let { drafts.removeState(it.name) }
        secrets.clear()
        open = page
    }
    val back = { show(null) }
    BackHandler(enabled = open != null, onBack = back)

    Crossfade(open, animationSpec = tween(Rah.motion.TRANSITION), label = "settings page") { page ->
        drafts.SaveableStateProvider(page?.name ?: "index") {
            when (page) {
                null -> {
                    LaunchedEffect(Unit) {
                        users.load()
                        tokens.load()
                        stats.load()
                    }
                    SettingsIndex(home, store, serverUrl, users.value, tokens.value, stats.value, words, ::show, onSignOut)
                }
                SettingsPage.Account -> WithApp(home, page, back) { AccountPage(it, store.api, secrets, back) }
                SettingsPage.TimeZone -> WithApp(home, page, back) { TimeZonePage(it, store.api, zones, back) }
                SettingsPage.RemoteAccess -> WithApp(home, page, back) { RemoteAccessPage(it.tunnel, store.api, secrets, back) }
                SettingsPage.People -> {
                    LaunchedEffect(Unit) { users.load() }
                    PeoplePage(users.value, store.api, secrets, words, onChanged = users::load, onBack = back)
                }
                SettingsPage.Tokens -> {
                    LaunchedEffect(Unit) { tokens.load() }
                    TokensPage(tokens.value, store.api, secrets, words, serverUrl, onChanged = tokens::load, onBack = back)
                }
                SettingsPage.Password -> PasswordPage(store.api, secrets, back)
            }
        }
    }
}

/** A page about the server's own settings, which are not known until its first state: until then, only its title. */
@Composable
private fun WithApp(home: Home, page: SettingsPage, onBack: () -> Unit, content: @Composable (AppInfo) -> Unit) {
    val app = home.app
    if (app == null) SubPage(page.title, onBack) {} else content(app)
}
