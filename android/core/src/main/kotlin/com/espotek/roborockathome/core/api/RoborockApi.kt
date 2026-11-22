package com.espotek.roborockathome.core.api

import com.espotek.roborockathome.core.model.AccountCodeRequest
import com.espotek.roborockathome.core.model.AccountLoginRequest
import com.espotek.roborockathome.core.model.Command
import com.espotek.roborockathome.core.model.CreatedToken
import com.espotek.roborockathome.core.model.ErrorBody
import com.espotek.roborockathome.core.model.HistoryEntry
import com.espotek.roborockathome.core.model.HomeState
import com.espotek.roborockathome.core.model.LoginRequest
import com.espotek.roborockathome.core.model.Look
import com.espotek.roborockathome.core.model.MeRequest
import com.espotek.roborockathome.core.model.Ok
import com.espotek.roborockathome.core.model.PasswordRequest
import com.espotek.roborockathome.core.model.Robot
import com.espotek.roborockathome.core.model.RobotPath
import com.espotek.roborockathome.core.model.Schedule
import com.espotek.roborockathome.core.model.ScheduleRequest
import com.espotek.roborockathome.core.model.Session
import com.espotek.roborockathome.core.model.SettingsRequest
import com.espotek.roborockathome.core.model.Stats
import com.espotek.roborockathome.core.model.Token
import com.espotek.roborockathome.core.model.TokenRequest
import com.espotek.roborockathome.core.model.TokenScope
import com.espotek.roborockathome.core.model.TunnelSetting
import com.espotek.roborockathome.core.model.User
import com.espotek.roborockathome.core.model.UserRequest
import com.espotek.roborockathome.core.model.WireJson
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** The server keeps the stream alive with a comment every 25 seconds. */
private val SILENCE = 60.seconds
private val JSON = "application/json".toMediaType()
private val EMPTY = ByteArray(0).toRequestBody(null)

/**
 * Everything a signed-in person can ask of the server at [baseUrl] (like `http://192.168.1.50:8770`),
 * as described in API.md. Each call throws [ApiException] when the server refuses it or cannot be
 * reached. The session cookie lives in [sessions].
 *
 * [client] is the OkHttp client to build on (its pool, its interceptors); the cookie jar and read
 * timeouts are this class's. Blocking calls run on [io] and end when their coroutine is cancelled.
 */
class RoborockApi(
    val baseUrl: String,
    sessions: SessionStore,
    client: OkHttpClient = OkHttpClient(),
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val http: OkHttpClient = client.newBuilder()
        .cookieJar(SessionCookieJar(sessions))
        // A command is answered once the robot accepts it, which the server waits up to 21 seconds for.
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    // The stream is quiet between keep-alives; events() times the silence itself.
    private val streaming: OkHttpClient = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()
    private val executor = io.asExecutor()
    private val ended = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits when the session has ended: a request other than a login was answered 401. */
    val signedOut: SharedFlow<Unit> = ended.asSharedFlow()

    // -- session -------------------------------------------------------------

    /** Who is signed in. Never refused, so it also answers whether the cookie still works. */
    suspend fun session(): Session = fetch(get(url("api", "session")))

    /** A wrong name and a wrong password give the same 401; too many give 429 with [ApiException.retryAfterSeconds]. */
    suspend fun login(name: String, password: String) =
        perform(send("POST", url("api", "login"), LoginRequest(name, password)), login = true)

    /** At least 8 characters. Signs out the person's other sessions; this one gets a new cookie. */
    suspend fun changePassword(current: String, new: String) =
        perform(send("POST", url("api", "password"), PasswordRequest(current, new)))

    /** How the app looks to the person signed in, wherever they sign in. */
    suspend fun setLook(look: Look) = perform(send("PUT", url("api", "me"), MeRequest(look)))

    // -- state ---------------------------------------------------------------

    suspend fun state(): HomeState = fetch(get(url("api", "state")))

    suspend fun robots(): List<Robot> = fetch(get(url("api", "robots")))

    suspend fun robot(id: String): Robot = fetch(get(url("api", "robots", id)))

    /** `w * h` bytes, row by row, one class per pixel. A [rev] that is no longer the robot's map is a 404. */
    suspend fun mapBin(robotId: String, rev: String): ByteArray =
        exchange(get(url("api", "robots", robotId, "map.bin").addQueryParameter("rev", rev))) { response ->
            val body = response.body!!
            val type = body.contentType()
            if (type?.subtype != "octet-stream") {
                throw unexpected(response.code, IllegalStateException("map.bin came as $type"))
            }
            body.bytes()
        }

    suspend fun path(robotId: String): RobotPath = fetch(get(url("api", "robots", robotId, "path")))

    /** Returns once the robot has accepted it; the new state follows on [events]. */
    suspend fun command(robotId: String, command: Command) =
        perform(send("POST", url("api", "robots", robotId, "command"), command))

    /** Newest first; [limit] is 1-200. */
    suspend fun history(limit: Int): List<HistoryEntry> =
        fetch(get(url("api", "history").addQueryParameter("limit", limit.toString())))

    suspend fun stats(): Stats = fetch(get(url("api", "stats")))

    /**
     * The live stream: a [ServerEvent.State] first, then only what changes. Cold: each collection
     * opens a stream of its own. It never ends by itself and never reconnects: it throws
     * [ApiException] when the stream breaks, ends or stays silent for 60 seconds, and
     * [UnreadableException] when an event is not the documented JSON.
     */
    fun events(): Flow<ServerEvent> = flow {
        val call = streaming.newCall(get(url("api", "events")))
        call.blocking { call.answer(login = false) }.use { response ->
            val body = response.body!!
            val type = body.contentType()
            if (type?.type != "text" || type.subtype != "event-stream") {
                throw unexpected(response.code, IllegalStateException("The live stream came as $type"))
            }
            val source = body.source()
            val parser = SseParser()
            while (true) {
                val line = withTimeoutOrNull(SILENCE) {
                    call.blocking {
                        try {
                            source.readUtf8LineStrict()
                        } catch (e: IOException) {
                            throw unreachable(e)
                        }
                    }
                } ?: throw unreachable(IOException("The live stream was silent for 60 seconds."))
                val message = parser.line(line) ?: continue
                val event = decodeEvent(message) ?: continue
                emit(event)
            }
        }
    }

    // -- schedules -----------------------------------------------------------

    suspend fun schedules(): List<Schedule> = fetch(get(url("api", "schedules")))

    /** Refused with `bad_request` until the time zone is set. */
    suspend fun createSchedule(schedule: ScheduleRequest): Schedule =
        fetch(send("POST", url("api", "schedules"), schedule))

    suspend fun updateSchedule(id: String, schedule: ScheduleRequest): Schedule =
        fetch(send("PUT", url("api", "schedules", id), schedule))

    suspend fun deleteSchedule(id: String) = perform(send("DELETE", url("api", "schedules", id)))

    /** Runs it now; the robot's refusal is thrown and recorded on the schedule. */
    suspend fun runSchedule(id: String) = perform(send("POST", url("api", "schedules", id, "run")))

    // -- settings ------------------------------------------------------------

    /** An IANA zone, like `Australia/Melbourne`: the one schedules run in. */
    suspend fun setTimeZone(tz: String) = perform(send("PUT", url("api", "settings"), SettingsRequest(tz)))

    suspend fun users(): List<User> = fetch(get(url("api", "users")))

    /** Name 1-100 characters and not taken; password at least 8. */
    suspend fun addUser(name: String, password: String): User =
        fetch(send("POST", url("api", "users"), UserRequest(name, password)))

    /** Signs them out everywhere. Removing yourself is refused. */
    suspend fun removeUser(name: String) = perform(send("DELETE", url("api", "users", name)))

    suspend fun tokens(): List<Token> = fetch(get(url("api", "tokens")))

    suspend fun createToken(name: String, scope: TokenScope): CreatedToken =
        fetch(send("POST", url("api", "tokens"), TokenRequest(name, scope)))

    suspend fun deleteToken(id: String) = perform(send("DELETE", url("api", "tokens", id)))

    /** Roborock emails a code for [linkAccount]. The demo answers 409 `demo`. */
    suspend fun requestAccountCode(email: String) =
        perform(send("POST", url("api", "account", "code"), AccountCodeRequest(email)))

    /** Links the account; its robots appear on [events]. */
    suspend fun linkAccount(email: String, code: String) =
        perform(send("POST", url("api", "account", "login"), AccountLoginRequest(email, code)))

    /** Forgets the account's robots, their maps and schedules, and the history. */
    suspend fun unlinkAccount() = perform(send("DELETE", url("api", "account")))

    /** Progress shows on `app.tunnel`. */
    suspend fun setTunnel(setting: TunnelSetting) = perform(send("PUT", url("api", "tunnel"), setting))

    // -- plumbing ------------------------------------------------------------

    private fun url(vararg segments: String): HttpUrl.Builder =
        base.newBuilder().apply { for (segment in segments) addPathSegment(segment) }

    private fun get(url: HttpUrl.Builder): Request = Request.Builder().url(url.build()).build()

    private inline fun <reified B> send(method: String, url: HttpUrl.Builder, body: B): Request =
        write(method, url, WireJson.encodeToString(body).toRequestBody(JSON))

    private fun send(method: String, url: HttpUrl.Builder): Request = write(method, url, EMPTY)

    // The server refuses a cookie request other than GET without this header (it stops other sites
    // from posting in a person's name).
    private fun write(method: String, url: HttpUrl.Builder, body: RequestBody): Request =
        Request.Builder().url(url.build()).header("X-RAH", "1").method(method, body).build()

    private suspend inline fun <reified T> fetch(request: Request, login: Boolean = false): T =
        exchange(request, login) { WireJson.decodeFromString<T>(it.body!!.string()) }

    /** For the answers that are only `{"ok": true}`. */
    private suspend fun perform(request: Request, login: Boolean = false) {
        fetch<Ok>(request, login)
    }

    private suspend fun <T> exchange(request: Request, login: Boolean = false, read: (Response) -> T): T {
        val call = http.newCall(request)
        return call.blocking {
            call.answer(login).use { response ->
                try {
                    read(response)
                } catch (e: SerializationException) {
                    throw unexpected(response.code, e)
                } catch (e: IOException) {
                    throw unreachable(e)
                }
            }
        }
    }

    /** Blocking: the response to a 2xx, or the [ApiException] anything else means. */
    private fun Call.answer(login: Boolean): Response {
        val response = try {
            execute()
        } catch (e: IOException) {
            throw unreachable(e)
        }
        if (response.isSuccessful) return response
        response.use { throw refusal(it, login) }
    }

    private fun refusal(response: Response, login: Boolean): ApiException {
        if (response.code == 401 && !login) ended.tryEmit(Unit)
        val error = try {
            WireJson.decodeFromString<ErrorBody>(response.body!!.string()).error
        } catch (e: SerializationException) {
            return unexpected(response.code, e)
        } catch (e: IOException) {
            return unreachable(e)
        }
        return ApiException(response.code, error.code, error.message, response.header("Retry-After")?.toInt())
    }

    /**
     * Runs blocking OkHttp work on the io dispatcher. A read blocked on a socket does not notice
     * coroutine cancellation, so cancelling the coroutine cancels the call, which closes the socket;
     * what the work throws then reaches a continuation that has already ended and is dropped with it.
     */
    private suspend fun <T> Call.blocking(work: () -> T): T = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        executor.execute { continuation.resumeWith(runCatching(work)) }
    }
}
