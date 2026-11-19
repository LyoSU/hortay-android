package dev.lyo.hortay.sim

import dev.lyo.hortay.data.TdClient
import dev.lyo.hortay.data.TdSender
import dev.lyo.hortay.data.record.TdJson
import dev.lyo.hortay.data.record.TdSessionLog
import dev.lyo.hortay.data.record.TdTap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.drinkless.tdlib.TdApi
import java.util.Base64

/** Inverse of [TdJson.encode]. Fields the current TDLib binding no longer has are skipped. */
object TdJsonDecoder {
    fun decode(json: JsonObject): TdApi.Object {
        val type = json.getValue(TdJson.TYPE).jsonPrimitive.content
        val cls = Class.forName("org.drinkless.tdlib.TdApi\$$type")
        val obj = cls.getDeclaredConstructor().newInstance() as TdApi.Object
        for ((name, element) in json) {
            val field = if (name == TdJson.TYPE) null else runCatching { cls.getField(name) }.getOrNull()
            field?.set(obj, value(field.type, element))
        }
        return obj
    }

    private fun value(type: Class<*>, element: JsonElement): Any? = when {
        element is JsonNull -> null
        type == Boolean::class.javaPrimitiveType -> element.jsonPrimitive.boolean
        type == Int::class.javaPrimitiveType -> element.jsonPrimitive.int
        type == Long::class.javaPrimitiveType -> element.jsonPrimitive.long
        type == Double::class.javaPrimitiveType -> element.jsonPrimitive.double
        type == String::class.java -> element.jsonPrimitive.content
        type == ByteArray::class.java -> Base64.getDecoder().decode(element.jsonPrimitive.content)
        type.isArray -> {
            val items = element.jsonArray
            val component = checkNotNull(type.componentType)
            val array = java.lang.reflect.Array.newInstance(component, items.size)
            items.forEachIndexed { i, item -> java.lang.reflect.Array.set(array, i, value(component, item)) }
            array
        }
        else -> decode(element.jsonObject)
    }
}

/** Feeds everything that crosses [delegate] into [tap] — the test-side twin of the tap in `TdClient`. */
class RecordingSender(private val delegate: TdSender, private val tap: TdTap, scope: CoroutineScope) : TdSender {
    private var nextId = 0L
    override val updates: SharedFlow<TdApi.Update> get() = delegate.updates

    init {
        scope.launch { delegate.updates.collect { tap.onUpdate(it) } }
    }

    override suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): T {
        val id = ++nextId
        tap.onRequest(id, query)
        return try {
            delegate.send(query).also { tap.onResponse(id, it) }
        } catch (e: TdClient.TdException) {
            // TdException renders as "[code] message"; the wire form is the bare message.
            tap.onResponse(id, TdApi.Error(e.code, e.message.orEmpty().substringAfter("] ")))
            throw e
        }
    }
}

/**
 * Replays a [TdSessionLog] recording behind [TdSender], so the repositories can be run
 * against what a real TDLib actually said.
 *
 * - **Updates** are re-delivered by [play] at their recorded offsets, on the caller's
 *   (virtual) clock.
 * - **Requests** are answered from the recording: a request matches a recorded one of the
 *   same type with the same non-string fields (strings are ignored because a recording is
 *   anonymised, and the app also sends strings of its own). Several recorded answers to the
 *   same request are served in order, the last one repeating; each takes as long as it
 *   took when recorded.
 * - A request the recording never saw fails with 404 and is listed in [misses] — after a
 *   behaviour change that is the signal that the app now talks to TDLib differently, which
 *   a recording cannot answer for.
 *
 * Updates follow the recorded clock, not the replayed requests: if the code under test
 * asks later than the recorded session did, updates that were caused by that request can
 * arrive before it.
 */
class ReplayTelegram(recording: String) : TdSender {
    private class Answer(val payload: JsonObject, val latencyMs: Long)

    private val answers = HashMap<String, ArrayDeque<Answer>>()
    private val timeline = ArrayList<Pair<Long, JsonObject>>()
    private val _updates = MutableSharedFlow<TdApi.Update>(extraBufferCapacity = 1024)
    override val updates: SharedFlow<TdApi.Update> = _updates.asSharedFlow()

    val misses = ArrayList<String>()
    val updateCount: Int get() = timeline.size

    init {
        val open = HashMap<Long, Pair<Long, String>>()
        for (line in recording.lineSequence().filter { it.isNotBlank() }) {
            val event = Json.parseToJsonElement(line).jsonObject
            val kind = event.getValue(TdSessionLog.KIND).jsonPrimitive.content
            if (kind == TdSessionLog.HEADER) continue
            val at = event.getValue(TdSessionLog.TIME).jsonPrimitive.long
            val payload = event.getValue(TdSessionLog.PAYLOAD).jsonObject
            val id = event[TdSessionLog.ID]?.jsonPrimitive?.long ?: 0L
            when (kind) {
                TdSessionLog.REQUEST -> open[id] = at to keyOf(payload)
                TdSessionLog.RESPONSE -> open.remove(id)?.let { (sentAt, key) ->
                    answers.getOrPut(key) { ArrayDeque() }.addLast(Answer(payload, at - sentAt))
                }
                else -> timeline += at to payload
            }
        }
    }

    override suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): T {
        val key = keyOf(TdJson.encode(query))
        val queue = answers[key]
        if (queue == null) {
            misses += key
            throw TdClient.TdException(NOT_RECORDED, "not in the recording: ${query.javaClass.simpleName}")
        }
        val answer = if (queue.size > 1) queue.removeFirst() else queue.first()
        delay(answer.latencyMs)
        val result = TdJsonDecoder.decode(answer.payload)
        if (result is TdApi.Error) throw TdClient.TdException(result.code, result.message)
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** Delivers every recorded update at its recorded offset from now. */
    suspend fun play() {
        var now = 0L
        for ((at, payload) in timeline) {
            delay(at - now)
            now = at
            _updates.emit(TdJsonDecoder.decode(payload) as TdApi.Update)
        }
    }

    private fun keyOf(request: JsonObject): String = blankStrings(request).toString()

    private fun blankStrings(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.mapValues { (name, value) -> if (name == TdJson.TYPE) value else blankStrings(value) },
        )
        is JsonArray -> JsonArray(element.map(::blankStrings))
        is JsonPrimitive -> if (element.isString) JsonPrimitive("") else element
    }

    private companion object {
        const val NOT_RECORDED = 404
    }
}
