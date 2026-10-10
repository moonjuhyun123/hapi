package app.hapi.companion.feature.jarvis.activity

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Its own file, not `hapi_prefs`: two DataStores on one file would crash, and this keeps the fork apart. */
private val Context.jarvisActivityStore: DataStore<Preferences> by preferencesDataStore(name = "jarvis_activity")

enum class SendStatus { NONE, SENT, NO_PERMISSION, FAILED }

data class ActivitySettings(
    val enabled: Boolean = false,
    /** Collection entrance (not the HAPI hub), e.g. `https://hapi.itmoon.site`. */
    val url: String = "",
    val token: String = "",
    /** End of the last window the server accepted; the next send starts here. */
    val lastSentTo: Long = 0L,
    val status: SendStatus = SendStatus.NONE,
    val statusAt: Long = 0L,
    /** Span count after a send, HTTP code (or -1 network) after a failure. */
    val statusDetail: Long = 0L,
)

class ActivityPrefs(context: Context) {
    private val store = context.applicationContext.jarvisActivityStore

    val settings: Flow<ActivitySettings> = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { p ->
            ActivitySettings(
                enabled = p[ENABLED] ?: false,
                url = p[URL] ?: "",
                token = p[TOKEN] ?: "",
                lastSentTo = p[LAST_SENT_TO] ?: 0L,
                status = SendStatus.entries.firstOrNull { it.name == p[STATUS] } ?: SendStatus.NONE,
                statusAt = p[STATUS_AT] ?: 0L,
                statusDetail = p[STATUS_DETAIL] ?: 0L,
            )
        }

    suspend fun current(): ActivitySettings = settings.first()

    suspend fun setEnabled(value: Boolean) = store.edit { it[ENABLED] = value }

    suspend fun setUrl(value: String) = store.edit { it[URL] = value.trim().trimEnd('/') }

    suspend fun setToken(value: String) = store.edit { it[TOKEN] = value.trim() }

    suspend fun markSent(to: Long, spans: Int, now: Long) = store.edit {
        it[LAST_SENT_TO] = to
        it[STATUS] = SendStatus.SENT.name
        it[STATUS_AT] = now
        it[STATUS_DETAIL] = spans.toLong()
    }

    suspend fun markStatus(status: SendStatus, now: Long, detail: Long = 0L) = store.edit {
        it[STATUS] = status.name
        it[STATUS_AT] = now
        it[STATUS_DETAIL] = detail
    }

    /** Stable per install; lets the server tell phones apart without any hardware id. */
    suspend fun deviceId(): String {
        store.data.first()[DEVICE_ID]?.let { return it }
        val id = UUID.randomUUID().toString()
        store.edit { if (it[DEVICE_ID] == null) it[DEVICE_ID] = id }
        return store.data.first()[DEVICE_ID] ?: id
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val URL = stringPreferencesKey("url")
        val TOKEN = stringPreferencesKey("token")
        val LAST_SENT_TO = longPreferencesKey("last_sent_to")
        val STATUS = stringPreferencesKey("status")
        val STATUS_AT = longPreferencesKey("status_at")
        val STATUS_DETAIL = longPreferencesKey("status_detail")
        val DEVICE_ID = stringPreferencesKey("device_id")
    }
}
