package app.hapi.companion.feature.jarvis.activity

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** The send window rules — see [PhoneActivityWorker.doWork]. */
internal const val MIN_WINDOW_MS = 60 * 60 * 1000L // one ledger row per send: never finer than an hour
internal const val FIRST_WINDOW_MS = 24 * 60 * 60 * 1000L // first send after enabling looks back a day
internal const val LOOKBACK_MS = 60 * 60 * 1000L // catch an app already in front when the window opens

/** Body of `POST <url>/phone-activity` (requirements step 7). Pure for tests. */
internal fun batchJson(
    device: String,
    from: Long,
    to: Long,
    apps: List<AppSpan>,
    screens: List<ScreenSpan>,
    label: (String) -> String,
): JsonObject = buildJsonObject {
    put("device", device)
    put("from", from)
    put("to", to)
    putJsonArray("apps") {
        apps.forEach { span ->
            add(buildJsonObject {
                put("start", span.start)
                put("end", span.end)
                put("package", span.pkg)
                put("label", label(span.pkg))
            })
        }
    }
    putJsonArray("screen") {
        screens.forEach { span ->
            add(buildJsonObject {
                put("start", span.start)
                put("end", span.end)
            })
        }
    }
}

/**
 * Reads the usage events since the last accepted window and posts them to our
 * collection entrance — never to the HAPI hub. Runs on app open and every few
 * hours; off by default and silent when off. A failed send leaves `lastSentTo`
 * alone, so the next run resends the same window (the server dedupes on
 * `device` + `from`).
 */
class PhoneActivityWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = ActivityPrefs(ctx)
        val settings = prefs.current()
        if (!settings.enabled || settings.url.isBlank()) return Result.success()
        val now = System.currentTimeMillis()
        if (!UsageReader.hasPermission(ctx)) {
            prefs.markStatus(SendStatus.NO_PERMISSION, now)
            return Result.success()
        }
        val from = if (settings.lastSentTo > 0) settings.lastSentTo else now - FIRST_WINDOW_MS
        val force = inputData.getBoolean(KEY_FORCE, false)
        if (!force && now - from < MIN_WINDOW_MS) return Result.success()

        val (apps, screens) = buildSpans(UsageReader.read(ctx, from - LOOKBACK_MS, now), from, now)
        val body = batchJson(prefs.deviceId(), from, now, apps, screens) { UsageReader.label(ctx, it) }
        val code = try {
            post(settings.url, settings.token, body.toString())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            -1
        }
        if (code in 200..299) prefs.markSent(now, apps.size, now) else prefs.markStatus(SendStatus.FAILED, now, code.toLong())
        return Result.success()
    }

    private suspend fun post(url: String, token: String, json: String): Int = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$url/phone-activity")
            .header("Authorization", "Bearer $token")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { it.code }
    }

    companion object {
        const val KEY_FORCE = "force"
        private const val PERIODIC = "jarvis-phone-activity"
        private const val NOW = "jarvis-phone-activity-now"
        private val client by lazy {
            OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        }
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** App opened: keep the periodic run scheduled and try a send (skipped inside an hour). */
        fun onAppOpened(context: Context) {
            val periodic = PeriodicWorkRequestBuilder<PhoneActivityWorker>(3, TimeUnit.HOURS)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
            sendNow(context, force = false)
        }

        /** 「지금 보내기」 ignores the one-hour floor. */
        fun sendNow(context: Context, force: Boolean = true) {
            val work = OneTimeWorkRequestBuilder<PhoneActivityWorker>()
                .setInputData(workDataOf(KEY_FORCE to force))
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, work)
        }
    }
}
