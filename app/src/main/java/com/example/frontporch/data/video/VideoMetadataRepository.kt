package com.example.frontporch.data.video

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class VideoCommentItem(
    val id: String,
    val author: String,
    val text: String,
    val publishedTime: String,
    val likeCount: String
)

data class VideoDetailsAndComments(
    val title: String,
    val channelName: String,
    val description: String,
    val comments: List<VideoCommentItem>
)

class VideoMetadataRepository(private val okHttpClient: OkHttpClient) {

    private val webUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    suspend fun loadDetailsAndComments(
        videoId: String,
        fallbackTitle: String,
        fallbackChannel: String
    ): VideoDetailsAndComments = withContext(Dispatchers.IO) {
        var title = fallbackTitle
        var channel = fallbackChannel
        var description = ""

        // 1. Fetch full description & metadata from /youtubei/v1/player
        runCatching {
            val playerPayload = JSONObject().apply {
                put("videoId", videoId)
                put(
                    "context",
                    JSONObject().put(
                        "client",
                        JSONObject().apply {
                            put("clientName", "WEB")
                            put("clientVersion", "2.20260101.00.00")
                            put("hl", "en")
                            put("gl", "US")
                        }
                    )
                )
            }
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .header("User-Agent", webUa)
                .post(playerPayload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                val root = JSONObject(resp.body?.string().orEmpty())
                val details = root.optJSONObject("videoDetails")
                if (details != null) {
                    title = details.optString("title").ifBlank { title }
                    channel = details.optString("author").ifBlank { channel }
                    description = details.optString("shortDescription").orEmpty()
                }
            }
        }

        // 2. Fetch comments continuation from /youtubei/v1/next
        val comments = runCatching { fetchCommentsFromNext(videoId) }.getOrDefault(emptyList())

        VideoDetailsAndComments(
            title = title,
            channelName = channel,
            description = description,
            comments = comments
        )
    }

    private fun fetchCommentsFromNext(videoId: String): List<VideoCommentItem> {
        val nextPayload = JSONObject().apply {
            put("videoId", videoId)
            put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20260101.00.00")
                        put("hl", "en")
                        put("gl", "US")
                    }
                )
            )
        }

        val firstResp = postNext(nextPayload) ?: return emptyList()
        val token = findContinuationToken(firstResp) ?: return emptyList()

        val contPayload = JSONObject().apply {
            put("continuation", token)
            put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20260101.00.00")
                        put("hl", "en")
                        put("gl", "US")
                    }
                )
            )
        }

        val commentsJson = postNext(contPayload) ?: return emptyList()
        return parseCommentEntities(commentsJson)
    }

    private fun postNext(payload: JSONObject): String? {
        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/next?prettyPrint=false")
            .header("User-Agent", webUa)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return okHttpClient.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    }

    private fun findContinuationToken(rawJson: String): String? {
        val match = Regex(""""token"\s*:\s*"(Eg0SC[^"]+)"""").find(rawJson)
        return match?.groupValues?.getOrNull(1)
    }

    private fun parseCommentEntities(rawJson: String): List<VideoCommentItem> {
        val results = mutableListOf<VideoCommentItem>()
        val root = JSONObject(rawJson)
        val mutations = root.optJSONObject("frameworkUpdates")
            ?.optJSONObject("entityBatchUpdate")
            ?.optJSONArray("mutations") ?: JSONArray()

        for (i in 0 until mutations.length()) {
            val payload = mutations.optJSONObject(i)?.optJSONObject("payload") ?: continue
            val commentEntity = payload.optJSONObject("commentEntityPayload") ?: continue
            val props = commentEntity.optJSONObject("properties") ?: continue
            val authorObj = commentEntity.optJSONObject("author") ?: continue
            val toolbar = commentEntity.optJSONObject("toolbar")

            val id = props.optString("commentId").ifBlank { "c_$i" }
            val text = props.optJSONObject("content")?.optString("content").orEmpty()
            val published = props.optString("publishedTime").orEmpty()
            val author = authorObj.optString("displayName").orEmpty()
            val likes = toolbar?.optString("likeCountNotliked").orEmpty()

            if (text.isNotBlank()) {
                results.add(
                    VideoCommentItem(
                        id = id,
                        author = author,
                        text = text,
                        publishedTime = published,
                        likeCount = likes
                    )
                )
            }
        }
        return results
    }
}
