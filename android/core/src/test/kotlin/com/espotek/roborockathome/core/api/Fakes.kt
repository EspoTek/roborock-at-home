package com.espotek.roborockathome.core.api

import java.util.Collections
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

fun fixture(name: String): String =
    checkNotNull(FakeServer::class.java.getResource("/fixtures/$name")) { "There is no fixture $name." }.readText()

class MemorySessions(@Volatile override var cookie: String? = null) : SessionStore

/**
 * Answers requests in place of the server, without a network: routes are `"GET /api/session"`. It
 * runs on the calling thread, so with a test dispatcher for io everything happens in virtual time.
 */
class FakeServer : Interceptor {
    private val routes = mutableMapOf<String, (Request) -> Response>()
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
    val calls: MutableList<okhttp3.Call> = Collections.synchronizedList(mutableListOf())

    fun on(route: String, answer: (Request) -> Response) {
        routes[route] = answer
    }

    fun asked(route: String): Int = requests.count { "${it.method} ${it.url.encodedPath}" == route }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requests += request
        calls += chain.call()
        val route = "${request.method} ${request.url.encodedPath}"
        val answer = checkNotNull(routes[route]) { "The fake server has no answer for $route." }
        return answer(request)
    }

    fun api(io: CoroutineDispatcher, sessions: SessionStore = MemorySessions()): RoborockApi =
        RoborockApi("http://home.test:8770", sessions, OkHttpClient.Builder().addInterceptor(this).build(), io)
}

fun Request.answer(code: Int, body: String, vararg headers: Pair<String, String>): Response =
    answer(code, body.toResponseBody("application/json".toMediaType()), *headers)

fun Request.answer(code: Int, body: ResponseBody, vararg headers: Pair<String, String>): Response =
    Response.Builder()
        .request(this)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("Fake")
        .body(body)
        .apply { for ((name, value) in headers) header(name, value) }
        .build()

fun Request.stream(text: String): Response = answer(200, text.toResponseBody("text/event-stream".toMediaType()))

fun Request.bodyText(): String = Buffer().also { checkNotNull(body).writeTo(it) }.readUtf8()

/** Runs [block], which must throw a [T]; returns it. */
inline fun <reified T : Throwable> failure(block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw e
    }
    throw AssertionError("Expected ${T::class.simpleName}, but nothing was thrown.")
}
