package dev.sfcloud.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

fun JsonObject.str(name: String): String? {
    val value = get(name) ?: return null
    if (value.isJsonNull) return null
    return if (value.isJsonPrimitive) value.asString else value.toString()
}

fun JsonObject.int(name: String): Int? {
    val value = get(name) ?: return null
    if (!value.isJsonPrimitive) return null
    return runCatching { value.asInt }.getOrElse { value.asString.toIntOrNull() }
}

fun JsonObject.bool(name: String): Boolean? {
    val value = get(name) ?: return null
    if (!value.isJsonPrimitive) return null
    val primitive = value.asJsonPrimitive
    return if (primitive.isBoolean) primitive.asBoolean else primitive.asString.toBooleanStrictOrNull()
}

fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

fun JsonObject.arr(name: String): JsonArray? = get(name)?.takeIf { it.isJsonArray }?.asJsonArray

fun JsonElement?.objects(): List<JsonObject> =
    this?.takeIf { it.isJsonArray }?.asJsonArray?.filter { it.isJsonObject }?.map { it.asJsonObject }.orEmpty()

fun JsonObject.path(vararg names: String): JsonElement? {
    var current: JsonElement = this
    for (name in names) {
        if (!current.isJsonObject) return null
        current = current.asJsonObject.get(name) ?: return null
    }
    return current.takeUnless { it.isJsonNull }
}
