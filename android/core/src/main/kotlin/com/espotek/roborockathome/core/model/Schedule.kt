package com.espotek.roborockathome.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A schedule the server runs itself, in `app.tz`. */
@Serializable
data class Schedule(
    val id: String,
    /** A robot id. */
    val robot: String,
    val name: String,
    val enabled: Boolean,
    /** 0 = Monday ... 6 = Sunday, sorted, at least one. */
    val days: List<Int>,
    /** `HH:MM`. */
    val time: String,
    val action: ScheduleAction,
    /** Null when disabled. */
    @SerialName("next_run") val nextRun: Long?,
    /** Null until it has run. */
    @SerialName("last_run") val lastRun: LastRun?,
)

@Serializable
data class ScheduleAction(
    val type: ActionType,
    /** Empty for [ActionType.CleanAll]. */
    val rooms: List<Int>,
    /** Passes, 1-3. */
    val repeat: Int,
    /** Set just before starting; null leaves the robot's setting alone. */
    val fan: String?,
    val water: String?,
)

@Serializable
enum class ActionType {
    @SerialName("clean_all") CleanAll,
    @SerialName("clean_rooms") CleanRooms,
}

@Serializable
data class LastRun(val ts: Long, val ok: Boolean, val message: String)

/** The body of `POST /api/schedules` and `PUT /api/schedules/{id}`. */
@Serializable
data class ScheduleRequest(
    val robot: String,
    /** Null lets the server name it from the action and days. */
    val name: String?,
    val enabled: Boolean,
    val days: List<Int>,
    val time: String,
    val action: ScheduleAction,
)
