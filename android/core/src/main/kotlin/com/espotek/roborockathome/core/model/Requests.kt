@file:OptIn(ExperimentalSerializationApi::class)

package com.espotek.roborockathome.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * The body of `POST /api/robots/{id}/command`. The server refuses fields a command does not take,
 * so each command carries only its own.
 */
@Serializable
@JsonClassDiscriminator("command")
sealed interface Command {
    /** Clean the whole home. */
    @Serializable @SerialName("start")
    data object Start : Command

    @Serializable @SerialName("pause")
    data object Pause : Command

    @Serializable @SerialName("resume")
    data object Resume : Command

    @Serializable @SerialName("stop")
    data object Stop : Command

    /** Go back to the dock. */
    @Serializable @SerialName("dock")
    data object Dock : Command

    /** Play the "I am here" sound. */
    @Serializable @SerialName("locate")
    data object Locate : Command

    /** [rooms] are ids from `Robot.rooms`; [repeat] is 1-3 passes. */
    @Serializable @SerialName("clean_rooms")
    data class CleanRooms(val rooms: List<Int>, val repeat: Int) : Command

    /** [value] is one of `Robot.fanOptions`. */
    @Serializable @SerialName("set_fan")
    data class SetFan(val value: String) : Command

    /** [value] is one of `Robot.waterOptions`. */
    @Serializable @SerialName("set_water")
    data class SetWater(val value: String) : Command
}

/** The body of `PUT /api/tunnel`. Only token mode may carry a token. */
@Serializable
@JsonClassDiscriminator("mode")
sealed interface TunnelSetting {
    @Serializable @SerialName("off")
    data object Off : TunnelSetting

    /** A free address that changes when the server restarts. */
    @Serializable @SerialName("quick")
    data object Quick : TunnelSetting

    /** A tunnel made in the Cloudflare dashboard, which keeps its address. */
    @Serializable @SerialName("token")
    data class OwnTunnel(val token: String) : TunnelSetting
}

@Serializable
internal data class LoginRequest(val name: String, val password: String)

@Serializable
internal data class SettingsRequest(val tz: String)

@Serializable
internal data class MeRequest(@SerialName("theme") val look: Look)

@Serializable
internal data class PasswordRequest(val current: String, @SerialName("new") val newPassword: String)

@Serializable
internal data class UserRequest(val name: String, val password: String)

@Serializable
internal data class TokenRequest(val name: String, val scope: TokenScope)

@Serializable
internal data class AccountCodeRequest(val email: String)

@Serializable
internal data class AccountLoginRequest(val email: String, val code: String)

/** `{"ok": true}`, what the server answers when there is nothing else to say. */
@Serializable
internal data class Ok(val ok: Boolean)

/** Every error the server sends: `{"error": {"code": ..., "message": ...}}`. */
@Serializable
internal data class ErrorBody(val error: ErrorDetail)

@Serializable
internal data class ErrorDetail(val code: String, val message: String)
