package app.hapi.companion.feature.jarvis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import app.hapi.companion.ChatViewModelHolder
import app.hapi.companion.R
import app.hapi.companion.Routes
import app.hapi.companion.di.AppGraph
import app.hapi.companion.di.HubGraph
import app.hapi.companion.di.viewModelFactory
import app.hapi.companion.feature.chat.ChatHost
import app.hapi.companion.feature.chat.ChatMedia
import app.hapi.companion.feature.chat.jarvis.ButlerMenu
import app.hapi.data.store.SessionListStore
import app.hapi.protocol.catalog.Flavors
import app.hapi.protocol.wire.SessionSummary
import app.hapi.protocol.wire.SpawnResponse
import app.hapi.protocol.wire.SpawnSessionRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** First list load for the butler screen. */
private enum class ButlerLoad { Loading, Loaded, Failed }

/** Butler chat ViewModels, one per butler session; outlives rotation with the nav entry. */
internal class ButlerChats : ViewModel() {
    private val stores = LinkedHashMap<String, ViewModelStore>()
    private val holders = HashMap<String, ChatViewModelHolder>()

    fun holder(key: String, create: () -> ChatViewModelHolder): ChatViewModelHolder =
        holders.getOrPut(key) {
            val store = stores.getOrPut(key) { ViewModelStore() }
            ViewModelProvider.create(store, viewModelFactory(create))[key, ChatViewModelHolder::class]
        }

    /** The live butler chat for [key], if one is shown (scratchlist "send to composer"). */
    fun existing(key: String): ChatViewModelHolder? = holders[key]

    /** The butler changed: close every other chat (drafts persist in their own store). */
    fun retainOnly(key: String?) {
        stores.keys.filter { it != key }.forEach { stale ->
            holders.remove(stale)
            stores.remove(stale)?.clear()
        }
    }

    override fun onCleared() {
        holders.clear()
        stores.values.forEach(ViewModelStore::clear)
        stores.clear()
    }
}

internal fun butlerChatKey(hubGraph: HubGraph, sessionId: String) = "butler-chat:${hubGraph.hubUrl}:$sessionId"

/** The butler's chat holder living under the `butler` route, if it is [sessionId]. */
internal fun butlerChatHolder(navController: NavHostController, hubGraph: HubGraph, sessionId: String): ChatViewModelHolder? {
    val entry = runCatching { navController.getBackStackEntry(Routes.BUTLER) }.getOrNull() ?: return null
    return ViewModelProvider(entry)["butler-chats", ButlerChats::class.java].existing(butlerChatKey(hubGraph, sessionId))
}

/** Current butler id from the cached list (notification taps reuse the butler view). */
internal fun currentButlerId(hubGraph: HubGraph?): String? = hubGraph?.let { resolveButler(it.sessionStore.sessions.value)?.id }

/**
 * Root screen once paired: the butler's conversation, straight away. No
 * session list, no harness/model choice. If nothing is globally pinned yet,
 * says so and offers the session list.
 */
@Composable
internal fun ButlerRoute(graph: AppGraph, hubGraph: HubGraph, navController: NavHostController) {
    val chats = viewModel<ButlerChats>(key = "butler-chats")
    val store = hubGraph.sessionStore
    val sessions by store.sessions.collectAsState()
    var load by remember(hubGraph) { mutableStateOf(ButlerLoad.Loading) }
    var refreshToken by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Pin changes arrive as full sessions or list refetches, not as patches:
    // refetch whenever the screen comes back to the foreground.
    LaunchedEffect(hubGraph, lifecycleOwner, refreshToken) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            load = refreshList(store, load)
        }
    }

    // A move or a resume/reopen can hand the butler to a new id before the
    // list says so (the old row is unpinned optimistically first).
    var superseded by remember(hubGraph) { mutableStateOf<Pair<String, String>?>(null) }
    val resolved = remember(sessions) { resolveButler(sessions) }
    val butlerId = butlerIdWithHandoff(resolved?.id, superseded)
    LaunchedEffect(resolved?.id) {
        // The list caught up (or moved on to another butler): drop the hand-off.
        if (superseded != null && resolved != null && resolved.id != superseded?.first) superseded = null
    }
    LaunchedEffect(butlerId) { chats.retainOnly(butlerId?.let { butlerChatKey(hubGraph, it) }) }

    var moveOpen by rememberSaveable { mutableStateOf(false) }
    val menu = remember(navController) {
        ButlerMenu(
            onOpenSessions = { navController.navigate(Routes.HOME) },
            onNewSession = { navController.navigate(Routes.newSession()) },
            onMoveHarness = { moveOpen = true },
        )
    }

    when {
        butlerId != null -> key(butlerId) {
            ButlerChat(graph, hubGraph, navController, chats, butlerId, menu) { next ->
                superseded = butlerId to next
                store.scheduleRefresh()
            }
        }
        load == ButlerLoad.Loading -> ButlerMessage(busy = true)
        else -> ButlerMessage(
            busy = false,
            failed = load == ButlerLoad.Failed,
            onOpenSessions = { navController.navigate(Routes.HOME) },
            onRetry = { refreshToken += 1 },
        )
    }

    val butler = sessions.firstOrNull { it.id == butlerId }
    LaunchedEffect(butler == null) { if (butler == null) moveOpen = false }
    if (moveOpen && butler != null) {
        ButlerMoveDialog(
            hubGraph = hubGraph,
            butler = butler,
            onDismiss = { moveOpen = false },
            onMoved = { newId ->
                moveOpen = false
                superseded = butler.id to newId
                store.scheduleRefresh()
            },
        )
    }
}

private suspend fun refreshList(store: SessionListStore, previous: ButlerLoad): ButlerLoad = try {
    store.refresh()
    ButlerLoad.Loaded
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: Exception) {
    // A failed foreground refresh keeps a list that already loaded.
    if (previous == ButlerLoad.Loaded) ButlerLoad.Loaded else ButlerLoad.Failed
}

@Composable
private fun ButlerChat(
    graph: AppGraph,
    hubGraph: HubGraph,
    navController: NavHostController,
    chats: ButlerChats,
    sessionId: String,
    menu: ButlerMenu,
    onSuperseded: (String) -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    // Same FCM suppress-when-open rule as the ordinary chat route.
    DisposableEffect(sessionId) {
        graph.openChatSessionId.value = sessionId
        onDispose {
            if (graph.openChatSessionId.value == sessionId) graph.openChatSessionId.value = null
        }
    }
    val holder = chats.holder(butlerChatKey(hubGraph, sessionId)) { ChatViewModelHolder(hubGraph, sessionId, appContext) }
    ChatHost(
        viewModel = holder.viewModel,
        media = remember(hubGraph, sessionId) {
            ChatMedia(hubGraph.imageLoader) { imageId -> hubGraph.generatedImageUrl(sessionId, imageId) }
        },
        // Root screen: system back leaves the app; a deleted butler simply
        // drops out of the resolved list.
        onBack = {},
        onNavigateToSession = onSuperseded,
        dictation = holder.dictation,
        onOpenFiles = { navController.navigate(Routes.files(sessionId)) },
        onOpenFile = { path, line -> navController.navigate(Routes.fileViewer(sessionId, path, mode = "file", line = line)) },
        onOpenScratchlist = { navController.navigate(Routes.scratchlist(sessionId)) },
        butlerMenu = menu,
    )
}

@Composable
private fun ButlerMessage(
    busy: Boolean,
    failed: Boolean = false,
    onOpenSessions: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp).testTag("butler-empty"),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (busy) {
                CircularProgressIndicator()
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.jarvis_butler_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                ButlerEmptyContent(failed, onOpenSessions, onRetry)
            }
        }
    }
}

@Composable
private fun ButlerEmptyContent(failed: Boolean, onOpenSessions: () -> Unit, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(if (failed) R.string.jarvis_butler_load_failed else R.string.jarvis_butler_none_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        if (!failed) {
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(R.string.jarvis_butler_none_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.size(24.dp))
        Button(onClick = onOpenSessions, modifier = Modifier.heightIn(min = 48.dp).testTag("butler-open-sessions")) {
            Text(stringResource(R.string.jarvis_butler_open_sessions))
        }
        TextButton(onClick = onRetry) { Text(stringResource(R.string.jarvis_butler_retry)) }
    }
}

/** Production [ButlerGateway]: spawn via the API, pin through the store (optimistic list update). */
private class HubButlerGateway(private val hubGraph: HubGraph) : ButlerGateway {
    override suspend fun spawn(machineId: String, request: SpawnSessionRequest): SpawnResponse =
        hubGraph.session.api.spawnSession(machineId, request)

    override suspend fun setPinMode(sessionId: String, mode: String) =
        hubGraph.sessionStore.setPinMode(sessionId, mode)
}

/**
 * "Move to another harness": machine and folder are the butler's (shown, not
 * editable); only the harness is chosen. No model choice — the server decides.
 */
@Composable
private fun ButlerMoveDialog(
    hubGraph: HubGraph,
    butler: SessionSummary,
    onDismiss: () -> Unit,
    onMoved: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val machines by hubGraph.machineStore.machines.collectAsState()
    val machineId = butler.metadata?.machineId
    val machineLabel = machines.firstOrNull { it.id == machineId }?.metadata
        ?.let { it.displayName?.takeIf(String::isNotBlank) ?: it.host } ?: machineId?.take(8) ?: "—"
    val current = butler.metadata?.flavor
    var choice by rememberSaveable(butler.id) { mutableStateOf(BUTLER_HARNESSES.firstOrNull { it != current }) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var finishedWithWarning by remember { mutableStateOf(false) }
    var unavailable by remember(machineId) { mutableStateOf(emptySet<String>()) }
    val context = LocalContext.current

    LaunchedEffect(machineId) {
        if (machineId == null) return@LaunchedEffect
        // Advisory only: if the check fails the spawn reports the problem.
        unavailable = runCatching { hubGraph.session.api.getMachineAgentAvailability(machineId) }.getOrNull()
            ?.agents?.filter { !it.available }?.map { it.agent }?.toSet().orEmpty()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.jarvis_move_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledValue(stringResource(R.string.jarvis_move_machine), machineLabel)
                LabeledValue(stringResource(R.string.jarvis_move_folder), butler.metadata?.path ?: "—")
                Text(stringResource(R.string.jarvis_move_harness), style = MaterialTheme.typography.labelLarge)
                Column(Modifier.selectableGroup()) {
                    BUTLER_HARNESSES.forEach { harness ->
                        val enabled = !busy && !finishedWithWarning && harness != current && harness !in unavailable
                        val note = when {
                            harness == current -> stringResource(R.string.jarvis_move_current)
                            harness in unavailable -> stringResource(R.string.jarvis_move_unavailable)
                            else -> null
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected = choice == harness, enabled = enabled, role = Role.RadioButton) { choice = harness }
                                .testTag("butler-move-$harness"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = choice == harness, onClick = null, enabled = enabled)
                            Text(
                                listOfNotNull(Flavors.label(harness), note?.let { "($it)" }).joinToString(" "),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.jarvis_move_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            if (finishedWithWarning) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.jarvis_move_close)) }
            } else {
                TextButton(
                    enabled = !busy && choice != null && choice != current && choice !in unavailable,
                    onClick = {
                        val harness = choice ?: return@TextButton
                        busy = true
                        error = null
                        scope.launch {
                            val result = ButlerMover(HubButlerGateway(hubGraph)).move(butler, harness)
                            busy = false
                            when (result) {
                                is MoveResult.Moved -> onMoved(result.newSessionId)
                                is MoveResult.UnpinOldFailed -> {
                                    error = context.getString(R.string.jarvis_move_unpin_failed)
                                    finishedWithWarning = true
                                    hubGraph.sessionStore.scheduleRefresh()
                                }
                                else -> error = moveErrorText(context, result)
                            }
                        }
                    },
                    modifier = Modifier.testTag("butler-move-confirm"),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.jarvis_move_confirm))
                }
            }
        },
        dismissButton = {
            if (!finishedWithWarning) {
                TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.jarvis_move_cancel)) }
            }
        },
    )
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun moveErrorText(context: android.content.Context, result: MoveResult): String = when (result) {
    MoveResult.MissingLocation -> context.getString(R.string.jarvis_move_missing_location)
    is MoveResult.PinFailed -> context.getString(R.string.jarvis_move_pin_failed)
    is MoveResult.SpawnFailed -> when (result.code) {
        "agent_unavailable" -> context.getString(R.string.new_session_error_selected_agent_unavailable)
        "runner_upgrade_required" -> context.getString(R.string.new_session_error_runner_upgrade_required)
        "outside_workspace_roots" -> context.getString(R.string.new_session_error_directory_outside_workspace_roots)
        else -> result.message ?: context.getString(R.string.new_session_error_create)
    }
    is MoveResult.UnpinOldFailed -> context.getString(R.string.jarvis_move_unpin_failed)
    is MoveResult.Moved -> ""
}
