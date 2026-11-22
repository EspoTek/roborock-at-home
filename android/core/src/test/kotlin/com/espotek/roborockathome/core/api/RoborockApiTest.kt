@file:OptIn(ExperimentalCoroutinesApi::class)

package com.espotek.roborockathome.core.api

import com.espotek.roborockathome.core.model.Command
import com.espotek.roborockathome.core.model.Look
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoborockApiTest {
    @Test
    fun aServerErrorKeepsTheServersWords() = runTest {
        val fake = FakeServer()
        fake.on("POST /api/robots/downstairs/command") { it.answer(400, fixture("error.json")) }
        val error = failure<ApiException> { fake.api(StandardTestDispatcher(testScheduler)).command("downstairs", Command.Locate) }
        assertEquals(400, error.status)
        assertEquals("bad_request", error.code)
        assertEquals(
            "'command' is one of start, pause, resume, stop, dock, locate, clean_rooms, set_fan, set_water.",
            error.message,
        )
        assertNull(error.retryAfterSeconds)
    }

    @Test
    fun tooManyWrongPasswordsSaysWhenToTryAgain() = runTest {
        val fake = FakeServer()
        fake.on("POST /api/login") {
            it.answer(
                429,
                """{"error":{"code":"too_many_attempts","message":"Too many wrong passwords. Try again in 30 seconds."}}""",
                "Retry-After" to "30",
            )
        }
        val error = failure<ApiException> { fake.api(StandardTestDispatcher(testScheduler)).login("demo", "wrong") }
        assertEquals(429, error.status)
        assertEquals("too_many_attempts", error.code)
        assertEquals(30, error.retryAfterSeconds)
    }

    @Test
    fun aServerThatCannotBeReachedIsANetworkError() = runBlocking {
        val port = ServerSocket(0).use { it.localPort } // closed again: nothing listens there
        val api = RoborockApi("http://127.0.0.1:$port", MemorySessions())
        val error = failure<ApiException> { api.state() }
        assertEquals(0, error.status)
        assertEquals("network", error.code)
        assertEquals("Could not reach the server. Check the connection.", error.message)
        assertTrue(error.cause is IOException)
    }

    @Test
    fun anAnswerThatIsNotTheDocumentedJsonIsHttp() = runTest {
        val fake = FakeServer()
        val api = fake.api(StandardTestDispatcher(testScheduler))
        fake.on("GET /api/state") { it.answer(502, "<html>Bad gateway</html>") }
        val gateway = failure<ApiException> { api.state() }
        assertEquals(502, gateway.status)
        assertEquals("http", gateway.code)
        assertEquals("The server gave an unexpected answer (502).", gateway.message)

        fake.on("GET /api/state") { it.answer(200, """{"rev":1}""") }
        val partial = failure<ApiException> { api.state() }
        assertEquals(200, partial.status)
        assertEquals("http", partial.code)

        fake.on("GET /api/robots/garage/map.bin") { it.answer(200, "<html>Sign in to the Wi-Fi</html>") }
        assertEquals("http", failure<ApiException> { api.mapBin("garage", "f565a078") }.code)
    }

    @Test
    fun onlyRequestsThatChangeSomethingCarryTheHeader() = runTest {
        val fake = FakeServer()
        val api = fake.api(StandardTestDispatcher(testScheduler))
        fake.on("GET /api/session") { it.answer(200, fixture("session.json")) }
        fake.on("POST /api/robots/down%20stairs/command") { it.answer(200, """{"ok":true}""") }
        fake.on("PUT /api/me") { it.answer(200, """{"ok":true}""") }
        fake.on("DELETE /api/users/them@example.com") { it.answer(200, """{"ok":true}""") }
        api.session()
        api.command("down stairs", Command.CleanRooms(listOf(16), 1))
        api.setLook(Look.Light)
        api.removeUser("them@example.com")
        val (get, post, put, delete) = fake.requests
        assertNull(get.header("X-RAH"))
        assertEquals("1", post.header("X-RAH"))
        assertEquals("""{"command":"clean_rooms","rooms":[16],"repeat":1}""", post.bodyText())
        assertEquals("application/json; charset=utf-8", post.body!!.contentType().toString())
        assertEquals("""{"theme":"light"}""", put.bodyText())
        assertEquals("1", delete.header("X-RAH"))
    }

    @Test
    fun mapBinIsTheBytes() = runTest {
        val fake = FakeServer()
        val bytes = ByteArray(268 * 208) { (it % 40).toByte() }
        fake.on("GET /api/robots/garage/map.bin") {
            assertEquals("f565a078", it.url.queryParameter("rev"))
            it.answer(200, bytes.toResponseBody("application/octet-stream".toMediaType()))
        }
        assertArrayEquals(bytes, fake.api(StandardTestDispatcher(testScheduler)).mapBin("garage", "f565a078"))
    }

    @Test
    fun a401EndsTheSessionButAWrongPasswordDoesNot() = runTest {
        val fake = FakeServer()
        val api = fake.api(StandardTestDispatcher(testScheduler))
        val unauthenticated = """{"error":{"code":"unauthenticated","message":"Sign in first."}}"""
        fake.on("POST /api/login") { it.answer(401, """{"error":{"code":"unauthenticated","message":"Wrong name or password."}}""") }
        fake.on("GET /api/schedules") { it.answer(401, unauthenticated) }
        var ended = 0
        backgroundScope.launch { api.signedOut.collect { ended++ } }
        runCurrent()

        failure<ApiException> { api.login("demo", "wrong") }
        runCurrent()
        assertEquals(0, ended)

        assertEquals(401, failure<ApiException> { api.schedules() }.status)
        runCurrent()
        assertEquals(1, ended)
    }

    @Test
    fun theCookieIsKeptAsTheServerSetsItAndForgottenOnThePhone() {
        val sessions = MemorySessions()
        val jar = SessionCookieJar(sessions)
        val url = "http://127.0.0.1:8770/api/login".toHttpUrl()
        assertEquals(emptyList<Cookie>(), jar.loadForRequest(url))

        // What the server sends, word for word.
        val set = "rah_session=ZGVtbw.84354763.1797920388.l9slB50VNVDLr; HttpOnly; Max-Age=2592000; Path=/; SameSite=Lax"
        jar.saveFromResponse(url, Cookie.parseAll(url, headersOf("Set-Cookie", set)))
        assertEquals("ZGVtbw.84354763.1797920388.l9slB50VNVDLr", sessions.cookie)
        val sent = jar.loadForRequest("http://127.0.0.1:8770/api/state".toHttpUrl()).single()
        assertEquals("rah_session" to "ZGVtbw.84354763.1797920388.l9slB50VNVDLr", sent.name to sent.value)

        // Signing out is the app forgetting it: nothing is sent from then on.
        sessions.cookie = null
        assertEquals(emptyList<Cookie>(), jar.loadForRequest(url))
    }

    @Test
    fun aCookieForSomethingElseIsLeftAlone() {
        val sessions = MemorySessions("abc")
        val url = "http://127.0.0.1:8770/".toHttpUrl()
        SessionCookieJar(sessions).saveFromResponse(url, Cookie.parseAll(url, headersOf("Set-Cookie", "other=1; Path=/")))
        assertEquals("abc", sessions.cookie)
    }

    @Test
    fun aRequestIsCancelledWithItsCoroutine() = runBlocking {
        val fake = FakeServer()
        val release = CountDownLatch(1)
        fake.on("GET /api/stats") { request ->
            release.await()
            request.answer(200, fixture("stats.json"))
        }
        val asking = launch(Dispatchers.Default) { fake.api(Dispatchers.IO).stats() }
        while (fake.calls.isEmpty()) delay(10)
        asking.cancel()
        asking.join()
        assertTrue(asking.isCancelled)
        assertTrue(fake.calls.single().isCanceled())
        release.countDown()
        // The answer that comes after the cancel goes nowhere.
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun theStreamFirstAnswersWithTheState() = runTest {
        val fake = FakeServer()
        fake.on("GET /api/events") { it.stream(fixture("events.sse")) }
        val first = fake.api(StandardTestDispatcher(testScheduler)).events().first()
        assertEquals(20L, (first as ServerEvent.State).state.rev)
    }
}
