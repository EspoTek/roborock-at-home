@file:OptIn(ExperimentalCoroutinesApi::class)

package com.espotek.roborockathome.core.state

import com.espotek.roborockathome.core.api.ApiException
import com.espotek.roborockathome.core.api.FakeServer
import com.espotek.roborockathome.core.api.MemorySessions
import com.espotek.roborockathome.core.api.ServerEvent
import com.espotek.roborockathome.core.api.SseParser
import com.espotek.roborockathome.core.api.answer
import com.espotek.roborockathome.core.api.bodyText
import com.espotek.roborockathome.core.api.decodeEvent
import com.espotek.roborockathome.core.api.failure
import com.espotek.roborockathome.core.api.fixture
import com.espotek.roborockathome.core.api.stream
import com.espotek.roborockathome.core.model.Activity
import com.espotek.roborockathome.core.model.CleanProgress
import com.espotek.roborockathome.core.model.HistoryEntry
import com.espotek.roborockathome.core.model.HistoryKind
import com.espotek.roborockathome.core.model.HomeState
import com.espotek.roborockathome.core.model.Look
import com.espotek.roborockathome.core.model.Position
import com.espotek.roborockathome.core.model.Session
import com.espotek.roborockathome.core.model.WireJson
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private val STATE: HomeState = WireJson.decodeFromString(fixture("state.json"))
private val STATE_JSON = WireJson.parseToJsonElement(fixture("state.json")).jsonObject
private val ROBOTS = STATE_JSON.getValue("robots").jsonArray

/** A state like the fixture's, with only [robots]. */
private fun stateWith(robots: List<JsonObject>): String = JsonObject(STATE_JSON + ("robots" to JsonArray(robots))).toString()

private fun event(name: String, data: String) = "event: $name\nid: 1\ndata: $data\n\n"

// Each line read is a hop to the io dispatcher and back, so keep-alives let the fetches a state
// starts finish before what comes next.
private val PAUSE = ": keep-alive\n\n".repeat(20)

private const val SIGN_IN_FIRST = """{"error":{"code":"unauthenticated","message":"Sign in first."}}"""

private class Rig(scope: TestScope) {
    val fake = FakeServer()
    val sessions = MemorySessions("ZGVtbw.84354763.1797920388.l9slB50VNVDLr")
    val store = HomeStore(fake.api(StandardTestDispatcher(scope.testScheduler), sessions), scope.backgroundScope)
    val home get() = store.home.value

    init {
        fake.on("GET /api/session") { it.answer(200, fixture("session.json")) }
        for (robot in STATE.robots) fake.on("GET /api/robots/${robot.id}/path") { it.answer(200, fixture("path.json")) }
        servePlans(STATE.robots.map { it.id }, STATE.robots.associate { it.map!!.rev to it.map!!.w * it.map!!.h })
    }

    /** map.bin for the robots [ids], with the size each rev has. */
    fun servePlans(ids: List<String>, sizes: Map<String, Int>) {
        for (id in ids) {
            fake.on("GET /api/robots/$id/map.bin") { request ->
                val size = sizes.getValue(request.url.queryParameter("rev")!!)
                request.answer(200, ByteArray(size).toResponseBody("application/octet-stream".toMediaType()))
            }
        }
    }

    fun robot(id: String) = home.robots.single { it.id == id }
}

class HomeStoreTest {
    @Test
    fun replayingTheRecordedStream() = runTest {
        val rig = Rig(this)
        val recorded = fixture("events.sse")
        rig.fake.on("GET /api/events") { it.stream(recorded) }
        // Where upstairs was when the recording started: its first update starts at 2126.
        val before = FloatArray(2126) { it * 0.5f }
        rig.fake.on("GET /api/robots/upstairs/path") { it.answer(200, """{"rev":2,"points":[${before.joinToString(",")}]}""") }

        rig.store.start()
        runCurrent()

        val home = rig.home
        // The recording ends, the session still works, so it waits to reconnect.
        assertEquals(Link.Reconnecting, home.link)
        assertEquals(Session.Person("demo", Look.System), home.session)
        assertEquals(listOf("downstairs", "upstairs", "garage", "studio"), home.robots.map { it.id })

        val downstairs = STATE.robots[0].copy(
            activity = Activity.Cleaning,
            stateLabel = "Cleaning rooms",
            charging = false,
            clean = CleanProgress(0.6, 12),
            pos = Position(46.5, 8.5, 0, 16, 1795326356, live = true),
            seen = 1795326356,
        )
        assertEquals(downstairs, rig.robot("downstairs"))
        val upstairs = STATE.robots[1].copy(
            clean = CleanProgress(78.4, 979),
            pos = Position(215.1, 32.5, 0, 18, 1795326356, live = true),
            seen = 1795326356,
        )
        assertEquals(upstairs, rig.robot("upstairs"))
        assertEquals(STATE.robots.drop(2), home.robots.drop(2))

        val parser = SseParser()
        val updates = recorded.lines().mapNotNull { parser.line(it) }.mapNotNull { (decodeEvent(it) as? ServerEvent.Path)?.update }
        fun added(id: String) = updates.filter { it.id == id }.flatMap { it.points.toList() }.toFloatArray()

        val downPath = home.paths.getValue("downstairs")
        assertEquals(13L, downPath.rev)
        assertEquals(28, downPath.points.size)
        assertArrayEquals(added("downstairs"), downPath.points, 0f)
        assertArrayEquals(floatArrayOf(42.5f, 8.5f), downPath.points.copyOfRange(0, 2), 0f)
        assertArrayEquals(floatArrayOf(46.5f, 8.5f), downPath.points.copyOfRange(26, 28), 0f)

        val upPath = home.paths.getValue("upstairs")
        assertEquals(16L, upPath.rev)
        assertEquals(2158, upPath.points.size)
        assertArrayEquals(before + added("upstairs"), upPath.points, 0f)
        assertArrayEquals(floatArrayOf(215.1f, 32.5f), upPath.points.copyOfRange(2156, 2158), 0f)

        assertEquals(0, home.paths.getValue("garage").points.size)
        assertEquals(setOf("downstairs", "upstairs", "garage"), home.paths.keys)
        // Fetched for the robots mid-job when the state came, and never again: every update lined up.
        assertEquals(1, rig.fake.asked("GET /api/robots/upstairs/path"))
        assertEquals(1, rig.fake.asked("GET /api/robots/garage/path"))
        assertEquals(0, rig.fake.asked("GET /api/robots/downstairs/path"))

        for (robot in STATE.robots) {
            val plan = home.plans.getValue(robot.id)
            assertEquals(robot.map!!.rev, plan.rev)
            assertEquals(robot.map!!.w * robot.map!!.h, plan.bytes.size)
        }
        // Not asked for, so the history event was passed over.
        assertNull(home.history)
        rig.store.stop()
    }

    @Test
    fun aPatchIsMergedFieldByFieldAndANewRobotArrivesWhole() = runTest {
        val rig = Rig(this)
        val porch = JsonObject(ROBOTS[3].jsonObject + ("id" to JsonPrimitive("porch")) + ("name" to JsonPrimitive("Porch")))
        val newMap = JsonObject(ROBOTS[0].jsonObject.getValue("map").jsonObject + ("rev" to JsonPrimitive("aaaa0001")))
        rig.fake.on("GET /api/events") {
            it.stream(
                event("state", fixture("state.json")) + PAUSE +
                    event("robot", """{"id":"garage","activity":"idle","state_label":"Idle","error":null,"attention":null}""") +
                    event("robot", JsonObject(mapOf("id" to JsonPrimitive("downstairs"), "map" to newMap)).toString()) +
                    event("robot", porch.toString()) + PAUSE,
            )
        }
        rig.servePlans(
            STATE.robots.map { it.id } + "porch",
            STATE.robots.associate { it.map!!.rev to it.map!!.w * it.map!!.h } + ("aaaa0001" to 288 * 228),
        )

        rig.store.start()
        runCurrent()

        assertEquals(listOf("downstairs", "upstairs", "garage", "studio", "porch"), rig.home.robots.map { it.id })
        assertEquals(
            STATE.robots[2].copy(activity = Activity.Idle, stateLabel = "Idle", error = null, attention = null),
            rig.robot("garage"),
        )
        assertEquals("aaaa0001", rig.robot("downstairs").map!!.rev)
        assertEquals("aaaa0001", rig.home.plans.getValue("downstairs").rev)
        assertEquals(STATE.robots[3].copy(id = "porch", name = "Porch"), rig.robot("porch"))
        assertEquals(STATE.robots[3].map!!.rev, rig.home.plans.getValue("porch").rev)
        rig.store.stop()
    }

    @Test
    fun aNewStateDropsWhatBelongedToRobotsThatHaveGone() = runTest {
        val rig = Rig(this)
        rig.fake.on("GET /api/events") {
            it.stream(event("state", fixture("state.json")) + PAUSE + event("state", stateWith(listOf(ROBOTS[1].jsonObject))) + PAUSE)
        }
        rig.store.start()
        runCurrent()
        assertEquals(listOf("upstairs"), rig.home.robots.map { it.id })
        assertEquals(setOf("upstairs"), rig.home.plans.keys)
        assertEquals(setOf("upstairs"), rig.home.paths.keys)
        rig.store.stop()
    }

    @Test
    fun pathsAppendReplaceAndRefetch() = runTest {
        val rig = Rig(this)
        fun path(rev: Int, start: Int, vararg points: Float) =
            event("path", """{"id":"downstairs","rev":$rev,"start":$start,"points":[${points.joinToString(",")}]}""")
        rig.fake.on("GET /api/events") {
            it.stream(
                event("state", fixture("state.json")) + PAUSE +
                    path(1, 0, 1f, 2f) +
                    path(2, 2, 3f, 4f) +
                    path(3, 0, 5f, 6f) +
                    // An update was missed: what is held is no longer the start of the server's path.
                    path(5, 4, 9f, 10f) + PAUSE +
                    path(6, 6, 11f, 12f) + PAUSE,
            )
        }
        rig.fake.on("GET /api/robots/downstairs/path") { it.answer(200, """{"rev":5,"points":[5,6,7,8,9,10]}""") }

        rig.store.start()
        runCurrent()

        val held = rig.home.paths.getValue("downstairs")
        assertEquals(6L, held.rev)
        assertArrayEquals(floatArrayOf(5f, 6f, 7f, 8f, 9f, 10f, 11f, 12f), held.points, 0f)
        assertEquals(1, rig.fake.asked("GET /api/robots/downstairs/path"))
        rig.store.stop()
    }

    @Test
    fun historyIsKeptNewestFirstAndAtMost200() = runTest {
        val rig = Rig(this)
        val kept = (1000L downTo 801L).map { HistoryEntry(it, "garage", HistoryKind.Command, "Locate") }
        rig.fake.on("GET /api/history") {
            assertEquals("200", it.url.queryParameter("limit"))
            it.answer(200, WireJson.encodeToString(kept))
        }
        rig.fake.on("GET /api/events") {
            it.stream(
                event("state", fixture("state.json")) + PAUSE +
                    event("history", """{"ts":2000,"robot":"downstairs","kind":"started","text":"Started cleaning"}""") +
                    event("history", """{"ts":2001,"robot":"downstairs","kind":"finished","text":"Finished: 0.6 m² in 1 min"}"""),
            )
        }
        rig.store.loadHistory()
        rig.store.start()
        runCurrent()

        val history = rig.home.history!!
        assertEquals(200, history.size)
        assertEquals(listOf(2001L, 2000L, 1000L), history.take(3).map { it.ts })
        assertEquals(803L, history.last().ts)
        assertEquals(HistoryKind.Finished, history[0].kind)
        rig.store.stop()
    }

    @Test
    fun reconnectWaitsDoubleUpTo30sAndStartOverWhenAStateArrives() = runTest {
        val rig = Rig(this)
        val asked = mutableListOf<Long>()
        rig.fake.on("GET /api/events") {
            asked += testScheduler.currentTime
            if (asked.size != 8) throw IOException("Connection refused")
            it.stream(event("state", stateWith(emptyList())))
        }
        // Not answering either: that says nothing about the session, so it keeps trying.
        rig.fake.on("GET /api/session") { throw IOException("Connection refused") }

        rig.store.start()
        runCurrent()
        assertEquals(Link.Reconnecting, rig.home.link)
        advanceTimeBy(95_000)
        runCurrent()

        assertEquals(listOf(0L, 1_000, 3_000, 7_000, 15_000, 31_000, 61_000, 91_000, 92_000, 94_000), asked)
        assertEquals(Link.Reconnecting, rig.home.link)
        rig.store.stop()
    }

    @Test
    fun theServerSayingNobodyIsSignedInStopsTheStore() = runTest {
        val rig = Rig(this)
        rig.fake.on("GET /api/events") { it.answer(401, SIGN_IN_FIRST) }
        rig.fake.on("GET /api/session") { it.answer(200, """{"authenticated":false}""") }

        rig.store.start()
        runCurrent()
        assertEquals(Link.SignedOut, rig.home.link)
        advanceTimeBy(600_000)
        assertEquals(1, rig.fake.asked("GET /api/events"))

        // After a sign-in it can start again.
        rig.store.start()
        assertEquals(Link.Connecting, rig.home.link)
        runCurrent()
        assertEquals(2, rig.fake.asked("GET /api/events"))
        rig.store.stop()
    }

    @Test
    fun a401AnywhereSignsOut() = runTest {
        val rig = Rig(this)
        rig.fake.on("GET /api/events") { it.stream(event("state", stateWith(emptyList()))) }
        rig.fake.on("GET /api/schedules") { it.answer(401, SIGN_IN_FIRST) }
        rig.store.loadHistory()
        rig.fake.on("GET /api/history") { it.answer(200, fixture("history.json")) }

        rig.store.start()
        runCurrent()
        assertEquals(Session.Person("demo", Look.System), rig.home.session)
        assertEquals(2, rig.home.history!!.size)

        failure<ApiException> { rig.store.api.schedules() }
        runCurrent()
        assertEquals(Link.SignedOut, rig.home.link)
        assertNull(rig.home.session)
        assertNull(rig.home.history)
        advanceTimeBy(600_000)
        assertEquals(1, rig.fake.asked("GET /api/events"))
    }

    @Test
    fun aRequestWithTheCookieForgottenIsRefusedAndSignsOut() = runTest {
        val rig = Rig(this)
        rig.fake.on("GET /api/events") { it.stream(event("state", stateWith(emptyList()))) }
        // As the server answers: without the session cookie nothing is let in.
        rig.fake.on("GET /api/schedules") { if (rig.sessions.cookie == null) it.answer(401, SIGN_IN_FIRST) else it.answer(200, "[]") }

        rig.store.start()
        runCurrent()
        // With the cookie the request is let in and the session goes on.
        rig.store.api.schedules()
        runCurrent()
        assertEquals(Session.Person("demo", Look.System), rig.home.session)
        rig.sessions.cookie = null
        failure<ApiException> { rig.store.api.schedules() }
        runCurrent()
        assertEquals(Link.SignedOut, rig.home.link)
        advanceTimeBy(600_000)
        assertEquals(1, rig.fake.asked("GET /api/events"))
    }

    @Test
    fun anEventThatDoesNotFitTheModelsStopsTheStoreUntilItIsStartedAgain() = runTest {
        val rig = Rig(this)
        val mopping = JsonObject(ROBOTS[0].jsonObject + ("activity" to JsonPrimitive("mopping")))
        rig.fake.on("GET /api/events") { it.stream(event("state", stateWith(listOf(mopping)))) }

        rig.store.start()
        runCurrent()
        assertEquals(Link.Unreadable, rig.home.link)
        assertEquals(
            "The server sent the state of the home this app cannot read: com.espotek.roborockathome.core.model.Activity " +
                "does not contain element with name 'mopping' at path \$.robots[0].activity.",
            rig.home.problem,
        )
        advanceTimeBy(600_000)
        assertEquals(1, rig.fake.asked("GET /api/events"))

        rig.fake.on("GET /api/events") { it.stream(event("state", fixture("state.json")) + PAUSE) }
        rig.store.start()
        assertNull(rig.home.problem)
        runCurrent()
        assertEquals(STATE.robots, rig.home.robots)
        assertNull(rig.home.problem)
        // Read, then the stream ended as streams do: back to reconnecting as usual.
        assertEquals(Link.Reconnecting, rig.home.link)
        rig.store.stop()
    }

    @Test
    fun aPatchThatDoesNotMergeIntoARobotStopsTheStoreUntilItIsStartedAgain() = runTest {
        val rig = Rig(this)
        rig.fake.on("GET /api/events") {
            it.stream(event("state", fixture("state.json")) + PAUSE + event("robot", """{"id":"downstairs","battery":"full"}"""))
        }

        rig.store.start()
        runCurrent()
        assertEquals(Link.Unreadable, rig.home.link)
        assertEquals(
            "The server sent a robot update this app cannot read: Failed to parse literal '\"full\"' as an int value " +
                "at element: \$.battery.",
            rig.home.problem,
        )
        assertEquals(91, rig.robot("downstairs").battery)
        advanceTimeBy(600_000)
        assertEquals(1, rig.fake.asked("GET /api/events"))

        rig.fake.on("GET /api/events") {
            it.stream(event("state", fixture("state.json")) + PAUSE + event("robot", """{"id":"downstairs","battery":90}"""))
        }
        rig.store.start()
        runCurrent()
        assertNull(rig.home.problem)
        assertEquals(90, rig.robot("downstairs").battery)
        rig.store.stop()
    }

    @Test
    fun aPlanOfTheWrongSizeStopsTheStoreUntilItIsStartedAgain() = runTest {
        val rig = Rig(this)
        rig.fake.on("GET /api/events") { it.stream(event("state", fixture("state.json")) + PAUSE) }
        rig.fake.on("GET /api/robots/downstairs/map.bin") {
            it.answer(200, ByteArray(10).toResponseBody("application/octet-stream".toMediaType()))
        }

        rig.store.start()
        runCurrent()
        assertEquals(Link.Unreadable, rig.home.link)
        assertEquals(
            "The server sent a map this app cannot read: the map of Downstairs has 10 bytes, not 288 x 228.",
            rig.home.problem,
        )
        advanceTimeBy(600_000)
        assertEquals(1, rig.fake.asked("GET /api/events"))
        assertEquals(1, rig.fake.asked("GET /api/robots/downstairs/map.bin"))

        rig.servePlans(listOf("downstairs"), mapOf(STATE.robots[0].map!!.rev to 288 * 228))
        rig.store.start()
        runCurrent()
        assertNull(rig.home.problem)
        assertEquals(STATE.robots.map { it.id }.toSet(), rig.home.plans.keys)
        rig.store.stop()
    }

    @Test
    fun aLookShowsAtOnceAndARefusedOneIsPutBack() = runTest {
        val rig = Rig(this)
        rig.fake.on("GET /api/events") { it.stream(event("state", stateWith(emptyList())) + PAUSE) }
        rig.store.start()
        runCurrent()
        assertEquals(Look.System, rig.home.session!!.look)

        rig.fake.on("PUT /api/me") { it.answer(200, """{"ok":true}""") }
        rig.store.setLook(Look.Dark)
        assertEquals(Session.Person("demo", Look.Dark), rig.home.session)
        assertEquals("""{"theme":"dark"}""", rig.fake.requests.last().bodyText())

        rig.fake.on("PUT /api/me") { it.answer(400, """{"error":{"code":"bad_request","message":"The theme is one of system, light, dark."}}""") }
        val refused = failure<ApiException> { rig.store.setLook(Look.Light) }
        assertEquals("bad_request", refused.code)
        assertEquals(Look.Dark, rig.home.session!!.look)
        rig.store.stop()
    }
}
