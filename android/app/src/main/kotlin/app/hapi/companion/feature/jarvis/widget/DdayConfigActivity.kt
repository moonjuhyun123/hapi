package app.hapi.companion.feature.jarvis.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.hapi.companion.R
import app.hapi.companion.feature.jarvis.EntranceHttp
import app.hapi.companion.ui.theme.HapiTheme
import kotlinx.coroutines.CancellationException

/*
 * D-day 위젯 고르기 (Jarvis fork, docs/jarvis/CHANGES.md step 25 — 10-10 주현님
 * 「어떤 걸 D-day 로 볼지 설정을 할 수가 없어」). Opens when the widget is
 * placed and again from the launcher's widget settings (reconfigurable).
 * Lists the coming year's events with the server's D-n; a tap pins that event
 * to this one widget. 「기본」 = the nearest event marked D-day in the calendar.
 */
class DdayConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out leaves the widget unplaced (launcher contract) unless a choice is made.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContent {
            HapiTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var events by remember { mutableStateOf(cached(this)?.events.orEmpty()) }
                    var failed by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        try {
                            val fresh = EntranceHttp(this@DdayConfigActivity).get("/calendar/widget", WidgetData.serializer())
                            saveCache(this@DdayConfigActivity, fresh)
                            events = fresh.events
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            failed = events.isEmpty()
                        }
                    }
                    LazyColumn(modifier = Modifier.safeDrawingPadding()) {
                        item {
                            Text(
                                stringResource(R.string.jarvis_widget_pick_title),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(20.dp),
                            )
                        }
                        item {
                            PickRow(label = "", day = "", title = stringResource(R.string.jarvis_widget_pick_default)) { done(widgetId, null) }
                        }
                        if (failed) {
                            item {
                                Text(
                                    stringResource(R.string.jarvis_widget_pick_failed),
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(20.dp),
                                )
                            }
                        }
                        items(events, key = { it.id }) { e ->
                            PickRow(label = e.label, day = e.day, title = e.title) { done(widgetId, e.id) }
                        }
                    }
                }
            }
        }
    }

    private fun done(widgetId: Int, eventId: String?) {
        savePick(this, widgetId, eventId)
        JarvisWidgets.drawAll(this)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}

@androidx.compose.runtime.Composable
private fun PickRow(label: String, day: String, title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        if (label.isNotEmpty()) {
            Text(label, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(64.dp))
        }
        if (day.isNotEmpty()) {
            Text(day, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
        }
        Text(title)
    }
    HorizontalDivider()
}
