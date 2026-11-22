package com.espotek.roborockathome.ui.signin

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerAddressTest {
    @Test
    fun whatPeopleTypeBecomesOneAddress() {
        val cases = mapOf(
            "192.168.1.50:8770" to "http://192.168.1.50:8770",
            "  192.168.1.50:8770  " to "http://192.168.1.50:8770",
            "http://192.168.1.50:8770" to "http://192.168.1.50:8770",
            "http://192.168.1.50:8770/" to "http://192.168.1.50:8770",
            "192.168.1.50:8770/" to "http://192.168.1.50:8770",
            "HTTP://Robots.Local:8770" to "http://robots.local:8770",
            "10.0.2.2:8793" to "http://10.0.2.2:8793",
            "homeserver" to "http://homeserver",
            "http://homeserver:80/" to "http://homeserver",
            "https://rah.example.com" to "https://rah.example.com",
            "https://rah.example.com/" to "https://rah.example.com",
            "https://home.example.com/robots/" to "https://home.example.com/robots",
            "[fd00::50]:8770" to "http://[fd00::50]:8770",
        )
        for ((typed, kept) in cases) assertEquals(typed, kept, serverAddressOrNull(typed))
    }

    @Test
    fun whatIsNotAnAddressIsNull() {
        for (typed in listOf("", "   ", "ftp://192.168.1.50", "192.168.1.50:port", "http://", "http://a b", "http://me:pw@192.168.1.50:8770",
            "http://192.168.1.50:8770/?x=1", "http://192.168.1.50:8770/#top")) {
            assertEquals(typed, null, serverAddressOrNull(typed))
        }
    }
}
