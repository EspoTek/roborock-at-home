package com.espotek.roborockathome.core.api

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Where the session cookie lives between launches: the app keeps it in its preferences, tests in
 * memory. [RoborockApi] reads it for every request and writes it whenever the server sets it. Null
 * means nobody is signed in; signing out is forgetting it.
 */
interface SessionStore {
    var cookie: String?
}

internal const val SESSION_COOKIE = "rah_session"

internal class SessionCookieJar(private val sessions: SessionStore) : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val value = sessions.cookie ?: return emptyList()
        return listOf(Cookie.Builder().name(SESSION_COOKIE).value(value).hostOnlyDomain(url.host).build())
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            if (cookie.name == SESSION_COOKIE) sessions.cookie = cookie.value
        }
    }
}
