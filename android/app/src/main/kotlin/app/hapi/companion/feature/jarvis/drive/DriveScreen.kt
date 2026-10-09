package app.hapi.companion.feature.jarvis.drive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hapi.companion.R
import app.hapi.companion.feature.files.FolderGlyph
import app.hapi.companion.feature.files.formatFileMetadata
import app.hapi.companion.ui.theme.hapi
import app.hapi.protocol.wire.FileSearchItem

/**
 * Drive-style files screen for the butler (step 9): a search box on top, then
 * either search results (while typing) or the current folder with breadcrumbs.
 * Tapping a folder steps in; system back clears the search, then goes up a
 * folder, then leaves. Files open in the upstream file viewer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DriveScreen(viewModel: DriveViewModel, onBack: () -> Unit, onOpenFile: (path: String) -> Unit) {
    DisposableEffect(viewModel) {
        viewModel.start()
        onDispose { }
    }
    val state by viewModel.state.collectAsState()
    BackHandler(enabled = state.query.isNotEmpty() || state.path.isNotEmpty()) { viewModel.back() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (!viewModel.back()) onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.files_back))
                    }
                },
                title = { Text(stringResource(R.string.jarvis_drive_title)) },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.files_refresh))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.jarvis_drive_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (state.query.isNotEmpty()) {
                    {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(ClearGlyph, contentDescription = stringResource(R.string.jarvis_drive_clear))
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
            )
            if (state.query.isBlank()) FolderView(state, viewModel::open, onOpenFile) else SearchView(state, onOpenFile)
        }
    }
}

@Composable
private fun FolderView(state: DriveUiState, onOpenFolder: (String) -> Unit, onOpenFile: (String) -> Unit) {
    Breadcrumbs(state.path, onOpenFolder)
    when {
        state.loading -> Centered { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) }
        state.error != null -> Centered { Text(state.error.ifEmpty { stringResource(R.string.jarvis_drive_error) }, color = MaterialTheme.colorScheme.error) }
        state.entries.isEmpty() -> Centered { Text(stringResource(R.string.jarvis_drive_empty), color = MaterialTheme.hapi.hint) }
        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.entries, key = { it.path }) { entry ->
                EntryRow(
                    glyph = if (entry.isDir) FolderGlyph else FileGlyph,
                    folder = entry.isDir,
                    title = entry.name,
                    subtitle = if (entry.isDir) null else formatFileMetadata(entry.size, entry.modified),
                ) { if (entry.isDir) onOpenFolder(entry.path) else onOpenFile(entry.path) }
            }
        }
    }
}

@Composable
private fun SearchView(state: DriveUiState, onOpenFile: (String) -> Unit) {
    when {
        state.searching && state.results.isEmpty() -> Centered { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) }
        state.searchError != null -> Centered { Text(state.searchError.ifEmpty { stringResource(R.string.jarvis_drive_error) }, color = MaterialTheme.colorScheme.error) }
        state.searched && state.results.isEmpty() -> Centered { Text(stringResource(R.string.jarvis_drive_no_match), color = MaterialTheme.hapi.hint) }
        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.results, key = { it.fullPath }) { item: FileSearchItem ->
                EntryRow(
                    glyph = FileGlyph,
                    folder = false,
                    title = item.fileName,
                    subtitle = item.filePath.ifEmpty { null },
                ) { onOpenFile(item.fullPath) }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(path: String, onOpen: (String) -> Unit) {
    val hint = MaterialTheme.hapi.hint
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val crumbs = listOf(stringResource(R.string.jarvis_drive_title) to "") + driveCrumbs(path)
        crumbs.forEachIndexed { i, (label, target) ->
            if (i > 0) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = hint, modifier = Modifier.size(16.dp))
            val last = i == crumbs.lastIndex
            Text(
                text = label,
                fontSize = 13.sp,
                color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = if (last) Modifier.padding(vertical = 6.dp) else Modifier.clickable { onOpen(target) }.padding(vertical = 6.dp, horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun EntryRow(glyph: ImageVector, folder: Boolean, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            glyph,
            contentDescription = null,
            tint = if (folder) MaterialTheme.colorScheme.primary else MaterialTheme.hapi.hint,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.hapi.hint, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}

private fun glyph(name: String, pathData: String): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .apply {
            addPath(
                pathData = addPathNodes(pathData),
                fill = null,
                stroke = SolidColor(Color.Black), // Icon() recolors via tint
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

/** Page with a folded corner. */
private val FileGlyph: ImageVector by lazy { glyph("JarvisFile", "M7 3h7l5 5v12a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z M14 3v5h5") }

private val ClearGlyph: ImageVector by lazy { glyph("JarvisClear", "M6 6l12 12 M18 6L6 18") }
