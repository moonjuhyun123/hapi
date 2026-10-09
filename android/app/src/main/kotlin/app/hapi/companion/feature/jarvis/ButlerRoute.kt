package app.hapi.companion.feature.jarvis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import app.hapi.companion.feature.jarvis.activity.PhoneActivityDialog
import app.hapi.companion.feature.jarvis.activity.PhoneActivityWorker
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
import kotlinx.coroutines.CancellationException

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

    /**
     * The butler changed (e.g. the server handed it to another harness):
     * close every other chat and carry an unsent draft into the new one, so
     * the switch does not eat what was being typed.
     */
    fun retainOnly(key: String?) {
        val hub = key?.substringBeforeLast(':')
        val carried = stores.keys.filter { it != key }.mapNotNull { stale ->
            val draft = holders.remove(stale)?.viewModel?.composer?.value?.text
            stores.remove(stale)?.clear()
            // Same hub only: a hub switch must not move text between hubs.
            draft?.takeIf { it.isNotBlank() && stale.substringBeforeLast(':') == hub }
        }
        val target = key?.let(holders::get) ?: return
        carried.forEach(target.viewModel::insertComposerText)
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

/** The two fixed rooms (주현님 10-09 「세션 목록이랑 새 세션 필요없음 그냥 잡담 하나」). */
internal enum class Room { BUTLER, CHAT }

/** Which room the root screen shows — app-wide so a notification tap can pick the room. */
internal object JarvisRoom {
    val current = MutableStateFlow(Room.BUTLER)
}

/**
 * Notification / list taps: if [sessionId] is one of the two rooms, switch the
 * root screen to that room and return true (the caller pops back to it).
 */
internal fun openRoomFor(hubGraph: HubGraph?, sessionId: String): Boolean {
    val sessions = hubGraph?.sessionStore?.sessions?.value ?: return false
    JarvisRoom.current.value = when (sessionId) {
        resolveButler(sessions)?.id -> Room.BUTLER
        resolveChatRoom(sessions)?.id -> Room.CHAT
        else -> return false
    }
    return true
}

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

    // A resume/reopen can hand the butler to a new id before the list says so.
    // (Harness hand-overs are the server's: it re-pins and the list follows.)
    val room by JarvisRoom.current.collectAsState()
    var superseded by remember(hubGraph, room) { mutableStateOf<Pair<String, String>?>(null) }
    val resolved = remember(sessions, room) { if (room == Room.CHAT) resolveChatRoom(sessions) else resolveButler(sessions) }
    val butlerId = butlerIdWithPendingSwitch(resolved?.id, superseded)
    LaunchedEffect(resolved?.id) {
        // The list caught up (or moved on to another butler): drop the hand-off.
        if (superseded != null && resolved != null && resolved.id != superseded?.first) superseded = null
    }
    LaunchedEffect(butlerId) { chats.retainOnly(butlerId?.let { butlerChatKey(hubGraph, it) }) }

    // Step 7: phone activity rides along with the butler — schedule on open, settings in ⋮.
    val context = LocalContext.current
    LaunchedEffect(Unit) { PhoneActivityWorker.onAppOpened(context) }
    var phoneActivityOpen by remember { mutableStateOf(false) }
    if (phoneActivityOpen) PhoneActivityDialog(onDismiss = { phoneActivityOpen = false })

    // The server makes the chat room; until it exists, stay on the butler.
    LaunchedEffect(room, resolved, load) {
        if (room == Room.CHAT && resolved == null && load == ButlerLoad.Loaded) JarvisRoom.current.value = Room.BUTLER
    }

    val butlerTitle = stringResource(R.string.jarvis_butler_title)
    val chatTitle = stringResource(R.string.jarvis_room_chat)
    val menu = remember(room, butlerTitle, chatTitle) {
        val inChat = room == Room.CHAT
        ButlerMenu(
            title = if (inChat) chatTitle else butlerTitle,
            otherRoom = if (inChat) butlerTitle else chatTitle,
            onOtherRoom = { JarvisRoom.current.value = if (inChat) Room.BUTLER else Room.CHAT },
            onPhoneActivity = { phoneActivityOpen = true },
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
