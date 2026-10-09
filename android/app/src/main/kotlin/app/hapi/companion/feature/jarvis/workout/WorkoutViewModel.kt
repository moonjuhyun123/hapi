package app.hapi.companion.feature.jarvis.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WorkoutUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val form: WorkoutForm? = null,
    /** Screen order of chip names (groups, then extras). */
    val order: List<String> = emptyList(),
    val chips: Map<String, WorkoutChip> = emptyMap(),
    val place: String = PLACE_GYM,
    val note: String = "",
    val saving: Boolean = false,
    val saveError: String? = null,
    /** Bumps on each successful save (snackbar trigger). */
    val savedCount: Int = 0,
    /** Entrance address and token for photo requests. */
    val entrance: Pair<String, String>? = null,
) {
    val canSave: Boolean get() = !saving && chips.values.any { it.checked }
}

class WorkoutViewModel(private val gateway: WorkoutGateway, private val scope: CoroutineScope) {
    private val stateFlow = MutableStateFlow(WorkoutUiState())
    val state: StateFlow<WorkoutUiState> = stateFlow.asStateFlow()

    fun load() {
        stateFlow.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val entrance = gateway.entrance()
                apply(gateway.form())
                stateFlow.update { it.copy(entrance = entrance) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stateFlow.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun apply(form: WorkoutForm) {
        val all = form.allChips()
        stateFlow.update {
            it.copy(loading = false, error = null, form = form, order = all.map { c -> c.name }, chips = all.associateBy { c -> c.name })
        }
    }

    fun toggle(name: String) = stateFlow.update { it.copy(chips = it.chips.toggled(name)) }
    fun setRow(name: String, index: Int, row: SetRow) = stateFlow.update { it.copy(chips = it.chips.withSet(name, index, row)) }
    fun addSet(name: String) = stateFlow.update { it.copy(chips = it.chips.withAddedSet(name)) }
    fun removeSet(name: String, index: Int) = stateFlow.update { it.copy(chips = it.chips.withoutSet(name, index)) }
    fun setFree(name: String, text: String) = stateFlow.update { it.copy(chips = it.chips.withFree(name, text)) }
    fun setPlace(place: String) = stateFlow.update { it.copy(place = place) }
    fun setNote(note: String) = stateFlow.update { it.copy(note = note) }

    fun save() {
        val s = stateFlow.value
        val body = saveBody(s.order, s.chips, s.place, s.note) ?: return
        if (s.saving) return
        stateFlow.update { it.copy(saving = true, saveError = null) }
        scope.launch {
            try {
                val form = gateway.save(body)
                apply(form)
                stateFlow.update { it.copy(saving = false, savedCount = it.savedCount + 1) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing is lost on failure: the ticked chips and sets stay as typed.
                stateFlow.update { it.copy(saving = false, saveError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }
}

/** Survives rotation and tab switches. */
internal class WorkoutViewModelHolder(gateway: WorkoutGateway) : ViewModel() {
    val viewModel = WorkoutViewModel(gateway, viewModelScope)
}
