package app.hapi.companion.feature.jarvis.activity

/**
 * Phone activity, pure part (Jarvis fork, docs/jarvis/CHANGES.md step 7).
 * Turns the OS usage events (UsageStatsManager) into foreground-app spans and
 * screen-on spans for one send window. No Android types here so it runs as a
 * JVM test; [UsageReader] copies the platform events into [UsageEvent].
 *
 * Only package names and times are kept — no window titles, notifications or
 * message text (정본 10장 「새 입력 — PC·폰 활동」 [새-37]).
 */
data class UsageEvent(val timeMs: Long, val type: Int, val pkg: String)

data class AppSpan(val start: Long, val end: Long, val pkg: String)

data class ScreenSpan(val start: Long, val end: Long)

/** The `UsageEvents.Event` type codes we read (values are part of the public SDK). */
object UsageEventTypes {
    const val RESUMED = 1 // ACTIVITY_RESUMED (formerly MOVE_TO_FOREGROUND)
    const val PAUSED = 2 // ACTIVITY_PAUSED (formerly MOVE_TO_BACKGROUND)
    const val SCREEN_ON = 15 // SCREEN_INTERACTIVE
    const val SCREEN_OFF = 16 // SCREEN_NON_INTERACTIVE
    const val STOPPED = 23 // ACTIVITY_STOPPED
    const val SHUTDOWN = 26 // DEVICE_SHUTDOWN
    val READ = setOf(RESUMED, PAUSED, SCREEN_ON, SCREEN_OFF, STOPPED, SHUTDOWN)
}

/** Activity switches inside one app pause and resume a few ms apart — glue them. */
internal const val MERGE_GAP_MS = 2_000L

/** Shorter flickers (launcher passing through, transient dialogs) are noise. */
internal const val MIN_SPAN_MS = 1_000L

/**
 * Builds spans clipped to `[from, to)`. [events] may start before [from] (the
 * reader looks back an hour) so an app already in front when the window opens
 * is still seen; anything still open at [to] is cut there and picked up again
 * by the next window's look-back.
 */
fun buildSpans(events: List<UsageEvent>, from: Long, to: Long): Pair<List<AppSpan>, List<ScreenSpan>> {
    val apps = mutableListOf<AppSpan>()
    val screens = mutableListOf<ScreenSpan>()
    var fgPkg: String? = null
    var fgStart = 0L
    var screenStart: Long? = null

    fun closeApp(at: Long) {
        val pkg = fgPkg ?: return
        apps += AppSpan(fgStart, at, pkg)
        fgPkg = null
    }

    fun closeScreen(at: Long) {
        val start = screenStart ?: return
        screens += ScreenSpan(start, at)
        screenStart = null
    }

    for (e in events.sortedBy { it.timeMs }) {
        if (e.timeMs >= to) break
        when (e.type) {
            UsageEventTypes.RESUMED -> if (fgPkg != e.pkg) {
                closeApp(e.timeMs)
                fgPkg = e.pkg
                fgStart = e.timeMs
            }
            UsageEventTypes.PAUSED, UsageEventTypes.STOPPED -> if (fgPkg == e.pkg) closeApp(e.timeMs)
            UsageEventTypes.SCREEN_ON -> if (screenStart == null) screenStart = e.timeMs
            UsageEventTypes.SCREEN_OFF, UsageEventTypes.SHUTDOWN -> {
                closeApp(e.timeMs)
                closeScreen(e.timeMs)
            }
        }
    }
    closeApp(to)
    closeScreen(to)

    return mergeAndClip(apps, from, to) to screens.mapNotNull { clip(it.start, it.end, from, to)?.let { (s, t) -> ScreenSpan(s, t) } }
}

private fun clip(start: Long, end: Long, from: Long, to: Long): Pair<Long, Long>? {
    val s = maxOf(start, from)
    val t = minOf(end, to)
    return if (t > s) s to t else null
}

private fun mergeAndClip(spans: List<AppSpan>, from: Long, to: Long): List<AppSpan> {
    val merged = mutableListOf<AppSpan>()
    for (span in spans) {
        val last = merged.lastOrNull()
        if (last != null && last.pkg == span.pkg && span.start - last.end <= MERGE_GAP_MS) {
            merged[merged.lastIndex] = last.copy(end = maxOf(last.end, span.end))
        } else {
            merged += span
        }
    }
    return merged.mapNotNull { span ->
        clip(span.start, span.end, from, to)
            ?.takeIf { (s, t) -> t - s >= MIN_SPAN_MS }
            ?.let { (s, t) -> AppSpan(s, t, span.pkg) }
    }
}
