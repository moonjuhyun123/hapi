package app.hapi.companion.feature.jarvis.workout

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkoutModelTest {
    private val lat = WorkoutChip("렛풀다운", sets = listOf(SetRow("50", "10")), hint = "지난번 09-09")
    private val run = WorkoutChip("달리기", mode = "free", free = "20분")
    private val press = WorkoutChip("벤치프레스")
    private val chips = listOf(lat, run, press).associateBy { it.name }
    private val order = listOf("렛풀다운", "달리기", "벤치프레스")

    @Test fun `nothing ticked means nothing to save`() {
        assertNull(saveBody(order, chips, PLACE_GYM, ""))
    }

    @Test fun `ticked chips go in screen order, blank rows dropped, free text as typed`() {
        val edited = chips.toggled("달리기").toggled("렛풀다운").withAddedSet("렛풀다운")
            .withSet("렛풀다운", 1, SetRow("60", "6")).withFree("달리기", "3km 18분")
        val body = saveBody(order, edited, "집", " 등 ")!!
        assertEquals("집", body["place"]!!.jsonPrimitive.content)
        assertEquals("등", body["note"]!!.jsonPrimitive.content)
        val items = body["items"]!!.jsonArray
        assertEquals(listOf("렛풀다운", "달리기"), items.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals(listOf("50" to "10", "60" to "6"), items[0].jsonObject["sets"]!!.jsonArray.map {
            it.jsonObject["w"]!!.jsonPrimitive.content to it.jsonObject["r"]!!.jsonPrimitive.content
        })
        assertEquals("3km 18분", items[1].jsonObject["free"]!!.jsonPrimitive.content)
    }

    @Test fun `ticking a weights chip with no history gives one row to type into`() {
        assertEquals(listOf(SetRow()), chips.toggled("벤치프레스")["벤치프레스"]!!.sets)
    }

    @Test fun `adding a set copies the last row, removing drops one`() {
        val c = chips.withAddedSet("렛풀다운").withoutSet("렛풀다운", 0)["렛풀다운"]!!
        assertEquals(listOf(SetRow("50", "10")), c.sets)
    }

    @Test fun `the server form decodes`() {
        val form = Json { ignoreUnknownKeys = true }.decodeFromString(
            WorkoutForm.serializer(),
            """{"date_label":"10/9 (금)","date_iso":"2026-10-09","groups":[{"title":"등","chips":[{"name":"렛풀다운","mode":"sets","sets":[{"w":"40","r":"10"}],"free":"","checked":false,"hint":"지난번","images":["/workout/img/X/0.jpg","/workout/img/X/1.jpg"],"spark":null}]}],"extras":[],"recency":[{"title":"등","label":"09-09"}],"summary":[]}""",
        )
        assertEquals("렛풀다운", form.allChips().single().name)
        assertTrue(form.allChips().single().images.size == 2)
    }
}
