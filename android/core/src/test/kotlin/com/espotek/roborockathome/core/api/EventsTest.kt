@file:OptIn(ExperimentalCoroutinesApi::class)

package com.espotek.roborockathome.core.api

import com.espotek.roborockathome.core.model.HistoryEntry
import com.espotek.roborockathome.core.model.HistoryKind
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Pipe
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventsTest {
    private fun parse(text: String): List<SseMessage> {
        val parser = SseParser()
        return text.lines().mapNotNull { parser.line(it) }
    }

    @Test
    fun theRecordedStreamParses() {
        val events = parse(fixture("events.sse")).map { checkNotNull(decodeEvent(it)) }
        assertEquals(82, events.size)
        assertEquals(1, events.count { it is ServerEvent.State })
        assertEquals(53, events.count { it is ServerEvent.RobotPatch })
        assertEquals(27, events.count { it is ServerEvent.Path })

        val state = (events[0] as ServerEvent.State).state
        assertEquals(20L, state.rev)
        assertEquals(4, state.robots.size)

        val patch = events[1] as ServerEvent.RobotPatch
        assertEquals("upstairs", patch.id)
        assertEquals(setOf("id", "clean", "seen"), patch.fields.keys)
        assertEquals(JsonPrimitive(1795326343), patch.fields["seen"])

        val path = (events[3] as ServerEvent.Path).update
        assertEquals("upstairs", path.id)
        assertEquals(3L, path.rev)
        assertEquals(2126, path.start)
        assertArrayEquals(floatArrayOf(233.9f, 38.5f), path.points, 0f)

        assertEquals(
            HistoryEntry(1795326344, "downstairs", HistoryKind.Started, "Started cleaning"),
            (events[7] as ServerEvent.History).entry,
        )
        val last = (events.last() as ServerEvent.Path).update
        assertEquals(2156, last.start)
        assertArrayEquals(floatArrayOf(215.1f, 32.5f), last.points, 0f)
    }

    @Test
    fun commentsFieldsAndLinesFollowTheRules() {
        val messages = parse(
            ": keep-alive\n\n" +
                "event: robot\nid: 5\ndata:{\"id\":\ndata: \"a\"}\n\n" +
                "data: no event name\n\n" +
                "event: later\n\n" +
                "event: added\ndata: {}\n\n",
        )
        assertEquals(listOf("robot", "message", "added"), messages.map { it.event })
        assertEquals("{\"id\":\n\"a\"}", messages[0].data)
        assertEquals("a", (decodeEvent(messages[0]) as ServerEvent.RobotPatch).id)
        // Events this app does not know are passed over.
        assertNull(decodeEvent(messages[1]))
        assertNull(decodeEvent(messages[2]))
    }

    @Test
    fun aPatchWithoutAnIdIsUnreadable() = runTest {
        val fake = FakeServer()
        fake.on("GET /api/events") { it.stream("event: robot\ndata: {\"battery\":5}\n\n") }
        val error = failure<UnreadableException> { fake.api(StandardTestDispatcher(testScheduler)).events().toList() }
        assertEquals(
            "The server sent a robot update this app cannot read: Field 'id' is required for type with serial name " +
                "'com.espotek.roborockathome.core.api.PatchId', but it was missing.",
            error.message,
        )
    }

    @Test
    fun theStreamEndingIsAFailure() = runTest {
        val fake = FakeServer()
        fake.on("GET /api/events") { it.stream(fixture("events.sse")) }
        val events = mutableListOf<ServerEvent>()
        val error = failure<ApiException> { fake.api(StandardTestDispatcher(testScheduler)).events().collect { events += it } }
        assertEquals(82, events.size)
        assertEquals(0, error.status)
        assertEquals("network", error.code)
        assertEquals("Could not reach the server. Check the connection.", error.message)
    }

    @Test
    fun aRefusedStreamIsTheServersError() = runTest {
        val fake = FakeServer()
        fake.on("GET /api/events") { it.answer(401, """{"error":{"code":"unauthenticated","message":"Sign in first."}}""") }
        val error = failure<ApiException> { fake.api(StandardTestDispatcher(testScheduler)).events().toList() }
        assertEquals(401, error.status)
        assertEquals("Sign in first.", error.message)
    }

    @Test
    fun aStreamThatIsNotAnEventStreamIsUnexpected() = runTest {
        val fake = FakeServer()
        fake.on("GET /api/events") { it.answer(200, "<html>Sign in to the Wi-Fi</html>") }
        val error = failure<ApiException> { fake.api(StandardTestDispatcher(testScheduler)).events().toList() }
        assertEquals("http", error.code)
    }

    @Test
    fun aStreamSilentForSixtySecondsFails() = runTest {
        // The read blocks on a real thread while the test's clock runs the timeout.
        val pipe = Pipe(1024)
        val fake = FakeServer()
        fake.on("GET /api/events") { request ->
            request.answer(200, pipe.source.buffer().asResponseBody("text/event-stream".toMediaType(), -1))
        }
        val error = failure<ApiException> { fake.api(Dispatchers.IO).events().toList() }
        assertEquals(60_000L, testScheduler.currentTime)
        assertEquals("network", error.code)
        assertEquals("The live stream was silent for 60 seconds.", error.cause!!.message)
        assertTrue(fake.calls.single().isCanceled())
        pipe.sink.close()
    }

    @Test
    fun collectingIsCancellableWhileTheReadIsBlocked() = runBlocking {
        val pipe = Pipe(1024)
        val fake = FakeServer()
        fake.on("GET /api/events") { request ->
            request.answer(200, pipe.source.buffer().asResponseBody("text/event-stream".toMediaType(), -1))
        }
        val collecting = launch { fake.api(Dispatchers.IO).events().collect {} }
        withTimeout(10.seconds) {
            while (fake.calls.isEmpty()) delay(10)
        }
        val started = System.nanoTime()
        collecting.cancel()
        collecting.join()
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000)
        assertTrue(fake.calls.single().isCanceled())
        pipe.sink.close()
    }
}
