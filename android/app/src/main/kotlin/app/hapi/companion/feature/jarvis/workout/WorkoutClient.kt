package app.hapi.companion.feature.jarvis.workout

import android.content.Context
import app.hapi.companion.feature.jarvis.activity.ActivityPrefs
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Our workout entrance (not the hub): read the form, save. Throws on failure. */
interface WorkoutGateway {
    /** Entrance address and token, for photo requests; null when not set up. */
    suspend fun entrance(): Pair<String, String>?
    suspend fun form(): WorkoutForm
    suspend fun save(body: JsonObject): WorkoutForm
}

/** Same entrance address and token as 「폰 활동」 (and drive search). */
internal class HttpWorkoutGateway(context: Context) : WorkoutGateway {
    private val prefs = ActivityPrefs(context.applicationContext)

    override suspend fun entrance(): Pair<String, String>? =
        prefs.current().takeIf { it.url.isNotBlank() }?.let { it.url to it.token }

    override suspend fun form(): WorkoutForm = call(Request.Builder().url(url("/workout/form")).get())

    override suspend fun save(body: JsonObject): WorkoutForm =
        call(Request.Builder().url(url("/workout/save")).post(body.toString().toRequestBody(JSON_TYPE)))

    private suspend fun url(path: String): String =
        (entrance() ?: error("입구가 없습니다 — ⋮ 폰 활동에서 주소·토큰을 넣어 주세요")).first + path

    private suspend fun call(builder: Request.Builder): WorkoutForm = withContext(Dispatchers.IO) {
        val token = entrance()?.second.orEmpty()
        client.newCall(builder.header("Authorization", "Bearer $token").build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val reason = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                error(reason ?: "운동 입구 ${response.code}")
            }
            json.decodeFromString(WorkoutForm.serializer(), text)
        }
    }

    private companion object {
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        val json = Json { ignoreUnknownKeys = true }
        val client by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build() }
    }
}
