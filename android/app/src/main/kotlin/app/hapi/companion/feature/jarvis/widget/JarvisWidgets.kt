package app.hapi.companion.feature.jarvis.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.hapi.companion.MainActivity
import app.hapi.companion.R
import app.hapi.companion.feature.jarvis.EntranceHttp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * 바탕화면 위젯 둘 (Jarvis fork, docs/jarvis/CHANGES.md step 23 — 10-10 주현님
 * 「D-day 2×1 · 할 일 5×2」). The app computes nothing: our entrance
 * `/calendar/widget` (cal.widget) already picked the D-day event, the five
 * rows and every 「D-n」 label. This file only fetches, caches the last good
 * answer and draws it. Tapping either widget opens the calendar screen.
 * Refresh: every 30 minutes, when a widget is placed, and right after the
 * calendar screen saves.
 */

@Serializable
data class WidgetDday(val title: String, val date: String, val label: String)

@Serializable
data class WidgetItem(
    val kind: String,
    val date: String,
    /** 「10/16 금」 — written by the server (step 24). */
    val day: String? = null,
    val time: String? = null,
    val title: String,
    val label: String,
)

/** Every event of the coming year with its D-n — the D-day picker's list (step 25). */
@Serializable
data class WidgetEvent(val id: String, val title: String, val date: String, val day: String = "", val label: String)

@Serializable
data class WidgetData(
    val today: String = "",
    val dday: WidgetDday? = null,
    val items: List<WidgetItem> = emptyList(),
    val events: List<WidgetEvent> = emptyList(),
)

/** What one D-day widget shows: the event picked for it, else the nearest marked one. */
fun ddayFor(data: WidgetData?, pickedId: String?): WidgetDday? =
    pickedId?.let { id -> data?.events?.firstOrNull { it.id == id } }?.let { WidgetDday(it.title, it.date, it.label) }
        ?: data?.dday

/** One row of the to-do widget: 「D-6」 · pill 「10/16 금」 · 「08:00 보컬」. */
fun todoRowText(item: WidgetItem): String = listOfNotNull(item.time, item.title).joinToString(" ")

const val TODO_ROWS = 5

private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
private const val PREFS = "jarvis_widget"
private const val KEY_DATA = "data"
const val ACTION_OPEN_CALENDAR = "app.hapi.companion.jarvis.OPEN_CALENDAR"

internal fun cached(context: Context): WidgetData? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DATA, null)
        ?.let { runCatching { json.decodeFromString(WidgetData.serializer(), it) }.getOrNull() }

private fun openCalendar(context: Context): PendingIntent = PendingIntent.getActivity(
    context,
    0,
    Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_CALENDAR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)

/** Widget tap → MainActivity sets this → the butler screen opens the calendar once. */
object OpenCalendarRequest {
    val pending = kotlinx.coroutines.flow.MutableStateFlow(false)
}

internal fun pickedFor(context: Context, widgetId: Int): String? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("dday_$widgetId", null)

internal fun savePick(context: Context, widgetId: Int, eventId: String?) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
        if (eventId == null) remove("dday_$widgetId") else putString("dday_$widgetId", eventId)
    }
}

internal fun saveCache(context: Context, data: WidgetData) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
        putString(KEY_DATA, json.encodeToString(WidgetData.serializer(), data))
    }
}

object JarvisWidgets {
    private const val PERIODIC = "jarvis-widget"
    private const val NOW = "jarvis-widget-now"
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetRefreshWorker>(30, TimeUnit.MINUTES).setConstraints(network).build(),
        )
    }

    fun refreshNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WidgetRefreshWorker>().setConstraints(network).build(),
        )
    }

    /** Redraw every placed widget from the cache (no network). */
    fun drawAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val data = cached(context)
        manager.getAppWidgetIds(ComponentName(context, DdayWidgetProvider::class.java)).forEach {
            manager.updateAppWidget(it, ddayViews(context, data, pickedFor(context, it)))
        }
        manager.getAppWidgetIds(ComponentName(context, TodoWidgetProvider::class.java)).forEach {
            manager.updateAppWidget(it, todoViews(context, data))
        }
    }

    private fun ddayViews(context: Context, data: WidgetData?, pickedId: String?): RemoteViews =
        RemoteViews(context.packageName, R.layout.jarvis_widget_dday).apply {
            val d = ddayFor(data, pickedId)
            setTextViewText(R.id.jarvis_dday_title, d?.title ?: context.getString(if (data == null) R.string.jarvis_widget_no_server else R.string.jarvis_widget_no_dday))
            setTextViewText(R.id.jarvis_dday_label, d?.label ?: "")
            setOnClickPendingIntent(R.id.jarvis_widget_root, openCalendar(context))
        }

    private val ROW = intArrayOf(R.id.jarvis_todo_row0, R.id.jarvis_todo_row1, R.id.jarvis_todo_row2, R.id.jarvis_todo_row3, R.id.jarvis_todo_row4)
    private val LABEL = intArrayOf(R.id.jarvis_todo_label0, R.id.jarvis_todo_label1, R.id.jarvis_todo_label2, R.id.jarvis_todo_label3, R.id.jarvis_todo_label4)
    private val DAY = intArrayOf(R.id.jarvis_todo_day0, R.id.jarvis_todo_day1, R.id.jarvis_todo_day2, R.id.jarvis_todo_day3, R.id.jarvis_todo_day4)
    private val TEXT = intArrayOf(R.id.jarvis_todo_text0, R.id.jarvis_todo_text1, R.id.jarvis_todo_text2, R.id.jarvis_todo_text3, R.id.jarvis_todo_text4)

    private fun todoViews(context: Context, data: WidgetData?): RemoteViews =
        RemoteViews(context.packageName, R.layout.jarvis_widget_todo).apply {
            val items = data?.items.orEmpty().take(TODO_ROWS)
            val empty = items.isEmpty()
            setViewVisibility(R.id.jarvis_todo_empty, if (empty) View.VISIBLE else View.GONE)
            setTextViewText(R.id.jarvis_todo_empty, context.getString(if (data == null) R.string.jarvis_widget_no_server else R.string.jarvis_widget_empty))
            for (i in 0 until TODO_ROWS) {
                val item = items.getOrNull(i)
                setViewVisibility(ROW[i], if (item == null) View.GONE else View.VISIBLE)
                setTextViewText(LABEL[i], item?.label ?: "")
                setTextViewText(DAY[i], item?.day ?: "")
                setTextViewText(TEXT[i], item?.let(::todoRowText) ?: "")
            }
            setOnClickPendingIntent(R.id.jarvis_widget_root, openCalendar(context))
        }
}

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val http = EntranceHttp(ctx)
        if (http.entrance() != null) {
            try {
                val data = http.get("/calendar/widget", WidgetData.serializer())
                saveCache(ctx, data)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // keep the last good answer on screen
            }
        }
        JarvisWidgets.drawAll(ctx)
        return Result.success()
    }
}

open class JarvisWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        JarvisWidgets.drawAll(context)
        JarvisWidgets.schedule(context)
        JarvisWidgets.refreshNow(context)
    }
}

class DdayWidgetProvider : JarvisWidgetProvider() {
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { savePick(context, it, null) }
    }
}

class TodoWidgetProvider : JarvisWidgetProvider()
