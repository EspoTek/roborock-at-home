package com.espotek.roborockathome.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /api/state`, and the body of the stream's `state` event. */
@Serializable
data class HomeState(
    val rev: Long,
    val app: AppInfo,
    val robots: List<Robot>,
    val schedules: List<Schedule>,
)

/** The `app` object: the server itself rather than its robots. */
@Serializable
data class AppInfo(
    val version: String,
    val demo: Boolean,
    /** The IANA zone schedules run in; null until set, and schedules cannot be made before then. */
    val tz: String?,
    val account: Account,
    val tunnel: Tunnel,
)

/** The Roborock account the robots come from. In the demo: linked, with no email. */
@Serializable
data class Account(
    val linked: Boolean,
    /** Masked, like `y***@example.com`. */
    val email: String?,
    /** Why the account stopped working (its login expired), or null while it works. */
    val problem: String?,
)

/** Cloudflare remote access. */
@Serializable
data class Tunnel(
    val mode: TunnelMode,
    val status: TunnelStatus,
    val url: String?,
    val error: String?,
)

@Serializable
enum class TunnelMode {
    @SerialName("off") Off,
    @SerialName("quick") Quick,
    @SerialName("token") Token,
}

@Serializable
enum class TunnelStatus {
    @SerialName("stopped") Stopped,
    @SerialName("installing") Installing,
    @SerialName("starting") Starting,
    @SerialName("running") Running,
    @SerialName("error") Error,
}

/** `GET /api/history`: newest first, at most 200 kept. */
@Serializable
data class HistoryEntry(
    val ts: Long,
    /** A robot id. */
    val robot: String,
    val kind: HistoryKind,
    val text: String,
)

@Serializable
enum class HistoryKind {
    @SerialName("started") Started,
    @SerialName("finished") Finished,
    @SerialName("docked") Docked,
    @SerialName("error") Error,
    @SerialName("cleared") Cleared,
    @SerialName("offline") Offline,
    @SerialName("online") Online,
    @SerialName("schedule") Schedule,
    @SerialName("command") Command,
}

/** `GET /api/stats`. */
@Serializable
data class Stats(
    @SerialName("rss_mb") val rssMb: Double,
    @SerialName("uptime_s") val uptimeSeconds: Long,
    /** Pages and apps with the live stream open. */
    val clients: Int,
    val robots: Int,
)
