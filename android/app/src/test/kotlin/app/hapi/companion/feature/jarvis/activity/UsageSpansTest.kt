package app.hapi.companion.feature.jarvis.activity

import app.hapi.companion.feature.jarvis.activity.UsageEventTypes.PAUSED
import app.hapi.companion.feature.jarvis.activity.UsageEventTypes.RESUMED
import app.hapi.companion.feature.jarvis.activity.UsageEventTypes.SCREEN_OFF
import app.hapi.companion.feature.jarvis.activity.UsageEventTypes.SCREEN_ON
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageSpansTest {

    private fun ev(t: Long, type: Int, pkg: String = "") = UsageEvent(t, type, pkg)

    @Test
    fun `foreground and background make one span`() {
        val (apps, _) = buildSpans(listOf(ev(10_000, RESUMED, "a"), ev(70_000, PAUSED, "a")), 0, 100_000)
        assertEquals(listOf(AppSpan(10_000, 70_000, "a")), apps)
    }

    @Test
    fun `switching apps closes the previous one`() {
        val (apps, _) = buildSpans(listOf(ev(10_000, RESUMED, "a"), ev(40_000, RESUMED, "b"), ev(90_000, PAUSED, "b")), 0, 100_000)
        assertEquals(listOf(AppSpan(10_000, 40_000, "a"), AppSpan(40_000, 90_000, "b")), apps)
    }

    @Test
    fun `activity switches inside one app are glued`() {
        val events = listOf(ev(10_000, RESUMED, "a"), ev(30_000, PAUSED, "a"), ev(30_500, RESUMED, "a"), ev(60_000, PAUSED, "a"))
        val (apps, _) = buildSpans(events, 0, 100_000)
        assertEquals(listOf(AppSpan(10_000, 60_000, "a")), apps)
    }

    @Test
    fun `screen off ends the app and the screen span`() {
        val events = listOf(ev(5_000, SCREEN_ON), ev(10_000, RESUMED, "a"), ev(50_000, SCREEN_OFF))
        val (apps, screens) = buildSpans(events, 0, 100_000)
        assertEquals(listOf(AppSpan(10_000, 50_000, "a")), apps)
        assertEquals(listOf(ScreenSpan(5_000, 50_000)), screens)
    }

    @Test
    fun `look-back event is clipped to the window start`() {
        // App came to front before the window (seen via the reader's look-back).
        val (apps, screens) = buildSpans(listOf(ev(1_000, SCREEN_ON), ev(2_000, RESUMED, "a"), ev(30_000, PAUSED, "a")), 10_000, 100_000)
        assertEquals(listOf(AppSpan(10_000, 30_000, "a")), apps)
        assertEquals(listOf(ScreenSpan(10_000, 100_000)), screens)
    }

    @Test
    fun `still open at the window end is cut there`() {
        val (apps, _) = buildSpans(listOf(ev(10_000, RESUMED, "a")), 0, 100_000)
        assertEquals(listOf(AppSpan(10_000, 100_000, "a")), apps)
    }

    @Test
    fun `events after the window end are ignored`() {
        val (apps, _) = buildSpans(listOf(ev(10_000, RESUMED, "a"), ev(150_000, PAUSED, "a")), 0, 100_000)
        assertEquals(listOf(AppSpan(10_000, 100_000, "a")), apps)
    }

    @Test
    fun `flicker shorter than a second is dropped`() {
        val (apps, _) = buildSpans(listOf(ev(10_000, RESUMED, "launcher"), ev(10_400, RESUMED, "a"), ev(20_000, PAUSED, "a")), 0, 100_000)
        assertEquals(listOf(AppSpan(10_400, 20_000, "a")), apps)
    }

    @Test
    fun `crossing midnight is just one span`() {
        val midnight = 1_000_000_000L
        val (apps, _) = buildSpans(listOf(ev(midnight - 60_000, RESUMED, "a"), ev(midnight + 60_000, PAUSED, "a")), midnight - 3_600_000, midnight + 3_600_000)
        assertEquals(listOf(AppSpan(midnight - 60_000, midnight + 60_000, "a")), apps)
    }

    @Test
    fun `out-of-order input is sorted first`() {
        val (apps, _) = buildSpans(listOf(ev(70_000, PAUSED, "a"), ev(10_000, RESUMED, "a")), 0, 100_000)
        assertEquals(listOf(AppSpan(10_000, 70_000, "a")), apps)
    }

    @Test
    fun `batch body has the agreed shape`() {
        val body = batchJson("dev", 0, 100_000, listOf(AppSpan(1_000, 5_000, "a")), listOf(ScreenSpan(0, 9_000))) { "Label-$it" }
        assertEquals("dev", body["device"]!!.jsonPrimitive.content)
        assertEquals(100_000L, body["to"]!!.jsonPrimitive.long)
        val app = body["apps"]!!.jsonArray.single().jsonObject
        assertEquals("a", app["package"]!!.jsonPrimitive.content)
        assertEquals("Label-a", app["label"]!!.jsonPrimitive.content)
        assertEquals(9_000L, body["screen"]!!.jsonArray.single().jsonObject["end"]!!.jsonPrimitive.long)
    }
}
