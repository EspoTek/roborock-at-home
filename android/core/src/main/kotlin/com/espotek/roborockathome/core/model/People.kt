package com.espotek.roborockathome.core.model

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * `GET /api/session`. The app always signs in as a person with the session cookie, so a signed-in
 * session always has a name and a look (only a bearer token has neither).
 */
@Serializable(with = SessionSerializer::class)
sealed interface Session {
    @Serializable
    data object SignedOut : Session

    @Serializable
    data class Person(
        val name: String,
        @SerialName("theme") val look: Look,
    ) : Session
}

internal object SessionSerializer : JsonContentPolymorphicSerializer<Session>(Session::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<Session> {
        val authenticated = (element as? JsonObject)?.get("authenticated") as? JsonPrimitive
            ?: throw SerializationException("A session answer has 'authenticated': $element")
        val signedIn = authenticated.booleanOrNull
            ?: throw SerializationException("'authenticated' is true or false: $element")
        return if (signedIn) Session.Person.serializer() else Session.SignedOut.serializer()
    }
}

/** How the app looks to one person, kept with their account so it follows them to every device. */
@Serializable
enum class Look {
    /** Follow the device. */
    @SerialName("system") System,
    @SerialName("light") Light,
    @SerialName("dark") Dark,
}

/** Someone who can sign in. Everyone has the same access. */
@Serializable
data class User(
    val name: String,
    val created: Long,
    /** The person asking. */
    val you: Boolean,
)

/** An API token for scripts and agents. The secret itself is shown only once, by [CreatedToken]. */
@Serializable
data class Token(
    val id: String,
    val name: String,
    val scope: TokenScope,
    val created: Long,
    @SerialName("last_used") val lastUsed: Long?,
)

@Serializable
enum class TokenScope {
    /** Every GET. */
    @SerialName("read") Read,

    /** Read, plus robot commands and schedules. */
    @SerialName("control") Control,
}

@Serializable
data class CreatedToken(
    val id: String,
    /** `rah_...`; the server never shows it again. */
    val token: String,
)
