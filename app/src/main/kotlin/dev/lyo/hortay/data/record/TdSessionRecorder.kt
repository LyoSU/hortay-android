package dev.lyo.hortay.data.record

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.lyo.hortay.data.warnUnlessCancelled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.BufferedWriter
import java.io.File

/**
 * Debug-build recorder of a real TDLib session, for turning field behaviour into unit-test
 * fixtures. Off unless asked for, per device boot:
 *
 * ```
 * adb shell setprop log.tag.HortayTdRecord DEBUG
 * adb shell am force-stop dev.lyo.hortay.debug    # recording starts with the next launch
 * adb pull /sdcard/Android/data/dev.lyo.hortay.debug/files/td-sessions/
 * ```
 *
 * The file is anonymised as it is written ([TdRedactor]) and stays on the device until
 * pulled — nothing is uploaded anywhere. [TdSessionLog] lists what is never recorded.
 *
 * The TDLib callbacks arrive on its JNI thread and must not block, so events only enter an
 * unbounded queue there; one coroutine serialises and writes them. Recording stops for the
 * rest of the process once the file reaches [MAX_CHARS].
 */
class TdSessionRecorder private constructor(scope: CoroutineScope, open: () -> BufferedWriter) : TdTap {
    private class Event(val kind: String, val id: Long, val obj: TdApi.Object, val atMs: Long)

    private val queue = Channel<Event>(Channel.UNLIMITED)
    private val startedAtMs = SystemClock.elapsedRealtime()

    init {
        scope.launch(Dispatchers.IO.limitedParallelism(1)) {
            runCatching {
                open().use { writer ->
                    val log = TdSessionLog(writer, TdRedactor(), SystemClock::elapsedRealtime, startedAtMs)
                    for (first in queue) {
                        var event: Event? = first
                        while (event != null) {
                            log.event(event.kind, event.id, event.obj, event.atMs)
                            event = queue.tryReceive().getOrNull()
                        }
                        writer.flush()
                        if (log.written > MAX_CHARS) break
                    }
                }
            }.warnUnlessCancelled(TAG, "session recording stopped")
            queue.close()
        }
    }

    override fun onRequest(id: Long, query: TdApi.Function<*>) = enqueue(TdSessionLog.REQUEST, id, query)

    override fun onResponse(id: Long, result: TdApi.Object) = enqueue(TdSessionLog.RESPONSE, id, result)

    override fun onUpdate(update: TdApi.Update) = enqueue(TdSessionLog.UPDATE, 0L, update)

    private fun enqueue(kind: String, id: Long, obj: TdApi.Object) {
        queue.trySend(Event(kind, id, obj, SystemClock.elapsedRealtime()))
    }

    companion object {
        private const val TAG = "HortayTdRecord"
        private const val MAX_CHARS = 32L * 1024 * 1024

        /** A recorder when the `log.tag.HortayTdRecord` switch is on, otherwise null. */
        fun startIfRequested(context: Context, scope: CoroutineScope): TdTap? {
            if (!Log.isLoggable(TAG, Log.DEBUG)) return null
            val app = context.applicationContext
            return TdSessionRecorder(scope) {
                val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "td-sessions").apply { mkdirs() }
                val file = File(dir, "session-${System.currentTimeMillis()}.jsonl")
                Log.i(TAG, "recording the TDLib session to $file")
                file.bufferedWriter()
            }
        }
    }
}
