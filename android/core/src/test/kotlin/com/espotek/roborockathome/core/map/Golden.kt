package com.espotek.roborockathome.core.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.DataInputStream
import java.util.zip.GZIPInputStream

/** The answers the page gave, written by `src/test/golden/make-golden.mjs`. */
object Golden {
    val MAPS = listOf("downstairs", "upstairs", "garage", "studio", "synth")

    fun bytes(path: String): ByteArray =
        checkNotNull(Golden::class.java.getResourceAsStream("/$path")) { "No test resource $path" }.use { it.readBytes() }

    fun json(path: String): JsonObject = Json.parseToJsonElement(bytes(path).decodeToString()).jsonObject

    fun plan(name: String): FloorPlan {
        val g = json("golden/$name.json")
        return FloorPlan(g.int("w"), g.int("h"), bytes("maps/$name.map"))
    }

    /** A painting the page made: big-endian ARGB ints, gzipped. */
    fun pixels(path: String, size: Int): IntArray =
        DataInputStream(GZIPInputStream(bytes("golden/$path").inputStream())).use { s ->
            IntArray(size) { s.readInt() }.also { check(s.read() == -1) { "$path holds more than $size pixels" } }
        }
}

fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
fun JsonObject.arr(key: String): JsonArray = getValue(key).jsonArray
fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
fun JsonObject.double(key: String): Double = getValue(key).jsonPrimitive.double
fun JsonElement.ints(): List<Int> = jsonArray.map { it.jsonPrimitive.int }
fun JsonElement.intOrNull(): Int? = if (this is JsonNull) null else jsonPrimitive.int

/** The page's runs, `[[value, count], ...]`, laid out again one value at a time. */
fun JsonElement.runs(): List<Int?> = jsonArray.flatMap { run ->
    val (value, count) = run.jsonArray
    List(count.jsonPrimitive.int) { value.intOrNull() }
}

/** The page's `[[id, slot], ...]`, the entries of the Map it returns, in order. */
fun JsonElement.slotPairs(): List<Pair<Int, Int>> = jsonArray.map { it.ints().let { (id, slot) -> id to slot } }
