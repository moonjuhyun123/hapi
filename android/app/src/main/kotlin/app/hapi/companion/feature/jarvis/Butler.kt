package app.hapi.companion.feature.jarvis

import app.hapi.protocol.wire.SessionSummary
import app.hapi.protocol.wire.SpawnResponse
import app.hapi.protocol.wire.SpawnSessionRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/*
 * Jarvis fork (docs/jarvis/CHANGES.md): the butler is one server-side session.
 * The server decides which session that is (by pinning it globally) and which
 * harness/model it runs. The app only finds it and shows it.
 */

/**
 * The butler = among globally pinned sessions, the one that moved last
 * (newest `updatedAt`, then `activeAt`, then id for a stable tie-break).
 * Null when nothing is globally pinned. The app never picks or creates one.
 */
fun resolveButler(sessions: List<SessionSummary>): SessionSummary? =
    sessions.asSequence()
        .filter { it.globalPinned == true }
        .maxWithOrNull(compareBy<SessionSummary>({ it.updatedAt }, { it.activeAt }, { it.id }))

/**
 * The id to show while a hand-off ([from] → [to]) is pending: the list may
 * still name the old butler, or (after the old row is unpinned optimistically)
 * none at all. Any other resolved butler wins.
 */
fun butlerIdWithHandoff(resolvedId: String?, handoff: Pair<String, String>?): String? = when {
    handoff == null -> resolvedId
    resolvedId == null || resolvedId == handoff.first -> handoff.second
    else -> resolvedId
}

/** Harnesses offered by "move to another harness". Model is never chosen here. */
val BUTLER_HARNESSES: List<String> = listOf("claude", "codex")

/** The two hub calls "move to another harness" needs (a seam for JVM tests). */
interface ButlerGateway {
    /** `POST /api/machines/:id/spawn` — check `type`, not HTTP status. */
    suspend fun spawn(machineId: String, request: SpawnSessionRequest): SpawnResponse

    /** `PUT /api/sessions/:id/pin` with `{mode}` (`none|project|global`). */
    suspend fun setPinMode(sessionId: String, mode: String)
}

sealed interface MoveResult {
    /** New butler is pinned globally and the old one unpinned. */
    data class Moved(val newSessionId: String) : MoveResult

    /** The butler has no machine/folder recorded, so there is nothing to copy. */
    data object MissingLocation : MoveResult

    /** Spawn failed; nothing changed on the hub. [code] is the hub's error code. */
    data class SpawnFailed(val code: String?, val message: String?) : MoveResult

    /** New session exists but could not be pinned; the old butler is untouched. */
    data class PinFailed(val newSessionId: String, val message: String?) : MoveResult

    /** New butler pinned, but the old one is still pinned too. */
    data class UnpinOldFailed(val newSessionId: String, val message: String?) : MoveResult
}

/**
 * "Move to another harness": start a session on the same machine and folder
 * with only the harness changed (model left to the server/machine default),
 * pin it globally, then unpin the old butler. The new pin goes first so there
 * is never a moment without a butler. Moving the conversation is not the
 * app's job.
 */
class ButlerMover(
    private val gateway: ButlerGateway,
    private val pinRetryDelayMs: Long = 1_000,
) {
    suspend fun move(butler: SessionSummary, harness: String): MoveResult {
        val machineId = butler.metadata?.machineId?.takeIf { it.isNotBlank() } ?: return MoveResult.MissingLocation
        val directory = butler.metadata?.path?.takeIf { it.isNotBlank() } ?: return MoveResult.MissingLocation

        val spawned = try {
            gateway.spawn(machineId, SpawnSessionRequest(directory = directory, agent = harness, sessionType = "simple"))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            return MoveResult.SpawnFailed(code = (error as? app.hapi.data.api.ApiError)?.code, message = error.message)
        }
        val newId = spawned.sessionId
        if (spawned.type != "success" || newId == null) {
            return MoveResult.SpawnFailed(spawned.code, spawned.message)
        }

        // One retry: the hub can take a moment to list a just-spawned session.
        val pinError = attempt(times = 2) { gateway.setPinMode(newId, "global") }
        if (pinError != null) return MoveResult.PinFailed(newId, pinError.message)

        val unpinError = attempt(times = 2) { gateway.setPinMode(butler.id, "none") }
        if (unpinError != null) return MoveResult.UnpinOldFailed(newId, unpinError.message)
        return MoveResult.Moved(newId)
    }

    /** Runs [block] up to [times] times; returns the last error, or null on success. */
    private suspend fun attempt(times: Int, block: suspend () -> Unit): Exception? {
        var last: Exception? = null
        repeat(times) { index ->
            try {
                block()
                return null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                last = error
                if (index < times - 1) delay(pinRetryDelayMs)
            }
        }
        return last
    }
}
