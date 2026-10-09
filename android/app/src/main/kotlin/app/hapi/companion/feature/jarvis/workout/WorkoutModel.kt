package app.hapi.companion.feature.jarvis.workout

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject

/*
 * 「운동」 tab (Jarvis fork, docs/jarvis/CHANGES.md step 17). The app draws, the
 * server computes: chips, last-time prefill and hints come from the same form
 * calculation as the dashboard's /workout/log, and a save goes through the same
 * single log writer (server `~/lab/v2/workout/server.py`, 정본 9장 「앱에 넣는 것」).
 * Photos: free-exercise-db (public domain), served by our entrance.
 */

@Serializable
data class SetRow(val w: String = "", val r: String = "")

@Serializable
data class WorkoutChip(
    val name: String,
    /** `sets` = weight × reps rows; `free` = one free-text box (cardio, rehab). */
    val mode: String = "sets",
    val sets: List<SetRow> = emptyList(),
    val free: String = "",
    val checked: Boolean = false,
    /** 「지난번 09-09 · 40kgx10…」 · 「최고 60kg」 — server-composed. */
    val hint: String = "",
    /** Entrance-relative photo paths (start, end); empty when the exercise has none. */
    val images: List<String> = emptyList(),
) {
    val isFree: Boolean get() = mode == "free"
}

@Serializable
data class WorkoutGroup(val title: String, val chips: List<WorkoutChip> = emptyList())

@Serializable
data class PartRecency(val title: String, val label: String)

@Serializable
data class WorkoutForm(
    @SerialName("date_label") val dateLabel: String = "",
    @SerialName("date_iso") val dateIso: String = "",
    val groups: List<WorkoutGroup> = emptyList(),
    /** Today's items that match no chip — kept verbatim so a re-save loses nothing. */
    val extras: List<WorkoutChip> = emptyList(),
    val recency: List<PartRecency> = emptyList(),
    /** Today's saved lines (with change vs last time), once saved. */
    val summary: List<String> = emptyList(),
) {
    fun allChips(): List<WorkoutChip> = groups.flatMap { it.chips } + extras
}

const val PLACE_GYM = "체련"
val PLACES = listOf(PLACE_GYM, "집", "체련+집")

// ------------------------------------------------------------ editing (pure) --

fun Map<String, WorkoutChip>.toggled(name: String): Map<String, WorkoutChip> {
    val chip = this[name] ?: return this
    val on = !chip.checked
    // Ticking a weights chip with nothing to prefill gives one empty row to type into.
    val sets = if (on && !chip.isFree && chip.sets.isEmpty()) listOf(SetRow()) else chip.sets
    return this + (name to chip.copy(checked = on, sets = sets))
}

fun Map<String, WorkoutChip>.withSet(name: String, index: Int, row: SetRow): Map<String, WorkoutChip> {
    val chip = this[name] ?: return this
    if (index !in chip.sets.indices) return this
    return this + (name to chip.copy(sets = chip.sets.toMutableList().also { it[index] = row }))
}

/** Adds a row copying the last one (same weight is the common next set). */
fun Map<String, WorkoutChip>.withAddedSet(name: String): Map<String, WorkoutChip> {
    val chip = this[name] ?: return this
    return this + (name to chip.copy(sets = chip.sets + (chip.sets.lastOrNull() ?: SetRow())))
}

fun Map<String, WorkoutChip>.withoutSet(name: String, index: Int): Map<String, WorkoutChip> {
    val chip = this[name] ?: return this
    if (index !in chip.sets.indices) return this
    return this + (name to chip.copy(sets = chip.sets.filterIndexed { i, _ -> i != index }))
}

fun Map<String, WorkoutChip>.withFree(name: String, text: String): Map<String, WorkoutChip> {
    val chip = this[name] ?: return this
    return this + (name to chip.copy(free = text))
}

/** Save request for the ticked chips, in screen order; null when nothing is ticked. */
fun saveBody(order: List<String>, chips: Map<String, WorkoutChip>, place: String, note: String): JsonObject? {
    val ticked = order.mapNotNull { chips[it] }.filter { it.checked }
    if (ticked.isEmpty()) return null
    return buildJsonObject {
        put("place", place)
        if (note.isNotBlank()) put("note", note.trim())
        putJsonArray("items") {
            ticked.forEach { chip ->
                addJsonObject {
                    put("name", chip.name)
                    if (chip.isFree) {
                        put("free", chip.free.trim())
                    } else {
                        putJsonArray("sets") {
                            chip.sets.filter { it.w.isNotBlank() || it.r.isNotBlank() }.forEach { row ->
                                addJsonObject { put("w", row.w.trim()); put("r", row.r.trim()) }
                            }
                        }
                    }
                }
            }
        }
    }
}
