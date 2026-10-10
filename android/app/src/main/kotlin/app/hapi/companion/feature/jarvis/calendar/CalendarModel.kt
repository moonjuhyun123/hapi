package app.hapi.companion.feature.jarvis.calendar

import java.time.LocalDate
import java.time.YearMonth
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * 「캘린더」 (Jarvis fork, docs/jarvis/CHANGES.md step 18). Our app calendar is
 * the main calendar (정본 9장 — 주현님 10-09 「구글 캘린더 없어 이제는 — 우리 서버
 * 저장」). Events live on our server (append-only, ~/brain/raw/calendar);
 * dated to-dos (Waiting wake dates) are read from where they are, never copied.
 */

@Serializable
data class CalEvent(
    val id: String,
    val title: String,
    /** YYYY-MM-DD; [endDate] (inclusive) for multi-day. */
    val date: String,
    @SerialName("end_date") val endDate: String? = null,
    /** HH:MM; null = all day. */
    val time: String? = null,
    @SerialName("end_time") val endTime: String? = null,
    val note: String? = null,
)

@Serializable
data class CalTask(val date: String, val title: String, val kind: String = "", val done: Boolean = false)

/** What was done that day — the dashboard calendar's records (workout · dev · review · executor · session). */
@Serializable
data class CalRecord(val date: String, val type: String, val summary: String, val status: String = "", val detail: String = "")

@Serializable
data class CalMonth(
    val ym: String,
    val today: String = "",
    val events: List<CalEvent> = emptyList(),
    val tasks: List<CalTask> = emptyList(),
    val records: List<CalRecord> = emptyList(),
)

/** Korean label for a record type; order = how the day list groups them. */
val RECORD_TYPES = listOf("workout" to "운동", "dev" to "개발", "review" to "리뷰", "company" to "실행기", "session" to "대화")

fun recordLabel(type: String): String = RECORD_TYPES.firstOrNull { it.first == type }?.second ?: type

fun recordsOn(day: LocalDate, records: List<CalRecord>): List<CalRecord> {
    val order = RECORD_TYPES.map { it.first }
    return records.filter { it.date == day.toString() }.sortedBy { order.indexOf(it.type).let { i -> if (i < 0) 99 else i } }
}

/** Six weeks starting on Sunday covering [ym]; days outside the month are kept (drawn faint). */
fun monthCells(ym: YearMonth): List<LocalDate> {
    val first = ym.atDay(1)
    val back = first.dayOfWeek.value % 7 // Sunday = 0
    val start = first.minusDays(back.toLong())
    return (0 until 42).map { start.plusDays(it.toLong()) }
}

fun CalEvent.covers(day: LocalDate): Boolean {
    val d = day.toString()
    return date <= d && (endDate ?: date) >= d
}

/** All-day first, then by start time, then title. */
fun eventsOn(day: LocalDate, events: List<CalEvent>): List<CalEvent> =
    events.filter { it.covers(day) }.sortedWith(compareBy({ it.time != null }, { it.time.orEmpty() }, { it.title }))

fun tasksOn(day: LocalDate, tasks: List<CalTask>): List<CalTask> = tasks.filter { it.date == day.toString() }

private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

/** What the editor holds while typing. */
data class EventDraft(
    val id: String? = null,
    val title: String = "",
    val date: String = "",
    val endDate: String = "",
    val allDay: Boolean = true,
    val time: String = "",
    val endTime: String = "",
    val note: String = "",
) {
    /** First problem, or null when it can be saved. Same rules as the server (cal.py validate). */
    fun problem(): String? = when {
        title.isBlank() -> "제목을 적어 주세요"
        !DATE.matches(date) || runCatching { LocalDate.parse(date) }.isFailure -> "날짜는 2026-10-12 꼴로"
        endDate.isNotBlank() && (!DATE.matches(endDate) || endDate < date) -> "끝 날짜가 이상해요"
        !allDay && !TIME.matches(time) -> "시간은 14:00 꼴로"
        !allDay && endTime.isNotBlank() && !TIME.matches(endTime) -> "끝 시간은 15:00 꼴로"
        else -> null
    }

    fun body(): JsonObject = buildJsonObject {
        id?.let { put("id", it) }
        put("title", title.trim())
        put("date", date)
        if (endDate.isNotBlank() && endDate != date) put("end_date", endDate)
        if (!allDay) {
            put("time", time)
            if (endTime.isNotBlank()) put("end_time", endTime)
        }
        if (note.isNotBlank()) put("note", note.trim())
    }
}

fun CalEvent.draft() = EventDraft(id, title, date, endDate.orEmpty(), time == null, time.orEmpty(), endTime.orEmpty(), note.orEmpty())

fun newDraft(day: LocalDate) = EventDraft(date = day.toString())

