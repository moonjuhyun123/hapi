package app.hapi.companion.feature.chat.jarvis

import app.hapi.data.api.ApiError
import app.hapi.data.api.MessagesApi
import app.hapi.data.api.MessagesQuery
import app.hapi.protocol.chat.UserTextBlock
import app.hapi.protocol.chat.VisibleChatBlock
import app.hapi.protocol.window.WindowMessage
import app.hapi.protocol.wire.DecryptedMessage
import app.hapi.protocol.wire.isUserMessage
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Jarvis fork (docs/jarvis/CHANGES.md). The server tags what it puts into a
 * session through the hub with a `localId` prefix; the app reads only that tag.
 *
 * - `jarvis-out-…`              a cron's "speak first" instruction → folded chip
 * - `jarvis-handoff-<prev id>`  hand-over packet when the server moves the
 *                                butler to another harness → never drawn, and
 *                                the previous session's conversation is shown
 *                                above as if it were one conversation.
 */

const val OUTREACH_LOCAL_ID_PREFIX = "jarvis-out-"
const val HANDOFF_LOCAL_ID_PREFIX = "jarvis-handoff-"

/** A cron's "speak first" instruction to the butler. */
fun isOutreachLocalId(localId: String?): Boolean = localId?.startsWith(OUTREACH_LOCAL_ID_PREFIX) == true

/** The session a hand-over packet came from, or null if [localId] is not one. */
fun handoffSourceId(localId: String?): String? =
    localId?.takeIf { it.startsWith(HANDOFF_LOCAL_ID_PREFIX) }
        ?.removePrefix(HANDOFF_LOCAL_ID_PREFIX)
        ?.takeIf { it.isNotBlank() }

/** Anything the server put in on its own (kept out of the queued-message bar). */
fun isServerTaggedLocalId(localId: String?): Boolean = isOutreachLocalId(localId) || handoffSourceId(localId) != null

/** Transcript rows never drawn: hand-over packets. */
fun isHiddenTranscriptBlock(block: VisibleChatBlock): Boolean =
    block is UserTextBlock && handoffSourceId(block.localId) != null

/** `localId` of a session's first user message (agent events before it don't count). */
fun firstUserLocalId(messages: List<DecryptedMessage>): String? =
    messages.firstOrNull { it.isUserMessage }?.localId

/** Same, over window rows (no copy of the window). */
fun firstUserLocalIdOfRows(rows: List<WindowMessage>): String? =
    rows.firstOrNull { it.wire.isUserMessage }?.localId

// ----------------------------------------------------------------- chain --

/** Older-page cursor inside one ancestor session (`null` = its newest page). */
data class ChainCursor(val sessionId: String, val beforeAt: Long? = null, val beforeSeq: Long? = null)

/** One ancestor session's loaded rows, oldest first. */
data class ChainSegment(val sessionId: String, val messages: List<WindowMessage>)

/** Upper bound on how far back a chain is followed (also a cycle backstop). */
const val MAX_CHAIN_SESSIONS = 64

/**
 * Previous sessions stitched above the current one. Pure transitions; the
 * loader below performs the fetches.
 */
data class HandoffChainState(
    /** Set once the current session's start has been seen. */
    val anchored: Boolean = false,
    /** The link already followed from the current session (not re-tried once it ended). */
    val linkedFrom: String? = null,
    /** Loaded ancestors, oldest session first. */
    val segments: List<ChainSegment> = emptyList(),
    /** What to load next; null when the chain ended (no link, missing session, limit). */
    val next: ChainCursor? = null,
    val visited: Set<String> = emptySet(),
    val loading: Boolean = false,
    val failed: Boolean = false,
    /** All ancestor rows, oldest first; rebuilt only when a page lands (stable identity). */
    val messages: List<WindowMessage> = emptyList(),
) {
    val hasMore: Boolean get() = next != null

    /**
     * The current session's start is on screen. Until anything is loaded the
     * link is re-read each time, so a hand-over packet that lands after the
     * chat opened still links up.
     */
    fun anchoredTo(currentSessionId: String, firstUserLocalId: String?): HandoffChainState {
        if (segments.isNotEmpty() || loading) return if (anchored) this else copy(anchored = true)
        val source = handoffSourceId(firstUserLocalId)?.takeIf { it != currentSessionId }
        if (anchored && source == linkedFrom) return this
        return copy(
            anchored = true,
            linkedFrom = source,
            next = source?.let { ChainCursor(it) },
            visited = setOf(currentSessionId),
            failed = false,
        )
    }

    /** One page of [cursor]'s session arrived (rows oldest first). */
    fun withPage(
        cursor: ChainCursor,
        rows: List<DecryptedMessage>,
        hasMoreInSession: Boolean,
        nextBeforeAt: Long?,
        nextBeforeSeq: Long?,
    ): HandoffChainState {
        if (next != cursor) return this // stale page
        val page = rows.map { WindowMessage(it) }.filter { !it.isQueuedForInvocation }
        val oldest = segments.firstOrNull()
        val nextSegments = if (oldest?.sessionId == cursor.sessionId) {
            listOf(oldest.copy(messages = page + oldest.messages)) + segments.drop(1)
        } else {
            listOf(ChainSegment(cursor.sessionId, page)) + segments
        }
        val seen = visited + cursor.sessionId
        val following = when {
            hasMoreInSession && nextBeforeAt != null && nextBeforeSeq != null ->
                ChainCursor(cursor.sessionId, nextBeforeAt, nextBeforeSeq)
            // Session fully read: does it itself start with a hand-over packet?
            else -> handoffSourceId(firstUserLocalId(nextSegments.first().messages.map { it.wire }))
                ?.takeIf { it !in seen && nextSegments.size < MAX_CHAIN_SESSIONS }
                ?.let { ChainCursor(it) }
        }
        return copy(
            segments = nextSegments,
            next = following,
            visited = seen,
            loading = false,
            failed = false,
            messages = nextSegments.flatMap { it.messages },
        )
    }

    /**
     * More stitched history may come: a cursor is pending, or the session's
     * start shows a hand-over packet the loader has not anchored yet (avoids
     * a one-frame "empty conversation" right after a hand-over).
     */
    fun mayHaveMore(firstUserLocalId: () -> String?): Boolean =
        hasMore || (!anchored && handoffSourceId(firstUserLocalId()) != null)

    /** The next session is gone (deleted / never existed): the chain stops here. */
    fun ended(): HandoffChainState = copy(next = null, loading = false, failed = false)

    /** Network or server trouble: keep the cursor so a later scroll retries. */
    fun failedLoad(): HandoffChainState = copy(loading = false, failed = true)
}

/**
 * Loads ancestor pages on demand (scrolling up past the current session's
 * start). Read-only: nothing here sends to an ancestor.
 */
class HandoffChain(
    private val sessionId: String,
    private val api: MessagesApi,
    private val pageSize: Int = 50,
    /** Pause after a page so the prepended rows lay out before asking again. */
    private val settleMs: Long = 250,
) {
    private val mutableState = MutableStateFlow(HandoffChainState())
    val state: StateFlow<HandoffChainState> = mutableState.asStateFlow()
    private var job: Job? = null

    fun anchor(firstUserLocalId: String?) {
        mutableState.update { it.anchoredTo(sessionId, firstUserLocalId) }
    }

    /** Loads the next page unless one is in flight; [onSettled] runs after a successful page. */
    fun requestMore(scope: CoroutineScope, io: CoroutineContext, onSettled: () -> Unit) {
        if (job?.isActive == true || !state.value.hasMore) return
        job = scope.launch {
            val loaded = withContext(io) { loadNext() }
            if (loaded) {
                delay(settleMs)
                onSettled()
            }
        }
    }

    /** Fetches one page for the current cursor. Returns true when a page was applied. */
    suspend fun loadNext(): Boolean {
        val cursor = state.value.next ?: return false
        mutableState.update { it.copy(loading = true) }
        return try {
            val query = if (cursor.beforeAt != null && cursor.beforeSeq != null) {
                MessagesQuery.Before(beforeAt = cursor.beforeAt, beforeSeq = cursor.beforeSeq, limit = pageSize)
            } else {
                MessagesQuery.Latest(limit = pageSize)
            }
            val response = api.getMessages(cursor.sessionId, query)
            val page = response.page
            mutableState.update {
                it.withPage(cursor, response.messages, page.hasMore, page.nextBeforeAt, page.nextBeforeSeq)
            }
            true
        } catch (cancellation: CancellationException) {
            mutableState.update { it.copy(loading = false) }
            throw cancellation
        } catch (error: ApiError) {
            mutableState.update { if (error.status == 404 || error.status == 403) it.ended() else it.failedLoad() }
            false
        } catch (_: Exception) {
            mutableState.update { it.failedLoad() }
            false
        }
    }
}
