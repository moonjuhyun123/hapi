package app.hapi.companion.feature.jarvis

import app.hapi.protocol.wire.SessionSummary
import app.hapi.protocol.wire.SessionSummaryMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        name: String? = null,
    ) = SessionSummary(
        id = id, active = true, activeAt = activeAt, updatedAt = updatedAt,
        pinned = project, globalPinned = global,
        metadata = SessionSummaryMetadata(name = name, path = path, machineId = machineId, flavor = flavor),
    )

    // ------------------------------------------------------------ resolve --

    @Test fun chatRoomIsNeverTheButler() {
        // 잡담 moved last, but the butler is still the other pinned session.
        val sessions = listOf(summary("butler", global = true, updatedAt = 5, name = "집사"), summary("chat", global = true, updatedAt = 9, name = "잡담"))
        assertEquals("butler", resolveButler(sessions)?.id)
        assertEquals("chat", resolveChatRoom(sessions)?.id)
    }

    @Test fun chatRoomNeedsTheNameAndTheGlobalPin() {
        assertNull(resolveChatRoom(listOf(summary("a", global = true, name = "집사"))))
        assertNull(resolveChatRoom(listOf(summary("b", project = true, name = "잡담"))))
        assertEquals("c", resolveChatRoom(listOf(summary("c", global = true, name = " 잡담 ")))?.id)
    }

    @Test fun chatRoomFollowsAHandOver() {
        // Hand-over: the new session is pinned and named 잡담 too, the old pin may linger briefly.
        val sessions = listOf(summary("old", global = true, updatedAt = 3, name = "잡담"), summary("new", global = true, updatedAt = 8, name = "잡담"))
        assertEquals("new", resolveChatRoom(sessions)?.id)
        assertNull(resolveButler(sessions))
    }

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

    @Test fun pendingSwitchShowsNewIdUntilTheListCatchesUp() {
        val switch = "old" to "new"
        assertEquals("x", butlerIdWithPendingSwitch("x", null))
        assertNull(butlerIdWithPendingSwitch(null, null))
        assertEquals("new", butlerIdWithPendingSwitch("old", switch))
        assertEquals("new", butlerIdWithPendingSwitch(null, switch))
        assertEquals("new", butlerIdWithPendingSwitch("new", switch))
        // Someone else became the butler meanwhile: the list wins.
        assertEquals("other", butlerIdWithPendingSwitch("other", switch))
    }

    @Test fun rightAfterAServerHandOverTheNewerPinWins() {
        // The server pins the new session before unpinning the old one.
        val sessions = listOf(
            summary("before", global = true, updatedAt = 1_000, flavor = "claude"),
            summary("after", global = true, updatedAt = 1_005, flavor = "codex"),
        )
        assertEquals("after", resolveButler(sessions)?.id)
    }
}
