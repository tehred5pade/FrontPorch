package io.tehred5pade.frontporch.data.importexport

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.tehred5pade.frontporch.data.db.SubscriptionDao
import io.tehred5pade.frontporch.data.db.SubscriptionEntity
import io.tehred5pade.frontporch.data.local.AffinityScoreEntity
import io.tehred5pade.frontporch.data.local.ChannelAffinityEntity
import io.tehred5pade.frontporch.data.local.FrontPorchDatabase
import io.tehred5pade.frontporch.data.local.SeenShortEntity
import io.tehred5pade.frontporch.data.local.ShortFeedbackEntity
import io.tehred5pade.frontporch.data.local.TagAffinityEntity
import io.tehred5pade.frontporch.data.playlist.PlaylistStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val Context.backupDataStore by preferencesDataStore(name = "user_settings")

data class ImportSummary(
    val channelsImported: Int,
    val playlistsImported: Int,
    val algorithmEntriesImported: Int,
    val settingsRestored: Boolean
)

object FrontPorchBackupManager {

    suspend fun exportBackup(
        context: Context,
        db: FrontPorchDatabase,
        subscriptionDao: SubscriptionDao,
        playlistStore: PlaylistStore
    ): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("version", 2)
        root.put("timestamp", System.currentTimeMillis())

        // 1. Settings (DataStore & SharedPreferences with typed values)
        val settingsObj = JSONObject()
        val dataStorePrefs = context.backupDataStore.data.first()
        val dsMap = JSONObject()
        dataStorePrefs.asMap().forEach { (key, value) ->
            if (value != null) {
                val entryObj = JSONObject().apply {
                    put("type", when (value) {
                        is Boolean -> "boolean"
                        is Int -> "int"
                        is Long -> "long"
                        is Float -> "float"
                        is Double -> "double"
                        is Set<*> -> "stringSet"
                        else -> "string"
                    })
                    put("value", when (value) {
                        is Set<*> -> JSONArray(value.toList())
                        else -> value.toString()
                    })
                }
                dsMap.put(key.name, entryObj)
            }
        }
        settingsObj.put("user_settings", dsMap)

        val prefsNames = listOf(
            "frontporch_download_prefs",
            "frontporch_sponsorblock_prefs",
            "frontporch_theme_prefs",
            "frontporch_download_settings"
        )
        val spObj = JSONObject()
        for (name in prefsNames) {
            val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val spContent = JSONObject()
            sp.all.forEach { (k, v) ->
                if (v != null) {
                    val entryObj = JSONObject().apply {
                        put("type", when (v) {
                            is Boolean -> "boolean"
                            is Int -> "int"
                            is Long -> "long"
                            is Float -> "float"
                            is Set<*> -> "stringSet"
                            else -> "string"
                        })
                        put("value", when (v) {
                            is Set<*> -> JSONArray(v.toList())
                            else -> v.toString()
                        })
                    }
                    spContent.put(k, entryObj)
                }
            }
            spObj.put(name, spContent)
        }
        settingsObj.put("shared_preferences", spObj)
        root.put("settings", settingsObj)

        // 2. Channels (Subscriptions)
        val subscriptions = subscriptionDao.getAllOnce()
        val channelsArray = JSONArray()
        subscriptions.forEach { sub ->
            channelsArray.put(
                JSONObject().apply {
                    put("channelId", sub.channelId)
                    put("name", sub.name)
                    put("url", sub.url)
                    put("importedAt", sub.importedAt)
                    put("thumbnailUrl", sub.thumbnailUrl)
                    put("subscriberCountText", sub.subscriberCountText)
                }
            )
        }
        root.put("channels", channelsArray)

        // 3. Playlists
        val playlists = playlistStore.playlists.first()
        val playlistsArray = JSONArray()
        playlists.forEach { playlist ->
            val entriesArray = JSONArray()
            playlist.entries.forEach { entry ->
                entriesArray.put(
                    JSONObject().apply {
                        put("videoId", entry.videoId)
                        put("title", entry.title)
                        put("channelName", entry.channelName)
                        put("localVideoPath", entry.localVideoPath)
                        put("localThumbnailPath", entry.localThumbnailPath)
                        put("thumbnailUrl", entry.thumbnailUrl)
                    }
                )
            }
            playlistsArray.put(
                JSONObject().apply {
                    put("id", playlist.id)
                    put("name", playlist.name)
                    put("entries", entriesArray)
                }
            )
        }
        root.put("playlists", playlistsArray)

        // 4. YouTube Shorts Algorithm Data
        val recommendationDao = db.recommendationDao()
        val algoObj = JSONObject()

        val feedbackList = recommendationDao.getAllFeedback()
        val feedbackArray = JSONArray()
        feedbackList.forEach { f ->
            feedbackArray.put(
                JSONObject().apply {
                    put("videoId", f.videoId)
                    put("channelId", f.channelId)
                    put("channelName", f.channelName)
                    put("title", f.title)
                    put("tags", f.tags)
                    put("feedbackType", f.feedbackType)
                    put("timestamp", f.timestamp)
                }
            )
        }
        algoObj.put("feedback", feedbackArray)

        val channelAffinities = recommendationDao.getAllChannelAffinities()
        val caArray = JSONArray()
        channelAffinities.forEach { ca ->
            caArray.put(
                JSONObject().apply {
                    put("channelId", ca.channelId)
                    put("channelName", ca.channelName)
                    put("score", ca.score.toDouble())
                    put("lastUpdated", ca.lastUpdated)
                }
            )
        }
        algoObj.put("channel_affinities", caArray)

        val tagAffinities = recommendationDao.getAllTagAffinities()
        val taArray = JSONArray()
        tagAffinities.forEach { ta ->
            taArray.put(
                JSONObject().apply {
                    put("tag", ta.tag)
                    put("score", ta.score.toDouble())
                    put("lastUpdated", ta.lastUpdated)
                }
            )
        }
        algoObj.put("tag_affinities", taArray)

        val affinityScores = recommendationDao.getAllAffinities()
        val asArray = JSONArray()
        affinityScores.forEach { a ->
            asArray.put(
                JSONObject().apply {
                    put("affinityKey", a.affinityKey)
                    put("type", a.type)
                    put("label", a.label)
                    put("score", a.score)
                    put("dislikeCount", a.dislikeCount)
                    put("muted", a.muted)
                    put("lastUpdated", a.lastUpdated)
                }
            )
        }
        algoObj.put("affinity_scores", asArray)

        val seenShorts = recommendationDao.getAllSeen()
        val seenArray = JSONArray()
        seenShorts.forEach { s ->
            seenArray.put(
                JSONObject().apply {
                    put("videoId", s.videoId)
                    put("timestamp", s.timestamp)
                    put("completionPercent", s.completionPercent)
                    put("implicitApplied", s.implicitApplied)
                }
            )
        }
        algoObj.put("seen_shorts", seenArray)

        root.put("shorts_algorithm", algoObj)

        return@withContext root.toString(2)
    }

    suspend fun importBackup(
        context: Context,
        db: FrontPorchDatabase,
        subscriptionDao: SubscriptionDao,
        playlistStore: PlaylistStore,
        jsonString: String
    ): Result<ImportSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(jsonString)

            // 1. Restore Settings
            var settingsRestored = false
            val settingsObj = root.optJSONObject("settings")
            if (settingsObj != null) {
                val dsMap = settingsObj.optJSONObject("user_settings")
                if (dsMap != null) {
                    context.backupDataStore.edit { prefs ->
                        val keys = dsMap.keys()
                        while (keys.hasNext()) {
                            val keyName = keys.next()
                            val entryObj = dsMap.optJSONObject(keyName)
                            if (entryObj != null) {
                                val type = entryObj.optString("type", "string")
                                val rawVal = entryObj.opt("value") ?: continue
                                when (type) {
                                    "boolean" -> {
                                        val b = rawVal.toString().toBooleanStrictOrNull() ?: continue
                                        prefs[booleanPreferencesKey(keyName)] = b
                                    }
                                    "int" -> {
                                        val i = rawVal.toString().toIntOrNull() ?: continue
                                        prefs[intPreferencesKey(keyName)] = i
                                    }
                                    "long" -> {
                                        val l = rawVal.toString().toLongOrNull() ?: continue
                                        prefs[longPreferencesKey(keyName)] = l
                                    }
                                    "float" -> {
                                        val f = rawVal.toString().toFloatOrNull() ?: continue
                                        prefs[floatPreferencesKey(keyName)] = f
                                    }
                                    "double" -> {
                                        val d = rawVal.toString().toDoubleOrNull() ?: continue
                                        prefs[doublePreferencesKey(keyName)] = d
                                    }
                                    "stringSet" -> {
                                        val arr = entryObj.optJSONArray("value")
                                        val set = mutableSetOf<String>()
                                        if (arr != null) {
                                            for (i in 0 until arr.length()) {
                                                set.add(arr.optString(i))
                                            }
                                        }
                                        prefs[stringSetPreferencesKey(keyName)] = set
                                    }
                                    else -> {
                                        prefs[stringPreferencesKey(keyName)] = rawVal.toString()
                                    }
                                }
                            } else {
                                // Fallback for legacy backups
                                prefs[stringPreferencesKey(keyName)] = dsMap.optString(keyName)
                            }
                        }
                    }
                }

                val spObj = settingsObj.optJSONObject("shared_preferences")
                if (spObj != null) {
                    val spKeys = spObj.keys()
                    while (spKeys.hasNext()) {
                        val prefsName = spKeys.next()
                        val content = spObj.optJSONObject(prefsName) ?: continue
                        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                        val editor = prefs.edit()
                        val contentKeys = content.keys()
                        while (contentKeys.hasNext()) {
                            val k = contentKeys.next()
                            val entryObj = content.optJSONObject(k)
                            if (entryObj != null) {
                                val type = entryObj.optString("type", "string")
                                val rawVal = entryObj.opt("value") ?: continue
                                when (type) {
                                    "boolean" -> editor.putBoolean(k, rawVal.toString().toBoolean())
                                    "int" -> editor.putInt(k, rawVal.toString().toIntOrNull() ?: 0)
                                    "long" -> editor.putLong(k, rawVal.toString().toLongOrNull() ?: 0L)
                                    "float" -> editor.putFloat(k, rawVal.toString().toFloatOrNull() ?: 0f)
                                    "stringSet" -> {
                                        val arr = entryObj.optJSONArray("value")
                                        val set = mutableSetOf<String>()
                                        if (arr != null) {
                                            for (i in 0 until arr.length()) {
                                                set.add(arr.optString(i))
                                            }
                                        }
                                        editor.putStringSet(k, set)
                                    }
                                    else -> editor.putString(k, rawVal.toString())
                                }
                            } else {
                                editor.putString(k, content.optString(k))
                            }
                        }
                        editor.apply()
                    }
                }
                settingsRestored = true
            }

            // 2. Restore Channels (Subscriptions)
            var channelsCount = 0
            val channelsArray = root.optJSONArray("channels")
            if (channelsArray != null) {
                val subList = buildList {
                    for (i in 0 until channelsArray.length()) {
                        val obj = channelsArray.optJSONObject(i) ?: continue
                        val channelId = obj.optString("channelId")
                        val name = obj.optString("name")
                        if (channelId.isBlank() || name.isBlank()) continue
                        add(
                            SubscriptionEntity(
                                channelId = channelId,
                                name = name,
                                url = obj.optString("url", "https://www.youtube.com/channel/$channelId"),
                                importedAt = obj.optLong("importedAt", System.currentTimeMillis()),
                                thumbnailUrl = obj.optString("thumbnailUrl", ""),
                                subscriberCountText = obj.optString("subscriberCountText", "")
                            )
                        )
                    }
                }
                if (subList.isNotEmpty()) {
                    subscriptionDao.insertAll(subList)
                    channelsCount = subList.size
                }
            }

            // 3. Restore Playlists
            var playlistsCount = 0
            val playlistsArray = root.optJSONArray("playlists")
            if (playlistsArray != null) {
                val playlistPrefs = context.applicationContext.getSharedPreferences("frontporch_playlists", Context.MODE_PRIVATE)
                playlistPrefs.edit().putString("playlists_json", playlistsArray.toString()).apply()
                playlistStore.reload()
                playlistsCount = playlistsArray.length()
            }

            // 4. Restore Shorts Algorithm Data
            var algoCount = 0
            val recommendationDao = db.recommendationDao()
            val algoObj = root.optJSONObject("shorts_algorithm")
            if (algoObj != null) {
                val feedbackArr = algoObj.optJSONArray("feedback")
                if (feedbackArr != null) {
                    for (i in 0 until feedbackArr.length()) {
                        val obj = feedbackArr.optJSONObject(i) ?: continue
                        val videoId = obj.optString("videoId")
                        if (videoId.isBlank()) continue
                        recommendationDao.insertFeedback(
                            ShortFeedbackEntity(
                                videoId = videoId,
                                channelId = obj.optString("channelId"),
                                channelName = obj.optString("channelName"),
                                title = obj.optString("title"),
                                tags = obj.optString("tags"),
                                feedbackType = obj.optString("feedbackType", "LIKE"),
                                timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                            )
                        )
                        algoCount++
                    }
                }

                val caArr = algoObj.optJSONArray("channel_affinities")
                if (caArr != null) {
                    for (i in 0 until caArr.length()) {
                        val obj = caArr.optJSONObject(i) ?: continue
                        val channelId = obj.optString("channelId")
                        if (channelId.isBlank()) continue
                        recommendationDao.upsertChannelAffinity(
                            ChannelAffinityEntity(
                                channelId = channelId,
                                channelName = obj.optString("channelName"),
                                score = obj.optDouble("score", 3.0).toFloat(),
                                lastUpdated = obj.optLong("lastUpdated", System.currentTimeMillis())
                            )
                        )
                        algoCount++
                    }
                }

                val taArr = algoObj.optJSONArray("tag_affinities")
                if (taArr != null) {
                    for (i in 0 until taArr.length()) {
                        val obj = taArr.optJSONObject(i) ?: continue
                        val tag = obj.optString("tag")
                        if (tag.isBlank()) continue
                        recommendationDao.upsertTagAffinity(
                            TagAffinityEntity(
                                tag = tag,
                                score = obj.optDouble("score", 0.0).toFloat(),
                                lastUpdated = obj.optLong("lastUpdated", System.currentTimeMillis())
                            )
                        )
                        algoCount++
                    }
                }

                val asArr = algoObj.optJSONArray("affinity_scores")
                if (asArr != null) {
                    for (i in 0 until asArr.length()) {
                        val obj = asArr.optJSONObject(i) ?: continue
                        val key = obj.optString("affinityKey")
                        if (key.isBlank()) continue
                        recommendationDao.upsertAffinity(
                            AffinityScoreEntity(
                                affinityKey = key,
                                type = obj.optString("type", "CHANNEL"),
                                label = obj.optString("label", ""),
                                score = obj.optDouble("score", 0.0),
                                dislikeCount = obj.optInt("dislikeCount", 0),
                                muted = obj.optBoolean("muted", false),
                                lastUpdated = obj.optLong("lastUpdated", System.currentTimeMillis())
                            )
                        )
                        algoCount++
                    }
                }

                val seenArr = algoObj.optJSONArray("seen_shorts")
                if (seenArr != null) {
                    for (i in 0 until seenArr.length()) {
                        val obj = seenArr.optJSONObject(i) ?: continue
                        val videoId = obj.optString("videoId")
                        if (videoId.isBlank()) continue
                        recommendationDao.insertSeen(
                            SeenShortEntity(
                                videoId = videoId,
                                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                                completionPercent = obj.optDouble("completionPercent", 0.0),
                                implicitApplied = obj.optBoolean("implicitApplied", false)
                            )
                        )
                        algoCount++
                    }
                }
            }

            ImportSummary(
                channelsImported = channelsCount,
                playlistsImported = playlistsCount,
                algorithmEntriesImported = algoCount,
                settingsRestored = settingsRestored
            )
        }
    }
}
