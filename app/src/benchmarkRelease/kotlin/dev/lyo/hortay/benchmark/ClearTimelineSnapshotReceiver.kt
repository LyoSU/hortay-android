package dev.lyo.hortay.benchmark

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.lyo.hortay.data.DataStoreTimelineSnapshotStore
import kotlinx.coroutines.runBlocking

class ClearTimelineSnapshotReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) {
            resultCode = Activity.RESULT_CANCELED
            resultData = "ignored:${intent.action}"
            return
        }

        runCatching {
            runBlocking {
                DataStoreTimelineSnapshotStore(context).clear()
            }
        }.fold(
            onSuccess = {
                resultCode = Activity.RESULT_OK
                resultData = RESULT_CLEARED
            },
            onFailure = { error ->
                resultCode = Activity.RESULT_CANCELED
                resultData = error.message ?: error::class.java.name
            },
        )
    }

    private companion object {
        const val ACTION = "dev.lyo.hortay.benchmark.CLEAR_TIMELINE_SNAPSHOT"
        const val RESULT_CLEARED = "cleared"
    }
}
