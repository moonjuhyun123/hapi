package app.hapi.companion.feature.jarvis.workout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hapi.companion.R
import app.hapi.companion.ui.theme.hapi
import coil.compose.AsyncImage
import coil.request.ImageRequest

/** Step 17: the 「운동」 tab — tick exercises, adjust prefilled sets, save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkoutScreen(viewModel: WorkoutViewModel) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) { if (state.form == null) viewModel.load() }
    val snackbar = remember { SnackbarHostState() }
    val savedText = stringResource(R.string.jarvis_workout_saved)
    LaunchedEffect(state.savedCount) { if (state.savedCount > 0) snackbar.showSnackbar(savedText) }

    Scaffold(
        modifier = Modifier.imePadding(), // typing a set keeps the save bar above the keyboard
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.jarvis_workout_title))
                        state.form?.dateLabel?.takeIf { it.isNotEmpty() }?.let {
                            Text(it, fontSize = 12.sp, color = MaterialTheme.hapi.hint)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::load) { Icon(Icons.Filled.Refresh, contentDescription = null) }
                },
            )
        },
        bottomBar = {
            if (state.form != null) {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        state.saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                        Button(onClick = viewModel::save, enabled = state.canSave, modifier = Modifier.fillMaxWidth()) {
                            if (state.saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text(stringResource(R.string.jarvis_workout_save))
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val form = state.form
            when {
                form == null && state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                form == null -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::load) { Text(stringResource(R.string.jarvis_workout_retry)) }
                }
                else -> WorkoutList(form, state, viewModel)
            }
        }
    }
}

@Composable
private fun WorkoutList(form: WorkoutForm, state: WorkoutUiState, viewModel: WorkoutViewModel) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (form.summary.isNotEmpty()) {
            item(key = "summary") {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.jarvis_workout_today), style = MaterialTheme.typography.labelLarge)
                        form.summary.forEach { Text(it, fontSize = 13.sp) }
                    }
                }
            }
        }
        if (form.recency.isNotEmpty()) {
            item(key = "recency") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    form.recency.forEach { Text("${it.title} ${it.label}", fontSize = 12.sp, color = MaterialTheme.hapi.hint) }
                }
            }
        }
        item(key = "place") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PLACES.forEach { p ->
                    FilterChip(selected = state.place == p, onClick = { viewModel.setPlace(p) }, label = { Text(p) })
                }
            }
        }
        val sections = form.groups.map { it.title to it.chips.map { c -> c.name } } +
            (if (form.extras.isNotEmpty()) listOf("extras" to form.extras.map { it.name }) else emptyList())
        sections.forEach { (title, names) ->
            item(key = "h:$title") {
                Text(
                    if (title == "extras") stringResource(R.string.jarvis_workout_extras) else title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp),
                )
            }
            items(names, key = { "c:$title:$it" }) { name ->
                state.chips[name]?.let { ExerciseRow(it, state.entrance, viewModel) }
            }
        }
        item(key = "note") {
            OutlinedTextField(
                value = state.note,
                onValueChange = viewModel::setNote,
                label = { Text(stringResource(R.string.jarvis_workout_note)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }
}

@Composable
private fun ExerciseRow(chip: WorkoutChip, entrance: Pair<String, String>?, viewModel: WorkoutViewModel) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { viewModel.toggle(chip.name) }.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Photo(chip.images.firstOrNull(), entrance, Modifier.size(52.dp))
            Column(Modifier.weight(1f)) {
                Text(chip.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (chip.hint.isNotEmpty()) {
                    Text(chip.hint, fontSize = 12.sp, color = MaterialTheme.hapi.hint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Checkbox(checked = chip.checked, onCheckedChange = { viewModel.toggle(chip.name) })
        }
        if (chip.checked) {
            Column(Modifier.padding(start = 80.dp, end = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (chip.images.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        chip.images.take(2).forEach { Photo(it, entrance, Modifier.size(width = 120.dp, height = 90.dp)) }
                    }
                }
                if (chip.isFree) {
                    OutlinedTextField(
                        value = chip.free,
                        onValueChange = { viewModel.setFree(chip.name, it) },
                        placeholder = { Text(stringResource(R.string.jarvis_workout_free_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    chip.sets.forEachIndexed { i, row ->
                        SetRowEditor(i, row, onChange = { viewModel.setRow(chip.name, i, it) }, onRemove = { viewModel.removeSet(chip.name, i) })
                    }
                    TextButton(onClick = { viewModel.addSet(chip.name) }) { Text(stringResource(R.string.jarvis_workout_add_set)) }
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 80.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}

@Composable
private fun SetRowEditor(index: Int, row: SetRow, onChange: (SetRow) -> Unit, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${index + 1}", modifier = Modifier.width(18.dp), color = MaterialTheme.hapi.hint)
        OutlinedTextField(
            value = row.w,
            onValueChange = { onChange(row.copy(w = it)) },
            suffix = { Text(stringResource(R.string.jarvis_workout_kg)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(104.dp),
        )
        Text("×")
        OutlinedTextField(
            value = row.r,
            onValueChange = { onChange(row.copy(r = it)) },
            suffix = { Text(stringResource(R.string.jarvis_workout_reps)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(88.dp),
        )
        TextButton(onClick = onRemove) { Text("✕") }
    }
}

@Composable
private fun Photo(path: String?, entrance: Pair<String, String>?, modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    if (path == null || entrance == null) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = shape, modifier = modifier) {}
        return
    }
    val context = LocalContext.current
    val request = remember(path, entrance) {
        ImageRequest.Builder(context)
            .data(entrance.first + path)
            .addHeader("Authorization", "Bearer ${entrance.second}")
            .crossfade(true)
            .build()
    }
    AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.clip(shape))
}

@Composable
internal fun WorkoutTab() {
    val context = LocalContext.current.applicationContext
    val holder = androidx.lifecycle.viewmodel.compose.viewModel<WorkoutViewModelHolder>(
        key = "jarvis-workout",
        factory = app.hapi.companion.di.viewModelFactory { WorkoutViewModelHolder(HttpWorkoutGateway(context)) },
    )
    WorkoutScreen(holder.viewModel)
}

