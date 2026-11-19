package dev.lyo.hortay.data.record

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.drinkless.tdlib.TdApi
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Called by [TdJson.encode] for every leaf that can carry personal data, so a recording
 * is anonymised while it is written and nothing raw ever reaches the disk.
 */
interface TdFieldFilter {
    fun string(owner: Class<*>, field: String, value: String): String = value
    fun long(owner: Class<*>, field: String, value: Long): Long = value
    fun bytes(owner: Class<*>, field: String, value: ByteArray): ByteArray = value

    object None : TdFieldFilter
}

/**
 * Reflective JSON form of the vendored TDLib objects: `{"@type": "<class>", <public fields>}`,
 * null fields omitted, `byte[]` as Base64. The Java binding ships no serialiser of its own
 * (objects cross JNI as native structures), and `toString()` is a native method, so this is
 * the only way to get a TDLib object onto disk — or to print one under a JVM unit test.
 *
 * Debug tooling only: it relies on unobfuscated field names, which release builds don't keep.
 */
object TdJson {
    const val TYPE = "@type"

    private val fieldCache = ConcurrentHashMap<Class<*>, List<Field>>()

    fun encode(obj: TdApi.Object, filter: TdFieldFilter = TdFieldFilter.None): JsonObject {
        val owner = obj.javaClass
        val out = LinkedHashMap<String, JsonElement>()
        out[TYPE] = JsonPrimitive(owner.simpleName)
        for (field in fieldsOf(owner)) {
            val value = field.get(obj) ?: continue
            out[field.name] = element(owner, field.name, value, filter)
        }
        return JsonObject(out)
    }

    private fun fieldsOf(cls: Class<*>): List<Field> =
        fieldCache.getOrPut(cls) { cls.fields.filter { !Modifier.isStatic(it.modifiers) } }

    private fun element(owner: Class<*>, field: String, value: Any, filter: TdFieldFilter): JsonElement =
        when (value) {
            is Boolean -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(filter.long(owner, field, value))
            is Double -> JsonPrimitive(if (value.isFinite()) value else 0.0)
            is String -> JsonPrimitive(filter.string(owner, field, value))
            is TdApi.Object -> encode(value, filter)
            is ByteArray -> JsonPrimitive(Base64.getEncoder().encodeToString(filter.bytes(owner, field, value)))
            is IntArray -> JsonArray(value.map { JsonPrimitive(it) })
            is LongArray -> JsonArray(value.map { JsonPrimitive(filter.long(owner, field, it)) })
            is Array<*> -> JsonArray(value.map { if (it == null) JsonNull else element(owner, field, it, filter) })
            else -> error("TdJson: unsupported field type ${value.javaClass.name} at ${owner.simpleName}.$field")
        }
}
