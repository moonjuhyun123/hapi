package app.hapi.companion.feature.jarvis.watch

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.hapi.companion.R
import app.hapi.companion.fcm.PushNotifications
import app.hapi.companion.feature.jarvis.EntranceHttp
import app.hapi.companion.feature.jarvis.push.ButlerChannel
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

/*
 * 비상 확인 (Jarvis fork, docs/jarvis/CHANGES.md step 20 — 정본 9장 [새-66]).
 * The new house has no Telegram, so the last way to hear 「집사가 지금 못 답한다」
 * is the phone itself: every 15 minutes the app asks our entrance `/status`.
 * No answer twice in a row, or the server names a problem → one local
 * notification (same tag, so it is replaced, never stacked). Back to normal →
 * that notification turns into 「다시 닿았어요」. The app judges nothing: the
 * problems are the server's words, the app only counts missed answers.
 * The check needs a network, so a phone that is offline never raises it.
 */

@Serializable
data class ServerStatus(val ok: Boolean = false, val problems: List<String> = emptyList())

data class WatchState(val fails: Int = 0, val alerted: String? = null)

/** What to do after one check. [status] null = no answer. [alert] null = stay quiet. */
data class WatchStep(val next: WatchState, val alert: Alert?)

sealed interface Alert {
    data object Down : Alert
    data class Problems(val lines: List<String>) : Alert
    data object Back : Alert
}

const val DOWN_KEY = "down"
const val MISSES_BEFORE_ALERT = 2

fun watchStep(prev: WatchState, status: ServerStatus?): WatchStep {
    if (status == null) {
        val fails = prev.fails + 1
        return if (fails >= MISSES_BEFORE_ALERT && prev.alerted != DOWN_KEY) {
            WatchStep(WatchState(fails, DOWN_KEY), Alert.Down)
        } else {
            WatchStep(prev.copy(fails = fails), null)
        }
    }
    if (status.problems.isNotEmpty()) {
        val key = status.problems.joinToString("\n")
        return if (key != prev.alerted) WatchStep(WatchState(0, key), Alert.Problems(status.problems))
        else WatchStep(WatchState(0, key), null)
    }
    return if (prev.alerted != null) WatchStep(WatchState(), Alert.Back) else WatchStep(WatchState(), null)
}

private val Context.jarvisWatchStore: DataStore<Preferences> by preferencesDataStore(name = "jarvis_watch")

class ServerWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val http = EntranceHttp(ctx)
        if (http.entrance() == null) return Result.success()
        val store = ctx.jarvisWatchStore
        val p = store.data.first()
        val prev = WatchState(p[FAILS] ?: 0, p[ALERTED])
        val status = try {
            http.get("/status", ServerStatus.serializer())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val step = watchStep(prev, status)
        store.edit {
            it[FAILS] = step.next.fails
            if (step.next.alerted == null) it.remove(ALERTED) else it[ALERTED] = step.next.alerted
            it[CHECKED_AT] = System.currentTimeMillis()
        }
        step.alert?.let { show(ctx, it) }
        return Result.success()
    }

    private fun show(ctx: Context, alert: Alert) {
        val text = when (alert) {
            Alert.Down -> ctx.getString(R.string.jarvis_watch_down)
            is Alert.Problems -> alert.lines.joinToString("\n")
            Alert.Back -> ctx.getString(R.string.jarvis_watch_back)
        }
        PushNotifications.showActionResult(
            context = ctx,
            tag = TAG,
            sessionId = null,
            channelId = ButlerChannel.ID,
            title = ctx.getString(R.string.jarvis_butler_title),
            text = text,
            autoExpire = alert == Alert.Back,
        )
    }

    companion object {
        private const val PERIODIC = "jarvis-server-watch"
        private const val TAG = "jarvis-server-watch"
        private val FAILS = intPreferencesKey("fails")
        private val ALERTED = stringPreferencesKey("alerted")
        private val CHECKED_AT = longPreferencesKey("checked_at")

        /** App opened: keep the 15-minute check scheduled (the WorkManager minimum). */
        fun onAppOpened(context: Context) {
            val periodic = PeriodicWorkRequestBuilder<ServerWatchWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
        }
    }
}
