package app.hapi.companion.feature.chat.jarvis

import app.hapi.protocol.window.WindowMessage
import app.hapi.protocol.wire.DecryptedMessage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class NoticesTest {
    private fun user(id: String, at: Long) = WindowMessage(
        DecryptedMessage(id = id, seq = at, createdAt = at, content = buildJsonObject {
            put("role", "user"); putJsonObject("content") { put("type", "text"); put("text", id) }
        }),
    )
    private val rows = listOf(user("a", 100), user("b", 300))

    @Test fun `notices slot in by time`() {
        val out = withNotices(rows, listOf(Notice(200, "브리핑"), Notice(400, "운동")), hasOlder = false, clearedThrough = null)
        assertEquals(listOf("a", "jarvis-notice-200", "b", "jarvis-notice-400"), out.map { it.id })
    }

    @Test fun `older than what is loaded waits for the older page, cleared ones stay hidden`() {
        val n = listOf(Notice(50, "옛"), Notice(150, "중간"), Notice(350, "새"))
        assertEquals(listOf("a", "jarvis-notice-150", "b", "jarvis-notice-350"), withNotices(rows, n, hasOlder = true, clearedThrough = null).map { it.id })
        assertEquals(listOf("a", "b", "jarvis-notice-350"), withNotices(rows, n, hasOlder = false, clearedThrough = 200).map { it.id })
    }
}
