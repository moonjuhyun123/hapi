package app.hapi.companion.feature.jarvis.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarModelTest {
    @Test fun `six weeks from the Sunday on or before the 1st`() {
        val cells = monthCells(YearMonth.of(2026, 10)) // 10/1 is a Thursday
        assertEquals(42, cells.size)
        assertEquals(LocalDate.of(2026, 9, 27), cells.first())
        assertEquals(DayOfWeek.SUNDAY, cells.first().dayOfWeek)
        assertTrue(LocalDate.of(2026, 10, 31) in cells)
    }

    @Test fun `multi-day events cover every day, all-day first`() {
        val trip = CalEvent("a", "휴가", "2026-10-30", endDate = "2026-11-02")
        val dentist = CalEvent("b", "치과", "2026-10-31", time = "14:00")
        val day = LocalDate.of(2026, 10, 31)
        assertEquals(listOf("휴가", "치과"), eventsOn(day, listOf(dentist, trip)).map { it.title })
        assertTrue(trip.covers(LocalDate.of(2026, 11, 2)))
        assertFalse(trip.covers(LocalDate.of(2026, 11, 3)))
    }

    @Test fun `draft rules match the server`() {
        val ok = newDraft(LocalDate.of(2026, 10, 12)).copy(title = "치과", allDay = false, time = "14:00", endTime = "15:00")
        assertNull(ok.problem())
        assertEquals("제목을 적어 주세요", ok.copy(title = " ").problem())
        assertEquals("시간은 14:00 꼴로", ok.copy(time = "2pm").problem())
        assertEquals("끝 날짜가 이상해요", ok.copy(endDate = "2026-10-01").problem())
        assertEquals("날짜는 2026-10-12 꼴로", ok.copy(date = "2026-02-30").problem())
    }

    @Test fun `body leaves out what is empty, all-day drops times`() {
        val body = newDraft(LocalDate.of(2026, 10, 12)).copy(title = "회의", time = "10:00").body()
        assertEquals(setOf("title", "date", "dday"), body.keys)
        val timed = CalEvent("x", "회의", "2026-10-12", time = "10:00", note = "3층").draft().body()
        assertEquals("x", timed["id"]!!.jsonPrimitive.content)
        assertEquals("10:00", timed["time"]!!.jsonPrimitive.content)
        assertEquals("3층", timed["note"]!!.jsonPrimitive.content)
    }
}
