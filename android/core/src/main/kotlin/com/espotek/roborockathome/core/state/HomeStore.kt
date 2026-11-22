package com.espotek.roborockathome.core.state

import com.espotek.roborockathome.core.api.ApiException
import com.espotek.roborockathome.core.api.ROBOT_UPDATE
import com.espotek.roborockathome.core.api.RoborockApi
import com.espotek.roborockathome.core.api.ServerEvent
import com.espotek.roborockathome.core.api.UnreadableException
import com.espotek.roborockathome.core.model.Activity
import com.espotek.roborockathome.core.model.HomeState
import com.espotek.roborockathome.core.model.Look
import com.espotek.roborockathome.core.model.PathUpdate
import com.espotek.roborockathome.core.model.Robot
import com.espotek.roborockathome.core.model.RobotPath
import com.espotek.roborockathome.core.model.Session
import com.espotek.roborockathome.core.model.WireJson
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

private val FIRST_WAIT = 1.seconds
private val LONGEST_WAIT = 30.seconds
private const val HISTORY_KEEP = 200

// In the middle of a job: the state does not carry the path, so it is fetched.
private val ACTIVE = setOf(Activity.Cleaning, Activity.Returning, Activity.Paused, Activity.Error)

/**
 * The live state of the home, for the screens to observe in [home]. [start] opens the event stream
 * and keeps it open while signed in, reconnecting after 1 s, then 2, 4 ... up to 30 s; [stop] closes
 * it. All its work, and so its delays, runs in [scope].
 *
 * The session ends, and [home] says [Link.SignedOut], when the server says nobody is signed in, or
 * when any request through [api] other than a login is answered 401.
 * When the stream brings something this app cannot read, the store stops with [Link.Unreadable] and
 * says what in [Home.problem]; [start] tries again from scratch.
 */
class HomeStore(val api: RoborockApi, private val scope: CoroutineScope) {
    private val state = MutableStateFlow(
        Home(
            link = Link.Connecting,
            problem = null,
            session = null,
            app = null,
            robots = emptyList(),
            schedules = emptyList(),
            paths = emptyMap(),
            plans = emptyMap(),
            history = null,
        ),
    )
    val home: StateFlow<Home> = state.asStateFlow()

    // The stream, the fetches it starts and the screens' calls may run on different threads, so
    // what follows, and every change to [state], is under this lock.
    private val lock = Any()
    private var running: CoroutineScope? = null
    private val pathsLoading = mutableSetOf<String>()
    private val plansLoading = mutableMapOf<String, String>() // robot id to the map rev on its way
    private var sessionLoading = false
    private var historyWanted = false
    private var historyLoading = false

    fun start() {
        synchronized(lock) {
            check(running == null) { "The store is already running." }
            val run = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext.job))
            running = run
            state.update { it.copy(link = Link.Connecting, problem = null) }
            run.launch { api.signedOut.collect { signOut(run) } }
            run.launch { stream(run) }
        }
    }

    /** Closes the stream. What is held stays, for [start] to carry on from. */
    fun stop() {
        synchronized(lock) { halt() }
    }

    /** Fetches the history, and from then on keeps it current. */
    fun loadHistory() {
        synchronized(lock) {
            historyWanted = true
            // When stopped, the first state after the next start asks for it.
            val run = running ?: return
            fetchHistory(run)
        }
    }

    /** Shows [look] at once, and puts the previous one back if the server refuses it. */
    suspend fun setLook(look: Look) {
        val was = synchronized(lock) {
            val person = checkNotNull(state.value.session) { "The look can be set once the session is known." }
            state.update { it.copy(session = person.copy(look = look)) }
            person.look
        }
        try {
            api.setLook(look)
        } catch (e: ApiException) {
            synchronized(lock) {
                val person = state.value.session
                // Unless it was changed again meanwhile.
                if (person != null && person.look == look) state.update { it.copy(session = person.copy(look = was)) }
            }
            throw e
        }
    }

    private suspend fun stream(run: CoroutineScope) {
        var wait = FIRST_WAIT
        while (true) {
            try {
                api.events().collect { event ->
                    if (event is ServerEvent.State) wait = FIRST_WAIT
                    onEvent(run, event)
                }
            } catch (e: UnreadableException) {
                // Reading it again would fail again.
                unreadable(run, e)
                return
            } catch (e: ApiException) {
                // The stream cannot say why it broke; the session answers whether it is a sign-in problem.
                if (saysSignedOut()) {
                    signOut(run)
                    return
                }
                synchronized(lock) {
                    if (run.isActive) state.update { it.copy(link = Link.Reconnecting) }
                }
                delay(wait)
                wait = minOf(wait * 2, LONGEST_WAIT)
            }
        }
    }

    private suspend fun saysSignedOut(): Boolean = try {
        api.session() == Session.SignedOut
    } catch (e: ApiException) {
        false // not answered, so possibly still signed in: try the stream again
    }

    private fun signOut(run: CoroutineScope) {
        synchronized(lock) {
            if (!run.isActive) return
            halt()
            historyWanted = false
            state.update { it.copy(link = Link.SignedOut, session = null, history = null) }
        }
    }

    private fun unreadable(run: CoroutineScope, problem: UnreadableException) {
        synchronized(lock) {
            if (!run.isActive) return
            halt()
            state.update { it.copy(link = Link.Unreadable, problem = problem.message) }
        }
    }

    private fun halt() {
        running?.cancel()
        running = null
        pathsLoading.clear()
        plansLoading.clear()
        sessionLoading = false
        historyLoading = false
    }

    private fun onEvent(run: CoroutineScope, event: ServerEvent) {
        synchronized(lock) {
            if (!run.isActive) return
            when (event) {
                is ServerEvent.State -> onState(run, event.state)
                is ServerEvent.RobotPatch -> onPatch(run, event)
                is ServerEvent.Path -> onPath(run, event.update)
                is ServerEvent.Schedules -> state.update { it.copy(schedules = event.schedules) }
                is ServerEvent.App -> state.update { it.copy(app = event.app) }
                is ServerEvent.History -> state.update { home ->
                    home.copy(history = home.history?.let { (listOf(event.entry) + it).take(HISTORY_KEEP) })
                }
            }
        }
    }

    private fun onState(run: CoroutineScope, new: HomeState) {
        val ids = new.robots.mapTo(HashSet()) { it.id }
        state.update { home ->
            home.copy(
                link = Link.Live,
                app = new.app,
                robots = new.robots,
                schedules = new.schedules,
                paths = home.paths.filterKeys { it in ids },
                plans = home.plans.filterKeys { it in ids },
            )
        }
        // What is on its way for a robot that has gone is not wanted any more.
        pathsLoading.retainAll(ids)
        plansLoading.keys.retainAll(ids)
        for (robot in new.robots) {
            if (robot.activity in ACTIVE) fetchPath(run, robot.id)
            fetchPlan(run, robot)
        }
        if (state.value.session == null) fetchSession(run)
        // Also after a reconnect: entries made while the stream was down came on no event.
        if (historyWanted) fetchHistory(run)
    }

    private fun onPatch(run: CoroutineScope, patch: ServerEvent.RobotPatch) {
        val robots = state.value.robots
        val at = robots.indexOfFirst { it.id == patch.id }
        // Thrown out of the stream's collector, which ends the stream on it.
        val robot = try {
            if (at < 0) WireJson.decodeFromJsonElement<Robot>(patch.fields) else merged(robots[at], patch.fields)
        } catch (e: SerializationException) {
            throw UnreadableException(ROBOT_UPDATE, e)
        }
        state.update { home ->
            home.copy(robots = if (at < 0) robots + robot else robots.mapIndexed { i, old -> if (i == at) robot else old })
        }
        fetchPlan(run, robot)
    }

    private fun merged(robot: Robot, fields: JsonObject): Robot =
        WireJson.decodeFromJsonElement(JsonObject(WireJson.encodeToJsonElement(robot).jsonObject + fields))

    private fun onPath(run: CoroutineScope, update: PathUpdate) {
        val held = state.value.paths[update.id]
        val points = when {
            update.start == 0 -> update.points
            held != null && update.start == held.points.size -> held.points + update.points
            else -> {
                fetchPath(run, update.id)
                return
            }
        }
        state.update { it.copy(paths = it.paths + (update.id to RobotPath(update.rev, points))) }
    }

    private fun fetchPath(run: CoroutineScope, robotId: String) {
        if (!pathsLoading.add(robotId)) return
        run.launch {
            try {
                val path = api.path(robotId)
                synchronized(lock) {
                    if (pathsLoading.remove(robotId)) state.update { it.copy(paths = it.paths + (robotId to path)) }
                }
            } catch (e: ApiException) {
                // Asked again by the next update that does not line up, or the next state.
                synchronized(lock) { pathsLoading.remove(robotId) }
            }
        }
    }

    /** Fetches the robot's plan unless it is held or on its way already. */
    private fun fetchPlan(run: CoroutineScope, robot: Robot) {
        val map = robot.map ?: return // no map yet
        if (state.value.plans[robot.id]?.rev == map.rev || plansLoading[robot.id] == map.rev) return
        plansLoading[robot.id] = map.rev
        run.launch {
            try {
                val bytes = api.mapBin(robot.id, map.rev)
                if (bytes.size != map.w * map.h) {
                    val why = "the map of ${robot.name} has ${bytes.size} bytes, not ${map.w} x ${map.h}"
                    unreadable(run, UnreadableException("a map", why, null))
                    return@launch
                }
                synchronized(lock) {
                    if (plansLoading.remove(robot.id, map.rev)) {
                        state.update { it.copy(plans = it.plans + (robot.id to Plan(map.rev, map.w, map.h, bytes))) }
                    }
                }
            } catch (e: ApiException) {
                // A 404 means the map changed again. Either way the robot's next patch or state asks again.
                synchronized(lock) { plansLoading.remove(robot.id, map.rev) }
            }
        }
    }

    private fun fetchSession(run: CoroutineScope) {
        if (sessionLoading) return
        sessionLoading = true
        run.launch {
            try {
                val session = api.session()
                synchronized(lock) {
                    sessionLoading = false
                    when (session) {
                        Session.SignedOut -> signOut(run)
                        is Session.Person -> state.update { it.copy(session = session) }
                    }
                }
            } catch (e: ApiException) {
                synchronized(lock) { sessionLoading = false } // asked again with the next state
            }
        }
    }

    private fun fetchHistory(run: CoroutineScope) {
        if (historyLoading) return
        historyLoading = true
        run.launch {
            try {
                val history = api.history(HISTORY_KEEP)
                synchronized(lock) {
                    historyLoading = false
                    if (historyWanted) state.update { it.copy(history = history) }
                }
            } catch (e: ApiException) {
                synchronized(lock) { historyLoading = false } // asked again with the next state
            }
        }
    }
}
