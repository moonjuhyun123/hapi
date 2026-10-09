package app.hapi.companion.feature.chat.jarvis

import app.hapi.data.api.ApiError
import app.hapi.data.api.MessagesApi
import app.hapi.data.api.MessagesQuery
import app.hapi.protocol.chat.AgentTextBlock
import app.hapi.protocol.chat.ToolGroupingOptions
import app.hapi.protocol.chat.UserTextBlock
import app.hapi.protocol.chat.VisibleChatBlock
import app.hapi.protocol.chat.buildVisibleChatBlocks
import app.hapi.protocol.chat.normalizeDecryptedMessage
import app.hapi.protocol.chat.reduceChatBlocks
import app.hapi.protocol.wire.DecryptedMessage
import app.hapi.protocol.wire.MessagesPage
import app.hapi.protocol.wire.MessagesResponse
import app.hapi.protocol.wire.QueuedStateResponse
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandoffChainTest {
    // ------------------------------------------------------------- fakes --

    private fun user(session: String, n: Int, text: String = "$session-u$n", localId: String? = null) = DecryptedMessage(
        id = "$session-m$n", seq = n.toLong(), localId = localId, createdAt = 1_000L * n + offset(session),
        content = buildJsonObject {
            put("role", "user")
            putJsonObject("content") { put("type", "text"); put("text", text) }
        },
    )

    private fun agent(session: String, n: Int, text: String = "$session-a$n") = DecryptedMessage(
        id = "$session-m$n", seq = n.toLong(), createdAt = 1_000L * n + offset(session),
        content = buildJsonObject {
            put("role", "agent")
            putJsonObject("content") {
                put("type", "codex")
                putJsonObject("data") { put("type", "message"); put("message", text) }
            }
        },
    )

    /** Older sessions sort earlier: A < B < C. */
    private fun offset(session: String) = (session.first() - 'A') * 1_000_000L

    /** A hub that pages oldest-first like `getMessagesByPosition`. */
    private class FakeHub(val sessions: Map<String, List<DecryptedMessage>>) : MessagesApi {
        val calls = mutableListOf<String>()
        val failOnce = mutableSetOf<String>()

        override suspend fun getMessages(sessionId: String, query: MessagesQuery): MessagesResponse {
            calls += "$sessionId:${query::class.simpleName}"
            if (failOnce.remove(sessionId)) throw IOException("offline")
            val all = sessions[sessionId] ?: throw ApiError(status = 404, code = "not_found")
            val older = when (query) {
                is MessagesQuery.Before -> all.filter { it.createdAt < query.beforeAt || (it.createdAt == query.beforeAt && (it.seq ?: 0) < query.beforeSeq) }
                else -> all
            }
            val page = older.takeLast(query.limit)
            val first = page.firstOrNull()
            return MessagesResponse(
                messages = page,
                page = MessagesPage(
                    direction = if (query is MessagesQuery.Before) "before" else "latest",
                    limit = query.limit, epoch = 1, reset = false,
                    nextBeforeSeq = first?.seq, nextBeforeAt = first?.createdAt,
                    hasMore = older.size > page.size,
                ),
            )
        }

        override suspend fun getQueuedState(sessionId: String, localIds: List<String>): QueuedStateResponse =
            error("unused")
    }

    private fun chain(hub: FakeHub, current: String = "C", pageSize: Int = 50) =
        HandoffChain(current, hub, pageSize = pageSize, settleMs = 0)

    private fun HandoffChain.drain(): Int = runBlocking {
        var pages = 0
        while (state.value.hasMore && loadNext()) pages++
        pages
    }

    /** Ancestors + current through the real protocol pipeline, hidden rows removed. */
    private fun transcript(messages: List<DecryptedMessage>): List<VisibleChatBlock> {
        val reduced = reduceChatBlocks(messages.mapNotNull(::normalizeDecryptedMessage), null) { 0 }
        return buildVisibleChatBlocks(reduced.blocks, ToolGroupingOptions(hasMoreMessages = false))
            .filterNot(::isHiddenTranscriptBlock)
    }

    private fun texts(blocks: List<VisibleChatBlock>) = blocks.map {
        when (it) {
            is UserTextBlock -> "U:" + it.text
            is AgentTextBlock -> "A:" + it.text
            else -> it::class.simpleName
        }
    }

    // -------------------------------------------------------------- tags --

    @Test fun tagsAreReadFromTheLocalIdPrefixOnly() {
        assertTrue(isOutreachLocalId("jarvis-out-2026-10-09T07:00"))
        assertFalse(isOutreachLocalId("local-123"))
        assertFalse(isOutreachLocalId(null))
        assertEquals("sess-B", handoffSourceId("jarvis-handoff-sess-B"))
        assertNull(handoffSourceId("jarvis-handoff-"))
        assertNull(handoffSourceId("x-jarvis-handoff-sess-B"))
        assertTrue(isServerTaggedLocalId("jarvis-out-1"))
        assertTrue(isServerTaggedLocalId("jarvis-handoff-B"))
        assertFalse(isServerTaggedLocalId("local-1"))
    }

    @Test fun outreachFoldsAndHandoffHidesThroughTheRealPipeline() {
        val blocks = transcript(listOf(
            user("C", 1, "인계 꾸러미", localId = "jarvis-handoff-B"),
            agent("C", 2, "이어서 하겠습니다"),
            user("C", 3, "아침 인사를 먼저 건네라", localId = "jarvis-out-0700"),
            agent("C", 4, "좋은 아침입니다"),
            user("C", 5, "고마워", localId = "local-1"),
        ))
        // The hand-over packet is gone; the cron instruction stays as one row
        // that the card dispatch turns into a chip.
        assertEquals(listOf("A:이어서 하겠습니다", "U:아침 인사를 먼저 건네라", "A:좋은 아침입니다", "U:고마워"), texts(blocks))
        val users = blocks.filterIsInstance<UserTextBlock>()
        assertEquals(listOf(true, false), users.map { isOutreachLocalId(it.localId) })
    }

    @Test fun firstUserMessageSkipsLeadingAgentEvents() {
        val messages = listOf(agent("C", 1, "ready"), user("C", 2, localId = "jarvis-handoff-B"), user("C", 3))
        assertEquals("jarvis-handoff-B", firstUserLocalId(messages))
        assertEquals("jarvis-handoff-B", firstUserLocalIdOfRows(messages.map { app.hapi.protocol.window.WindowMessage(it) }))
        assertNull(firstUserLocalId(listOf(agent("C", 1))))
    }

    // ------------------------------------------------------------ anchor --

    @Test fun noHandoffAtTheStartMeansNoChain() {
        val state = HandoffChainState().anchoredTo("C", "local-1")
        assertTrue(state.anchored)
        assertFalse(state.hasMore)
    }

    @Test fun lateHandoffPacketStillLinksButAnEndedLinkIsNotRetried() {
        var state = HandoffChainState().anchoredTo("C", null)
        assertFalse(state.hasMore)
        // The packet lands after the chat opened.
        state = state.anchoredTo("C", "jarvis-handoff-B")
        assertEquals(ChainCursor("B"), state.next)
        // B turns out to be gone: the same link is not followed again.
        state = state.ended().anchoredTo("C", "jarvis-handoff-B")
        assertFalse(state.hasMore)
    }

    @Test fun aHandoffStartCountsAsMoreHistoryBeforeAnchoring() {
        assertTrue(HandoffChainState().mayHaveMore { "jarvis-handoff-B" })
        assertFalse(HandoffChainState().mayHaveMore { "local-1" })
        // Once anchored, only a real cursor counts.
        assertFalse(HandoffChainState().anchoredTo("C", "jarvis-handoff-B").ended().mayHaveMore { "jarvis-handoff-B" })
    }

    @Test fun aSelfLinkIsIgnored() {
        assertFalse(HandoffChainState().anchoredTo("C", "jarvis-handoff-C").hasMore)
    }

    // ------------------------------------------------------------- chain --

    @Test fun followsTheChainBackPageByPageOldestFirst() {
        val a = listOf(user("A", 1), agent("A", 2), user("A", 3))
        val b = listOf(user("B", 1, localId = "jarvis-handoff-A")) + (2..70).map { agent("B", it) }
        val hub = FakeHub(mapOf("A" to a, "B" to b))
        val chain = chain(hub)
        chain.anchor("jarvis-handoff-B")

        // One page per scroll step: B newest, B older, then A.
        runBlocking { chain.loadNext() }
        assertEquals(50, chain.state.value.messages.size)
        assertEquals(ChainCursor("B", b[20].createdAt, 21), chain.state.value.next)
        assertEquals(2, chain.drain())
        assertEquals(listOf("B:Latest", "B:Before", "A:Latest"), hub.calls)

        val state = chain.state.value
        assertFalse(state.hasMore)
        assertEquals(listOf("A", "B"), state.segments.map { it.sessionId })
        assertEquals((a + b).map { it.id }, state.messages.map { it.id })

        // Stitched with the current session it reads as one conversation:
        // both hand-over packets (B's start, C's start) are invisible.
        val c = listOf(user("C", 1, localId = "jarvis-handoff-B"), agent("C", 2, "C-reply"))
        val blocks = transcript(state.messages.map { it.wire } + c)
        assertEquals("U:A-u1", texts(blocks).first())
        assertEquals("A:C-reply", texts(blocks).last())
        assertTrue(blocks.none { it is UserTextBlock && it.localId?.startsWith("jarvis-handoff-") == true })
    }

    @Test fun stopsWhereThePreviousSessionIsGone() {
        val b = listOf(user("B", 1, localId = "jarvis-handoff-A"), agent("B", 2))
        val hub = FakeHub(mapOf("B" to b)) // A was deleted
        val chain = chain(hub)
        chain.anchor("jarvis-handoff-B")
        chain.drain()
        val state = chain.state.value
        assertFalse(state.hasMore)
        assertFalse(state.failed)
        assertEquals(listOf("B"), state.segments.map { it.sessionId })
        assertEquals(listOf("B:Latest", "A:Latest"), hub.calls)
    }

    @Test fun missingFirstLinkLeavesOnlyTheCurrentSession() {
        val chain = chain(FakeHub(emptyMap()))
        chain.anchor("jarvis-handoff-B")
        chain.drain()
        assertFalse(chain.state.value.hasMore)
        assertTrue(chain.state.value.messages.isEmpty())
    }

    @Test fun aLoopInTheChainStops() {
        // C ← B ← C again (bad data): C is the current session, already visited.
        val b = listOf(user("B", 1, localId = "jarvis-handoff-C"))
        val hub = FakeHub(mapOf("B" to b, "C" to listOf(user("C", 1))))
        val chain = chain(hub)
        chain.anchor("jarvis-handoff-B")
        chain.drain()
        assertFalse(chain.state.value.hasMore)
        assertEquals(listOf("B:Latest"), hub.calls)
    }

    @Test fun aNetworkFailureKeepsTheCursorForTheNextScroll() {
        val b = listOf(user("B", 1), agent("B", 2))
        val hub = FakeHub(mapOf("B" to b)).apply { failOnce += "B" }
        val chain = chain(hub)
        chain.anchor("jarvis-handoff-B")
        assertFalse(runBlocking { chain.loadNext() })
        assertTrue(chain.state.value.failed)
        assertTrue(chain.state.value.hasMore)
        assertTrue(runBlocking { chain.loadNext() })
        assertFalse(chain.state.value.hasMore)
        assertEquals(2, chain.state.value.messages.size)
    }

    @Test fun aStalePageIsIgnored() {
        val state = HandoffChainState().anchoredTo("C", "jarvis-handoff-B")
        val stale = state.withPage(ChainCursor("Z"), listOf(user("Z", 1)), false, null, null)
        assertEquals(state, stale)
    }
}
