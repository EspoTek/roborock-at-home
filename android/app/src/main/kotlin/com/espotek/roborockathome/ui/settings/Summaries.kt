package com.espotek.roborockathome.ui.settings

import com.espotek.roborockathome.core.model.AppInfo
import com.espotek.roborockathome.core.model.Stats
import com.espotek.roborockathome.core.model.Token
import com.espotek.roborockathome.core.model.TokenScope
import com.espotek.roborockathome.core.model.Tunnel
import com.espotek.roborockathome.core.model.TunnelMode
import com.espotek.roborockathome.core.model.TunnelStatus
import com.espotek.roborockathome.core.model.User
import com.espotek.roborockathome.ui.common.TimeWords
import com.espotek.roborockathome.ui.common.joinNames
import java.util.Locale

// What each row of the index says about its page, so the index is worth reading on its own.

internal fun accountSummary(app: AppInfo): String {
    val problem = app.account.problem
    return when {
        app.demo -> "Not available in the demo"
        !app.account.linked -> "Not linked"
        problem != null -> problem
        else -> "Linked as ${linkedEmail(app)}"
    }
}

/** The masked address of the linked account, which the server always gives outside the demo. */
internal fun linkedEmail(app: AppInfo): String =
    checkNotNull(app.account.email) { "A linked account outside the demo has an email address" }

internal fun timeZoneSummary(app: AppInfo): String = app.tz ?: "Not set"

// The address is left to the page: a long unbroken word wraps badly in a summary.
internal fun remoteSummary(tunnel: Tunnel): String = when {
    tunnel.mode == TunnelMode.Off -> "Off"
    tunnel.status != TunnelStatus.Running -> tunnelStatus(tunnel.status)
    tunnel.mode == TunnelMode.Quick -> "On, using a quick link"
    else -> "On, using your own Cloudflare tunnel"
}

/**
 * The page's word for where remote access has got to. Connecting names Cloudflare, so it is not
 * taken for the app connecting to the server.
 */
internal fun tunnelStatus(status: TunnelStatus): String = when (status) {
    TunnelStatus.Stopped -> "Off"
    TunnelStatus.Installing -> "Getting Cloudflare ready"
    TunnelStatus.Starting -> "Connecting to Cloudflare"
    TunnelStatus.Running -> "On"
    TunnelStatus.Error -> "Not working"
}

/** Null [users] have not been fetched yet. The list always has the person asking in it. */
internal fun peopleSummary(users: List<User>?): String = when {
    users == null -> "Who can sign in"
    users.size == 1 -> "Only you can sign in"
    else -> "${users.size} people can sign in"
}

/** Null [tokens] have not been fetched yet. */
internal fun tokensSummary(tokens: List<Token>?): String = when {
    tokens == null -> "For scripts, agents and home automation"
    tokens.isEmpty() -> "No tokens yet"
    else -> joinNames(tokens.map { it.name })
}

internal const val PASSWORD_SUMMARY = "Change the password you sign in with"

/** A token in the list: "Control · made 22 Nov · used 3 min ago". */
internal fun tokenDetail(token: Token, words: TimeWords): String {
    val scope = when (token.scope) {
        TokenScope.Read -> "Read only"
        TokenScope.Control -> "Control"
    }
    val used = token.lastUsed?.let { "used " + words.ago(it) } ?: "never used"
    return "$scope · made ${words.dayMonth(token.created)} · $used"
}

/**
 * About's figures, each a label and its value: this app's version, the server's once its first
 * state is in, and the page's memory, running time and pages open once [stats] are. Memory has the
 * decimal mark of [locale].
 */
internal fun aboutRows(
    appVersion: String,
    serverVersion: String?,
    stats: Stats?,
    words: TimeWords,
    locale: Locale,
): List<Pair<String, String>> =
    buildList {
        add("This app" to appVersion)
        if (serverVersion != null) add("The server" to serverVersion)
        if (stats != null) {
            add("Memory in use" to String.format(locale, "%.1f MB", stats.rssMb))
            add("Running for" to words.duration(stats.uptimeSeconds))
            // The server counts every live stream: web pages, and apps like this one.
            add("Pages and apps open" to stats.clients.toString())
        }
    }
