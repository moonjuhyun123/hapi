package app.hapi.companion.feature.jarvis.drive

import android.content.Context
import app.hapi.companion.feature.jarvis.activity.ActivityPrefs
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 「드라이브에서 찾기」 content search on our server (`GET <entrance>/drive-search?q=`), not the hub —
 * words + local passage embeddings, no harness or model API (정본 7장, X3). Uses the same entrance
 * address and token as 「폰 활동」. With no entrance set it returns nothing, so the drive shows the
 * hub's name search alone.
 */
internal fun contentSearchFor(context: Context): ContentSearch = { query ->
    val settings = ActivityPrefs(context.applicationContext).current()
    if (settings.url.isBlank()) {
        emptyList()
    } else {
        withContext(Dispatchers.IO) {
            val url = "${settings.url}/drive-search?k=20&q=" + URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder().url(url).header("Authorization", "Bearer ${settings.token}").build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                check(response.isSuccessful) { "내용 검색 ${response.code}" }
                Json.parseToJsonElement(body).jsonObject["hits"]!!.jsonArray.map { hit ->
                    val o = hit.jsonObject
                    DriveHit(
                        path = o["path"]!!.jsonPrimitive.content,
                        title = o["title"]!!.jsonPrimitive.content,
                        snippet = o["snippet"]?.jsonPrimitive?.content.orEmpty(),
                    )
                }
            }
        }
    }
}

private val client by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build() }
