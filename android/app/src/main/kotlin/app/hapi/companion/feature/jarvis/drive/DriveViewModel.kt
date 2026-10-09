package app.hapi.companion.feature.jarvis.drive

import app.hapi.companion.feature.files.FilesGateway
import app.hapi.protocol.wire.FileSearchItem
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「드라이브」 (Jarvis fork, docs/jarvis/CHANGES.md step 9): the butler's files
 * as a Drive-style browser — step into folders (breadcrumbs, back goes up)
 * and one search box over the whole session root. Replaces the upstream
 * Changes / Browse / Search tabs for the butler only (주현님 10-09 「세션 파일은
 * 이름을 드라이브로 바꾸고 변경사항은 빼, 찾아보기 검색은 하나로 합쳐서 드라이브처럼」).
 * Reuses [FilesGateway]; nothing new on the hub.
 */
data class DriveEntry(
    val name: String,
    /** Session-relative path, `""`-rooted (`jarvis/overhaul`). */
    val path: String,
    val isDir: Boolean,
    val size: Long? = null,
    val modified: Long? = null,
)

data class DriveUiState(
    /** Current folder, `""` = session root. */
    val path: String = "",
    val entries: List<DriveEntry> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val query: String = "",
    val results: List<FileSearchItem> = emptyList(),
    val searching: Boolean = false,
    /** True once a non-blank query has come back (empty results ≠ not searched yet). */
    val searched: Boolean = false,
    val searchError: String? = null,
)

/** Breadcrumb segments of [path] as (label, path); the root itself is not included. */
fun driveCrumbs(path: String): List<Pair<String, String>> {
    if (path.isEmpty()) return emptyList()
    val parts = path.split('/').filter { it.isNotEmpty() }
    return parts.indices.map { i -> parts[i] to parts.take(i + 1).joinToString("/") }
}

/** Parent of [path]; root stays root. */
fun driveParent(path: String): String = path.substringBeforeLast('/', missingDelimiterValue = "")

/** Folders first, then files, each by name ignoring case; dot-entries hidden like Drive. */
fun driveSort(raw: List<DriveEntry>): List<DriveEntry> =
    raw.filter { !it.name.startsWith(".") }
        .sortedWith(compareBy<DriveEntry>({ !it.isDir }, { it.name.lowercase() }, { it.name }))

class DriveViewModel(
    private val sessionId: String,
    private val gateway: FilesGateway,
    private val scope: CoroutineScope,
    private val searchDebounceMs: Long = 250,
) {
    private val stateFlow = MutableStateFlow(DriveUiState())
    val state: StateFlow<DriveUiState> = stateFlow.asStateFlow()

    private val queryInput = MutableStateFlow("")
    private var listJob: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        open("")
        scope.launch {
            queryInput.collectLatest { query ->
                if (query.isBlank()) {
                    stateFlow.update { it.copy(results = emptyList(), searching = false, searched = false, searchError = null) }
                    return@collectLatest
                }
                delay(searchDebounceMs)
                runSearch(query)
            }
        }
    }

    fun open(path: String) {
        stateFlow.update { it.copy(path = path, entries = emptyList(), loading = true, error = null) }
        listJob?.cancel()
        listJob = scope.launch {
            val next = try {
                val response = gateway.listDirectory(sessionId, path.ifEmpty { null })
                if (response.success) {
                    val entries = response.entries.orEmpty().mapNotNull { entry ->
                        val child = if (path.isEmpty()) entry.name else "$path/${entry.name}"
                        when (entry.type) {
                            "directory" -> DriveEntry(entry.name, child, isDir = true, modified = entry.modified)
                            "file" -> DriveEntry(entry.name, child, isDir = false, size = entry.size, modified = entry.modified)
                            else -> null // sockets, links… dropped like the upstream tree
                        }
                    }
                    stateFlow.value.copy(entries = driveSort(entries), loading = false, error = null)
                } else {
                    stateFlow.value.copy(loading = false, error = response.error ?: "")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stateFlow.value.copy(loading = false, error = e.message ?: "")
            }
            // A newer open() may have moved on; only apply to the folder still shown.
            if (stateFlow.value.path == path) stateFlow.value = next
        }
    }

    /** Back: clear an active search first, then go up a folder. False at the root with no search. */
    fun back(): Boolean = when {
        stateFlow.value.query.isNotEmpty() -> { setQuery(""); true }
        stateFlow.value.path.isNotEmpty() -> { open(driveParent(stateFlow.value.path)); true }
        else -> false
    }

    fun refresh() {
        val query = stateFlow.value.query
        if (query.isNotBlank()) scope.launch { runSearch(query) } else open(stateFlow.value.path)
    }

    fun setQuery(query: String) {
        stateFlow.update { it.copy(query = query) }
        queryInput.value = query
    }

    private suspend fun runSearch(query: String) {
        stateFlow.update { it.copy(searching = true, searchError = null) }
        try {
            val response = gateway.searchFiles(sessionId, query, SEARCH_LIMIT)
            stateFlow.update {
                if (response.success) {
                    it.copy(results = response.files.orEmpty(), searching = false, searched = true)
                } else {
                    it.copy(results = emptyList(), searching = false, searched = true, searchError = response.error ?: "")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            stateFlow.update { it.copy(searching = false, searched = true, searchError = e.message ?: "") }
        }
    }

    private companion object {
        const val SEARCH_LIMIT = 200
    }
}
