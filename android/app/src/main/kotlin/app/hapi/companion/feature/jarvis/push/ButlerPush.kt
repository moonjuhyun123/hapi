package app.hapi.companion.feature.jarvis.push

import app.hapi.companion.feature.chat.jarvis.dropHandoffNoise
import app.hapi.companion.feature.chat.jarvis.handoffSourceId
import app.hapi.data.api.MessagesQuery
import app.hapi.data.push.PushHubAccess
import app.hapi.data.push.PushPayload
import app.hapi.data.push.PushType
import app.hapi.protocol.chat.AgentTextBlock
import app.hapi.protocol.chat.ToolGroupingOptions
import app.hapi.protocol.chat.buildVisibleChatBlocks
import app.hapi.protocol.chat.normalizeDecryptedMessage
import app.hapi.protocol.chat.reduceChatBlocks
import app.hapi.protocol.window.WindowMessage
import app.hapi.protocol.wire.DecryptedMessage
import app.hapi.protocol.wire.isUserMessage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/*
 * 집사 알림 (Jarvis fork, docs/jarvis/CHANGES.md step 15). The hub's native
 * push names the harness (「Ready for input」 / 「Claude is waiting in 집사」);
 * the butler screen never does (정본 9장 — 「난 클로드고 코덱스고 알고싶지 않음」).
 * Pushes are re-titled 「집사」 and a `ready` push carries what the butler
 * actually said, read from the hub when the push lands. A hand-over's own
 * ack to its packet never notifies (it isn't drawn either — step 13).
 */

/** The butler's last words in a page of newest messages, and whether that turn only acked a hand-over. */
data class LatestReply(val text: String?, val handoffAck: Boolean)

/** Localized words for the rewrite (resolved by the caller). */
data class ButlerPushLabels(val butler: String, val replied: String, val asks: String, val permission: String)

fun latestReply(messages: List<DecryptedMessage>): LatestReply {
    val rows = messages.map { WindowMessage(it) }
    val lastUser = rows.lastOrNull { it.wire.isUserMessage }
    val ack = lastUser != null && handoffSourceId(lastUser.localId) != null
    val reduced = reduceChatBlocks(dropHandoffNoise(rows).mapNotNull { normalizeDecryptedMessage(it.wire) }, null) { 0 }
    val text = buildVisibleChatBlocks(reduced.blocks, ToolGroupingOptions(hasMoreMessages = false))
        .filterIsInstance<AgentTextBlock>().lastOrNull()?.text
    return LatestReply(text?.let(::plainPreview)?.takeIf { it.isNotBlank() }, ack)
}

/** Markdown marks off, whitespace folded, capped — a notification line, not a document. */
fun plainPreview(markdown: String, max: Int = 400): String {
    val flat = markdown
        .replace(Regex("```[a-zA-Z0-9]*"), "")
        .replace(Regex("(?m)^\\s{0,3}(#{1,6}\\s+|>\\s?|[-*+]\\s+)"), "")
        .replace(Regex("[*_`]+"), "")
        .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    return if (flat.length <= max) flat else flat.take(max).trimEnd() + "…"
}

/** What to show for [payload]; null = stay silent (a hand-over ack). */
fun butlerPush(payload: PushPayload, latest: LatestReply?, labels: ButlerPushLabels): PushPayload? {
    val base = payload.copy(sessionName = null, notifySummary = null)
    return when (payload.type) {
        PushType.READY ->
            if (latest?.handoffAck == true) null
            else base.copy(title = labels.butler, body = latest?.text ?: labels.replied)
        PushType.INPUT_REQUEST -> base.copy(title = labels.asks)
        PushType.PERMISSION_REQUEST -> base.copy(title = labels.permission)
        PushType.TASK_NOTIFICATION, null -> base.copy(title = labels.butler)
    }
}

/** Newest page of [sessionId] from the first paired hub that has it; null on any failure (the push still shows). */
suspend fun fetchLatestReply(hubAccess: PushHubAccess, sessionId: String, timeoutMs: Long = 8_000): LatestReply? =
    withTimeoutOrNull(timeoutMs) {
        for (hub in hubAccess.pairedHubsActiveFirst()) {
            try {
                val page = hubAccess.withApi(hub) { api -> api.getMessages(sessionId, MessagesQuery.Latest(limit = 30)) }
                return@withTimeoutOrNull latestReply(page.messages)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // not this hub, or unreachable — try the next
            }
        }
        null
    }
