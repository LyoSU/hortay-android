package dev.lyo.hortay.data.web

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.lyo.hortay.HortayApp
import java.util.concurrent.TimeUnit

/**
 * WorkManager side of the tier-3 background check: one unique periodic request, every
 * [INTERVAL_MINUTES] minutes, on any connected network (unmetered with "Wi-Fi only") and
 * a battery that isn't low. [WebNotifyCoordinator] schedules and cancels it.
 */
class WebNotifyWork(context: Context) : WebNotifyScheduling {

    private val context = context.applicationContext

    override fun schedule(wifiOnly: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<WebNotifyWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        // UPDATE keeps the running period (no reset of the next run time on every launch)
        // and applies changed constraints in place.
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }

    companion object {
        const val UNIQUE_NAME = "web_new_posts_check"
        const val INTERVAL_MINUTES = 30L
    }
}

/**
 * One background check ([WebNotifyCheck]). Always [Result.success]: a network error or a
 * rate limit waits for the next period rather than WorkManager's retry backoff, which
 * would stack extra runs onto a t.me that is already refusing us.
 */
class WebNotifyWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as HortayApp).graph
        val outcome = runCatching { graph.webNotifyCheck.run() }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrElse {
                Log.w(TAG, "background check failed", it)
                return Result.success()
            }
        Log.i(TAG, "background check: $outcome")
        // Guest mode ended or every bell is off while the schedule lingered (process killed
        // before the coordinator saw it): stop waking the app.
        if (outcome == WebNotifyCheck.Outcome.Disabled) graph.webNotifyWork.cancel()
        return Result.success()
    }

    private companion object {
        const val TAG = "WebNotifyWorker"
    }
}
