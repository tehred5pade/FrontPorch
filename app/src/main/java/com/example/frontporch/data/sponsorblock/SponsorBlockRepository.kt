package com.example.frontporch.data.sponsorblock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class SponsorSegment(
    val uuid: String,
    val startSec: Float,
    val endSec: Float,
    val category: SponsorCategory,
    val actionType: String = "skip"
)

class SponsorBlockRepository(private val okHttpClient: OkHttpClient) {

    private val cache = ConcurrentHashMap<String, List<SponsorSegment>>()

    suspend fun getSegments(videoId: String): List<SponsorSegment> = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext emptyList()
        cache[videoId]?.let { return@withContext it }

        val categoriesJson = JSONArray(SponsorCategory.entries.map { it.apiKey }).toString()
        val actionTypesJson = JSONArray(listOf("skip", "poi")).toString()
        val encodedCats = URLEncoder.encode(categoriesJson, "UTF-8")
        val encodedActions = URLEncoder.encode(actionTypesJson, "UTF-8")

        // 1. Try direct videoID query first (fastest and most accurate)
        val directUrl = "https://sponsor.ajay.app/api/skipSegments?videoID=$videoId&categories=$encodedCats&actionTypes=$encodedActions"
        val directReq = Request.Builder()
            .url(directUrl)
            .header("User-Agent", "FrontPorch/1.0")
            .get()
            .build()

        val directResults = runCatching {
            okHttpClient.newCall(directReq).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val segArray = JSONArray(resp.body?.string().orEmpty())
                val matched = mutableListOf<SponsorSegment>()

                for (j in 0 until segArray.length()) {
                    val segObj = segArray.optJSONObject(j) ?: continue
                    val cat = SponsorCategory.fromApiKey(segObj.optString("category")) ?: continue
                    val times = segObj.optJSONArray("segment") ?: continue
                    if (times.length() < 2) continue

                    val actionType = segObj.optString("actionType", "skip")
                    val start = times.optDouble(0, 0.0).toFloat()
                    val end = times.optDouble(1, 0.0).toFloat()
                    val uuid = segObj.optString("UUID").ifBlank { "${videoId}_${cat.apiKey}_${start}_$end" }

                    if (actionType == "poi" && start > 0f) {
                        matched.add(SponsorSegment(uuid, start, start, cat, actionType = "poi"))
                    } else if (end > start + 0.3f) {
                        matched.add(SponsorSegment(uuid, start, end, cat, actionType = "skip"))
                    }
                }
                matched.sortedBy { it.startSec }
            }
        }.getOrNull()

        if (directResults != null) {
            cache[videoId] = directResults
            return@withContext directResults
        }

        // 2. Fallback to hash prefix query
        val prefix = sha256Prefix(videoId, 4)
        val prefixUrl = "https://sponsor.ajay.app/api/skipSegments/$prefix?categories=$encodedCats&actionTypes=$encodedActions"
        val prefixReq = Request.Builder()
            .url(prefixUrl)
            .header("User-Agent", "FrontPorch/1.0")
            .get()
            .build()

        val prefixResults = runCatching {
            okHttpClient.newCall(prefixReq).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching emptyList()
                val videosArray = JSONArray(resp.body?.string().orEmpty())
                val matched = mutableListOf<SponsorSegment>()

                for (i in 0 until videosArray.length()) {
                    val videoObj = videosArray.optJSONObject(i) ?: continue
                    if (videoObj.optString("videoID") != videoId) continue

                    val segArray = videoObj.optJSONArray("segments") ?: continue
                    for (j in 0 until segArray.length()) {
                        val segObj = segArray.optJSONObject(j) ?: continue
                        val cat = SponsorCategory.fromApiKey(segObj.optString("category")) ?: continue
                        val times = segObj.optJSONArray("segment") ?: continue
                        if (times.length() < 2) continue

                        val actionType = segObj.optString("actionType", "skip")
                        val start = times.optDouble(0, 0.0).toFloat()
                        val end = times.optDouble(1, 0.0).toFloat()
                        val uuid = segObj.optString("UUID").ifBlank { "${videoId}_${cat.apiKey}_${start}_$end" }

                        if (actionType == "poi" && start > 0f) {
                            matched.add(SponsorSegment(uuid, start, start, cat, actionType = "poi"))
                        } else if (end > start + 0.3f) {
                            matched.add(SponsorSegment(uuid, start, end, cat, actionType = "skip"))
                        }
                    }
                }
                matched.sortedBy { it.startSec }
            }
        }.getOrDefault(emptyList())

        cache[videoId] = prefixResults
        prefixResults
    }

    private fun sha256Prefix(input: String, length: Int): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(length)
    }
}
