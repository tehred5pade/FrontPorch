package io.tehred5pade.frontporch.data.importexport

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import io.tehred5pade.frontporch.data.db.SubscriptionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

object NewPipeSubscriptionImporter {

    private val channelIdRegex = Regex("""(?:/channel/|/c/|^)(UC[a-zA-Z0-9_-]{22})""")

    suspend fun importFromUri(context: Context, uri: Uri): Result<List<SubscriptionEntity>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val mimeType = context.contentResolver.getType(uri).orEmpty()
                val fileName = uri.lastPathSegment.orEmpty().lowercase()

                val channels = if (mimeType.contains("zip") || fileName.endsWith(".zip")) {
                    parseFromZipBackup(context, uri)
                } else {
                    val rawText = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: error("Unable to read selected file")
                    parseFromRawText(rawText)
                }

                if (channels.isEmpty()) {
                    error("No YouTube channels found in the selected file.")
                }
                channels
            }
        }

    fun parseFromRawText(rawText: String): List<SubscriptionEntity> {
        val imported = SubscriptionImporter.parseSubscriptionsFile(rawText)
        if (imported.isNotEmpty()) {
            return imported.map {
                SubscriptionEntity(
                    channelId = it.channelId,
                    name = it.title,
                    url = "https://www.youtube.com/channel/${it.channelId}"
                )
            }
        }
        return parseFromJson(rawText)
    }

    fun parseFromJson(jsonText: String): List<SubscriptionEntity> {
        val result = mutableListOf<SubscriptionEntity>()
        runCatching {
            val root = JSONObject(jsonText)
            val subsArray = root.optJSONArray("subscriptions") ?: return@runCatching
            for (i in 0 until subsArray.length()) {
                val item = subsArray.optJSONObject(i) ?: continue
                if (item.optInt("service_id", 0) != 0) continue

                val url = item.optString("url")
                val name = item.optString("name").ifBlank { "Subscribed Channel" }
                val channelId = channelIdRegex.find(url)?.groupValues?.getOrNull(1) ?: continue

                result.add(
                    SubscriptionEntity(
                        channelId = channelId,
                        name = name,
                        url = "https://www.youtube.com/channel/$channelId"
                    )
                )
            }
        }

        if (result.isEmpty()) {
            val subObjectRegex = Regex("""\{[^{}]*"service_id"\s*:\s*0[^{}]*\}""")
            val urlRegex = Regex(""""url"\s*:\s*"([^"]+)"""")
            val nameRegex = Regex(""""name"\s*:\s*"([^"]+)"""")

            subObjectRegex.findAll(jsonText).forEach { match ->
                val block = match.value
                val url = urlRegex.find(block)?.groupValues?.getOrNull(1).orEmpty()
                val name = nameRegex.find(block)?.groupValues?.getOrNull(1).orEmpty().ifBlank { "Subscribed Channel" }
                val channelId = channelIdRegex.find(url)?.groupValues?.getOrNull(1) ?: return@forEach

                result.add(
                    SubscriptionEntity(
                        channelId = channelId,
                        name = name,
                        url = "https://www.youtube.com/channel/$channelId"
                    )
                )
            }
        }

        return result.distinctBy { it.channelId }
    }

    private fun parseFromZipBackup(context: Context, uri: Uri): List<SubscriptionEntity> {
        val tempDbFile = File(context.cacheDir, "temp_newpipe_import.db")
        try {
            var foundDb = false
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name.endsWith("newpipe.db")) {
                            tempDbFile.outputStream().use { out -> zip.copyTo(out) }
                            foundDb = true
                            break
                        }
                        if (entry.name.endsWith(".json")) {
                            val json = zip.bufferedReader().readText()
                            return parseFromJson(json)
                        }
                        entry = zip.nextEntry
                    }
                }
            }

            if (!foundDb) error("No newpipe.db or subscriptions JSON found inside ZIP.")

            val result = mutableListOf<SubscriptionEntity>()
            SQLiteDatabase.openDatabase(tempDbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery(
                    "SELECT url, name FROM subscriptions WHERE service_id = 0",
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val url = cursor.getString(0).orEmpty()
                        val name = cursor.getString(1).orEmpty().ifBlank { "Subscribed Channel" }
                        val channelId = channelIdRegex.find(url)?.groupValues?.getOrNull(1) ?: continue
                        result.add(SubscriptionEntity(channelId = channelId, name = name, url = url))
                    }
                }
            }
            return result.distinctBy { it.channelId }
        } finally {
            tempDbFile.delete()
        }
    }
}
