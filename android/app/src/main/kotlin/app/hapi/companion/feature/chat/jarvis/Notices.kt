package app.hapi.companion.feature.chat.jarvis

import app.hapi.protocol.window.WindowMessage
import app.hapi.protocol.wire.DecryptedMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/*
 * 집사가 먼저 한 말 (Jarvis fork, docs/jarvis/CHANGES.md step 19). The server's
 * notices — morning briefing, reminders, what also went to Telegram — are
 * pushed straight to the phone without a model turn, so they are not in the
 * hub's conversation. They are read from our entrance (`/notices`, from the
 * outbound log the butler itself also reads) and drawn in the butler's voice,
 * in time order, between the real rows. Never sent anywhere.
 */

@Serializable
data class Notice(val at: Long, val text: String)

@Serializable
data class NoticeList(val notices: List<Notice> = emptyList())

const val NOTICE_ID_PREFIX = "jarvis-notice-"

/** A notice as an agent row the normal pipeline draws as the butler's words. */
fun noticeRow(n: Notice): WindowMessage = WindowMessage(
    DecryptedMessage(
        id = NOTICE_ID_PREFIX + n.at,
        createdAt = n.at,
        content = buildJsonObject {
            put("role", "agent")
            putJsonObject("content") {
                put("type", "codex")
                putJsonObject("data") { put("type", "message"); put("message", n.text) }
            }
        },
    ),
)

/**
 * Notices merged into [rows] by time. Only notices inside what is loaded
 * ([hasOlder] = older pages still unloaded → nothing older than the first row)
 * and newer than a 「화면 비우기」 floor.
 */
fun withNotices(rows: List<WindowMessage>, notices: List<Notice>, hasOlder: Boolean, clearedThrough: Long?): List<WindowMessage> {
    if (notices.isEmpty()) return rows
    val from = if (hasOlder) rows.firstOrNull()?.positionAt ?: Long.MAX_VALUE else Long.MIN_VALUE
    val add = notices.filter { it.at >= from && (clearedThrough == null || it.at > clearedThrough) }.map(::noticeRow)
    if (add.isEmpty()) return rows
    return (rows + add).sortedBy { it.positionAt }
}
