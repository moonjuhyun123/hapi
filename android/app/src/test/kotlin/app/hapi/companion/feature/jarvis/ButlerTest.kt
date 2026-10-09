package app.hapi.companion.feature.jarvis

import app.hapi.data.api.ApiError
import app.hapi.protocol.wire.SessionSummary
import app.hapi.protocol.wire.SessionSummaryMetadata
import app.hapi.protocol.wire.SpawnResponse
import app.hapi.protocol.wire.SpawnSessionRequest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ButlerTest {
    private fun summary(
        id: String,
        global: Boolean? = null,
        project: Boolean? = null,
        updatedAt: Long = 0,
        activeAt: Long = 0,
        machineId: String? = "m1",
        path: String = "/home/jarvis",
        flavor: String? = "claude",
    ) = SessionSummary(
        id = id, active = true, activeAt = activeAt, updatedAt = updatedAt,
        pinned = project, globalPinned = global,
        metadata = SessionSummaryMetadata(path = path, machineId = machineId, flavor = flavor),
    )

    // ------------------------------------------------------------ resolve --

    @Test fun noGlobalPinMeansNoButler() {
        assertNull(resolveButler(emptyList()))
        assertNull(resolveButler(listOf(summary("a", project = true, updatedAt = 9), summary("b", updatedAt = 10))))
    }

    @Test fun newestGlobalPinWins() {
        val sessions = listOf(
            summary("old", global = true, updatedAt = 100),
            summary("project", project = true, updatedAt = 900),
            summary("new", global = true, updatedAt = 500),
            summary("plain", updatedAt = 1_000),
        )
        assertEquals("new", resolveButler(sessions)?.id)
    }

    @Test fun tiesFallBackToActiveAtThenId() {
        val byActive = listOf(summary("a", global = true, updatedAt = 1, activeAt = 5), summary("b", global = true, updatedAt = 1, activeAt = 3))
        assertEquals("a", resolveButler(byActive)?.id)
        val byId = listOf(summary("a", global = true, updatedAt = 1), summary("b", global = true, updatedAt = 1))
        assertEquals("b", resolveButler(byId)?.id)
    }

    @Test fun handoffShowsNewIdUntilTheListCatchesUp() {
        val handoff = "old" to "new"
        assertEquals("x", butlerIdWithHandoff("x", null))
        assertNull(butlerIdWithHandoff(null, null))
        assertEquals("new", butlerIdWithHandoff("old", handoff))
        // Old row unpinned optimistically, new row not listed yet.
        assertEquals("new", butlerIdWithHandoff(null, handoff))
        assertEquals("new", butlerIdWithHandoff("new", handoff))
        // Someone else became the butler meanwhile: the list wins.
        assertEquals("other", butlerIdWithHandoff("other", handoff))
    }

    // --------------------------------------------------------------- move --

    private class FakeGateway(
        val spawnResult: () -> SpawnResponse = { SpawnResponse(type = "success", sessionId = "new") },
        val pinFailures: MutableMap<Pair<String, String>, Int> = mutableMapOf(),
    ) : ButlerGateway {
        val calls = mutableListOf<String>()
        var lastRequest: Pair<String, SpawnSessionRequest>? = null

        override suspend fun spawn(machineId: String, request: SpawnSessionRequest): SpawnResponse {
            calls += "spawn"
            lastRequest = machineId to request
            return spawnResult()
        }

        override suspend fun setPinMode(sessionId: String, mode: String) {
            calls += "pin $sessionId $mode"
            val key = sessionId to mode
            val left = pinFailures[key] ?: 0
            if (left > 0) {
                pinFailures[key] = left - 1
                throw ApiError(status = 404, code = "not_found")
            }
        }
    }

    private fun move(gateway: FakeGateway, butler: SessionSummary = summary("old", global = true), harness: String = "codex") =
        runBlocking { ButlerMover(gateway, pinRetryDelayMs = 0).move(butler, harness) }

    @Test fun movesWithSameMachineAndFolderOnlyHarnessChanges() {
        val gateway = FakeGateway()
        assertEquals(MoveResult.Moved("new"), move(gateway))
        assertEquals(listOf("spawn", "pin new global", "pin old none"), gateway.calls)
        val (machineId, request) = gateway.lastRequest!!
        assertEquals("m1", machineId)
        assertEquals("/home/jarvis", request.directory)
        assertEquals("codex", request.agent)
        // The server/machine decides model, effort and permissions.
        assertNull(request.model)
        assertNull(request.effort)
        assertNull(request.modelReasoningEffort)
        assertNull(request.permissionMode)
        assertNull(request.yolo)
        assertEquals("simple", request.sessionType)
    }

    @Test fun missingMachineOrFolderStopsBeforeSpawning() {
        val gateway = FakeGateway()
        assertEquals(MoveResult.MissingLocation, move(gateway, summary("old", global = true, machineId = null)))
        assertEquals(MoveResult.MissingLocation, move(gateway, summary("old", global = true, path = " ")))
        assertTrue(gateway.calls.isEmpty())
    }

    @Test fun spawnErrorsLeaveTheButlerAlone() {
        val refused = FakeGateway(spawnResult = { SpawnResponse(type = "error", code = "agent_unavailable", message = "no codex") })
        assertEquals(MoveResult.SpawnFailed("agent_unavailable", "no codex"), move(refused))
        assertEquals(listOf("spawn"), refused.calls)

        val thrown = FakeGateway(spawnResult = { throw ApiError(status = 409, code = "runner_upgrade_required") })
        val result = move(thrown)
        assertTrue(result is MoveResult.SpawnFailed && result.code == "runner_upgrade_required", result.toString())
        assertEquals(listOf("spawn"), thrown.calls)
    }

    @Test fun pinIsRetriedOnceThenOldIsUnpinned() {
        val gateway = FakeGateway(pinFailures = mutableMapOf(("new" to "global") to 1))
        assertEquals(MoveResult.Moved("new"), move(gateway))
        assertEquals(listOf("spawn", "pin new global", "pin new global", "pin old none"), gateway.calls)
    }

    @Test fun pinFailureKeepsTheOldButlerPinned() {
        val gateway = FakeGateway(pinFailures = mutableMapOf(("new" to "global") to 5))
        val result = move(gateway)
        assertTrue(result is MoveResult.PinFailed && result.newSessionId == "new", result.toString())
        assertTrue("pin old none" !in gateway.calls)
    }

    @Test fun unpinFailureIsReportedAfterTheNewPin() {
        val gateway = FakeGateway(pinFailures = mutableMapOf(("old" to "none") to 5))
        val result = move(gateway)
        assertTrue(result is MoveResult.UnpinOldFailed && result.newSessionId == "new", result.toString())
        assertEquals("pin new global", gateway.calls[1])
    }
}
