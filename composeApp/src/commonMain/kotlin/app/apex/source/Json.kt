package app.apex.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    prettyPrint = false
}

fun parseJson(text: String): JsonElement? = try {
    AppJson.parseToJsonElement(text)
} catch (_: Exception) {
    null
}

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)?.takeIf { it !is JsonNull }

operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)?.takeIf { it !is JsonNull }

/** Caminho tolerante: `json.path("a", "b", "c")`. Números viram índice de lista. */
fun JsonElement?.path(vararg keys: Any): JsonElement? {
    var cur: JsonElement? = this
    for (k in keys) {
        cur = when (k) {
            is String -> cur[k]
            is Int -> cur[k]
            else -> null
        }
        if (cur == null) return null
    }
    return cur
}

fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

fun JsonElement?.int(): Int? = long()?.toInt()

fun JsonElement?.double(): Double? = (this as? JsonPrimitive)?.doubleOrNull

fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

fun JsonElement?.list(): List<JsonElement> = (this as? JsonArray)?.toList() ?: emptyList()

fun JsonElement?.keys(): Set<String> = (this as? JsonObject)?.keys ?: emptySet()

/** `//host/x.jpg` → `https://host/x.jpg`. */
fun String?.httpsUrl(): String? = when {
    this == null || isBlank() -> null
    startsWith("//") -> "https:$this"
    else -> this
}
