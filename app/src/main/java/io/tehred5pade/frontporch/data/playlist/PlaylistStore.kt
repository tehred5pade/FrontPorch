package io.tehred5pade.frontporch.data.playlist

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PlaylistEntry(
    val videoId: String,
    val title: String,
    val channelName: String,
    val localVideoPath: String? = null,
    val localThumbnailPath: String? = null,
    val thumbnailUrl: String? = null
)

data class Playlist(
    val id: String,
    val name: String,
    val entries: List<PlaylistEntry>
)

/**
 * Small persistent playlist store. Playlists are kept in SharedPreferences as JSON so no
 * Room migration is needed; the in-memory StateFlow keeps every screen in sync immediately.
 */
class PlaylistStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "frontporch_playlists",
        Context.MODE_PRIVATE
    )

    private val _playlists = MutableStateFlow(load())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    fun create(name: String, initialEntry: PlaylistEntry? = null): Playlist {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "Playlist name cannot be blank" }
        val playlist = Playlist(
            id = UUID.randomUUID().toString(),
            name = trimmed,
            entries = listOfNotNull(initialEntry).distinctBy { it.videoId }
        )
        commit(_playlists.value + playlist)
        return playlist
    }

    fun addToPlaylist(playlistId: String, entry: PlaylistEntry) {
        commit(
            _playlists.value.map { playlist ->
                if (playlist.id != playlistId || playlist.entries.any { it.videoId == entry.videoId }) {
                    playlist
                } else {
                    playlist.copy(entries = playlist.entries + entry)
                }
            }
        )
    }

    fun removeFromPlaylist(playlistId: String, videoId: String) {
        commit(
            _playlists.value.map { playlist ->
                if (playlist.id != playlistId) {
                    playlist
                } else {
                    playlist.copy(entries = playlist.entries.filterNot { it.videoId == videoId })
                }
            }
        )
    }

    fun deletePlaylist(playlistId: String) {
        commit(_playlists.value.filterNot { it.id == playlistId })
    }

    private fun commit(next: List<Playlist>) {
        _playlists.value = next
        val arr = JSONArray()
        next.forEach { playlist ->
            val entries = JSONArray()
            playlist.entries.forEach { entry ->
                entries.put(
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
            arr.put(
                JSONObject().apply {
                    put("id", playlist.id)
                    put("name", playlist.name)
                    put("entries", entries)
                }
            )
        }
        prefs.edit().putString(KEY_PLAYLISTS, arr.toString()).apply()
    }

    private fun load(): List<Playlist> = runCatching {
        val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return@runCatching emptyList()
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                val name = obj.optString("name")
                if (id.isBlank() || name.isBlank()) continue
                val entryArr = obj.optJSONArray("entries") ?: JSONArray()
                val entries = buildList {
                    for (j in 0 until entryArr.length()) {
                        val entryObj = entryArr.optJSONObject(j) ?: continue
                        val videoId = entryObj.optString("videoId")
                        if (videoId.isBlank()) continue
                        add(
                            PlaylistEntry(
                                videoId = videoId,
                                title = entryObj.optString("title"),
                                channelName = entryObj.optString("channelName"),
                                localVideoPath = entryObj.optString("localVideoPath").ifBlank { null },
                                localThumbnailPath = entryObj.optString("localThumbnailPath").ifBlank { null },
                                thumbnailUrl = entryObj.optString("thumbnailUrl").ifBlank { null }
                            )
                        )
                    }
                }
                add(Playlist(id, name, entries))
            }
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY_PLAYLISTS = "playlists_json"
    }
}
