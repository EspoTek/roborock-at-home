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
import com.espotek.roborockathome.fixtures.Fixtures
import com.espotek.roborockathome.fixtures.timeWords
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// What the Settings index says about each page, and the lines of its lists and About.

private const val NOW = Fixtures.RECORDED // Sunday 22 November 2026, 18:00 in Melbourne
private const val MINUTE = 60L
private const val HOUR = 3600L
private const val DAY = 86400L

// Robolectric for Android's ICU, which writes the times.
@RunWith(RobolectricTestRunner::class)
class SummariesTest {
    private val tunnelOff = Tunnel(TunnelMode.Off, TunnelStatus.Stopped, null, null)
    private val app = AppInfo("0.1.0", demo = false, tz = "Australia/Melbourne", Account(true, "y***@example.com", null), tunnelOff)

    @Test
    fun account() {
        assertEquals("Linked as y***@example.com", accountSummary(app))
        assertEquals("Not linked", accountSummary(app.copy(account = Account(false, null, null))))
        assertEquals(
            "The Roborock login stopped working.",
            accountSummary(app.copy(account = app.account.copy(problem = "The Roborock login stopped working."))),
        )
        // The demo is linked, with no address.
        assertEquals("Not available in the demo", accountSummary(app.copy(demo = true, account = Account(true, null, null))))
    }

    @Test
    fun aLinkedAccountWithoutAnAddressOutsideTheDemoIsABug() {
        assertThrows(IllegalStateException::class.java) { accountSummary(app.copy(account = Account(true, null, null))) }
    }

    @Test
    fun timeZone() {
        assertEquals("Australia/Melbourne", timeZoneSummary(app))
        assertEquals("Not set", timeZoneSummary(app.copy(tz = null)))
    }

    @Test
    fun remoteAccess() {
        val url = "https://maple-river-quiet-lantern.trycloudflare.com"
        assertEquals("Off", remoteSummary(tunnelOff))
        assertEquals("Getting Cloudflare ready", remoteSummary(Tunnel(TunnelMode.Quick, TunnelStatus.Installing, null, null)))
        assertEquals("Connecting to Cloudflare", remoteSummary(Tunnel(TunnelMode.Quick, TunnelStatus.Starting, null, null)))
        assertEquals("On, using a quick link", remoteSummary(Tunnel(TunnelMode.Quick, TunnelStatus.Running, url, null)))
        assertEquals("On, using your own Cloudflare tunnel", remoteSummary(Tunnel(TunnelMode.Token, TunnelStatus.Running, null, null)))
        assertEquals("Not working", remoteSummary(Tunnel(TunnelMode.Token, TunnelStatus.Error, null, "Could not get cloudflared")))
    }

    @Test
    fun people() {
        assertEquals("Who can sign in", peopleSummary(null))
        assertEquals("Only you can sign in", peopleSummary(listOf(User("demo", NOW, you = true))))
        assertEquals("2 people can sign in", peopleSummary(listOf(User("demo", NOW, you = true), User("anna", NOW, you = false))))
    }

    @Test
    fun tokens() {
        val homeAssistant = Token("t_1", "Home Assistant", TokenScope.Control, NOW, null)
        assertEquals("For scripts, agents and home automation", tokensSummary(null))
        assertEquals("No tokens yet", tokensSummary(emptyList()))
        assertEquals("Home Assistant", tokensSummary(listOf(homeAssistant)))
        assertEquals("Home Assistant and Agent", tokensSummary(listOf(homeAssistant, homeAssistant.copy(id = "t_2", name = "Agent"))))
    }

    @Test
    fun tokenDetailSaysScopeMadeAndUsed() {
        val words = timeWords(Locale.UK)
        val token = Token("t_1", "Home Assistant", TokenScope.Control, NOW - 12 * DAY, lastUsed = NOW - 3 * MINUTE)
        assertEquals("Control · made 10 Nov · used 3 min ago", tokenDetail(token, words))
        assertEquals("Read only · made 22 Nov · never used", tokenDetail(token.copy(scope = TokenScope.Read, created = NOW, lastUsed = null), words))
    }

    @Test
    fun aboutGrowsAsTheServerAnswers() {
        val words = timeWords()
        assertEquals(listOf("This app" to "0.1.0"), aboutRows("0.1.0", null, null, words, Locale.US))
        val stats = Stats(62.71, 2 * HOUR + 5 * MINUTE, clients = 1, robots = 4)
        assertEquals(
            listOf(
                "This app" to "0.1.0",
                "The server" to "0.2.0",
                "Memory in use" to "62.7 MB",
                "Running for" to "2 h 5 min",
                "Pages and apps open" to "1",
            ),
            aboutRows("0.1.0", "0.2.0", stats, words, Locale.US),
        )
        // With the device's decimal mark.
        assertEquals("Memory in use" to "62,7 MB", aboutRows("0.1.0", "0.2.0", stats, words, Locale.GERMANY)[2])
    }
}
