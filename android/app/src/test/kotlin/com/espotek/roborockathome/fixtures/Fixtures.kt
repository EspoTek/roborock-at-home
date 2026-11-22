package com.espotek.roborockathome.fixtures

import com.espotek.roborockathome.core.api.RoborockApi
import com.espotek.roborockathome.core.api.SessionStore
import com.espotek.roborockathome.core.model.Account
import com.espotek.roborockathome.core.model.HistoryEntry
import com.espotek.roborockathome.core.model.HistoryKind
import com.espotek.roborockathome.core.model.HomeState
import com.espotek.roborockathome.core.model.Point
import com.espotek.roborockathome.core.model.Robot
import com.espotek.roborockathome.core.model.RobotMap
import com.espotek.roborockathome.core.model.RobotPath
import com.espotek.roborockathome.core.model.Room
import com.espotek.roborockathome.core.model.Schedule
import com.espotek.roborockathome.core.model.Session
import com.espotek.roborockathome.core.state.Home
import com.espotek.roborockathome.core.state.HomeStore
import com.espotek.roborockathome.core.state.Link
import com.espotek.roborockathome.core.state.Plan
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Ready-made states of the home for screens under test, so every screen renders the same realistic
 * data. They are the demo server's own answers: core's recording of it (test resources `fixtures/`),
 * more answers recorded from it for this file (`demo/`), and its floor plans (`maps/`). The
 * recordings were made on 22 November 2026 around 16:45 and 18:00 in Melbourne, the server's zone.
 *
 * Every home is signed in as `demo` with the look Match device and has its history loaded; change
 * what a test needs with `copy`, such as `Fixtures.demo.copy(link = Link.Reconnecting)` or
 * `copy(session = Session.Person("demo", Look.Dark))`.
 */
object Fixtures {
    /** The address the fixtures' server is at, as Settings shows it. */
    const val SERVER_URL = "http://192.168.1.50:8770"

    /** When the fixtures were recorded, in seconds: 18:00 on Sunday 22 November 2026 in Melbourne. */
    const val RECORDED = 1_795_330_800L

    /** [RECORDED] held still on Melbourne's clock: every screen under test reads its time from this. */
    val clock: Clock = Clock.fixed(Instant.ofEpochSecond(RECORDED), ZoneId.of("Australia/Melbourne"))

    private val opening: HomeState = read(HomeState.serializer(), "/fixtures/state.json")
    // Twenty seconds later, after the time zone was set: Studio's battery has given out.
    private val later: HomeState = read(HomeState.serializer(), "/demo/state-studio-offline.json")
    private val person = read(Session.serializer(), "/fixtures/session.json") as Session.Person

    // -- robots --------------------------------------------------------------

    /** Downstairs on its dock, charging, with no job. */
    val downstairsDocked: Robot = opening.robot("downstairs")

    /** Upstairs 45% of the way through cleaning the whole home ("Cleaning"); its path is [upstairsPath]. */
    val upstairsCleaning: Robot = opening.robot("upstairs")

    /**
     * Garage stuck in the Workshop with its main brush jammed: a fault, attention of kind error, and
     * its last position, no longer live. Its path so far is [garagePath].
     */
    val garageJammed: Robot = opening.robot("garage")

    /** Studio idle far from its dock with 4% battery: attention of kind low battery. It cannot mop. */
    val studioLowBattery: Robot = opening.robot("studio")

    /** Studio once its battery has given out: offline, attention of kind offline, last seen in the Bedroom. */
    val studioOffline: Robot = later.robot("studio")

    /** Downstairs three minutes into cleaning Living room and Kitchen ("Cleaning rooms"); its path is [downstairsRoomsPath]. */
    val downstairsCleaningRooms: Robot = read(Robot.serializer(), "/demo/robot-downstairs-cleaning-rooms.json")

    /**
     * Ground floor, docked, on the irregular 14-room synth plan: thirteen named rooms, one of them
     * "Kids' playroom and reading nook", then "Unnamed room". Its `map` (rev, room boxes and label
     * points) is worked out from the plan the way the server does it.
     */
    val groundFloor: Robot = run {
        val meta = ServerJson.parseToJsonElement(text("/maps/meta.json")).jsonObject.getValue("synth").jsonObject
        val w = meta.getValue("w").jsonPrimitive.int
        val h = meta.getValue("h").jsonPrimitive.int
        val ids = meta.getValue("rooms").jsonArray.map { it.jsonPrimitive.int }
        val classes = bytes("/maps/synth.map")
        check(ids.sorted() == GROUND_FLOOR_ROOMS.keys.sorted()) { "The synth plan's rooms are $ids" }
        downstairsDocked.copy(
            id = "ground",
            name = "Ground floor",
            rooms = GROUND_FLOOR_ROOMS.map { (id, name) -> Room(id, name) },
            map = RobotMap(
                rev = planRev(w, h, classes),
                w = w,
                h = h,
                mmPerPx = 50,
                rooms = roomGeometry(w, classes, ids),
                // On the top wall of the Living room, with the robot just off it, as Downstairs sits.
                charger = Point(94.5, 11.5),
                noGo = emptyList(),
                walls = emptyList(),
            ),
            pos = checkNotNull(downstairsDocked.pos).copy(x = 94.5, y = 14.5, room = 17),
        )
    }

    // -- paths ---------------------------------------------------------------

    /** Upstairs' path so far through the whole home: 1063 points, ending where the robot is. */
    val upstairsPath: RobotPath = read(RobotPath.serializer(), "/demo/path-upstairs.json")

    /** Garage's path up to the jam in the Workshop: 1014 points. */
    val garagePath: RobotPath = read(RobotPath.serializer(), "/demo/path-garage.json")

    /** Downstairs' path three minutes into cleaning Living room and Kitchen: 196 points of lanes in the Living room. */
    val downstairsRoomsPath: RobotPath = read(RobotPath.serializer(), "/demo/path-downstairs-cleaning-rooms.json")

    // -- schedules and history -----------------------------------------------

    /**
     * Five schedules as the demo server made them, with names it chose: the whole home on weekdays
     * (Downstairs); every room, every day, two passes at max suction and intense mop water, which is
     * the longest room list (Upstairs); one room at weekends, switched off (Garage); one named
     * "Before guests arrive" (Downstairs); and one whose last run failed with "Studio did not answer".
     */
    val schedules: List<Schedule> = read(ListSerializer(Schedule.serializer()), "/demo/schedules.json")

    /**
     * Three days of history, newest first, for the four demo robots: today's entries as the server
     * recorded them, and the two days before in the server's own wording for each kind of entry
     * (hub.py and scheduler.py), with every kind at least once.
     */
    val history: List<HistoryEntry> = run {
        val recorded = read(ListSerializer(HistoryEntry.serializer()), "/demo/history.json")
        val today = ZonedDateTime.ofInstant(Instant.ofEpochSecond(recorded.first().ts), ZoneId.of("Australia/Melbourne"))
        fun at(daysBefore: Long, hour: Int, minute: Int) =
            today.minusDays(daysBefore).withHour(hour).withMinute(minute).withSecond(12).toEpochSecond()
        val upstairsDaily = "Schedule Main bedroom, Ensuite, Bedroom, Landing, Kids room, Study, Toilet and Storage, every day 07:30"
        val earlier = listOf(
            HistoryEntry(at(0, 7, 30), "upstairs", HistoryKind.Schedule, "$upstairsDaily started"),
            HistoryEntry(at(0, 7, 30), "upstairs", HistoryKind.Started, "Started cleaning"),
            HistoryEntry(at(0, 8, 41), "upstairs", HistoryKind.Finished, "Finished: 64.2 m² in 71 min"),
            HistoryEntry(at(0, 8, 43), "upstairs", HistoryKind.Docked, "Back on the dock"),
            HistoryEntry(at(1, 7, 30), "upstairs", HistoryKind.Schedule, "$upstairsDaily started"),
            HistoryEntry(at(1, 7, 30), "upstairs", HistoryKind.Started, "Started cleaning"),
            HistoryEntry(at(1, 8, 52), "upstairs", HistoryKind.Finished, "Finished: 71.8 m² in 82 min"),
            HistoryEntry(at(1, 8, 54), "upstairs", HistoryKind.Docked, "Back on the dock"),
            HistoryEntry(at(1, 11, 5), "downstairs", HistoryKind.Command, "Clean Kitchen, sent by Home Assistant"),
            HistoryEntry(at(1, 11, 5), "downstairs", HistoryKind.Started, "Started cleaning"),
            HistoryEntry(at(1, 11, 19), "downstairs", HistoryKind.Finished, "Finished: 12.6 m² in 14 min"),
            HistoryEntry(at(1, 11, 21), "downstairs", HistoryKind.Docked, "Back on the dock"),
            HistoryEntry(at(1, 15, 40), "garage", HistoryKind.Error, "Main brush jammed in Workshop"),
            HistoryEntry(at(1, 16, 2), "garage", HistoryKind.Cleared, "Error cleared"),
            HistoryEntry(at(1, 19, 12), "studio", HistoryKind.Offline, "Stopped answering in Entry, battery at 12%"),
            HistoryEntry(at(1, 19, 40), "studio", HistoryKind.Online, "Answering again"),
            HistoryEntry(at(2, 7, 30), "upstairs", HistoryKind.Schedule, "$upstairsDaily started"),
            HistoryEntry(at(2, 7, 30), "upstairs", HistoryKind.Started, "Started cleaning"),
            HistoryEntry(at(2, 8, 47), "upstairs", HistoryKind.Finished, "Finished: 70.3 m² in 77 min"),
            HistoryEntry(at(2, 8, 49), "upstairs", HistoryKind.Docked, "Back on the dock"),
            HistoryEntry(at(2, 9, 0), "downstairs", HistoryKind.Schedule, "Schedule Whole home, weekdays 09:00 started"),
            HistoryEntry(at(2, 9, 0), "downstairs", HistoryKind.Started, "Started cleaning"),
            HistoryEntry(at(2, 10, 12), "downstairs", HistoryKind.Finished, "Finished: 96.3 m² in 72 min"),
            HistoryEntry(at(2, 10, 14), "downstairs", HistoryKind.Docked, "Back on the dock"),
            HistoryEntry(at(2, 14, 20), "studio", HistoryKind.Command, "Suction to quiet, sent by Home Assistant"),
            HistoryEntry(at(2, 17, 0), "downstairs", HistoryKind.Schedule, "Schedule Before guests arrive started"),
            HistoryEntry(at(2, 17, 0), "downstairs", HistoryKind.Started, "Started cleaning"),
            HistoryEntry(at(2, 17, 31), "downstairs", HistoryKind.Finished, "Finished: 38.4 m² in 31 min"),
            HistoryEntry(at(2, 17, 33), "downstairs", HistoryKind.Docked, "Back on the dock"),
        )
        // Entries made in the same second keep the order the server gave them.
        (recorded + earlier.reversed()).sortedByDescending { it.ts }
    }

    // -- homes ---------------------------------------------------------------

    /**
     * The demo as it opens: Downstairs docked, Upstairs cleaning the whole home with its path, Garage
     * jammed with its path, Studio low on battery; every plan delivered, the five [schedules], the
     * [history], and the time zone Australia/Melbourne.
     */
    val demo: Home = home(
        listOf(downstairsDocked, upstairsCleaning, garageJammed, studioLowBattery),
        paths = mapOf("upstairs" to upstairsPath, "garage" to garagePath),
    )

    /** [demo] with Downstairs cleaning Living room and Kitchen, its path drawn through the plan. */
    val cleaningRooms: Home = home(
        listOf(downstairsCleaningRooms, upstairsCleaning, garageJammed, studioLowBattery),
        paths = mapOf("downstairs" to downstairsRoomsPath, "upstairs" to upstairsPath, "garage" to garagePath),
    )

    /** [demo] with Studio offline, last seen in the Bedroom. */
    val offlineRobot: Home = home(
        listOf(downstairsDocked, upstairsCleaning, garageJammed, studioOffline),
        paths = mapOf("upstairs" to upstairsPath, "garage" to garagePath),
    )

    /** One robot, Upstairs, cleaning the whole home with its path, and only its schedules and history. */
    val singleRobot: Home = home(listOf(upstairsCleaning), paths = mapOf("upstairs" to upstairsPath))

    /** One robot, [groundFloor], on the 14-room synth plan, with no schedules or history yet. */
    val groundFloorHome: Home = home(listOf(groundFloor), paths = emptyMap())

    /** A server whose Roborock account is not linked yet: no robots, schedules, history or time zone. */
    val notLinked: Home = Home(
        link = Link.Live,
        problem = null,
        session = person,
        app = opening.app.copy(demo = false, account = Account(linked = false, email = null, problem = null)),
        robots = emptyList(),
        schedules = emptyList(),
        paths = emptyMap(),
        plans = emptyMap(),
        history = emptyList(),
    )

    /** What a store holds before the server has said anything: connecting, and nothing known. */
    val connecting: Home = Home(
        link = Link.Connecting,
        problem = null,
        session = null,
        app = null,
        robots = emptyList(),
        schedules = emptyList(),
        paths = emptyMap(),
        plans = emptyMap(),
        history = null,
    )

    // -- stores --------------------------------------------------------------

    /**
     * A store to hand a screen under test. It is never started, and nothing asked of its `api` is
     * sent or answered, so a control that asks stays busy. Its own `home` is not the fixture's: a
     * screen reads the [Home] it is given. Its `setLook` throws, as a store's does before the server
     * has said who is signed in.
     */
    fun store(): HomeStore =
        HomeStore(RoborockApi(SERVER_URL, NoSession(), OkHttpClient(), Nowhere), CoroutineScope(Nowhere))

    /**
     * A store whose every request is refused at once with [message], as the server words its errors,
     * for a screen's failure states: a message, or an error next to a form. Otherwise like [store].
     */
    fun refusingStore(message: String, code: String = "robot_unreachable", status: Int = 503): HomeStore {
        val body = buildJsonObject {
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }.toString()
        return HomeStore(RoborockApi(SERVER_URL, NoSession(), answering(status, body), Inline), CoroutineScope(Nowhere))
    }

    /**
     * A store whose every request is accepted at once, as the server answers `{"ok": true}`. Like
     * [store] it is never started, so the home only changes when a test changes it.
     */
    fun acceptingStore(): HomeStore =
        HomeStore(RoborockApi(SERVER_URL, NoSession(), answering(200, """{"ok":true}"""), Inline), CoroutineScope(Inline))

    // A client that answers every request with [status] and the JSON [body].
    private fun answering(status: Int, body: String): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("Answered")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    // -- plumbing ------------------------------------------------------------

    private fun home(robots: List<Robot>, paths: Map<String, RobotPath>): Home {
        val ids = robots.mapTo(HashSet()) { it.id }
        return Home(
            link = Link.Live,
            problem = null,
            session = person,
            app = later.app,
            robots = robots,
            schedules = schedules.filter { it.robot in ids },
            paths = paths,
            plans = robots.associate { it.id to plan(it) },
            history = history.filter { it.robot in ids },
        )
    }

    /** The robot's plan from the floor plan files, which must be the one its map names. */
    private fun plan(robot: Robot): Plan {
        val map = checkNotNull(robot.map) { "${robot.name} has no map" }
        val classes = bytes("/maps/${if (robot.id == "ground") "synth" else robot.id}.map")
        check(planRev(map.w, map.h, classes) == map.rev) { "The plan file for ${robot.name} is not map ${map.rev}" }
        return Plan(map.rev, map.w, map.h, classes)
    }

    private fun HomeState.robot(id: String): Robot = robots.single { it.id == id }
}

// The synth plan's rooms in the order a robot lists them: the named ones, then the unnamed.
private val GROUND_FLOOR_ROOMS = linkedMapOf(
    16 to "Entry",
    17 to "Living room",
    18 to "Dining room",
    19 to "Kitchen",
    20 to "Study",
    21 to "Family room",
    22 to "Hall",
    23 to "Laundry",
    24 to "Guest bedroom",
    25 to "Bathroom",
    26 to "Main bedroom",
    27 to "Kids' playroom and reading nook",
    28 to "Walk-in wardrobe",
    29 to "Unnamed room",
)

// As core reads the server: a field a newer server adds is ignored.
private val ServerJson = Json { ignoreUnknownKeys = true }

private fun <T> read(strategy: DeserializationStrategy<T>, path: String): T = ServerJson.decodeFromString(strategy, text(path))

private fun text(path: String): String = resource(path).readText()

private fun bytes(path: String): ByteArray = resource(path).readBytes()

private fun resource(path: String) =
    checkNotNull(Fixtures::class.java.getResource(path)) { "There is no test resource $path" }

private class NoSession : SessionStore {
    override var cookie: String? = null
}

// Takes the work and never does it: a request handed to it is never sent and never answered.
private object Nowhere : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {}
}

// Does the work on the spot, so an answer reaches the screen before the next frame.
private object Inline : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = false

    override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
}
