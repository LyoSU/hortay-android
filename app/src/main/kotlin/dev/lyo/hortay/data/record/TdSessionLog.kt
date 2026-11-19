package dev.lyo.hortay.data.record

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.drinkless.tdlib.TdApi

/**
 * Observer of the raw TDLib traffic. [id] pairs a request with its response; a response
 * is a [TdApi.Error] when the request failed.
 */
interface TdTap {
    fun onRequest(id: Long, query: TdApi.Function<*>)
    fun onResponse(id: Long, result: TdApi.Object)
    fun onUpdate(update: TdApi.Update)
}

/**
 * Writes a session as JSON lines, one event per line, in arrival order:
 *
 * ```
 * {"k":"hdr","v":1}
 * {"t":12,"k":"req","id":1,"td":{"@type":"LoadChats",…}}
 * {"t":40,"k":"upd","td":{"@type":"UpdateNewChat",…}}
 * {"t":95,"k":"res","id":1,"td":{"@type":"Ok"}}
 * ```
 *
 * `t` is milliseconds since the recording started. The format is what the unit-test
 * replayer reads back, so a real session becomes a regression fixture for the repositories.
 *
 * Never recorded, whatever the [filter]:
 * - requests that carry credentials or device secrets ([SECRET_REQUESTS]) and their answers;
 * - `updateOption` — option values are untyped, so ids in them can't be anonymised;
 * - `updateFile` — download progress, by far the chattiest update and irrelevant to feed logic.
 *
 * Synchronous and not thread-safe; [TdSessionRecorder] puts a queue in front of it.
 */
class TdSessionLog(
    private val out: Appendable,
    private val filter: TdFieldFilter,
    private val nowMs: () -> Long,
    private val startMs: Long = nowMs(),
) : TdTap {
    private val openRequests = HashSet<Long>()

    /** Characters written so far — the recorder's size cap reads this. */
    var written: Long = 0L
        private set

    init {
        line(mapOf(KIND to JsonPrimitive(HEADER), "v" to JsonPrimitive(FORMAT_VERSION)))
    }

    override fun onRequest(id: Long, query: TdApi.Function<*>) = event(REQUEST, id, query, nowMs())

    override fun onResponse(id: Long, result: TdApi.Object) = event(RESPONSE, id, result, nowMs())

    override fun onUpdate(update: TdApi.Update) = event(UPDATE, 0L, update, nowMs())

    /** Same as the [TdTap] callbacks, with the arrival time captured by the caller. */
    fun event(kind: String, id: Long, obj: TdApi.Object, atMs: Long) {
        val keep = when (kind) {
            REQUEST -> !SECRET_REQUESTS.containsMatchIn(obj.javaClass.simpleName) && openRequests.add(id)
            RESPONSE -> openRequests.remove(id)
            else -> obj !is TdApi.UpdateOption && obj !is TdApi.UpdateFile
        }
        if (!keep) return
        val fields = LinkedHashMap<String, JsonElement>()
        fields[TIME] = JsonPrimitive(atMs - startMs)
        fields[KIND] = JsonPrimitive(kind)
        if (kind != UPDATE) fields[ID] = JsonPrimitive(id)
        fields[PAYLOAD] = TdJson.encode(obj, filter)
        line(fields)
    }

    private fun line(fields: Map<String, JsonElement>) {
        val text = JsonObject(fields).toString()
        out.append(text).append('\n')
        written += text.length + 1
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val TIME = "t"
        const val KIND = "k"
        const val ID = "id"
        const val PAYLOAD = "td"
        const val HEADER = "hdr"
        const val REQUEST = "req"
        const val RESPONSE = "res"
        const val UPDATE = "upd"

        private val SECRET_REQUESTS =
            Regex("SetTdlibParameters|Authentication|Password|Proxy|Option|EncryptionKey|RegisterDevice|Passport|Payment")
    }
}
