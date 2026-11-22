package com.espotek.roborockathome.fixtures

import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Answers requests in place of the server, without a network, through [client]: routes are like
 * `"POST /api/login"`. A route with no answer is a server that cannot be reached.
 */
class FakeServer {
    private val routes = mutableMapOf<String, (Request) -> Response>()
    val asked: MutableList<String> = mutableListOf()

    fun on(route: String, answer: (Request) -> Response) {
        routes[route] = answer
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val route = "${request.method} ${request.url.encodedPath}"
            asked += route
            val answer = routes[route] ?: throw IOException("Nothing answers $route")
            answer(request)
        }
        .build()
}

fun Request.answer(code: Int, body: String, type: String = "application/json", vararg headers: Pair<String, String>): Response =
    Response.Builder()
        .request(this)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("Fake")
        .body(body.toResponseBody(type.toMediaType()))
        .apply { for ((name, value) in headers) header(name, value) }
        .build()
