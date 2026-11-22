package com.espotek.roborockathome.core.api

import kotlinx.serialization.SerializationException

/**
 * A request the server refused, or could not be asked. [message] is a sentence to show a person.
 *
 * - The server's own errors carry its status, `code` (see API.md) and message.
 * - [status] 0 with [code] `network`: the server could not be reached, or the live stream broke.
 * - [code] `http`: the answer was not the documented JSON.
 *
 * [retryAfterSeconds] comes with `too_many_attempts`: how long before signing in may be tried again.
 */
class ApiException(
    val status: Int,
    val code: String,
    message: String,
    val retryAfterSeconds: Int?,
) : Exception(message)

/**
 * The live stream carried something this app cannot read, most likely because the server is newer
 * than the app. Unlike a broken connection, trying again does not help. [message] is a sentence to
 * show a person, naming what could not be read and why.
 */
class UnreadableException internal constructor(what: String, why: String, cause: Throwable?) :
    Exception("The server sent $what this app cannot read: ${why.removeSuffix(".")}.", cause) {
    // The parser's first line says why; the lines after it quote the JSON.
    internal constructor(what: String, cause: SerializationException) :
        this(what, checkNotNull(cause.message) { "The parser gave no reason." }.lines().first(), cause)
}

internal fun unreachable(cause: Throwable): ApiException =
    ApiException(0, "network", "Could not reach the server. Check the connection.", null).also { it.initCause(cause) }

internal fun unexpected(status: Int, cause: Throwable): ApiException =
    ApiException(status, "http", "The server gave an unexpected answer ($status).", null).also { it.initCause(cause) }
