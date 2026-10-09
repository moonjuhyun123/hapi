package app.hapi.companion.feature.chat.jarvis

import app.hapi.protocol.window.WindowMessage
import app.hapi.protocol.wire.DecryptedMessage
import app.hapi.protocol.wire.OptionalField
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenClearTest {
    private fun row(id: String, at: Long, localId: String? = null, invokedAt: OptionalField<Long?> = OptionalField.Absent) = WindowMessage(
        DecryptedMessage(
            id = id, seq = at, localId = localId, createdAt = at, invokedAt = invokedAt,
            content = buildJsonObject {
                put("role", "user")
                putJsonObject("content") { put("type", "text"); put("text", id) }
            },
        ),
    )

    private val rows = listOf(row("a", 100), row("b", 200), row("c", 300))

    @Test fun `no floor shows everything and keeps paging`() {
        assertEquals(rows, afterClear(rows, null))
        assertFalse(reachedClear(rows, null))
    }

    @Test fun `rows at or under the floor are hidden and paging stops`() {
        assertEquals(listOf("c"), afterClear(rows, 200).map { it.id })
        assertTrue(reachedClear(rows, 200))
        assertTrue(afterClear(rows, 300).isEmpty())
    }

    @Test fun `a floor older than every loaded row hides nothing yet and paging goes on`() {
        assertEquals(rows, afterClear(rows, 50))
        assertFalse(reachedClear(rows, 50))
    }

    @Test fun `clear point is the newest delivered row, not an optimistic or queued one`() {
        val optimistic = row("local-9", 9_999, localId = "local-9") // id == localId → phone clock
        val queued = row("q", 8_888, localId = "q-local", invokedAt = OptionalField.Present(null))
        assertEquals(300, clearPoint(rows + optimistic + queued))
        assertNull(clearPoint(emptyList()))
    }

    @Test fun `a message invoked after clearing shows even if it was written before`() {
        val late = row("late", 150, localId = "late-local", invokedAt = OptionalField.Present(400L))
        assertEquals(listOf("late"), afterClear(rows + late, 300).map { it.id })
    }
}
