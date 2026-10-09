package app.hapi.companion.feature.jarvis

import app.hapi.protocol.wire.SessionSummary

/*
 * Jarvis fork (docs/jarvis/CHANGES.md): the butler is one server-side session.
 * The server decides which session that is (by pinning it globally), which
 * harness/model it runs, and when to hand it over to another harness. The app
 * only finds it and shows it.
 */

/**
 * The butler = among globally pinned sessions, the one that moved last
 * (newest `updatedAt`, then `activeAt`, then id for a stable tie-break).
 * Null when nothing is globally pinned. The app never picks or creates one.
 */
fun resolveButler(sessions: List<SessionSummary>): SessionSummary? = latestPinned(sessions) { !isChatRoom(it) }

/**
 * The chat room (「잡담」) is a second fixed room next to the butler: same
 * butler, a separate thread for light talk so it doesn't pile onto the main
 * one. The server pins it globally too and names it [CHAT_ROOM_NAME]; a
 * hand-over keeps both (re-pins the new session and gives it the same name).
 */
fun resolveChatRoom(sessions: List<SessionSummary>): SessionSummary? = latestPinned(sessions, ::isChatRoom)

const val CHAT_ROOM_NAME = "잡담"

private fun isChatRoom(session: SessionSummary): Boolean = session.metadata?.name?.trim() == CHAT_ROOM_NAME

private fun latestPinned(sessions: List<SessionSummary>, keep: (SessionSummary) -> Boolean): SessionSummary? =
    sessions.asSequence()
        .filter { it.globalPinned == true && keep(it) }
        .maxWithOrNull(compareBy<SessionSummary>({ it.updatedAt }, { it.activeAt }, { it.id }))

/**
 * The id to show while a resume/reopen hands the chat to a new id
 * ([switch] = from → to) before the list says so: the list may still name the
 * old butler, or none at all. Any other resolved butler wins.
 */
fun butlerIdWithPendingSwitch(resolvedId: String?, switch: Pair<String, String>?): String? = when {
    switch == null -> resolvedId
    resolvedId == null || resolvedId == switch.first -> switch.second
    else -> resolvedId
}
