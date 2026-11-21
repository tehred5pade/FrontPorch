package com.example.frontporch.data.importexport

import org.json.JSONObject

data class ImportedChannel(
    val channelId: String,
    val title: String
)

object SubscriptionImporter {

    private val channelIdRegex = Regex("""(?:/channel/|/c/|^)(UC[a-zA-Z0-9_-]{22})""")

    /**
     * Parses either a NewPipe .json export or a YouTube Google Takeout subscriptions.csv file.
     */
    fun parseSubscriptionsFile(rawContent: String): List<ImportedChannel> {
        val trimmed = rawContent.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (trimmed.isEmpty()) return emptyList()

        return if (trimmed.startsWith("{")) {
            parseNewPipeJson(trimmed)
        } else {
            parseYouTubeTakeoutCsv(trimmed)
        }
    }

    private fun parseNewPipeJson(jsonText: String): List<ImportedChannel> {
        val results = mutableListOf<ImportedChannel>()
        runCatching {
            val root = JSONObject(jsonText)
            val subs = root.optJSONArray("subscriptions") ?: return@runCatching

            for (i in 0 until subs.length()) {
                val item = subs.optJSONObject(i) ?: continue
                // service_id == 0 is YouTube in NewPipe
                if (item.has("service_id") && item.optInt("service_id", 0) != 0) continue

                val url = item.optString("url")
                val channelId = extractChannelIdFromUrl(url)
                val name = item.optString("name").ifBlank { channelId }

                if (channelId.startsWith("UC")) {
                    results.add(ImportedChannel(channelId = channelId, title = name))
                }
            }
        }

        if (results.isEmpty()) {
            val subObjectRegex = Regex("""\{[^{}]*"service_id"\s*:\s*0[^{}]*\}""")
            val urlRegex = Regex(""""url"\s*:\s*"([^"]+)"""")
            val nameRegex = Regex(""""name"\s*:\s*"([^"]+)"""")

            subObjectRegex.findAll(jsonText).forEach { match ->
                val block = match.value
                val url = urlRegex.find(block)?.groupValues?.getOrNull(1).orEmpty()
                val channelId = extractChannelIdFromUrl(url)
                val name = nameRegex.find(block)?.groupValues?.getOrNull(1).orEmpty().ifBlank { channelId }

                if (channelId.startsWith("UC")) {
                    results.add(ImportedChannel(channelId = channelId, title = name))
                }
            }
        }

        return results.distinctBy { it.channelId }
    }

    private fun parseYouTubeTakeoutCsv(csvText: String): List<ImportedChannel> {
        val lines = csvText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        if (lines.isEmpty()) return emptyList()

        val results = mutableListOf<ImportedChannel>()
        for ((index, line) in lines.withIndex()) {
            // Skip Google Takeout header row ("Channel Id,Channel Url,Channel Title")
            if (index == 0 && line.startsWith("Channel Id", ignoreCase = true)) continue

            val cols = splitCsvRow(line)
            if (cols.isEmpty()) continue

            val rawId = cols.getOrNull(0).orEmpty().trim()
            val rawUrl = cols.getOrNull(1).orEmpty().trim()
            val channelId = if (rawId.startsWith("UC")) {
                rawId
            } else {
                extractChannelIdFromUrl(rawUrl)
            }

            val title = cols.getOrNull(2)?.trim().orEmpty().ifBlank { channelId }
            if (channelId.startsWith("UC")) {
                results.add(ImportedChannel(channelId = channelId, title = title))
            }
        }
        return results.distinctBy { it.channelId }
    }

    private fun extractChannelIdFromUrl(url: String): String {
        val match = channelIdRegex.find(url)
        if (match != null) {
            return match.groupValues[1]
        }
        val marker = "/channel/"
        val idx = url.indexOf(marker)
        if (idx == -1) return ""
        return url.substring(idx + marker.length)
            .substringBefore('/')
            .substringBefore('?')
            .trim()
    }

    /**
     * Handles RFC-4180 CSV rows where a channel title contains commas inside double quotes.
     */
    private fun splitCsvRow(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < line.length) {
            val ch = line[i]
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    current.append('"')
                    i++
                } else {
                    inQuotes = !inQuotes
                }
            } else if (ch == ',' && !inQuotes) {
                fields.add(current.toString())
                current.setLength(0)
            } else {
                current.append(ch)
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }
}
