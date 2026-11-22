package com.espotek.roborockathome.core.api

import com.espotek.roborockathome.core.model.ActionType
import com.espotek.roborockathome.core.model.Activity
import com.espotek.roborockathome.core.model.Command
import com.espotek.roborockathome.core.model.LastRun
import com.espotek.roborockathome.core.model.Look
import com.espotek.roborockathome.core.model.ScheduleAction
import com.espotek.roborockathome.core.model.ScheduleRequest
import com.espotek.roborockathome.core.model.Session
import com.espotek.roborockathome.core.model.TokenScope
import com.espotek.roborockathome.core.state.Home
import com.espotek.roborockathome.core.state.HomeStore
import com.espotek.roborockathome.core.state.Link
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** Against the real server in demo mode: one server for the class, a fresh sign-in per test. */
class DemoServerTest {
    companion object {
        private lateinit var server: DemoServer

        @JvmStatic
        @BeforeClass
        fun startServer() {
            server = DemoServer.start()
        }

        @JvmStatic
        @AfterClass
        fun stopServer() {
            server.stop()
        }
    }

    private val sessions = MemorySessions()
    private val api by lazy { RoborockApi(server.baseUrl, sessions) }

    private fun live(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(30.seconds) { block() } }

    private suspend fun signIn() = api.login(DemoServer.NAME, DemoServer.PASSWORD)

    /** Runs a started store in its own scope, which ends once the store has stopped. */
    private suspend fun withStore(block: suspend (HomeStore) -> Unit) = coroutineScope {
        val store = HomeStore(api, this)
        store.start()
        block(store)
        store.stop()
    }

    private suspend fun HomeStore.until(what: (Home) -> Boolean): Home = home.first(what)

    @Test
    fun aWrongPasswordIs401() = live {
        val wrong = failure<ApiException> { api.login(DemoServer.NAME, "not the password") }
        assertEquals(401, wrong.status)
        assertEquals("unauthenticated", wrong.code)
        assertEquals("Wrong name or password.", wrong.message)
        assertNull(sessions.cookie)
        assertEquals(Session.SignedOut, api.session())
    }

    @Test
    fun signedInTheStateAndMapsAreThere() = live {
        signIn()
        assertNotNull(sessions.cookie)
        assertEquals(DemoServer.NAME, (api.session() as Session.Person).name)
        val state = api.state()
        assertTrue(state.app.demo)
        assertEquals(listOf("downstairs", "upstairs", "garage", "studio"), state.robots.map { it.id })
        assertEquals(state.robots.map { it.id }, api.robots().map { it.id })
        assertEquals("Garage", api.robot("garage").name)
        for (robot in state.robots) {
            val map = robot.map!!
            assertEquals(map.w * map.h, api.mapBin(robot.id, map.rev).size)
        }
        assertEquals(404, failure<ApiException> { api.mapBin("downstairs", "00000000") }.status)
        assertEquals("not_found", failure<ApiException> { api.robot("attic") }.code)
        assertEquals(4, api.stats().robots)
        api.path("upstairs")
    }

    @Test
    fun aRoomCleanArrivesThroughTheStore() = live {
        signIn()
        withStore { store ->
            store.until { it.link == Link.Live && it.plans.size == 4 && it.session != null }
            api.command("downstairs", Command.CleanRooms(listOf(16), 1))
            val cleaning = store.until { home ->
                home.robots.single { it.id == "downstairs" }.activity == Activity.Cleaning &&
                    home.paths["downstairs"]?.points?.isNotEmpty() == true
            }
            val first = cleaning.paths.getValue("downstairs")
            val grown = store.until { (it.paths["downstairs"]?.points?.size ?: 0) >= first.points.size + 4 }
            assertTrue(grown.paths.getValue("downstairs").rev > first.rev)
            assertEquals(Activity.Cleaning, grown.robots.single { it.id == "downstairs" }.activity)
            assertEquals(Link.Live, store.home.value.link)
        }
    }

    @Test
    fun schedulesAreMadeChangedRunAndDeleted() = live {
        signIn()
        api.setTimeZone("Australia/Melbourne")
        assertEquals("Australia/Melbourne", api.state().app.tz)
        val asked = ScheduleRequest(
            robot = "downstairs",
            name = null,
            enabled = true,
            days = listOf(0, 1, 2, 3, 4),
            time = "09:30",
            action = ScheduleAction(ActionType.CleanRooms, listOf(16), 1, null, null),
        )
        val made = api.createSchedule(asked)
        assertEquals("Living room, weekdays 09:30", made.name)
        assertNotNull(made.nextRun)
        assertNull(made.lastRun)

        val changed = api.updateSchedule(
            made.id,
            asked.copy(name = "Kitchen early", enabled = false, time = "07:15", action = asked.action.copy(rooms = listOf(17), fan = "max")),
        )
        assertEquals(made.id, changed.id)
        assertEquals("Kitchen early", changed.name)
        assertEquals("07:15", changed.time)
        assertEquals(listOf(17), changed.action.rooms)
        assertEquals("max", changed.action.fan)
        assertNull(changed.nextRun)

        api.runSchedule(made.id)
        val ran = api.schedules().single { it.id == made.id }
        assertEquals(LastRun(ran.lastRun!!.ts, ok = true, message = "Started"), ran.lastRun)

        api.deleteSchedule(made.id)
        assertTrue(api.schedules().none { it.id == made.id })
        assertEquals(404, failure<ApiException> { api.deleteSchedule(made.id) }.status)
    }

    @Test
    fun theLookFollowsTheAccount() = live {
        signIn()
        try {
            api.setLook(Look.Dark)
            assertEquals(Session.Person(DemoServer.NAME, Look.Dark), api.session())
            withStore { store ->
                store.until { it.session != null }
                store.setLook(Look.Light)
                assertEquals(Look.Light, store.home.value.session!!.look)
                assertEquals(Session.Person(DemoServer.NAME, Look.Light), api.session())
            }
        } finally {
            api.setLook(Look.System)
        }
    }

    @Test
    fun peopleAndTokensComeAndGo() = live {
        signIn()
        assertEquals(DemoServer.NAME, api.users().single { it.you }.name)
        val added = api.addUser("someone@example.com", "a long password")
        assertEquals("someone@example.com", added.name)
        assertFalse(added.you)
        assertTrue(api.users().any { it.name == "someone@example.com" && !it.you })

        val theirs = RoborockApi(server.baseUrl, MemorySessions())
        theirs.login("someone@example.com", "a long password")
        assertEquals("someone@example.com", (theirs.session() as Session.Person).name)
        api.removeUser("someone@example.com")
        assertTrue(api.users().none { it.name == "someone@example.com" })
        assertEquals(Session.SignedOut, theirs.session())
        assertEquals("bad_request", failure<ApiException> { api.removeUser(DemoServer.NAME) }.code)

        val made = api.createToken("Agent", TokenScope.Control)
        assertTrue(made.token.startsWith("rah_"))
        val token = api.tokens().single { it.id == made.id }
        assertEquals("Agent", token.name)
        assertEquals(TokenScope.Control, token.scope)
        assertNull(token.lastUsed)
        api.deleteToken(made.id)
        assertTrue(api.tokens().none { it.id == made.id })

        // A new password signs out other sessions but gives this one a new cookie.
        val before = sessions.cookie
        api.changePassword(DemoServer.PASSWORD, "another long password")
        assertNotEquals(before, sessions.cookie)
        assertEquals(DemoServer.NAME, (api.session() as Session.Person).name)
        api.changePassword("another long password", DemoServer.PASSWORD)
    }

    @Test
    fun theHistoryLoads() = live {
        signIn()
        val history = api.history(200)
        assertTrue(history.isNotEmpty())
        assertEquals(history.sortedByDescending { it.ts }, history)
        withStore { store ->
            store.loadHistory()
            assertTrue(store.until { it.history != null }.history!!.isNotEmpty())
        }
    }

    @Test
    fun linkingARoborockAccountIsNotInTheDemo() = live {
        signIn()
        for (refused in listOf(
            failure<ApiException> { api.requestAccountCode("someone@example.com") },
            failure<ApiException> { api.linkAccount("someone@example.com", "123456") },
            failure<ApiException> { api.unlinkAccount() },
        )) {
            assertEquals(409, refused.status)
            assertEquals("demo", refused.code)
        }
    }

    @Test
    fun forgettingTheCookieEndsTheStore() = live {
        signIn()
        withStore { store ->
            store.until { it.link == Link.Live && it.session != null }
            // Signing out is the app forgetting the cookie; the server then lets nothing in.
            sessions.cookie = null
            assertEquals(401, failure<ApiException> { api.state() }.status)
            assertEquals(Link.SignedOut, store.until { it.link == Link.SignedOut }.link)
        }
        assertEquals(Session.SignedOut, api.session())
    }

    @Test
    fun withoutASessionTheStoreIsSignedOut() = live {
        withStore { store -> store.until { it.link == Link.SignedOut } }
    }
}
