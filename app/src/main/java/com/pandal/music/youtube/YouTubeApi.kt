package com.pandal.music.youtube

import android.text.Html
import com.pandal.music.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class YouTubeVideo(
    val videoId: String,
    val title: String,
    val channel: String,
    val thumbnailUrl: String
)

object YouTubeApi {
    val hasApiKey: Boolean get() = BuildConfig.YOUTUBE_API_KEY.isNotBlank()

    suspend fun search(query: String, maxResults: Int = 20): Result<List<YouTubeVideo>> = withContext(Dispatchers.IO) {
        runCatching {
            require(query.isNotBlank()) { "Escribe una búsqueda." }
            require(hasApiKey) { "Falta YOUTUBE_API_KEY en local.properties." }

            val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.toString())
            val endpoint = "https://www.googleapis.com/youtube/v3/search" +
                "?part=snippet&type=video&safeSearch=moderate&maxResults=$maxResults" +
                "&q=$encoded&key=${BuildConfig.YOUTUBE_API_KEY}"

            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 12_000
                setRequestProperty("Accept", "application/json")
            }

            try {
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream.bufferedReader().use { it.readText() }
                if (code !in 200..299) {
                    val message = runCatching {
                        JSONObject(body).getJSONObject("error").optString("message")
                    }.getOrDefault("Error de YouTube: HTTP $code")
                    error(message)
                }

                val root = JSONObject(body)
                val items = root.optJSONArray("items") ?: return@runCatching emptyList()
                buildList {
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        val id = item.optJSONObject("id")?.optString("videoId").orEmpty()
                        val snippet = item.optJSONObject("snippet") ?: continue
                        if (id.isBlank()) continue
                        val thumbs = snippet.optJSONObject("thumbnails")
                        val thumb = thumbs?.optJSONObject("high")?.optString("url")
                            ?: thumbs?.optJSONObject("medium")?.optString("url")
                            ?: thumbs?.optJSONObject("default")?.optString("url").orEmpty()
                        add(
                            YouTubeVideo(
                                videoId = id,
                                title = decodeHtml(snippet.optString("title")),
                                channel = decodeHtml(snippet.optString("channelTitle")),
                                thumbnailUrl = thumb
                            )
                        )
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun decodeHtml(value: String): String =
        Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
}
