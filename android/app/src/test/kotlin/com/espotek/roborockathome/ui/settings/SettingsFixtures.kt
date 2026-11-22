package com.espotek.roborockathome.ui.settings

import com.espotek.roborockathome.core.model.Account
import com.espotek.roborockathome.core.model.AppInfo
import com.espotek.roborockathome.core.model.Stats
import com.espotek.roborockathome.core.model.Token
import com.espotek.roborockathome.core.model.TokenScope
import com.espotek.roborockathome.core.model.Tunnel
import com.espotek.roborockathome.core.model.TunnelMode
import com.espotek.roborockathome.core.model.TunnelStatus
import com.espotek.roborockathome.core.model.User
import com.espotek.roborockathome.core.state.Home
import com.espotek.roborockathome.fixtures.Fixtures
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * What Settings shows in tests, around the shared [Fixtures]: the lists the server keeps outside the
 * live state, from the demo server's own answers where it gave them (`fixtures/users.json`,
 * `fixtures/stats.json`), dated from the moment the fixtures were recorded.
 */
internal object SettingsFixtures {
    // First: what follows reads with it.
    private val json = Json { ignoreUnknownKeys = true }

    private const val NOW = Fixtures.RECORDED
    private const val DAY = 86400L

    /** The demo's one person, and Anna, added three days before. */
    val users: List<User> = read(ListSerializer(User.serializer()), "/fixtures/users.json") +
        User("anna@example.com", NOW - 3 * DAY - 5 * 3600, you = false)

    /** Home Assistant, used three minutes ago. */
    val homeAssistant = Token("t_4f1a", "Home Assistant", TokenScope.Control, NOW - 12 * DAY, lastUsed = NOW - 180)

    /** Agent, made a moment ago and not used yet; [newAgent] is its secret. */
    val agent = Token("t_9c2e", "Agent", TokenScope.Read, NOW - 40, lastUsed = null)
    val newAgent = NewToken(agent.id, agent.name, "rah_mJ4uQ0b7Xc2LzT9vWnK1pR8sYd5eHf3gA6iBtC0oVqE")

    /** The demo's figures as it answered them just after starting, two hours on. */
    val stats: Stats = read(Stats.serializer(), "/fixtures/stats.json").copy(uptimeSeconds = 2 * 3600 + 5 * 60, clients = 2)

    /** The demo as a real server: a linked account and a quick link running. */
    val linked: Home = Fixtures.demo.withApp {
        copy(
            demo = false,
            account = Account(linked = true, email = "y***@example.com", problem = null),
            tunnel = Tunnel(TunnelMode.Quick, TunnelStatus.Running, "https://maple-river-quiet-lantern.trycloudflare.com", null),
        )
    }

    /** [linked] once the Roborock login has stopped working, in the server's words. */
    val loginExpired: Home = linked.withApp {
        copy(account = account.copy(problem = "The Roborock login stopped working. Link the account again to keep controlling the robots."))
    }

    fun Home.withApp(change: AppInfo.() -> AppInfo): Home = copy(app = checkNotNull(app).change())

    private fun <T> read(strategy: kotlinx.serialization.DeserializationStrategy<T>, path: String): T {
        val resource = checkNotNull(SettingsFixtures::class.java.getResource(path)) { "There is no test resource $path" }
        return json.decodeFromString(strategy, resource.readText())
    }
}
