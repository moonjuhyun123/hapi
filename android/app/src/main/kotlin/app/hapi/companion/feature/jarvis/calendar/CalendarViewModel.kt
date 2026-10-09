package app.hapi.companion.feature.jarvis.calendar

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hapi.companion.feature.jarvis.EntranceHttp
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface CalendarGateway {
    suspend fun month(ym: YearMonth): CalMonth
    suspend fun save(body: JsonObject): CalMonth
}

internal class HttpCalendarGateway(context: Context) : CalendarGateway {
    private val http = EntranceHttp(context)
    override suspend fun month(ym: YearMonth): CalMonth = http.get("/calendar/month?ym=$ym", CalMonth.serializer())
    override suspend fun save(body: JsonObject): CalMonth = http.post("/calendar/event", body, CalMonth.serializer())
}

val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

data class CalendarUiState(
    val ym: YearMonth,
    val selected: LocalDate,
    val month: CalMonth? = null,
    val loading: Boolean = true,
    val error: String? = null,
    /** Open editor (new or existing event); null = closed. */
    val draft: EventDraft? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
)

class CalendarViewModel(private val gateway: CalendarGateway, private val scope: CoroutineScope, today: LocalDate = LocalDate.now(SEOUL)) {
    private val stateFlow = MutableStateFlow(CalendarUiState(YearMonth.from(today), today))
    val state: StateFlow<CalendarUiState> = stateFlow.asStateFlow()

    fun load() {
        val ym = stateFlow.value.ym
        stateFlow.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val m = gateway.month(ym)
                stateFlow.update { if (it.ym == ym) it.copy(month = m, loading = false) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stateFlow.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun shiftMonth(by: Long) {
        stateFlow.update {
            val ym = it.ym.plusMonths(by)
            it.copy(ym = ym, month = null, selected = if (YearMonth.from(it.selected) == ym) it.selected else ym.atDay(1))
        }
        load()
    }

    fun select(day: LocalDate) {
        val ym = YearMonth.from(day)
        if (ym != stateFlow.value.ym) {
            stateFlow.update { it.copy(ym = ym, month = null, selected = day) }
            load()
        } else {
            stateFlow.update { it.copy(selected = day) }
        }
    }

    fun newEvent() = stateFlow.update { it.copy(draft = newDraft(it.selected), saveError = null) }
    fun edit(event: CalEvent) = stateFlow.update { it.copy(draft = event.draft(), saveError = null) }
    fun change(draft: EventDraft) = stateFlow.update { it.copy(draft = draft, saveError = null) }
    fun closeEditor() = stateFlow.update { it.copy(draft = null, saveError = null) }

    fun saveDraft() {
        val draft = stateFlow.value.draft ?: return
        draft.problem()?.let { p -> stateFlow.update { it.copy(saveError = p) }; return }
        submit(draft.body(), LocalDate.parse(draft.date))
    }

    fun deleteDraft() {
        val draft = stateFlow.value.draft ?: return
        val id = draft.id ?: return
        submit(buildJsonObject { put("id", id); put("delete", true); put("date", draft.date) }, LocalDate.parse(draft.date))
    }

    private fun submit(body: JsonObject, day: LocalDate) {
        if (stateFlow.value.saving) return
        stateFlow.update { it.copy(saving = true, saveError = null) }
        scope.launch {
            try {
                val m = gateway.save(body)
                // The server answers with the month of the saved day: show that day.
                stateFlow.update { it.copy(saving = false, draft = null, month = m, ym = YearMonth.parse(m.ym), selected = day) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stateFlow.update { it.copy(saving = false, saveError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }
}

internal class CalendarViewModelHolder(gateway: CalendarGateway) : ViewModel() {
    val viewModel = CalendarViewModel(gateway, viewModelScope)
}
