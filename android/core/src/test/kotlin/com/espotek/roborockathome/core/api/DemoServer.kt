package com.espotek.roborockathome.core.api

import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The real server in demo mode, as a child process: `uv run roborock-at-home serve --demo` from the
 * repository Gradle names in the system property `rahRepo`.
 */
class DemoServer private constructor(private val process: Process, val baseUrl: String, private val log: File) {
    fun stop() {
        // SIGTERM: uv passes it on, and the demo deletes its data directory on the way out.
        process.destroy()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            error("The demo server did not stop within 10 seconds, so it was killed. Its output is in $log.")
        }
        println("Stopped the demo server.")
    }

    companion object {
        const val NAME = "demo"
        const val PASSWORD = "integration-test-password"

        fun start(): DemoServer {
            val repo = File(checkNotNull(System.getProperty("rahRepo")) { "Run the tests through Gradle, which sets rahRepo." })
            val port = ServerSocket(0).use { it.localPort }
            val log = File("build/demo-server-$port.log")
            val started = System.nanoTime()
            println("Starting the demo server from $repo on port $port; its output goes to $log.")
            val process = ProcessBuilder(
                "uv", "run", "--project", repo.path,
                "roborock-at-home", "serve", "--demo", "--host", "127.0.0.1", "--port", port.toString(),
            )
                .redirectErrorStream(true)
                .redirectOutput(log)
                .apply { environment()["RAH_DEMO_PASSWORD"] = PASSWORD }
                .start()
            val server = DemoServer(process, "http://127.0.0.1:$port", log)
            val health = Request.Builder().url("${server.baseUrl}/healthz").build()
            val client = OkHttpClient()
            val deadline = started + TimeUnit.SECONDS.toNanos(60)
            while (true) {
                check(process.isAlive) { "The demo server exited with ${process.exitValue()}:\n${log.readText()}" }
                val up = try {
                    client.newCall(health).execute().use { it.isSuccessful }
                } catch (e: IOException) {
                    false // not listening yet: ask again shortly
                }
                if (up) break
                if (System.nanoTime() > deadline) {
                    server.stop()
                    error("The demo server did not answer /healthz within 60 seconds:\n${log.readText()}")
                }
                Thread.sleep(100)
            }
            println("The demo server answered after ${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)} ms.")
            return server
        }
    }
}
