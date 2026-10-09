package app.hapi.companion.feature.jarvis.workout

import android.content.Context
import app.hapi.companion.feature.jarvis.EntranceHttp
import kotlinx.serialization.json.JsonObject

/** Our workout entrance (not the hub): read the form, save. Throws on failure. */
interface WorkoutGateway {
    /** Entrance address and token, for photo requests; null when not set up. */
    suspend fun entrance(): Pair<String, String>?
    suspend fun form(): WorkoutForm
    suspend fun save(body: JsonObject): WorkoutForm
}

internal class HttpWorkoutGateway(context: Context) : WorkoutGateway {
    private val http = EntranceHttp(context)
    override suspend fun entrance() = http.entrance()
    override suspend fun form(): WorkoutForm = http.get("/workout/form", WorkoutForm.serializer())
    override suspend fun save(body: JsonObject): WorkoutForm = http.post("/workout/save", body, WorkoutForm.serializer())
}
