package app.hapi.companion.feature.chat.jarvis

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.hapi.protocol.window.WindowMessage
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/*
 * 「화면 비우기」 (Jarvis fork, docs/jarvis/CHANGES.md step 12): hides what is
 * above on the butler screen. View only — the butler's context, the hub's
 * messages and the hand-over chain are untouched (주현님 10-09 「위에 대화가
 * 너저분해서 그거 날리고 싶다」, not a context reset). The floor is the newest
 * delivered row's position time at the moment of clearing — hub clock, so the
 * phone's clock can't hide a reply that lands right after.
 */

private val Context.jarvisViewStore: DataStore<Preferences> by preferencesDataStore(name = "jarvis_view")

/** One butler room, one floor (it spans hand-over sessions). */
class ScreenClearPrefs(context: Context) {
    private val store = context.applicationContext.jarvisViewStore

    val clearedThrough: Flow<Long?> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[CLEARED_THROUGH] }

    suspend fun set(value: Long?) {
        store.edit { if (value == null) it.remove(CLEARED_THROUGH) else it[CLEARED_THROUGH] = value }
    }

    private companion object {
        val CLEARED_THROUGH = longPreferencesKey("cleared_through")
    }
}

/** Rows still shown under a floor: strictly newer than it. */
fun afterClear(rows: List<WindowMessage>, clearedThrough: Long?): List<WindowMessage> =
    if (clearedThrough == null) rows else rows.filter { it.positionAt > clearedThrough }

/** A loaded row sits at or under the floor: everything older is hidden, so paging stops here. */
fun reachedClear(rows: List<WindowMessage>, clearedThrough: Long?): Boolean =
    clearedThrough != null && rows.any { it.positionAt <= clearedThrough }

/** Floor for clearing now: the newest delivered row (optimistic rows carry the phone's clock). */
fun clearPoint(rows: List<WindowMessage>): Long? =
    rows.filter { !it.isOptimistic && !it.isQueuedForInvocation }.maxOfOrNull { it.positionAt }
