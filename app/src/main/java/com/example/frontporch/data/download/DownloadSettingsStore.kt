package com.example.frontporch.data.download

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

class DownloadSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("frontporch_download_prefs", Context.MODE_PRIVATE)

    fun getCustomFolderUri(): Uri? {
        val raw = prefs.getString("download_tree_uri", null) ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    fun setCustomFolderUri(uri: Uri?) {
        prefs.edit().putString("download_tree_uri", uri?.toString()).apply()
    }

    fun getSortNewestFirst(): Boolean {
        return prefs.getBoolean("sort_newest_first", false) // default false = oldest to newest
    }

    fun setSortNewestFirst(newestFirst: Boolean) {
        prefs.edit().putBoolean("sort_newest_first", newestFirst).apply()
    }

    fun getDisplayFolderPath(): String {
        val uri = getCustomFolderUri()
        return if (uri != null) {
            Uri.decode(uri.lastPathSegment ?: uri.toString())
        } else {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "FrontPorch"
            ).absolutePath
        }
    }
}
