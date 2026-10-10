package app.hapi.companion.feature.jarvis.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hapi.companion.R
import app.hapi.companion.ui.theme.hapi
import java.time.LocalDate
import java.time.YearMonth

/** Step 18: month grid on top, the selected day's events and dated to-dos below, + to add. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarScreen(viewModel: CalendarViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) { if (state.month == null) viewModel.load() }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) } },
                title = { Text("${state.ym.year}년 ${state.ym.monthValue}월") },
                actions = {
                    IconButton(onClick = { viewModel.shiftMonth(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null) }
                    IconButton(onClick = { viewModel.shiftMonth(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::newEvent) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.jarvis_calendar_add)) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val month = state.month
            MonthGrid(state.ym, state.selected, month, viewModel::select)
            HorizontalDivider()
            when {
                month == null && state.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                month == null -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::load) { Text(stringResource(R.string.jarvis_workout_retry)) }
                }
                else -> DayList(state.selected, month, viewModel::edit)
            }
        }
    }
    state.draft?.let { EventEditor(it, state.saving, state.saveError, viewModel) }
}

private val WEEK = listOf("일", "월", "화", "수", "목", "금", "토")

@Composable
private fun MonthGrid(ym: YearMonth, selected: LocalDate, month: CalMonth?, onSelect: (LocalDate) -> Unit) {
    val today = month?.today?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now(SEOUL)
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            WEEK.forEachIndexed { i, d ->
                Text(
                    d, fontSize = 11.sp, modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                    color = weekdayColor(i, MaterialTheme.hapi.hint), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        monthCells(ym).chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEachIndexed { i, day ->
                    DayCell(
                        day, inMonth = YearMonth.from(day) == ym, isToday = day == today, isSelected = day == selected,
                        events = month?.let { eventsOn(day, it.events) }.orEmpty(),
                        hasTask = month?.let { tasksOn(day, it.tasks).any { t -> !t.done } } == true,
                        recordTypes = month?.let { recordsOn(day, it.records).map { r -> r.type }.distinct() }.orEmpty(),
                        weekday = i, modifier = Modifier.weight(1f), onClick = { onSelect(day) },
                    )
                }
            }
        }
    }
}

@Composable
private fun weekdayColor(i: Int, base: Color): Color = when (i) {
    0 -> MaterialTheme.colorScheme.error
    6 -> MaterialTheme.colorScheme.primary
    else -> base
}

@Composable
private fun DayCell(
    day: LocalDate, inMonth: Boolean, isToday: Boolean, isSelected: Boolean, events: List<CalEvent>, hasTask: Boolean,
    recordTypes: List<String>, weekday: Int, modifier: Modifier, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .aspectRatio(0.8f)
            .padding(1.dp)
            .clip(shape)
            .then(if (isSelected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val numberColor = if (!inMonth) MaterialTheme.hapi.hint.copy(alpha = 0.5f) else weekdayColor(weekday, MaterialTheme.colorScheme.onSurface)
        Box(
            Modifier.size(22.dp).clip(CircleShape).then(if (isToday) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${day.dayOfMonth}", fontSize = 12.sp,
                color = if (isToday) MaterialTheme.colorScheme.onPrimary else numberColor,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            )
        }
        events.take(2).forEach { e ->
            Text(
                e.title, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Clip,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.fillMaxWidth().padding(top = 1.dp).clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (inMonth) 1f else 0.4f)).padding(horizontal = 2.dp),
            )
        }
        if (events.size > 2) Text("+${events.size - 2}", fontSize = 9.sp, color = MaterialTheme.hapi.hint)
        if (hasTask || recordTypes.isNotEmpty()) {
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (hasTask) Box(Modifier.size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
                recordTypes.forEach { t -> Box(Modifier.size(5.dp).clip(CircleShape).background(recordColor(t))) }
            }
        }
    }
}

/** Same hue per record type as the dots in the grid. */
private fun recordColor(type: String): Color = when (type) {
    "workout" -> Color(0xFF2E9E5B)
    "dev" -> Color(0xFF3B82F6)
    "review" -> Color(0xFF9B5DE5)
    "company" -> Color(0xFFE08A1E)
    else -> Color(0xFF8A8F98)
}

@Composable
private fun DayList(day: LocalDate, month: CalMonth, onEdit: (CalEvent) -> Unit) {
    val events = eventsOn(day, month.events)
    val tasks = tasksOn(day, month.tasks)
    val records = recordsOn(day, month.records)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)) {
        item(key = "head") {
            Text(
                "${day.monthValue}월 ${day.dayOfMonth}일 (${WEEK[day.dayOfWeek.value % 7]})",
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
            )
        }
        if (events.isEmpty() && tasks.isEmpty() && records.isEmpty()) {
            item(key = "empty") {
                Text(stringResource(R.string.jarvis_calendar_empty), color = MaterialTheme.hapi.hint, modifier = Modifier.padding(16.dp, 8.dp))
            }
        }
        items(events, key = { "e:" + it.id }) { e ->
            Row(
                Modifier.fillMaxWidth().clickable { onEdit(e) }.padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    e.time?.let { t -> t + (e.endTime?.let { "~$it" } ?: "") } ?: stringResource(R.string.jarvis_calendar_all_day),
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(84.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(e.title, style = MaterialTheme.typography.bodyLarge)
                    val span = e.endDate?.let { "${e.date.substring(5)} ~ ${it.substring(5)}" }
                    listOfNotNull(span, e.note).takeIf { it.isNotEmpty() }?.let {
                        Text(it.joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.hapi.hint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        items(tasks, key = { "t:" + it.date + it.title }) { t ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (t.done) "☑" else "☐", fontSize = 14.sp, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.width(84.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        t.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textDecoration = if (t.done) TextDecoration.LineThrough else null,
                    )
                    Text(t.kind, fontSize = 11.sp, color = MaterialTheme.hapi.hint)
                }
            }
        }
        if (records.isNotEmpty()) {
            item(key = "done-head") {
                Text(stringResource(R.string.jarvis_calendar_done), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.hapi.hint, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 2.dp))
            }
            items(records.size, key = { "r:$it" }) { i -> RecordRow(records[i]) }
        }
    }
}

@Composable
private fun RecordRow(r: CalRecord) {
    var open by androidx.compose.runtime.saveable.rememberSaveable(r.date, r.type, r.summary) { androidx.compose.runtime.mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = r.detail.isNotEmpty()) { open = !open }.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.width(84.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(recordColor(r.type)))
            Text(recordLabel(r.type), fontSize = 13.sp, color = recordColor(r.type))
        }
        Column(Modifier.weight(1f)) {
            Text(r.summary, style = MaterialTheme.typography.bodyMedium, maxLines = if (open) 6 else 2, overflow = TextOverflow.Ellipsis)
            if (open && r.detail.isNotEmpty()) Text(r.detail, fontSize = 12.sp, color = MaterialTheme.hapi.hint)
        }
    }
}

@Composable
private fun EventEditor(draft: EventDraft, saving: Boolean, error: String?, viewModel: CalendarViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::closeEditor,
        title = { Text(stringResource(if (draft.id == null) R.string.jarvis_calendar_new else R.string.jarvis_calendar_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft.title, { viewModel.change(draft.copy(title = it)) }, label = { Text(stringResource(R.string.jarvis_calendar_title_label)) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(draft.date, { viewModel.change(draft.copy(date = it)) }, label = { Text(stringResource(R.string.jarvis_calendar_date)) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(draft.endDate, { viewModel.change(draft.copy(endDate = it)) }, label = { Text(stringResource(R.string.jarvis_calendar_end_date)) }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.jarvis_calendar_all_day), modifier = Modifier.weight(1f))
                    Switch(checked = draft.allDay, onCheckedChange = { viewModel.change(draft.copy(allDay = it)) })
                }
                if (!draft.allDay) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(draft.time, { viewModel.change(draft.copy(time = it)) }, label = { Text(stringResource(R.string.jarvis_calendar_start)) }, placeholder = { Text("14:00") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(draft.endTime, { viewModel.change(draft.copy(endTime = it)) }, label = { Text(stringResource(R.string.jarvis_calendar_end)) }, placeholder = { Text("15:00") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                }
                OutlinedTextField(draft.note, { viewModel.change(draft.copy(note = it)) }, label = { Text(stringResource(R.string.jarvis_workout_note)) })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::saveDraft, enabled = !saving) {
                if (saving) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.jarvis_workout_save))
            }
        },
        dismissButton = {
            Row {
                if (draft.id != null) {
                    TextButton(onClick = viewModel::deleteDraft, enabled = !saving) {
                        Text(stringResource(R.string.jarvis_calendar_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = viewModel::closeEditor) { Text(stringResource(R.string.jarvis_calendar_cancel)) }
            }
        },
    )
}

@Composable
internal fun CalendarTab(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val holder = androidx.lifecycle.viewmodel.compose.viewModel<CalendarViewModelHolder>(
        key = "jarvis-calendar",
        factory = app.hapi.companion.di.viewModelFactory { CalendarViewModelHolder(HttpCalendarGateway(context)) },
    )
    CalendarScreen(holder.viewModel, onBack)
}
