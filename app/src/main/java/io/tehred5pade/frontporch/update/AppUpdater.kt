package io.tehred5pade.frontporch.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val versionName: String,
    val tagName: String,
    val releaseNotes: String,
    val apkDownloadUrl: String,
    val htmlUrl: String
)

sealed class UpdateCheckResult {
    data class UpdateAvailable(val release: ReleaseInfo) : UpdateCheckResult()
    data class UpToDate(val currentVersion: String) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

object AppUpdater {
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/tehred5pade/FrontPorch/releases/latest"
    private const val PREFS_NAME = "frontporch_update_prefs"
    private const val KEY_AUTO_CHECK_ON_LAUNCH = "auto_check_on_launch"
    private const val KEY_LAST_CHECK_MS = "last_check_ms"

    fun isAutoCheckEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CHECK_ON_LAUNCH, true)

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_CHECK_ON_LAUNCH, enabled)
            .apply()
    }

    fun getLastCheckedTimestamp(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CHECK_MS, 0L)

    fun getInstalledVersionName(context: Context): String {
        return try {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pkgInfo.versionName ?: "0.00"
        } catch (e: Exception) {
            "0.00"
        }
    }

    suspend fun checkForUpdates(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(LATEST_RELEASE_API)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "FrontPorch-Android")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return@withContext UpdateCheckResult.Error("GitHub returned HTTP $code")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            // Record successful check timestamp
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis())
                .apply()

            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "").trim()
            val remoteVersion = normalizeVersion(tagName)
            val releaseNotes = json.optString("body", "No release notes provided.")
            val htmlUrl = json.optString("html_url", "https://github.com/tehred5pade/FrontPorch/releases")

            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url", "")
                        break
                    }
                }
            }

            if (apkUrl.isEmpty()) {
                return@withContext UpdateCheckResult.Error("No APK attached to release $tagName")
            }

            val currentVersion = getInstalledVersionName(context)
            if (isNewerVersion(remoteVersion, currentVersion)) {
                UpdateCheckResult.UpdateAvailable(
                    ReleaseInfo(
                        versionName = remoteVersion,
                        tagName = tagName,
                        releaseNotes = releaseNotes,
                        apkDownloadUrl = apkUrl,
                        htmlUrl = htmlUrl
                    )
                )
            } else {
                UpdateCheckResult.UpToDate(currentVersion)
            }
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.localizedMessage ?: "Failed to check for updates")
        }
    }

    /**
     * GitHub tags in this repo look like "FrontPorch0.02" or "v0.03". Strip any prefix up to
     * the first digit so the numeric comparison uses the actual version, not the tag text.
     */
    fun normalizeVersion(raw: String): String {
        val trimmed = raw.trim()
        val firstDigit = trimmed.indexOfFirst { it.isDigit() }
        if (firstDigit < 0) return trimmed
        return trimmed.substring(firstDigit)
            .takeWhile { it.isDigit() || it == '.' || it == '-' || it == '_' }
            .trimEnd('.', '-', '_')
    }

    /**
     * Compares numeric segments like "0.02" vs "0.01" or "1.2.0" vs "1.1.9".
     */
    fun isNewerVersion(remote: String, installed: String): Boolean {
        val remoteParts = normalizeVersion(remote).split(".", "-", "_").mapNotNull { it.toIntOrNull() }
        val installedParts = normalizeVersion(installed).split(".", "-", "_").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(remoteParts.size, installedParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = installedParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    suspend fun downloadAndInstallApk(
        context: Context,
        apkUrl: String,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Prompt user to enable "Install unknown apps" for Front Porch if not yet granted
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()
            ) {
                withContext(Dispatchers.Main) {
                    val permIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(permIntent)
                }
                return@withContext Result.failure(
                    IllegalStateException("Allow 'Install unknown apps' for Front Porch, then tap Install again.")
                )
            }

            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            // Clean older downloaded APKs
            updatesDir.listFiles()?.forEach { it.delete() }
            val apkFile = File(updatesDir, "FrontPorch-update.apk")

            val connection = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "FrontPorch-Android")
                connectTimeout = 15_000
                readTimeout = 30_000
            }

            val totalBytes = connection.contentLengthLong
            connection.inputStream.use { input ->
                apkFile.outputStream().use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var downloaded = 0L
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (totalBytes > 0) {
                            withContext(Dispatchers.Main) {
                                onProgress((downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            connection.disconnect()

            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            withContext(Dispatchers.Main) {
                context.startActivity(installIntent)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
