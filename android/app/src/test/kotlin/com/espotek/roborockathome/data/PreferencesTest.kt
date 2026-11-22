package com.espotek.roborockathome.data

import com.espotek.roborockathome.core.api.RoborockApi
import com.espotek.roborockathome.core.model.Look
import com.espotek.roborockathome.core.model.Session
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PreferencesTest {
    private fun preferences() = Preferences(RuntimeEnvironment.getApplication())

    @Test
    fun whatIsKeptOutlivesTheObjectThatKeptIt() {
        val kept = preferences()
        assertNull(kept.serverUrl)
        assertNull(kept.name)
        assertNull(kept.cookie)
        assertNull(kept.look)
        kept.serverUrl = "http://192.168.1.50:8770"
        kept.name = "demo"
        kept.cookie = "abc123"
        kept.look = Look.Dark

        val again = preferences()
        assertEquals("http://192.168.1.50:8770", again.serverUrl)
        assertEquals("demo", again.name)
        assertEquals("abc123", again.cookie)
        assertEquals(Look.Dark, again.look)

        again.cookie = null
        assertNull(preferences().cookie)
    }

    // Through OkHttp's own cookie handling, which a fake inside the client would skip: a server on
    // this machine, answering as the real one does.
    @Test
    fun theSessionCookieIsTheOneTheServerSetsUntilTheAppForgetsIt() {
        val sent = mutableListOf<Pair<String, String?>>()
        LocalServer { path, cookie ->
            sent += path to cookie
            when (path) {
                "/api/login" -> "rah_session=s3cr3t; Path=/; HttpOnly; SameSite=Strict" to """{"ok":true}"""
                "/api/session" -> null to if (cookie == null) {
                    """{"authenticated":false}"""
                } else {
                    """{"authenticated":true,"kind":"session","scope":"all","name":"demo","theme":"system"}"""
                }
                else -> error("Nothing answers $path")
            }
        }.use { server ->
            val api = RoborockApi(server.url, preferences())
            runBlocking { api.login("demo", "a password") }
            assertEquals("s3cr3t", preferences().cookie)
            assertEquals(Session.Person("demo", Look.System), runBlocking { api.session() })
            // Signing out is the app forgetting it: the next request goes without it.
            preferences().cookie = null
            assertEquals(Session.SignedOut, runBlocking { api.session() })
            assertEquals(listOf("/api/login" to null, "/api/session" to "rah_session=s3cr3t", "/api/session" to null), sent)
        }
    }
}

/**
 * An HTTP server on the loopback address, one request a connection: [answer] gets the path and the
 * Cookie header and gives the Set-Cookie header (or null) and a JSON body to answer 200 with.
 */
private class LocalServer(private val answer: (path: String, cookie: String?) -> Pair<String?, String>) : AutoCloseable {
    private val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    val url = "http://127.0.0.1:${socket.localPort}"

    private val serving = thread(name = "local server") {
        while (true) {
            val connection = try {
                socket.accept()
            } catch (e: SocketException) {
                return@thread // closed: the test is over
            }
            connection.use {
                val input = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.ISO_8859_1))
                val path = input.readLine().split(" ")[1]
                val headers = generateSequence { input.readLine().takeIf(String::isNotEmpty) }
                    .associate { line -> line.substringBefore(":").lowercase() to line.substringAfter(":").trim() }
                repeat(headers["content-length"]?.toInt() ?: 0) { input.read() }
                val (cookie, body) = answer(path, headers["cookie"])
                val reply = buildString {
                    append("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n")
                    if (cookie != null) append("Set-Cookie: $cookie\r\n")
                    append("Content-Length: ${body.length}\r\n\r\n$body")
                }
                it.getOutputStream().write(reply.toByteArray(Charsets.ISO_8859_1))
            }
        }
    }

    override fun close() {
        socket.close()
        serving.join()
    }
}
