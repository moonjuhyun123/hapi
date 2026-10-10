package app.hapi.companion.feature.jarvis.push

import app.hapi.data.push.PushPayload
import app.hapi.data.push.PushType
import app.hapi.protocol.wire.DecryptedMessage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ButlerPushTest {
    private var n = 0L
    private fun user(text: String, localId: String? = null) = DecryptedMessage(
        id = "m${++n}", seq = n, localId = localId, createdAt = n,
        content = buildJsonObject {
            put("role", "user")
            putJsonObject("content") { put("type", "text"); put("text", text) }
        },
    )
    private fun agent(text: String) = DecryptedMessage(
        id = "m${++n}", seq = n, createdAt = n,
        content = buildJsonObject {
            put("role", "agent")
            putJsonObject("content") {
                put("type", "codex")
                putJsonObject("data") { put("type", "message"); put("message", text) }
            }
        },
    )
    private fun payload(type: PushType?, body: String = "Claude is waiting in 집사") = PushPayload(
        type = type, rawType = type?.wire ?: "x", sessionId = "s1", sessionName = "claude - brain", url = null,
        title = "Ready for input", body = body, requestId = null, severity = null, contractVersion = "1", notifySummary = null,
    )
    private val labels = ButlerPushLabels("집사", "집사가 답했습니다", "집사가 물어봅니다", "집사가 허락을 구합니다")

    @Test fun `ready shows the butler's last words, no harness name`() {
        val latest = latestReply(listOf(user("저녁 뭐 먹지"), agent("**한식** 어때요?\n- 된장찌개")))
        val shown = butlerPush(payload(PushType.READY), latest, labels)!!
        assertEquals("집사", shown.displayTitle)
        assertEquals("한식 어때요?\n된장찌개", shown.displayBody)
        assertNull(shown.sessionName)
    }

    @Test fun `a hand-over ack stays silent`() {
        val latest = latestReply(listOf(user("꾸러미", "jarvis-handoff-OLD"), agent("이어받음")))
        assertTrue(latest.handoffAck)
        assertNull(butlerPush(payload(PushType.READY), latest, labels))
    }

    @Test fun `the answer to a re-sent message does notify`() {
        val latest = latestReply(listOf(user("꾸러미", "jarvis-handoff-OLD"), agent("이어받음"), user("질문", "jarvis-resend-m1"), agent("답")))
        assertEquals("답", butlerPush(payload(PushType.READY), latest, labels)!!.displayBody)
    }

    @Test fun `no fetch falls back to a plain line`() {
        assertEquals("집사가 답했습니다", butlerPush(payload(PushType.READY), null, labels)!!.displayBody)
    }

    @Test fun `questions and permissions keep their body under the butler's title`() {
        assertEquals("집사가 물어봅니다", butlerPush(payload(PushType.INPUT_REQUEST, "저녁 뭐 먹을까요?"), null, labels)!!.displayTitle)
        val p = butlerPush(payload(PushType.PERMISSION_REQUEST, "Bash: ls"), null, labels)!!
        assertEquals("집사가 허락을 구합니다" to "Bash: ls", p.displayTitle to p.displayBody)
    }

    @Test fun `preview caps long text`() {
        assertEquals(401, plainPreview("가".repeat(1000)).length)
    }
}
