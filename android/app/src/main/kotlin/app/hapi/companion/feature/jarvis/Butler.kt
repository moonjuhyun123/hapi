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
fun resolveButler(sessions: List<SessionSummary>): SessionSummary? =
    sessions.asSequence()
        .filter { it.globalPinned == true }
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
