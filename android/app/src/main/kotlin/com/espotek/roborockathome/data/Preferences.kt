package com.espotek.roborockathome.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.espotek.roborockathome.core.api.SessionStore
import com.espotek.roborockathome.core.model.Look

/**
 * What the app keeps on the phone, in its private storage: the server's address and the name last
 * signed in with, for the next sign-in; the session cookie; and the look last used, so the app opens
 * in it before the server has answered. Nothing else is kept, and never a password.
 */
class Preferences(context: Context) : SessionStore {
    private val stored: SharedPreferences = context.getSharedPreferences("rah", Context.MODE_PRIVATE)

    /** The address last signed in to, normalised, like `http://192.168.1.50:8770`; null before the first sign-in. */
    var serverUrl: String?
        get() = stored.getString(SERVER_URL, null)
        set(value) = stored.edit { putString(SERVER_URL, value) }

    /** The email or name last signed in with; null before the first sign-in. */
    var name: String?
        get() = stored.getString(NAME, null)
        set(value) = stored.edit { putString(NAME, value) }

    // Read for every request and written whenever the server sets or clears it, from OkHttp's threads.
    override var cookie: String?
        get() = stored.getString(COOKIE, null)
        set(value) = stored.edit { putString(COOKIE, value) }

    /** The look the person signed in last had; null until the server has first said. */
    var look: Look?
        get() = stored.getString(LOOK, null)?.let(Look::valueOf)
        set(value) = stored.edit { putString(LOOK, value?.name) }

    private companion object {
        const val SERVER_URL = "server_url"
        const val NAME = "name"
        const val COOKIE = "cookie"
        const val LOOK = "look"
    }
}
