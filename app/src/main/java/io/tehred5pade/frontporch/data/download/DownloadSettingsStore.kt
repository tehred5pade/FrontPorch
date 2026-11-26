package io.tehred5pade.frontporch.data.download

import android.content.Context
import android.net.Uri

class DownloadSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("frontporch_download_prefs", Context.MODE_PRIVATE)

    fun getCustomFolderUri(): Uri? = null

    fun setCustomFolderUri(uri: Uri?) {}

    fun getSortNewestFirst(): Boolean {
        return prefs.getBoolean("sort_newest_first", false) // default false = oldest to newest
    }

    fun setSortNewestFirst(newestFirst: Boolean) {
        prefs.edit().putBoolean("sort_newest_first", newestFirst).apply()
    }

    fun getDisplayFolderPath(): String {
        return "App Storage (Internal)"
    }
}
