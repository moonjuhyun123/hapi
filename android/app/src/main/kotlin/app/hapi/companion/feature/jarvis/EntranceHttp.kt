package app.hapi.companion.feature.jarvis

import android.content.Context
import app.hapi.companion.feature.jarvis.activity.ActivityPrefs
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Our server's entrance (not the hub) for the butler app's own screens —
 * workout, calendar. Address and token are the ones set in 「폰 활동」. A
 * failure throws with the server's `error` text, never an empty result.
 */
internal class EntranceHttp(context: Context) {
    private val prefs = ActivityPrefs(context.applicationContext)

    suspend fun entrance(): Pair<String, String>? =
        prefs.current().takeIf { it.url.isNotBlank() }?.let { it.url to it.token }

    suspend fun <T> get(path: String, serializer: KSerializer<T>): T = call(path, serializer) { get() }

    suspend fun <T> post(path: String, body: JsonObject, serializer: KSerializer<T>): T =
        call(path, serializer) { post(body.toString().toRequestBody(JSON_TYPE)) }

    private suspend fun <T> call(path: String, serializer: KSerializer<T>, method: Request.Builder.() -> Request.Builder): T {
        val (url, token) = entrance() ?: error("입구가 없습니다 — ⋮ 폰 활동에서 주소·토큰을 넣어 주세요")
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url + path).header("Authorization", "Bearer $token").method().build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val reason = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                    error(reason ?: "입구 ${response.code}")
                }
                json.decodeFromString(serializer, text)
            }
        }
    }

    private companion object {
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true } // step 24: 서버 칸이 null 이어도 기본값으로
        val client by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build() }
    }
}
