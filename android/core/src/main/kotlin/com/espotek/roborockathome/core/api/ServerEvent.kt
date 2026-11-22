package com.espotek.roborockathome.core.api

import com.espotek.roborockathome.core.model.AppInfo
import com.espotek.roborockathome.core.model.HistoryEntry
import com.espotek.roborockathome.core.model.HomeState
import com.espotek.roborockathome.core.model.PathUpdate
import com.espotek.roborockathome.core.model.Schedule
import com.espotek.roborockathome.core.model.WireJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** One event of `GET /api/events`. */
sealed interface ServerEvent {
    /** The whole state: the first event of every stream, and again when robots were removed. */
    data class State(val state: HomeState) : ServerEvent

    /**
     * The top-level fields of a robot that changed, `id` among them, to merge into the robot held.
     * A robot not seen before arrives whole.
     */
    data class RobotPatch(val id: String, val fields: JsonObject) : ServerEvent

    data class Path(val update: PathUpdate) : ServerEvent

    data class Schedules(val schedules: List<Schedule>) : ServerEvent

    data class App(val app: AppInfo) : ServerEvent

    /** The newest history entry. */
    data class History(val entry: HistoryEntry) : ServerEvent
}

@Serializable
private class PatchId(val id: String)

internal const val ROBOT_UPDATE = "a robot update"

/** Each event the app reads: what it is called in a sentence, and how its data is read. */
private class Kind(val what: String, val decode: (String) -> ServerEvent)

private val KINDS = mapOf(
    "state" to Kind("the state of the home") { ServerEvent.State(WireJson.decodeFromString(it)) },
    "robot" to Kind(ROBOT_UPDATE) {
        val fields = WireJson.decodeFromString<JsonObject>(it)
        ServerEvent.RobotPatch(WireJson.decodeFromJsonElement<PatchId>(fields).id, fields)
    },
    "path" to Kind("a path update") { ServerEvent.Path(WireJson.decodeFromString(it)) },
    "schedules" to Kind("the schedules") { ServerEvent.Schedules(WireJson.decodeFromString(it)) },
    "app" to Kind("the server's settings") { ServerEvent.App(WireJson.decodeFromString(it)) },
    "history" to Kind("a history entry") { ServerEvent.History(WireJson.decodeFromString(it)) },
)

/** Throws [UnreadableException] for data that is not the documented JSON. */
internal fun decodeEvent(message: SseMessage): ServerEvent? {
    // An event a newer server added is passed over, like a field it added.
    val kind = KINDS[message.event] ?: return null
    return try {
        kind.decode(message.data)
    } catch (e: SerializationException) {
        throw UnreadableException(kind.what, e)
    }
}

internal class SseMessage(val event: String, val data: String)

/**
 * Server-Sent Events, one line at a time, by the WHATWG rules. `id` and `retry` are passed over: a
 * new stream starts again from a whole state, and when to reconnect is the store's business.
 */
internal class SseParser {
    private var event = ""
    private val data = StringBuilder()
    private var hasData = false

    /** Takes one line without its line ending; a blank line returns the message it completes. */
    fun line(line: String): SseMessage? {
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return null // a comment: the keep-alive
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }

    private fun dispatch(): SseMessage? {
        val message = if (hasData) SseMessage(event.ifEmpty { "message" }, data.toString()) else null
        event = ""
        data.setLength(0)
        hasData = false
        return message
    }
}
