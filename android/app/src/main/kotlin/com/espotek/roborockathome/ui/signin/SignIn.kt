package com.espotek.roborockathome.ui.signin

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.espotek.roborockathome.core.api.ApiException
import com.espotek.roborockathome.core.api.RoborockApi

/**
 * What the sign-in form holds. [address] and [name] are the caller's, so it can keep them past the
 * process being killed; the password is held here only, and dropped once it has been used.
 */
@Stable
class SignInForm(address: MutableState<String>, name: MutableState<String>) {
    var address: String by address
    var name: String by name
    var password: String by mutableStateOf("")

    /** Shown under the address: it is missing, malformed, unreachable or not this kind of server. */
    var addressError: String? by mutableStateOf(null)
    var nameError: String? by mutableStateOf(null)

    /** Shown under the password, which is where a refused name and password are talked about. */
    var passwordError: String? by mutableStateOf(null)
}

/**
 * Signs in with what [form] holds through a client from [connect], and on success hands
 * [onSignedIn] the normalised address, the name and that client. Each problem is put next to the
 * field it is about; a refusal this does not expect is thrown, for the form to show by the password.
 */
internal suspend fun signIn(
    form: SignInForm,
    connect: (serverUrl: String) -> RoborockApi,
    onSignedIn: (serverUrl: String, name: String, api: RoborockApi) -> Unit,
) {
    val address = serverAddressOrNull(form.address)
    val name = form.name.trim()
    form.addressError = when {
        form.address.isBlank() -> "Enter the server's address."
        address == null -> "Enter an address like 192.168.1.50:8770."
        else -> null
    }
    form.nameError = if (name.isEmpty()) "Enter your email or name." else null
    form.passwordError = if (form.password.isEmpty()) "Enter your password." else null
    if (address == null || form.nameError != null || form.passwordError != null) return

    form.address = address
    val api = connect(address)
    try {
        api.login(name, form.password)
    } catch (e: ApiException) {
        when (e.code) {
            "network" -> form.addressError = UNREACHABLE
            // Whatever answered did not answer in this server's documented JSON.
            "http" -> form.addressError = NOT_THIS_SERVER
            "unauthenticated" -> form.passwordError = "That name and password do not match."
            "too_many_attempts" -> form.passwordError = paused(checkNotNull(e.retryAfterSeconds) { "A 429 says how long to wait" })
            else -> throw e
        }
        return
    }
    form.password = ""
    onSignedIn(address, name, api)
}

internal const val UNREACHABLE =
    "Nothing answered at this address, so check it and that this phone is on the same network as the server."
internal const val NOT_THIS_SERVER = "Something answered at this address, but it is not a Roborock at Home server."

// The page's wording of the wait: seconds up to a minute and a half, then whole minutes.
internal fun paused(seconds: Int): String {
    val wait = if (seconds >= 90) plural((seconds + 59) / 60, "minute") else plural(seconds, "second")
    return "Signing in is paused for $wait after too many wrong passwords."
}

private fun plural(n: Int, unit: String) = if (n == 1) "1 $unit" else "$n ${unit}s"
