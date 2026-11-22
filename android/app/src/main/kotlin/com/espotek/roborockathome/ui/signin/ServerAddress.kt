package com.espotek.roborockathome.ui.signin

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The server's address as typed (`192.168.1.50:8770`, with or without `http://`, with a trailing
 * slash) in the form the app keeps and shows: `http://192.168.1.50:8770`. Plain http is the default
 * because the server usually sits on the home network; `https://` stays as typed, for remote access.
 * Null when what was typed is not such an address, which the form says next to the field.
 */
internal fun serverAddressOrNull(typed: String): String? {
    val text = typed.trim()
    if (text.isEmpty()) return null
    val url = (if ("://" in text) text else "http://$text").toHttpUrlOrNull() ?: return null
    // Only a server's address: a name and password, a query or a fragment mean something else was pasted.
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
    return url.toString().trimEnd('/')
}
