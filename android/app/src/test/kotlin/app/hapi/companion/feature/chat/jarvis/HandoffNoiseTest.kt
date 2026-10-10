package app.hapi.companion.feature.chat.jarvis

import app.hapi.protocol.window.WindowMessage
import app.hapi.protocol.wire.DecryptedMessage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HandoffNoiseTest {
    private var n = 0L
    private fun user(id: String, localId: String? = null) = WindowMessage(
        DecryptedMessage(
            id = id, seq = ++n, localId = localId, createdAt = n,
            content = buildJsonObject {
                put("role", "user")
                putJsonObject("content") { put("type", "text"); put("text", id) }
            },
        ),
    )
    private fun agent(id: String) = WindowMessage(
        DecryptedMessage(
            id = id, seq = ++n, createdAt = n,
            content = buildJsonObject {
                put("role", "agent")
                putJsonObject("content") {
                    put("type", "codex")
                    putJsonObject("data") { put("type", "message"); put("message", id) }
                }
            },
        ),
    )

    @Test fun `the ack to a packet is dropped, the next exchange is kept`() {
        val rows = listOf(user("packet", "jarvis-handoff-OLD"), agent("ack"), user("hi"), agent("hello"))
        assertEquals(listOf("packet", "hi", "hello"), dropHandoffNoise(rows).map { it.id })
    }

    @Test fun `a re-sent message is dropped but its answer is kept`() {
        val rows = listOf(user("packet", "jarvis-handoff-OLD"), agent("ack"), user("again", "jarvis-resend-m9"), agent("answer"))
        assertEquals(listOf("packet", "answer"), dropHandoffNoise(rows).map { it.id })
    }

    @Test fun `no packet means nothing is dropped`() {
        val rows = listOf(user("a"), agent("b"), user("c", "jarvis-out-1"), agent("d"))
        assertEquals(rows, dropHandoffNoise(rows))
    }

    @Test fun `resend is server-tagged so it stays out of the queued bar`() {
        assertTrue(isServerTaggedLocalId("jarvis-resend-m9"))
    }
}
