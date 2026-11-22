package com.espotek.roborockathome.ui.settings

// What each form checks before it sends anything, as the page's form fields do, so a person learns
// at once and next to the field. The server checks again and has the last word.

/** The fields these checks speak about. */
internal enum class Field { Email, Code, TunnelToken, PersonName, PersonPassword, TokenName, CurrentPassword, NewPassword }

private const val MIN_PASSWORD = 8
private const val MAX_NAME = 100

internal fun emailProblems(email: String): Map<Field, String> = buildMap {
    if ('@' !in email.trim()) put(Field.Email, "Enter the email address of your Roborock account.")
}

internal fun codeProblems(code: String): Map<Field, String> = buildMap {
    if (code.isBlank()) put(Field.Code, "Enter the code Roborock emailed you.")
}

internal fun tunnelTokenProblems(token: String): Map<Field, String> = buildMap {
    if (token.isBlank()) put(Field.TunnelToken, "Paste the tunnel token from the Cloudflare dashboard.")
}

internal fun personProblems(name: String, password: String): Map<Field, String> = buildMap {
    nameProblemOrNull(name, "Enter their email address or name.")?.let { put(Field.PersonName, it) }
    if (password.length < MIN_PASSWORD) put(Field.PersonPassword, "The password needs at least $MIN_PASSWORD characters.")
}

internal fun tokenProblems(name: String): Map<Field, String> = buildMap {
    nameProblemOrNull(name, "Give the token a name.")?.let { put(Field.TokenName, it) }
}

internal fun passwordProblems(current: String, new: String): Map<Field, String> = buildMap {
    if (current.isEmpty()) put(Field.CurrentPassword, "Enter your current password.")
    if (new.length < MIN_PASSWORD) put(Field.NewPassword, "The new password needs at least $MIN_PASSWORD characters.")
}

private fun nameProblemOrNull(name: String, whenEmpty: String): String? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> whenEmpty
        trimmed.length > MAX_NAME -> "A name can be at most $MAX_NAME characters."
        else -> null
    }
}
