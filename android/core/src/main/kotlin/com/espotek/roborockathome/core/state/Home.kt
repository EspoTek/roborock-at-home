package com.espotek.roborockathome.core.state

import com.espotek.roborockathome.core.model.AppInfo
import com.espotek.roborockathome.core.model.HistoryEntry
import com.espotek.roborockathome.core.model.Robot
import com.espotek.roborockathome.core.model.RobotPath
import com.espotek.roborockathome.core.model.Schedule
import com.espotek.roborockathome.core.model.Session

/** The app's connection to the server (not a robot's). */
enum class Link {
    /** Started, waiting for the stream's first state. */
    Connecting,

    /** The stream is open and the state is current. */
    Live,

    /** The stream broke while signed in; it is being opened again. What is shown is the last state. */
    Reconnecting,

    /** The session has ended; the store has stopped until it is started again after a sign-in. */
    SignedOut,

    /**
     * The server sent something this app cannot read, most likely because it is newer than the app;
     * [Home.problem] says what. The store has stopped until it is started again.
     */
    Unreadable,
}

/**
 * Everything the screens show, as one immutable value. Nothing in it, arrays included, changes after
 * it is published: each change is a new [Home].
 */
data class Home(
    val link: Link,
    /** With [Link.Unreadable], a sentence naming what could not be read and why; otherwise null. */
    val problem: String?,
    /** Who is signed in and their look; null until the server has said. */
    val session: Session.Person?,
    /** Null until the first state. */
    val app: AppInfo?,
    /** In the server's order. */
    val robots: List<Robot>,
    val schedules: List<Schedule>,
    /** The current job's path by robot id, for the robots whose path has been heard of. */
    val paths: Map<String, RobotPath>,
    /**
     * The floor plan's pixels by robot id. While a new plan is on its way the previous one stays, so
     * compare [Plan.rev] with the robot's `map.rev`.
     */
    val plans: Map<String, Plan>,
    /** Newest first, at most 200; null until [HomeStore.loadHistory] has been answered. */
    val history: List<HistoryEntry>?,
)

/** A robot's `map.bin` for map [rev]: [w] * [h] bytes, row by row, one class per pixel. */
class Plan(val rev: String, val w: Int, val h: Int, val bytes: ByteArray)
