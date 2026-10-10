package app.hapi.companion.feature.jarvis.activity

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * The only platform reads for phone activity: the usage-access check and the
 * raw event list. Needs `PACKAGE_USAGE_STATS`, which the user grants once in
 * system settings (「사용 기록 접근」) — no other permission is used.
 */
internal object UsageReader {

    fun hasPermission(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun read(context: Context, begin: Long, end: Long): List<UsageEvent> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val events = usm.queryEvents(begin, end) ?: return emptyList()
        val event = UsageEvents.Event()
        val out = mutableListOf<UsageEvent>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType in UsageEventTypes.READ) {
                out += UsageEvent(event.timeStamp, event.eventType, event.packageName.orEmpty())
            }
        }
        return out
    }

    /**
     * Human label when the package is visible to us; Android 11+ hides most
     * packages without QUERY_ALL_PACKAGES, which we deliberately don't ask for,
     * so the package name itself is the fallback (the server can map it).
     */
    fun label(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }
}
