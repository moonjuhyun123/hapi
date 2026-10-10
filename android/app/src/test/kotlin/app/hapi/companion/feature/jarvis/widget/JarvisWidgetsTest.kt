package app.hapi.companion.feature.jarvis.widget

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JarvisWidgetsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun rowShowsTimeOnlyWhenThere() {
        assertEquals("10/16 금 08:00 보컬", todoRowText(WidgetItem("일정", "2026-10-16", "10/16 금", "08:00", "보컬", "D-6")))
        assertEquals("10/23 금 창경", todoRowText(WidgetItem("일정", "2026-10-23", "10/23 금", null, "창경", "D-13")))
    }

    @Test fun readsServerShape() {
        val text = """{"today":"2026-10-10","dday":{"id":"e1","title":"Adsp 시험","date":"2026-10-31","label":"D-21"},
            "items":[{"kind":"할 일","date":"2026-10-15","time":null,"title":"심사 결과","label":"D-5"}]}"""
        val d = json.decodeFromString(WidgetData.serializer(), text)
        assertEquals("D-21", d.dday?.label)
        assertEquals("D-5", d.items.single().label)
    }

    @Test fun noDdayIsNull() {
        assertNull(json.decodeFromString(WidgetData.serializer(), """{"today":"2026-10-10","dday":null,"items":[]}""").dday)
    }
}
