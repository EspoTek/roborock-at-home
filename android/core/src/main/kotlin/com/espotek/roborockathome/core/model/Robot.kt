package com.espotek.roborockathome.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One robot as the server publishes it. Everything but the identity shows the last values the robot
 * reported, also once it is offline. Map coordinates are pixels of the robot's map image (origin top
 * left, y down, 50 mm a pixel).
 */
@Serializable
data class Robot(
    val id: String,
    val name: String,
    /** Like `roborock.vacuum.a65`. */
    val model: String,
    /** Like `S7 Max Ultra`. */
    @SerialName("model_name") val modelName: String,
    val activity: Activity,
    /** The robot's own wording, like "Washing the mop". */
    @SerialName("state_label") val stateLabel: String,
    /** False once the robot has stopped answering. */
    val online: Boolean,
    val link: RobotLink,
    /** 0-100; null until first known. */
    val battery: Int?,
    /** Null until first known. */
    val charging: Boolean?,
    val error: Fault?,
    /** The one most important problem a person should see, or null. */
    val attention: Attention?,
    /** The suction level, one of [fanOptions]; null until first known. */
    val fan: String?,
    @SerialName("fan_options") val fanOptions: List<String>,
    /** The mop water level, one of [waterOptions]; null until first known. */
    val water: String?,
    /** Empty on a robot that cannot mop. */
    @SerialName("water_options") val waterOptions: List<String>,
    /** This job so far; null while the robot reports no figures. */
    val clean: CleanProgress?,
    /** Every area `clean_rooms` takes: the named rooms, then any unnamed ones as "Unnamed room". */
    val rooms: List<Room>,
    /** The floor plan's geometry; its pixels come from `map.bin` for [RobotMap.rev]. Null until first known. */
    val map: RobotMap?,
    /** The last known position; null until first known. */
    val pos: Position?,
    /** When the robot last answered; null until it first has. */
    val seen: Long?,
)

@Serializable
enum class Activity {
    @SerialName("cleaning") Cleaning,
    @SerialName("returning") Returning,
    @SerialName("paused") Paused,
    @SerialName("docked") Docked,
    @SerialName("idle") Idle,
    @SerialName("error") Error,
    @SerialName("offline") Offline,
}

/** How the server reaches a robot. */
@Serializable
enum class RobotLink {
    @SerialName("local") Local,
    @SerialName("cloud") Cloud,
    @SerialName("none") None,
}

/** A fault the robot reports, like 5, "Main brush jammed". */
@Serializable
data class Fault(val code: Int, val label: String)

@Serializable
data class Attention(
    val kind: AttentionKind,
    val title: String,
    /** One ready-made sentence in the app's time zone, saying where the robot was last seen. */
    val summary: String,
    val since: Long,
    /** The room it was in, or null. */
    val room: Int?,
    val battery: Int,
)

@Serializable
enum class AttentionKind {
    @SerialName("error") Error,
    @SerialName("offline") Offline,
    @SerialName("low_battery") LowBattery,
}

/** Each figure is null until the robot has reported it. */
@Serializable
data class CleanProgress(
    @SerialName("area_m2") val areaM2: Double?,
    val seconds: Int?,
)

@Serializable
data class Room(val id: Int, val name: String)

@Serializable
data class RobotMap(
    /** Names this floor plan; `map.bin?rev=` takes it. */
    val rev: String,
    val w: Int,
    val h: Int,
    @SerialName("mm_per_px") val mmPerPx: Int,
    /** By room id, in id order. */
    val rooms: Map<Int, RoomGeometry>,
    val charger: Point?,
    /** Each area is four corners: x0, y0, ... x3, y3. */
    @SerialName("no_go") val noGo: List<List<Double>>,
    /** Each virtual wall is x0, y0, x1, y1. */
    val walls: List<List<Double>>,
)

@Serializable
data class RoomGeometry(
    /** Where a label goes: always on a pixel of the room. */
    val cx: Double,
    val cy: Double,
    /** The bounding box, inclusive. */
    val x0: Int,
    val y0: Int,
    val x1: Int,
    val y1: Int,
)

@Serializable
data class Point(val x: Double, val y: Double)

@Serializable
data class Position(
    val x: Double,
    val y: Double,
    /** Degrees, as the robot reports it; null when it has not said. */
    val angle: Int?,
    /** The room it is in, or null. */
    val room: Int?,
    /** When it was read. */
    val ts: Long,
    /** True while the robot is reachable and the position is from its latest map. */
    val live: Boolean,
)

/** `GET /api/robots/{id}/path`: the current job's path as x0, y0, x1, y1, ... */
@Serializable
class RobotPath(
    /** Changes whenever the path does. */
    val rev: Long,
    val points: FloatArray,
)

/** The stream's `path` event: the points added since the last one. */
@Serializable
class PathUpdate(
    /** A robot id. */
    val id: String,
    val rev: Long,
    /** Where [points] go in the flat array, counting numbers (two per point); 0 starts a new path. */
    val start: Int,
    val points: FloatArray,
)
