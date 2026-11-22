package com.espotek.roborockathome.core.model

import com.espotek.roborockathome.core.api.failure
import com.espotek.roborockathome.core.api.fixture
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelTest {
    private fun state(): HomeState = WireJson.decodeFromString(fixture("state.json"))

    @Test
    fun stateParses() {
        val state = state()
        assertEquals(20L, state.rev)
        assertEquals(
            AppInfo(
                "0.1.0",
                demo = true,
                tz = null,
                account = Account(linked = true, email = null, problem = null),
                tunnel = Tunnel(TunnelMode.Off, TunnelStatus.Stopped, null, null),
            ),
            state.app,
        )
        assertEquals(listOf("downstairs", "upstairs", "garage", "studio"), state.robots.map { it.id })
        assertEquals(emptyList<Schedule>(), state.schedules)

        val downstairs = state.robots[0]
        assertEquals("S7 Max Ultra", downstairs.modelName)
        assertEquals(Activity.Docked, downstairs.activity)
        assertEquals(RobotLink.Local, downstairs.link)
        assertEquals(true, downstairs.charging)
        assertNull(downstairs.clean)
        assertEquals(Room(17, "Kitchen"), downstairs.rooms[1])
        val map = downstairs.map!!
        assertEquals("94495f89", map.rev)
        assertEquals(288 to 228, map.w to map.h)
        assertEquals(RoomGeometry(80.0, 59.5, 5, 5, 154, 114), map.rooms[16])
        assertEquals((16..22).toList(), map.rooms.keys.toList())
        assertEquals(Point(42.5, 5.5), map.charger)
        assertEquals(listOf(listOf(283.0, 5.0, 283.0, 48.2, 267.0, 48.2, 267.0, 5.0)), map.noGo)
        assertEquals(listOf(listOf(205.0, 213.0, 283.0, 213.0)), map.walls)
        assertEquals(Position(42.5, 8.5, 0, 16, 1795326342, live = true), downstairs.pos)

        val garage = state.robots[2]
        assertEquals(RobotLink.Cloud, garage.link)
        assertEquals(Fault(5, "Main brush jammed"), garage.error)
        assertEquals(
            Attention(
                AttentionKind.Error,
                "Main brush jammed",
                "Stuck: main brush jammed. Last seen in Workshop at 16:45, battery 40%.",
                since = 1795326342,
                room = 17,
                battery = 40,
            ),
            garage.attention,
        )
        assertEquals(false, garage.pos!!.live)

        val studio = state.robots[3]
        assertEquals(AttentionKind.LowBattery, studio.attention!!.kind)
        assertNull(studio.water)
        assertEquals(emptyList<String>(), studio.waterOptions)
        assertEquals(CleanProgress(7.2, 124), studio.clean)
    }

    @Test
    fun everyOtherFixtureParses() {
        assertEquals(Session.Person("demo", Look.System), WireJson.decodeFromString<Session>(fixture("session.json")))
        assertEquals(listOf(User("demo", 1795326342, you = true)), WireJson.decodeFromString<List<User>>(fixture("users.json")))
        assertEquals(Stats(62.7, 0, 0, 4), WireJson.decodeFromString<Stats>(fixture("stats.json")))
        assertEquals(
            listOf(
                HistoryEntry(1795326342, "garage", HistoryKind.Error, "Main brush jammed"),
                HistoryEntry(1795326342, "upstairs", HistoryKind.Started, "Started cleaning"),
            ),
            WireJson.decodeFromString<List<HistoryEntry>>(fixture("history.json")),
        )
        val path = WireJson.decodeFromString<RobotPath>(fixture("path.json"))
        assertEquals(0L, path.rev)
        assertEquals(0, path.points.size)
        assertEquals(emptyList<Schedule>(), WireJson.decodeFromString<List<Schedule>>(fixture("schedules.json")))
        assertEquals(emptyList<Token>(), WireJson.decodeFromString<List<Token>>(fixture("tokens.json")))
        assertEquals(
            ErrorDetail("bad_request", "Set the time zone first."),
            WireJson.decodeFromString<ErrorBody>(fixture("schedule-created.json")).error,
        )
        assertEquals("bad_request", WireJson.decodeFromString<ErrorBody>(fixture("error.json")).error.code)
    }

    @Test
    fun theOtherShapesParse() {
        assertEquals(Session.SignedOut, WireJson.decodeFromString<Session>("""{"authenticated":false}"""))
        assertEquals(
            Token("t_1a2b", "Agent", TokenScope.Control, 1794563000, null),
            WireJson.decodeFromString<Token>(
                """{"id":"t_1a2b","name":"Agent","scope":"control","created":1794563000,"last_used":null}""",
            ),
        )
        assertEquals(
            Schedule(
                "s_4f9a1c", "b3f1c2d4e5", "Weekday kitchen", enabled = true, days = listOf(0, 1, 2, 3, 4),
                time = "09:30", action = ScheduleAction(ActionType.CleanRooms, listOf(16), 1, null, null),
                nextRun = 1794600600, lastRun = LastRun(1794514200, ok = true, message = "Started"),
            ),
            WireJson.decodeFromString<Schedule>(
                """{"id":"s_4f9a1c","robot":"b3f1c2d4e5","name":"Weekday kitchen","enabled":true,"days":[0,1,2,3,4],
                "time":"09:30","action":{"type":"clean_rooms","rooms":[16],"repeat":1,"fan":null,"water":null},
                "next_run":1794600600,"last_run":{"ts":1794514200,"ok":true,"message":"Started"}}""",
            ),
        )
        val update = WireJson.decodeFromString<PathUpdate>("""{"id":"b3f1c2d4e5","rev":88,"start":1848,"points":[203.0,310.4]}""")
        assertEquals(1848, update.start)
        assertArrayEquals(floatArrayOf(203.0f, 310.4f), update.points, 0f)
    }

    @Test
    fun aDocumentedFieldMissingFails() {
        val whole = WireJson.parseToJsonElement(fixture("state.json")).jsonObject
        val robots = whole.getValue("robots").jsonArray
        val noBattery = JsonObject(robots[0].jsonObject - "battery")
        failure<SerializationException> {
            WireJson.decodeFromJsonElement(HomeState.serializer(), JsonObject(whole + ("robots" to JsonArray(listOf(noBattery) + robots.drop(1)))))
        }
        // Nullable is not optional: a null field still has to be there.
        val app = whole.getValue("app").jsonObject
        failure<SerializationException> {
            WireJson.decodeFromJsonElement(HomeState.serializer(), JsonObject(whole + ("app" to JsonObject(app - "tz"))))
        }
        failure<SerializationException> {
            WireJson.decodeFromJsonElement(HomeState.serializer(), JsonObject(whole + ("app" to JsonObject(app + ("version" to JsonNull)))))
        }
        failure<SerializationException> { WireJson.decodeFromString<Session>("""{"authenticated":true,"kind":"session"}""") }
        failure<SerializationException> { WireJson.decodeFromString<Session>("{}") }
    }

    @Test
    fun fieldsANewerServerAddsAreIgnored() {
        val whole = WireJson.parseToJsonElement(fixture("state.json")).jsonObject
        val robots = whole.getValue("robots").jsonArray
        val newer = JsonObject(
            whole + ("later" to JsonPrimitive(1)) +
                ("robots" to JsonArray(robots.map { JsonObject(it.jsonObject + ("lidar" to JsonPrimitive("on"))) })),
        )
        assertEquals(state(), WireJson.decodeFromJsonElement(HomeState.serializer(), newer))
    }

    @Test
    fun requestBodies() {
        assertEquals(
            """{"command":"clean_rooms","rooms":[16,17],"repeat":2}""",
            WireJson.encodeToString<Command>(Command.CleanRooms(listOf(16, 17), 2)),
        )
        assertEquals("""{"command":"start"}""", WireJson.encodeToString<Command>(Command.Start))
        assertEquals("""{"command":"set_water","value":"mild"}""", WireJson.encodeToString<Command>(Command.SetWater("mild")))
        assertEquals("""{"mode":"quick"}""", WireJson.encodeToString<TunnelSetting>(TunnelSetting.Quick))
        assertEquals("""{"mode":"token","token":"abc"}""", WireJson.encodeToString<TunnelSetting>(TunnelSetting.OwnTunnel("abc")))
        assertEquals("""{"theme":"dark"}""", WireJson.encodeToString(MeRequest(Look.Dark)))
        assertEquals("""{"current":"a","new":"b"}""", WireJson.encodeToString(PasswordRequest("a", "b")))
        assertEquals("""{"name":"Agent","scope":"read"}""", WireJson.encodeToString(TokenRequest("Agent", TokenScope.Read)))
        assertEquals(
            """{"robot":"downstairs","name":null,"enabled":true,"days":[5,6],"time":"08:00",""" +
                """"action":{"type":"clean_all","rooms":[],"repeat":1,"fan":"max","water":null}}""",
            WireJson.encodeToString(
                ScheduleRequest("downstairs", null, true, listOf(5, 6), "08:00", ScheduleAction(ActionType.CleanAll, emptyList(), 1, "max", null)),
            ),
        )
    }
}
