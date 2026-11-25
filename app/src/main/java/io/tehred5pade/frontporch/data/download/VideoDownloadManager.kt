package io.tehred5pade.frontporch.data.download

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.OptIn
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.MediaMuxerCompat
import io.tehred5pade.frontporch.data.innertube.FeedVideoItem
import io.tehred5pade.frontporch.data.local.DownloadedVideoDao
import io.tehred5pade.frontporch.data.local.DownloadedVideoEntity
import io.tehred5pade.frontporch.service.DownloadForegroundService
import io.tehred5pade.frontporch.ui.downloads.DownloadQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.schabi.newpipe.extractor.MediaFormat as NewPipeMediaFormat
import org.schabi.newpipe.extractor.downloader.Request as NewPipeRequest
import org.schabi.newpipe.extractor.downloader.Response as NewPipeResponse

data class ActiveDownloadItem(
    val videoId: String,
    val title: String,
    val channelName: String,
    val qualityLabel: String,
    val progress: Float,
    val statusText: String,
    /** Failed downloads stay visible so the user can retry them. */
    val isFailed: Boolean = false
)

/**
 * Coroutine cancellation alone cannot interrupt the blocking OkHttp/muxer work in a
 * download. Each download owns one of these so [cancelDownload] can abort the in-flight
 * requests and stop the pipeline before the result is written to the downloads folder.
 */
private class DownloadCancellation {
    private val calls = mutableSetOf<Call>()

    @Volatile
    var isCancelled: Boolean = false
        private set

    fun register(call: Call) {
        val cancelNow = synchronized(calls) {
            if (isCancelled) true else {
                calls.add(call)
                false
            }
        }
        if (cancelNow) runCatching { call.cancel() }
    }

    fun unregister(call: Call) {
        synchronized(calls) { calls.remove(call) }
    }

    fun cancel() {
        isCancelled = true
        synchronized(calls) { calls.toList() }.forEach { runCatching { it.cancel() } }
    }

    fun ensureActive() {
        if (isCancelled) throw CancellationException("Download cancelled")
    }
}

private fun Call.executeCancellable(cancellation: DownloadCancellation): Response {
    cancellation.register(this)
    try {
        return execute()
    } finally {
        cancellation.unregister(this)
    }
}

/**
 * Progress callbacks fire on every network buffer. Without throttling, a fast download
 * triggers hundreds of StateFlow emissions per second, each rebuilding and reposting the
 * foreground notification on the main thread. That overhead itself became a bottleneck.
 */
private class ThrottledProgress(
    private val intervalMs: Long = 250L,
    private val initialBytes: Long = 0L,
    private val onReport: (Float, Long) -> Unit
) {
    private val lastReportMs = AtomicLong(0L)
    private val lastBytes = AtomicLong(initialBytes)
    @Volatile private var lastSpeedBps = 0L

    fun report(fraction: Float, bytes: Long) {
        val now = System.currentTimeMillis()
        val previous = lastReportMs.get()
        if (now - previous >= intervalMs && lastReportMs.compareAndSet(previous, now)) {
            val elapsedMs = (now - previous).coerceAtLeast(1L)
            val deltaBytes = (bytes - lastBytes.getAndSet(bytes)).coerceAtLeast(0L)
            lastSpeedBps = deltaBytes * 1000L / elapsedMs
            onReport(fraction.coerceIn(0f, 1f), lastSpeedBps)
        }
    }

    fun finish(bytes: Long) {
        lastReportMs.set(System.currentTimeMillis())
        lastBytes.set(bytes)
        onReport(1f, lastSpeedBps)
    }
}

private fun formatSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1_000_000 -> "%.1f MB/s".format(bytesPerSecond / 1_000_000f)
    bytesPerSecond >= 1_000 -> "%.0f KB/s".format(bytesPerSecond / 1_000f)
    else -> "$bytesPerSecond B/s"
}

/**
 * Small sidecar files describe which parts of a temp file are safe to reuse. Without them a
 * preallocated ranged download looks "complete" by length even though most chunks are holes.
 */
private sealed interface DownloadPartState {
    data class Direct(
        val identity: String,
        val totalBytes: Long,
        val chunkSize: Long,
        val chunkCount: Int,
        val completedChunks: Set<Int>
    ) : DownloadPartState

    data class Hls(
        val identity: String,
        val segmentCount: Int,
        val completedSegments: Int,
        val completedBytes: Long
    ) : DownloadPartState
}

private fun streamIdentity(url: String): String =
    url.substringBefore('?').replace("|", "%7C")

private fun serializeDownloadPartState(state: DownloadPartState): String = when (state) {
    is DownloadPartState.Direct -> listOf(
        "direct",
        state.identity,
        state.totalBytes.toString(),
        state.chunkSize.toString(),
        state.chunkCount.toString(),
        state.completedChunks.sorted().joinToString(",")
    ).joinToString("|")

    is DownloadPartState.Hls -> listOf(
        "hls",
        state.identity,
        state.segmentCount.toString(),
        state.completedSegments.toString(),
        state.completedBytes.toString()
    ).joinToString("|")
}

private fun parseDownloadPartState(raw: String): DownloadPartState? {
    val parts = raw.trim().split("|")
    return when (parts.getOrNull(0)) {
        "direct" -> {
            val completed = parts.getOrNull(5).orEmpty()
                .split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .toSet()
            DownloadPartState.Direct(
                identity = parts.getOrNull(1).orEmpty(),
                totalBytes = parts.getOrNull(2)?.toLongOrNull() ?: return null,
                chunkSize = parts.getOrNull(3)?.toLongOrNull() ?: return null,
                chunkCount = parts.getOrNull(4)?.toIntOrNull() ?: return null,
                completedChunks = completed
            )
        }

        "hls" -> DownloadPartState.Hls(
            identity = parts.getOrNull(1).orEmpty(),
            segmentCount = parts.getOrNull(2)?.toIntOrNull() ?: return null,
            completedSegments = parts.getOrNull(3)?.toIntOrNull() ?: return null,
            completedBytes = parts.getOrNull(4)?.toLongOrNull() ?: return null
        )

        else -> null
    }
}

data class VideoFormatCandidate(
    val itag: Int,
    val url: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Long,
    val mimeType: String
)

fun selectBestVideoFormat(
    adaptiveFormats: JSONArray,
    maxHeight: Int = 1080
): VideoFormatCandidate? {
    val candidates = mutableListOf<VideoFormatCandidate>()

    for (i in 0 until adaptiveFormats.length()) {
        val obj = adaptiveFormats.optJSONObject(i) ?: continue
        val mime = obj.optString("mimeType")
        // MediaMuxer supports H.264 (avc1) in MP4 containers natively
        if (!mime.startsWith("video/mp4") || !mime.contains("avc1")) continue

        val url = obj.optString("url")
        if (url.isBlank()) continue

        val height = obj.optInt("height", 0)
        val width = obj.optInt("width", 0)
        val fps = obj.optInt("fps", 30)
        val bitrate = obj.optLong("bitrate", 0L)
        val itag = obj.optInt("itag", 0)

        if (height in 144..maxHeight) {
            candidates.add(
                VideoFormatCandidate(
                    itag = itag,
                    url = url,
                    width = width,
                    height = height,
                    fps = fps,
                    bitrate = bitrate,
                    mimeType = mime
                )
            )
        }
    }

    // Prefer target resolution first, then highest FPS (60fps over 30fps), then highest bitrate
    return candidates.maxWithOrNull(
        compareBy<VideoFormatCandidate> { it.height }
            .thenBy { it.fps }
            .thenBy { it.bitrate }
    )
}

/**
 * Converts the H.264/MP4 streams NewPipe can actually resolve into picker options. A 60fps
 * option is added only when a stream at that height really reports ~60fps; a 30fps option is
 * added only when a 30fps stream exists. Heights the picker has no label for still remain
 * available through the "Best Available" entries.
 */
private fun availableDownloadQualitiesFrom(info: StreamInfo): Set<DownloadQuality> {
    val result = mutableSetOf<DownloadQuality>()

    val hasAudio = info.audioStreams.any {
        it.format == NewPipeMediaFormat.M4A && !it.url.isNullOrBlank()
    }
    if (hasAudio) result += DownloadQuality.AUDIO_ONLY

    val videoStreams = info.videoStreams.filter {
        it.format == NewPipeMediaFormat.MPEG_4 && it.height > 0 && !it.url.isNullOrBlank()
    } + info.videoOnlyStreams.filter {
        it.format == NewPipeMediaFormat.MPEG_4 &&
            it.codec.orEmpty().startsWith("avc", ignoreCase = true) &&
            it.height > 0 &&
            !it.url.isNullOrBlank()
    }
    if (videoStreams.isEmpty()) return result

    result += DownloadQuality.BEST_AVAILABLE
    if (videoStreams.any { it.height <= 1080 }) {
        result += DownloadQuality.BEST_AVAILABLE_HD
    }

    // [has 30fps-capable stream, has 60fps-capable stream]
    val fpsByHeight = mutableMapOf<Int, BooleanArray>()
    videoStreams.forEach { stream ->
        val flags = fpsByHeight.getOrPut(stream.height) { booleanArrayOf(false, false) }
        if (stream.fps >= 55) flags[1] = true else flags[0] = true
    }

    fpsByHeight.forEach { (height, fpsFlags) ->
        when (height) {
            2160 -> {
                if (fpsFlags[1]) result += DownloadQuality.Q2160P_60FPS
                if (fpsFlags[0]) result += DownloadQuality.Q2160P_30FPS
            }
            1440 -> {
                if (fpsFlags[1]) result += DownloadQuality.Q1440P_60FPS
                if (fpsFlags[0]) result += DownloadQuality.Q1440P_30FPS
            }
            1080 -> {
                if (fpsFlags[1]) result += DownloadQuality.Q1080P_60FPS
                if (fpsFlags[0]) result += DownloadQuality.Q1080P_30FPS
            }
            720 -> {
                if (fpsFlags[1]) result += DownloadQuality.Q720P_60FPS
                if (fpsFlags[0]) result += DownloadQuality.Q720P_30FPS
            }
            480 -> result += DownloadQuality.Q480P
            360 -> result += DownloadQuality.Q360P
            240 -> result += DownloadQuality.Q240P
            144 -> result += DownloadQuality.Q144P
        }
    }
    return result
}

class VideoDownloadManager(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val dao: DownloadedVideoDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settingsStore = DownloadSettingsStore(context)

    companion object {
        private const val TAG = "VideoDownloadManager"
    }

    // Each video can use several parallel connections. Limit simultaneous videos so a
    // large batch does not saturate the disk/TLS layer and slow every transfer down.
    private val videoDownloadSlots = Semaphore(3)

    @Volatile
    private var newPipeReady = false

    private fun ensureNewPipeInitialized(): Boolean {
        if (newPipeReady) return true
        synchronized(this) {
            if (newPipeReady) return true
            return runCatching {
                NewPipe.init(NewPipeOkHttpDownloader(okHttpClient), Localization.DEFAULT)
                newPipeReady = true
                true
            }.getOrDefault(false)
        }
    }

    private val _activeDownloads = MutableStateFlow<Map<String, ActiveDownloadItem>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, ActiveDownloadItem>> = _activeDownloads.asStateFlow()

    /** Everything needed to start again after a failed attempt. */
    private data class PendingDownload(
        val video: FeedVideoItem,
        val quality: DownloadQuality,
        val preferredAudioLanguage: String
    )

    private val failedDownloads = ConcurrentHashMap<String, PendingDownload>()
    private val stateFileLock = Any()

    private val thumbsDir: File
        get() = File(context.filesDir, "download_thumbs").apply { mkdirs() }

    private val tempWorkDir: File
        get() = File(context.cacheDir, "download_tmp").apply { mkdirs() }

    private fun stateFileFor(destination: File): File = File(tempWorkDir, "${destination.name}.state")

    private fun readPartState(destination: File): DownloadPartState? = synchronized(stateFileLock) {
        val file = stateFileFor(destination)
        if (!file.exists()) null else runCatching { parseDownloadPartState(file.readText()) }.getOrNull()
    }

    private fun writePartState(destination: File, state: DownloadPartState) {
        synchronized(stateFileLock) {
            val file = stateFileFor(destination)
            val tmp = File(file.path + ".tmp")
            runCatching {
                tmp.writeText(serializeDownloadPartState(state))
                if (!tmp.renameTo(file)) {
                    file.writeText(serializeDownloadPartState(state))
                    tmp.delete()
                }
            }
        }
    }

    private fun deletePartState(destination: File) {
        synchronized(stateFileLock) {
            val file = stateFileFor(destination)
            file.delete()
            File(file.path + ".tmp").delete()
        }
    }

    /** Removes only resumable temp artifacts; completed downloads are never touched. */
    private fun clearTempArtifacts(videoId: String) {
        listOf("${videoId}_v.mp4", "${videoId}_a.m4a", "${videoId}_out.mp4").forEach { name ->
            val file = File(tempWorkDir, name)
            file.delete()
            deletePartState(file)
        }
    }

    /**
     * Reject empty or corrupt output before it is written to the downloads folder or the DB.
     * A file can be a structurally valid MP4 yet contain zero media samples (for example when
     * MediaExtractor had no selected track), which used to surface as "0.0 MB" and no playback.
     */
    private fun validateMediaFile(file: File, audioOnly: Boolean = false) {
        if (!file.exists() || file.length() < 4_096L) {
            throw IllegalStateException("Downloaded file is empty or too small")
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var hasSamples = false
            val checkBuffer = java.nio.ByteBuffer.allocateDirect(1024)
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                val isVideo = mime.startsWith("video/")
                val isAudio = mime.startsWith("audio/")
                if (!isVideo && !isAudio) continue
                if (audioOnly && isVideo) continue
                extractor.selectTrack(i)
                if (extractor.readSampleData(checkBuffer, 0) >= 0 || extractor.sampleTime >= 0L) {
                    hasSamples = true
                }
                extractor.unselectTrack(i)
            }
            if (!hasSamples) throw IllegalStateException("Downloaded file has no playable media samples")
        } finally {
            runCatching { extractor.release() }
        }
    }

    // Touched from the UI (cancel tap) and from IO coroutines, so keep them thread-safe.
    private val downloadJobs = ConcurrentHashMap<String, Job>()
    private val downloadCancellations = ConcurrentHashMap<String, DownloadCancellation>()

    fun cancelDownload(videoId: String) {
        downloadCancellations.remove(videoId)?.cancel()
        downloadJobs[videoId]?.cancel()
        downloadJobs.remove(videoId)
        failedDownloads.remove(videoId)
        _activeDownloads.update { it - videoId }
        clearTempArtifacts(videoId)
    }

    fun enqueueVideos(videos: List<FeedVideoItem>, quality: DownloadQuality, preferredAudioLanguage: String = "en") {
        val current = _activeDownloads.value
        val toStart = mutableListOf<FeedVideoItem>()

        videos.forEach { video ->
            val existing = current[video.videoId]
            if (existing == null || existing.isFailed) {
                // Re-enqueueing a failed download starts clean: the quality may have changed,
                // and an explicit fresh request should not silently reuse old partial bytes.
                if (existing?.isFailed == true) {
                    failedDownloads.remove(video.videoId)
                    clearTempArtifacts(video.videoId)
                }
                updateProgress(video, quality.label, 0.02f, "Resolving ${quality.label}...")
                toStart.add(video)
            }
        }
        if (toStart.isEmpty()) return

        // Publish the active state before starting the service so its first notification has
        // something to show. The manager remains the single source of truth for progress and
        // cancellation; the service only keeps the process alive after the user leaves the task.
        DownloadForegroundService.start(context.applicationContext)
        toStart.forEach { video ->
            launchDownload(video, quality, preferredAudioLanguage, resume = false)
        }
    }

    /** Retry starts from partial files and only clears them if the resume attempt fails. */
    fun retryDownload(videoId: String) {
        val pending = failedDownloads.remove(videoId) ?: return

        val lastProgress = _activeDownloads.value[videoId]?.progress ?: 0f
        updateProgress(
            pending.video,
            pending.quality.label,
            lastProgress,
            "Resuming ${pending.quality.label}..."
        )
        DownloadForegroundService.start(context.applicationContext)
        launchDownload(
            pending.video,
            pending.quality,
            pending.preferredAudioLanguage,
            resume = true
        )
    }

    private fun launchDownload(
        video: FeedVideoItem,
        quality: DownloadQuality,
        preferredAudioLanguage: String,
        resume: Boolean
    ) {
        val job = scope.launch {
            videoDownloadSlots.withPermit {
                runDownloadJob(video, quality, preferredAudioLanguage, resume)
            }
        }
        downloadJobs[video.videoId] = job
        // Only clear this exact job; a retry may already have replaced the map entry.
        job.invokeOnCompletion { downloadJobs.remove(video.videoId, job) }
    }

    private suspend fun runDownloadJob(
        video: FeedVideoItem,
        quality: DownloadQuality,
        preferredAudioLanguage: String,
        resume: Boolean
    ) {
        val cancellation = DownloadCancellation().also { downloadCancellations[video.videoId] = it }
        try {
            val maxAttempts = if (resume) 2 else 1
            var useResume = resume
            var lastError: Throwable? = null
            var attempt = 0

            while (attempt < maxAttempts) {
                try {
                    downloadSingle(video, quality, preferredAudioLanguage, cancellation, useResume)
                    finishDownloadSuccess(video)
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    if (cancellation.isCancelled) throw CancellationException("Download cancelled", t)
                    lastError = t
                    attempt++
                    if (attempt < maxAttempts) {
                        val lastProgress = _activeDownloads.value[video.videoId]?.progress ?: 0f
                        updateProgress(
                            video,
                            quality.label,
                            lastProgress,
                            "Resume failed, restarting..."
                        )
                        clearTempArtifacts(video.videoId)
                        useResume = false
                    }
                }
            }
            markDownloadFailed(video, quality, preferredAudioLanguage, lastError)
        } finally {
            // A retry may have installed a newer cancellation; do not remove that one.
            downloadCancellations.remove(video.videoId, cancellation)
        }
    }

    private fun finishDownloadSuccess(video: FeedVideoItem) {
        failedDownloads.remove(video.videoId)
        _activeDownloads.update { it - video.videoId }
        clearTempArtifacts(video.videoId)
    }

    private fun markDownloadFailed(
        video: FeedVideoItem,
        quality: DownloadQuality,
        preferredAudioLanguage: String,
        error: Throwable?
    ) {
        val excName = error?.javaClass?.simpleName ?: "Error"
        val topTrace = error?.stackTrace?.firstOrNull()?.let { "${it.className.substringAfterLast('.')}.${it.methodName}(:${it.lineNumber})" }
        val excMsg = error?.message?.takeIf { !it.isBlank() }
            ?: error?.cause?.message?.takeIf { !it.isBlank() }
            ?: topTrace
            ?: error?.toString()
            ?: "Download error"
        val fullReason = "$excName: $excMsg"
        Log.e("FrontPorchDownload", "Download failed for ${video.videoId} (${video.title}): $fullReason", error)
        failedDownloads[video.videoId] = PendingDownload(video, quality, preferredAudioLanguage)
        _activeDownloads.update { current ->
            val updated = current[video.videoId]?.copy(
                isFailed = true,
                statusText = "Failed: $fullReason"
            ) ?: ActiveDownloadItem(
                videoId = video.videoId,
                title = video.title,
                channelName = video.channelName,
                qualityLabel = quality.label,
                progress = 0f,
                statusText = "Failed: $fullReason",
                isFailed = true
            )
            current + (video.videoId to updated)
        }
    }

    private fun updateProgress(
        video: FeedVideoItem,
        qualityLabel: String,
        progress: Float,
        statusText: String,
        isFailed: Boolean = false
    ) {
        _activeDownloads.update { current ->
            current + (
                video.videoId to ActiveDownloadItem(
                    videoId = video.videoId,
                    title = video.title,
                    channelName = video.channelName,
                    qualityLabel = qualityLabel,
                    progress = progress.coerceIn(0f, 1f),
                    statusText = statusText,
                    isFailed = isFailed
                )
            )
        }
    }

    private val iosUserAgent =
        "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
    private val webUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val safariMobileUserAgent =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_3_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3 Mobile/15E148 Safari/604.1"

    private fun fetchSessionContext(cancellation: DownloadCancellation): Pair<String?, String?> = runCatching {
        val req = Request.Builder()
            .url("https://www.youtube.com/sw.js_data")
            .header("User-Agent", webUserAgent)
            .header("Accept", "*/*")
            .get()
            .build()

        okHttpClient.newCall(req).executeCancellable(cancellation).use { resp ->
            val raw = resp.body?.string().orEmpty().removePrefix(")]}'")
            val cookies = resp.headers("Set-Cookie")
                .joinToString("; ") { it.substringBefore(";") }
                .takeIf { it.isNotBlank() }
            val vd = Regex(""""(Cgt[a-zA-Z0-9_-]{10,})"""").find(raw)?.groupValues?.getOrNull(1)
            vd to cookies
        }
    }.getOrDefault(null to null)

    private data class HlsMasterResult(
        val title: String,
        val channelName: String,
        val masterManifestUrl: String
    )

    private data class ResolvedQualityStreams(
        val title: String,
        val channelName: String,
        val videoUrl: String?,
        val audioUrl: String?,
        val muxedUrl: String?,
        val userAgent: String,
        val cookieHeader: String?
    )

    /**
     * Resolves direct googlevideo URLs via NewPipeExtractor. It handles signature/n-parameter
     * deciphering and returns plain ranged-MP4 URLs that can be downloaded with OkHttp.
     */
    private suspend fun resolveViaNewPipe(
        videoId: String,
        quality: DownloadQuality,
        cancellation: DownloadCancellation,
        preferredAudioLanguage: String = "en"
    ): ResolvedQualityStreams? = withContext(Dispatchers.IO) {
        if (!ensureNewPipeInitialized()) return@withContext null

        // NewPipeExtractor performs its own blocking requests; let them join this download's
        // cancellation so a cancel during resolution stops the network work immediately.
        NewPipeOkHttpDownloader.setCancellation(cancellation)
        try {
            runCatching {
                cancellation.ensureActive()
                val info = StreamInfo.getInfo(
                    ServiceList.YouTube,
                    "https://www.youtube.com/watch?v=$videoId"
                )
                cancellation.ensureActive()
                val title = info.name.orEmpty()
                val channelName = info.uploaderName.orEmpty()
                val targetHeight = quality.maxHeight

                val aacAudio = info.audioStreams
                    .filter { it.format == NewPipeMediaFormat.M4A || it.codec.orEmpty().startsWith("mp4a", ignoreCase = true) }
                    .filter { stream ->
                        val lang = stream.audioLocale?.language.orEmpty()
                        preferredAudioLanguage.isBlank() || preferredAudioLanguage == "auto" || lang.equals(preferredAudioLanguage, ignoreCase = true)
                    }
                    .maxByOrNull { it.averageBitrate }
                    ?: info.audioStreams
                        .filter { it.format == NewPipeMediaFormat.M4A || it.codec.orEmpty().startsWith("mp4a", ignoreCase = true) }
                        .maxByOrNull { it.averageBitrate }

                if (quality.audioOnly) {
                    val targetAudio = aacAudio ?: info.audioStreams.maxByOrNull { it.averageBitrate }
                    return@withContext targetAudio?.url?.let {
                        ResolvedQualityStreams(title, channelName, null, it, null, "", null)
                    }
                }

                // Prefer progressive (muxed) MP4 streams: they play as-is and skip the
                // MediaMuxer step entirely. YouTube offers these at 360p and below.
                val muxed = info.videoStreams
                    .filter { it.format == NewPipeMediaFormat.MPEG_4 }
                    .filter { it.height > 0 && it.height <= targetHeight }
                    .maxWithOrNull(
                        compareBy<org.schabi.newpipe.extractor.stream.VideoStream> { it.height }
                            .thenBy { if (quality.targetFps > 0) (if (it.fps >= quality.targetFps) 1 else 0) else it.fps }
                            .thenBy { it.fps }
                            .thenBy { it.bitrate }
                    )

                if (muxed != null) {
                    return@withContext ResolvedQualityStreams(
                        title, channelName, null, null, muxed.url, "", null
                    )
                }

                // 1. Primary: H.264/AVC MP4 video-only stream plus AAC/M4A audio track
                val avcVideo = info.videoOnlyStreams
                    .filter { it.format == NewPipeMediaFormat.MPEG_4 }
                    .filter { it.codec.isNullOrBlank() || it.codec.startsWith("avc", ignoreCase = true) }
                    .filter { it.height > 0 && it.height <= targetHeight }
                    .maxWithOrNull(
                        compareBy<org.schabi.newpipe.extractor.stream.VideoStream> { it.height }
                            .thenBy { if (quality.targetFps > 0) (if (it.fps >= quality.targetFps) 1 else 0) else it.fps }
                            .thenBy { it.fps }
                            .thenBy { it.bitrate }
                    )

                if (avcVideo != null && aacAudio != null) {
                    return@withContext ResolvedQualityStreams(
                        title, channelName, avcVideo.url, aacAudio.url, null, "", null
                    )
                }

                // 2. WebM Fallback: VP9/AV1 video + Opus audio (for WebM container output)
                val opusAudio = info.audioStreams
                    .filter { it.format == NewPipeMediaFormat.WEBMA || it.codec.orEmpty().contains("opus", ignoreCase = true) }
                    .maxByOrNull { it.averageBitrate }

                val webmVideo = info.videoOnlyStreams
                    .filter { it.format == NewPipeMediaFormat.WEBMA || it.codec.orEmpty().contains("vp", ignoreCase = true) || it.codec.orEmpty().contains("av01", ignoreCase = true) }
                    .filter { it.height > 0 && it.height <= targetHeight }
                    .maxWithOrNull(
                        compareBy<org.schabi.newpipe.extractor.stream.VideoStream> { it.height }
                            .thenBy { if (quality.targetFps > 0) (if (it.fps >= quality.targetFps) 1 else 0) else it.fps }
                            .thenBy { it.fps }
                            .thenBy { it.bitrate }
                    )

                if (webmVideo != null && opusAudio != null) {
                    return@withContext ResolvedQualityStreams(
                        title, channelName, webmVideo.url, opusAudio.url, null, "", null
                    )
                }

                // 3. Fallback: Any video stream + best available audio stream
                val fallbackVideo = info.videoOnlyStreams
                    .filter { it.height > 0 && it.height <= targetHeight }
                    .maxWithOrNull(
                        compareBy<org.schabi.newpipe.extractor.stream.VideoStream> { it.height }
                            .thenBy { if (quality.targetFps > 0) (if (it.fps >= quality.targetFps) 1 else 0) else it.fps }
                            .thenBy { it.fps }
                            .thenBy { it.bitrate }
                    )

                val fallbackAudio = aacAudio ?: opusAudio ?: info.audioStreams.maxByOrNull { it.averageBitrate }

                if (fallbackVideo != null && fallbackAudio != null) {
                    return@withContext ResolvedQualityStreams(
                        title, channelName, fallbackVideo.url, fallbackAudio.url, null, "", null
                    )
                }

                null
            }.getOrElse { err ->
                Log.e("FrontPorchDownload", "NewPipe resolution failed for $videoId: ${err.message}", err)
                null
            }
        } finally {
            NewPipeOkHttpDownloader.setCancellation(null)
        }
    }

    /**
     * Probes each video with the same NewPipe path used by the downloader and returns the
     * intersection of qualities that can actually be downloaded. Empty means the probe failed,
     * so callers can fall back to the full allowed list instead of offering nothing.
     */
    suspend fun probeAvailableDownloadQualities(videos: List<FeedVideoItem>): Set<DownloadQuality> {
        if (videos.isEmpty()) return emptySet()
        if (!ensureNewPipeInitialized()) return emptySet()

        val probeSlots = Semaphore(4)
        val perVideo = withContext(Dispatchers.IO) {
            coroutineScope {
                videos.map { video ->
                    async {
                        if (!isActive) return@async emptySet<DownloadQuality>()
                        probeSlots.withPermit {
                            runCatching {
                                StreamInfo.getInfo(
                                    ServiceList.YouTube,
                                    "https://www.youtube.com/watch?v=${video.videoId}"
                                )
                            }.getOrNull()?.let(::availableDownloadQualitiesFrom) ?: emptySet()
                        }
                    }
                }.awaitAll()
            }
        }

        if (perVideo.any { it.isEmpty() }) return emptySet()
        return perVideo.reduce { common, next -> common intersect next }
    }

    private fun fetchIosHlsManifest(videoId: String, cancellation: DownloadCancellation): HlsMasterResult? {
        val (sessionVisitor, sessionCookie) = fetchSessionContext(cancellation)
        cancellation.ensureActive()

        val clientObj = JSONObject().apply {
            if (!sessionVisitor.isNullOrBlank()) put("visitorData", sessionVisitor)
            put("clientName", "IOS")
            put("clientVersion", "20.10.4")
            put("deviceMake", "Apple")
            put("deviceModel", "iPhone16,2")
            put("osName", "iPhone")
            put("osVersion", "18.3.2.22D82")
            put("hl", "en")
            put("gl", "US")
        }

        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("context", JSONObject().put("client", clientObj))
        }

        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", iosUserAgent)
            .header("X-YouTube-Client-Name", "5")
            .header("X-YouTube-Client-Version", "20.10.4")
            .apply {
                if (!sessionVisitor.isNullOrBlank()) header("X-Goog-Visitor-Id", sessionVisitor)
                if (!sessionCookie.isNullOrBlank()) header("Cookie", sessionCookie)
            }
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val body = runCatching {
            okHttpClient.newCall(req).executeCancellable(cancellation).use { it.body?.string().orEmpty() }
        }.getOrNull()
        val root = runCatching { JSONObject(body.orEmpty()) }.getOrNull() ?: return null

        if (root.optJSONObject("playabilityStatus")?.optString("status") != "OK") return null

        val hlsUrl = root.optJSONObject("streamingData")
            ?.optString("hlsManifestUrl")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val details = root.optJSONObject("videoDetails")
        return HlsMasterResult(
            title = details?.optString("title").orEmpty(),
            channelName = details?.optString("author").orEmpty(),
            masterManifestUrl = hlsUrl
        )
    }

    private suspend fun downloadSingle(
        video: FeedVideoItem,
        quality: DownloadQuality,
        preferredAudioLanguage: String,
        cancellation: DownloadCancellation,
        resume: Boolean
    ) {
        val rawVideoFile = File(tempWorkDir, "${video.videoId}_v.mp4")
        val rawAudioFile = File(tempWorkDir, "${video.videoId}_a.m4a")
        val tempMuxed = File(tempWorkDir, "${video.videoId}_out.mp4")

        cancellation.ensureActive()
        val startingProgress = if (resume) (_activeDownloads.value[video.videoId]?.progress ?: 0f) else 0.04f
        updateProgress(
            video,
            quality.label,
            startingProgress,
            if (resume) "Resuming stream..." else "Resolving stream..."
        )

        // 1. Primary: NewPipeExtractor resolves direct, decipher-signed MP4 URLs.
        var resolved = resolveViaNewPipe(video.videoId, quality, cancellation, preferredAudioLanguage)
        cancellation.ensureActive()

        if (resolved == null) {
            // 2. Fallback: iOS HLS manifest
            val hlsInfo = fetchIosHlsManifest(video.videoId, cancellation)
            if (hlsInfo != null) {
                downloadFromHls(video, quality, hlsInfo, rawVideoFile, rawAudioFile, tempMuxed, cancellation, resume)
                return
            }

            // 3. Last resort: extract ytInitialPlayerResponse + live PO token (pot) via mobile web session
            updateProgress(video, quality.label, startingProgress, "Authorizing stream...")
            resolved = resolveViaWebSession(video.videoId, quality, cancellation)
            cancellation.ensureActive()
        }

        val resolvedStreams = resolved
            ?: throw IllegalStateException("Could not authorize video stream")

        val displayTitle = resolvedStreams.title.ifBlank { video.title }
        val displayChannel = resolvedStreams.channelName.ifBlank { video.channelName }

        if (quality.audioOnly) {
            val targetAudio = resolvedStreams.audioUrl ?: resolvedStreams.muxedUrl
                ?: throw IllegalStateException("No audio stream available")
            downloadDirectStream(
                targetAudio,
                resolvedStreams.userAgent,
                resolvedStreams.cookieHeader,
                rawAudioFile,
                cancellation,
                resume = resume
            ) { p, speed ->
                updateProgress(video, quality.label, 0.08f + p * 0.85f, "Audio ${(p * 100).toInt()}% · ${formatSpeed(speed)}")
            }
            cancellation.ensureActive()
            validateMediaFile(rawAudioFile, audioOnly = true)
            val saved = saveToChosenFolder(rawAudioFile, sanitizeFileName(displayTitle), "m4a", "audio/mp4")
            dao.upsert(
                DownloadedVideoEntity(
                    videoId = video.videoId,
                    title = displayTitle,
                    channelName = displayChannel,
                    localVideoPath = saved.uri,
                    localThumbnailPath = null,
                    fileSizeBytes = saved.sizeBytes,
                    durationSeconds = video.durationSeconds,
                    isShort = video.isShort
                )
            )
            return
        }

        val adaptiveVideoUrl = resolvedStreams.videoUrl
        val adaptiveAudioUrl = resolvedStreams.audioUrl
        var isWebmResult = false
        val finalTempFile: File = if (adaptiveVideoUrl != null && adaptiveAudioUrl != null) {
            // 1080p+ has no muxed stream: video and audio are separate files. Download
            // them concurrently instead of waiting for the whole video first.
            val videoPct = AtomicInteger(0)
            val audioPct = AtomicInteger(0)
            fun reportCombined(speed: Long) {
                val fraction = (videoPct.get() + audioPct.get()) / 200f
                updateProgress(
                    video, quality.label,
                    0.08f + fraction * 0.60f,
                    "Video + Audio ${(fraction * 100).toInt()}% · ${formatSpeed(speed)}"
                )
            }
            coroutineScope {
                val videoJob = async(Dispatchers.IO) {
                    downloadDirectStream(
                        adaptiveVideoUrl,
                        resolvedStreams.userAgent,
                        resolvedStreams.cookieHeader,
                        rawVideoFile,
                        cancellation,
                        resume = resume
                    ) { p, speed ->
                        videoPct.set((p * 100).toInt())
                        reportCombined(speed)
                    }
                }
                val audioJob = async(Dispatchers.IO) {
                    downloadDirectStream(
                        adaptiveAudioUrl,
                        resolvedStreams.userAgent,
                        resolvedStreams.cookieHeader,
                        rawAudioFile,
                        cancellation,
                        resume = resume
                    ) { p, speed ->
                        audioPct.set((p * 100).toInt())
                        reportCombined(speed)
                    }
                }
                awaitAll(videoJob, audioJob)
            }
            updateProgress(video, quality.label, 0.90f, "Muxing media...")
            val outputFormat = remuxTracksToMp4(rawVideoFile, rawAudioFile, tempMuxed, cancellation)
            isWebmResult = outputFormat == MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            tempMuxed
        } else if (resolvedStreams.muxedUrl != null) {
            downloadDirectStream(
                resolvedStreams.muxedUrl,
                resolvedStreams.userAgent,
                resolvedStreams.cookieHeader,
                tempMuxed,
                cancellation,
                resume = resume
            ) { p, speed ->
                updateProgress(video, quality.label, 0.08f + p * 0.84f, "Video ${(p * 100).toInt()}% · ${formatSpeed(speed)}")
            }
            tempMuxed
        } else {
            throw IllegalStateException("No playable stream for ${quality.label}")
        }

        cancellation.ensureActive()
        validateMediaFile(finalTempFile)
        updateProgress(video, quality.label, 0.95f, "Generating thumbnail...")
        val thumbFile = extractFrameFromVideo(finalTempFile, video.videoId)
        cancellation.ensureActive()
        val ext = if (isWebmResult) "webm" else "mp4"
        val mime = if (isWebmResult) "video/webm" else "video/mp4"
        val saved = saveToChosenFolder(finalTempFile, sanitizeFileName(displayTitle), ext, mime)

        dao.upsert(
            DownloadedVideoEntity(
                videoId = video.videoId,
                title = displayTitle,
                channelName = displayChannel,
                localVideoPath = saved.uri,
                localThumbnailPath = thumbFile?.absolutePath,
                fileSizeBytes = saved.sizeBytes,
                durationSeconds = video.durationSeconds,
                isShort = video.isShort
            )
        )
    }

    private suspend fun downloadFromHls(
        video: FeedVideoItem,
        quality: DownloadQuality,
        hlsInfo: HlsMasterResult,
        rawVideoStream: File,
        rawAudioStream: File,
        tempMuxed: File,
        cancellation: DownloadCancellation,
        resume: Boolean
    ) {
        val displayTitle = hlsInfo.title.ifBlank { video.title }
        val displayChannel = hlsInfo.channelName.ifBlank { video.channelName }

        val masterText = httpGetText(hlsInfo.masterManifestUrl, cancellation)
        cancellation.ensureActive()
        val (videoPlaylistUrl, audioPlaylistUrl) = parseMasterM3u8(masterText, quality)

        if (quality.audioOnly) {
            val targetAudioUrl = audioPlaylistUrl ?: videoPlaylistUrl
                ?: throw IllegalStateException("No audio playlist in manifest")
            downloadHlsSegments(targetAudioUrl, rawAudioStream, cancellation, resume = resume) { p ->
                updateProgress(video, quality.label, 0.05f + p * 0.85f, "Downloading audio ${(p * 100).toInt()}%")
            }
            updateProgress(video, quality.label, 0.93f, "Finalizing M4A...")
            remuxTracksToMp4(videoRaw = null, audioRaw = rawAudioStream, outMp4 = tempMuxed, cancellation = cancellation)
            cancellation.ensureActive()
            validateMediaFile(tempMuxed, audioOnly = true)
            val saved = saveToChosenFolder(tempMuxed, sanitizeFileName(displayTitle), "m4a", "audio/mp4")
            dao.upsert(
                DownloadedVideoEntity(
                    videoId = video.videoId,
                    title = displayTitle,
                    channelName = displayChannel,
                    localVideoPath = saved.uri,
                    localThumbnailPath = null,
                    fileSizeBytes = saved.sizeBytes,
                    durationSeconds = video.durationSeconds,
                    isShort = video.isShort
                )
            )
            return
        }

        val targetVideoUrl = videoPlaylistUrl
            ?: throw IllegalStateException("No matching ${quality.label} stream")

        downloadHlsSegments(targetVideoUrl, rawVideoStream, cancellation, resume = resume) { p ->
            val weight = if (audioPlaylistUrl != null) 0.65f else 0.85f
            updateProgress(video, quality.label, 0.05f + p * weight, "Downloading video ${(p * 100).toInt()}%")
        }

        if (audioPlaylistUrl != null) {
            downloadHlsSegments(audioPlaylistUrl, rawAudioStream, cancellation, resume = resume) { p ->
                updateProgress(video, quality.label, 0.70f + p * 0.18f, "Downloading audio ${(p * 100).toInt()}%")
            }
        }

        updateProgress(video, quality.label, 0.90f, "Muxing MP4...")
        remuxTracksToMp4(
            videoRaw = rawVideoStream,
            audioRaw = rawAudioStream.takeIf { it.exists() && it.length() > 0L },
            outMp4 = tempMuxed,
            cancellation = cancellation
        )

        cancellation.ensureActive()
        validateMediaFile(tempMuxed)
        updateProgress(video, quality.label, 0.95f, "Generating thumbnail...")
        val thumbFile = extractFrameFromVideo(tempMuxed, video.videoId)
        cancellation.ensureActive()

        val saved = saveToChosenFolder(tempMuxed, sanitizeFileName(displayTitle), "mp4", "video/mp4")
        dao.upsert(
            DownloadedVideoEntity(
                videoId = video.videoId,
                title = displayTitle,
                channelName = displayChannel,
                localVideoPath = saved.uri,
                localThumbnailPath = thumbFile?.absolutePath,
                fileSizeBytes = saved.sizeBytes,
                durationSeconds = video.durationSeconds,
                isShort = video.isShort
            )
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveViaWebSession(
        videoId: String,
        quality: DownloadQuality,
        cancellation: DownloadCancellation
    ): ResolvedQualityStreams? = withTimeoutOrNull(25_000L) {
        cancellation.ensureActive()
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val mainHandler = Handler(Looper.getMainLooper())
                var finished = false
                var capturedTitle = ""
                var capturedAuthor = ""
                var capturedVideoUrl: String? = null
                var capturedAudioUrl: String? = null
                var capturedMuxedUrl: String? = null
                var capturedHlsUrl: String? = null

                val webView = WebView(context.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.userAgentString = safariMobileUserAgent
                    // Give the player a real viewport so adaptive quality selection activates
                    layout(0, 0, 1080, 1920)
                }

                fun destroyWebViewSafely() {
                    mainHandler.post {
                        runCatching {
                            webView.stopLoading()
                            webView.webChromeClient = null
                            webView.webViewClient = WebViewClient()
                            webView.loadUrl("about:blank")
                        }
                        mainHandler.postDelayed({
                            runCatching { webView.destroy() }
                        }, 150L)
                    }
                }

                fun tryComplete() {
                    if (finished) return

                    val ready = if (quality.audioOnly) {
                        capturedAudioUrl != null || capturedMuxedUrl != null
                    } else {
                        (capturedVideoUrl != null && capturedAudioUrl != null) || capturedMuxedUrl != null
                    }
                    if (!ready) return

                    finished = true
                    val cookies = runCatching {
                        CookieManager.getInstance().getCookie("https://www.youtube.com")
                    }.getOrNull()

                    val resolved = ResolvedQualityStreams(
                        title = capturedTitle,
                        channelName = capturedAuthor,
                        videoUrl = capturedVideoUrl,
                        audioUrl = capturedAudioUrl,
                        muxedUrl = capturedMuxedUrl ?: capturedHlsUrl,
                        userAgent = safariMobileUserAgent,
                        cookieHeader = cookies
                    )
                    destroyWebViewSafely()
                    if (cont.isActive) cont.resume(resolved) {}
                }

                fun parsePlayerJson(jsonStr: String) {
                    runCatching {
                        val root = JSONObject(jsonStr)
                        val details = root.optJSONObject("videoDetails")
                        if (details != null) {
                            if (capturedTitle.isBlank()) capturedTitle = details.optString("title")
                            if (capturedAuthor.isBlank()) capturedAuthor = details.optString("author")
                        }

                        val streaming = root.optJSONObject("streamingData")
                        if (streaming != null) {
                            val hls = streaming.optString("hlsManifestUrl")
                            if (hls.isNotBlank() && capturedHlsUrl == null) capturedHlsUrl = hls

                            val formats = streaming.optJSONArray("formats")
                            if (formats != null && capturedMuxedUrl == null) {
                                for (i in 0 until formats.length()) {
                                    val fmt = formats.optJSONObject(i) ?: continue
                                    val fUrl = fmt.optString("url")
                                    val h = fmt.optInt("height", 0)
                                    if (fUrl.isNotBlank() && h <= quality.maxHeight) {
                                        capturedMuxedUrl = fUrl
                                        break
                                    }
                                }
                            }

                            val adaptive = streaming.optJSONArray("adaptiveFormats")
                            if (adaptive != null) {
                                val bestVid = selectBestVideoFormat(adaptive, quality.maxHeight)
                                if (bestVid != null && capturedVideoUrl == null) {
                                    capturedVideoUrl = bestVid.url
                                }
                                if (capturedAudioUrl == null) {
                                    for (i in 0 until adaptive.length()) {
                                        val item = adaptive.optJSONObject(i) ?: continue
                                        val mime = item.optString("mimeType")
                                        val aUrl = item.optString("url")
                                        if (mime.startsWith("audio/") && aUrl.isNotBlank()) {
                                            capturedAudioUrl = aUrl
                                            if (mime.startsWith("audio/mp4")) break
                                        }
                                    }
                                }
                            }
                        }
                        mainHandler.post { tryComplete() }
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        webView.evaluateJavascript("(function() { return JSON.stringify(window.ytInitialPlayerResponse || {}); })()") { jsRes ->
                            if (!jsRes.isNullOrBlank() && jsRes != "{}" && jsRes != "null") {
                                val unquoted = if (jsRes.startsWith("\"") && jsRes.endsWith("\"")) {
                                    runCatching { org.json.JSONTokener(jsRes).nextValue().toString() }.getOrDefault(jsRes)
                                } else jsRes
                                parsePlayerJson(unquoted)
                            }
                        }
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url?.toString().orEmpty()
                        if (url.contains("/youtubei/v1/player")) {
                            runCatching {
                                val reqHeaders = request?.requestHeaders.orEmpty()
                                val okReq = Request.Builder()
                                    .url(url)
                                    .header("User-Agent", safariMobileUserAgent)
                                    .apply { reqHeaders.forEach { (k, v) -> header(k, v) } }
                                    .post(
                                        "{\"videoId\":\"$videoId\"}".toRequestBody("application/json".toMediaType())
                                    )
                                    .build()

                                okHttpClient.newCall(okReq).execute().use { resp ->
                                    val jsonStr = resp.body?.string().orEmpty()
                                    parsePlayerJson(jsonStr)
                                }
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                cont.invokeOnCancellation { destroyWebViewSafely() }
                webView.loadUrl("https://www.youtube.com/watch?v=$videoId")
            }
        }
    }

    private suspend fun downloadDirectStream(
        url: String,
        userAgent: String,
        cookieHeader: String?,
        destination: File,
        cancellation: DownloadCancellation,
        resume: Boolean,
        onProgress: (Float, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        fun requestFor(range: String? = null): Request {
            val builder = Request.Builder().url(url)
            if (userAgent.isNotBlank()) builder.header("User-Agent", userAgent)
            if (!cookieHeader.isNullOrBlank()) builder.header("Cookie", cookieHeader)
            if (range != null) builder.header("Range", range)
            return builder.get().build()
        }

        // A 1-byte range probe tells us whether the CDN supports ranged requests and the
        // real file size. Most YouTube media hosts do; when they don't we fall back to a
        // single ordinary GET (which cannot be resumed safely).
        var totalBytes = -1L
        var rangeSupported = false
        okHttpClient.newCall(requestFor("bytes=0-0")).executeCancellable(cancellation).use { probe ->
            val contentRange = probe.header("Content-Range")
            val contentLength = probe.header("Content-Length")?.toLongOrNull() ?: -1L
            if (probe.code == 206 && contentRange != null) {
                totalBytes = contentRange.substringAfterLast('/').trim().toLongOrNull() ?: -1L
                rangeSupported = totalBytes > 0L
            } else if (probe.code == 200 && contentLength > 0L) {
                totalBytes = contentLength
                rangeSupported = false
            }
        }
        cancellation.ensureActive()

        val identity = streamIdentity(url)

        if (!rangeSupported || totalBytes <= 0L) {
            deletePartState(destination)
            if (destination.exists()) destination.delete()
            okHttpClient.newCall(requestFor()).executeCancellable(cancellation).use { response ->
                if (!response.isSuccessful) throw IllegalStateException("[ChunkDownload] HTTP ${response.code} downloading stream")
                val body = response.body ?: throw IllegalStateException("[ChunkDownload] Empty response body")
                val expected = body.contentLength().takeIf { it > 0L } ?: 10_000_000L
                val progress = ThrottledProgress(onReport = onProgress)
                var downloaded = 0L
                body.byteStream().use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            cancellation.ensureActive()
                            output.write(buffer, 0, read)
                            downloaded += read.toLong()
                            progress.report(downloaded.toFloat() / expected, downloaded)
                        }
                    }
                }
                progress.finish(downloaded)
            }
            return@withContext
        }

        if (totalBytes < 4L * 1024 * 1024) {
            // Small files use one request, but can still resume from the last verified byte.
            val prior = if (resume) readPartState(destination) as? DownloadPartState.Direct else null
            val matches = prior != null && prior.identity == identity && prior.totalBytes == totalBytes && prior.chunkCount == 1
            val completedBefore = prior?.completedChunks.orEmpty()
            if (matches && completedBefore.contains(0) && destination.exists() && destination.length() == totalBytes) {
                onProgress(1f, totalBytes)
                return@withContext
            }

            val canResume = matches && !completedBefore.contains(0) &&
                destination.exists() && destination.length() in 1 until totalBytes
            val startAt = if (canResume) destination.length() else 0L
            if (!canResume) {
                deletePartState(destination)
                destination.delete()
                destination.parentFile?.mkdirs()
                writePartState(destination, DownloadPartState.Direct(identity, totalBytes, 1L, 1, emptySet()))
            }

            val range = if (startAt > 0L) "bytes=$startAt-${totalBytes - 1}" else null
            okHttpClient.newCall(requestFor(range)).executeCancellable(cancellation).use { response ->
                if (range != null && response.code != 206) {
                    throw IllegalStateException("Server ignored resume range")
                }
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code} downloading stream")
                val body = response.body ?: throw IllegalStateException("Empty response body")
                val progress = ThrottledProgress(initialBytes = startAt, onReport = onProgress)
                var downloaded = startAt
                body.byteStream().use { input ->
                    FileOutputStream(destination, startAt > 0L).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            cancellation.ensureActive()
                            output.write(buffer, 0, read)
                            downloaded += read.toLong()
                            progress.report(downloaded.toFloat() / totalBytes, downloaded)
                        }
                    }
                }
                if (downloaded != totalBytes) {
                    throw IllegalStateException("Incomplete stream: $downloaded/$totalBytes bytes")
                }
                writePartState(destination, DownloadPartState.Direct(identity, totalBytes, 1L, 1, setOf(0)))
                progress.finish(downloaded)
            }
            return@withContext
        }

        // Parallel ranged chunks. Each coroutine writes to its own disjoint offset of the
        // same file via RandomAccessFile, so no merge pass is needed. Completed chunk indexes
        // live in a sidecar file so a retry can skip them instead of re-downloading everything.
        val chunkCount = when {
            totalBytes >= 64L * 1024 * 1024 -> 12
            totalBytes >= 16L * 1024 * 1024 -> 8
            else -> 4
        }.coerceAtLeast(2)
        val chunkSize = totalBytes / chunkCount

        fun chunkEnd(index: Int): Long =
            if (index == chunkCount - 1) totalBytes - 1 else (index + 1) * chunkSize - 1L
        fun chunkLength(index: Int): Long = chunkEnd(index) - index * chunkSize + 1L

        val completedChunks = ConcurrentHashMap.newKeySet<Int>()
        val prior = if (resume) readPartState(destination) as? DownloadPartState.Direct else null
        val canResume = prior != null && prior.identity == identity && prior.totalBytes == totalBytes &&
            prior.chunkCount == chunkCount && prior.chunkSize == chunkSize &&
            destination.exists() && destination.length() == totalBytes

        var initialBytes = 0L
        if (canResume) {
            prior?.completedChunks.orEmpty().filter { it in 0 until chunkCount }.forEach { index ->
                if (completedChunks.add(index)) initialBytes += chunkLength(index)
            }
        } else {
            deletePartState(destination)
            destination.parentFile?.mkdirs()
            RandomAccessFile(destination, "rw").use { it.setLength(totalBytes) }
            writePartState(destination, DownloadPartState.Direct(identity, totalBytes, chunkSize, chunkCount, emptySet()))
        }

        val progress = ThrottledProgress(initialBytes = initialBytes, onReport = onProgress)
        val downloaded = AtomicLong(initialBytes)
        onProgress(initialBytes.toFloat() / totalBytes, 0L)

        coroutineScope {
            (0 until chunkCount).filterNot(completedChunks::contains).map { index ->
                async(Dispatchers.IO) {
                    val start = index * chunkSize
                    val end = chunkEnd(index)
                    var attempt = 0
                    var completed = false
                    var lastError: Exception? = null
                    while (attempt < 3 && !completed) {
                        attempt++
                        var attemptWritten = 0L
                        try {
                            cancellation.ensureActive()
                            okHttpClient.newCall(requestFor("bytes=$start-$end"))
                                .executeCancellable(cancellation).use { response ->
                                    if (response.code != 206) {
                                        throw IllegalStateException("[ChunkDownload] HTTP ${response.code} for range $start-$end")
                                    }
                                    val body = response.body
                                        ?: throw IllegalStateException("[ChunkDownload] Empty response body for range $start-$end")

                                    RandomAccessFile(destination, "rw").use { file ->
                                        file.seek(start)
                                        body.byteStream().use { input ->
                                            val buffer = ByteArray(256 * 1024)
                                            var read: Int
                                            while (input.read(buffer).also { read = it } != -1) {
                                                cancellation.ensureActive()
                                                file.write(buffer, 0, read)
                                                attemptWritten += read.toLong()
                                                val downloadedNow = downloaded.addAndGet(read.toLong())
                                                progress.report(downloadedNow.toFloat() / totalBytes, downloadedNow)
                                            }
                                            val expectedLen = end - start + 1L
                                            if (attemptWritten != expectedLen) {
                                                throw IllegalStateException("[ChunkDownload] Incomplete chunk $index: $attemptWritten/$expectedLen bytes")
                                            }
                                        }
                                    }
                                }
                            completedChunks.add(index)
                            writePartState(
                                destination,
                                DownloadPartState.Direct(identity, totalBytes, chunkSize, chunkCount, completedChunks.toSet())
                            )
                            completed = true
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            cancellation.ensureActive()
                            lastError = e
                            if (attemptWritten > 0L) {
                                downloaded.addAndGet(-attemptWritten)
                                progress.report(downloaded.get().toFloat() / totalBytes, downloaded.get())
                            }
                        }
                    }
                    if (!completed) throw (lastError ?: IllegalStateException("[ChunkDownload] Chunk $index failed"))
                }
            }.awaitAll()
        }
        writePartState(destination, DownloadPartState.Direct(identity, totalBytes, chunkSize, chunkCount, (0 until chunkCount).toSet()))
        progress.finish(downloaded.get())
    }

    private suspend fun httpGetText(url: String, cancellation: DownloadCancellation): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", iosUserAgent)
            .get()
            .build()
        okHttpClient.newCall(req).executeCancellable(cancellation).use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} fetching HLS manifest")
            resp.body?.string().orEmpty()
        }
    }

    private data class HlsPlaylists(
        val videoUrl: String?,
        val audioUrl: String?
    )

    private fun parseMasterM3u8(masterText: String, quality: DownloadQuality): HlsPlaylists {
        val lines = masterText.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        var bestVideoUrl: String? = null
        var bestVideoBandwidth = -1
        var audioUrl: String? = null

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val resolution = Regex("""RESOLUTION=(\d+x\d+)""").find(line)?.groupValues?.get(1)
                val height = resolution?.substringAfter('x')?.toIntOrNull() ?: 0
                val urlLine = lines.getOrNull(i + 1)

                if (urlLine != null && !urlLine.startsWith("#")) {
                    if (height in 1..quality.maxHeight) {
                        if (bandwidth > bestVideoBandwidth) {
                            bestVideoBandwidth = bandwidth
                            bestVideoUrl = urlLine
                        }
                    } else if (bestVideoUrl == null) {
                        // Fallback if requested height is unavailable
                        bestVideoUrl = urlLine
                    }
                }
            } else if (line.startsWith("#EXT-X-MEDIA") && line.contains("TYPE=AUDIO")) {
                val uriMatch = Regex("""URI="([^"]+)"""").find(line)
                if (uriMatch != null) {
                    audioUrl = uriMatch.groupValues[1]
                }
            }
            i++
        }

        if (bestVideoUrl == null && lines.isNotEmpty()) {
            bestVideoUrl = lines.lastOrNull { !it.startsWith("#") }
        }

        return HlsPlaylists(bestVideoUrl, audioUrl)
    }

    private suspend fun downloadHlsSegments(
        playlistUrl: String,
        outputFile: File,
        cancellation: DownloadCancellation,
        resume: Boolean,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        val playlistText = httpGetText(playlistUrl, cancellation)
        val baseUrl = playlistUrl.substringBeforeLast('/') + "/"
        val segmentUrls = mutableListOf<String>()

        playlistText.lineSequence().map { it.trim() }.forEach { line ->
            if (line.isNotEmpty() && !line.startsWith("#")) {
                val fullUrl = if (line.startsWith("http")) line else baseUrl + line
                segmentUrls.add(fullUrl)
            }
        }

        if (segmentUrls.isEmpty()) {
            // Might be a master manifest or direct media playlist; treat as single file if needed
            deletePartState(outputFile)
            downloadDirectStream(
                playlistUrl,
                iosUserAgent,
                null,
                outputFile,
                cancellation,
                resume = resume
            ) { p, _ -> onProgress(p) }
            return@withContext
        }

        val identity = listOf(
            streamIdentity(playlistUrl),
            segmentUrls.size.toString(),
            streamIdentity(segmentUrls.last())
        ).joinToString("#").replace("|", "%7C")

        val prior = if (resume) readPartState(outputFile) as? DownloadPartState.Hls else null
        val canResume = prior != null && prior.identity == identity &&
            prior.segmentCount == segmentUrls.size &&
            prior.completedSegments in 0..segmentUrls.size &&
            prior.completedBytes >= 0L &&
            outputFile.exists() && outputFile.length() >= prior.completedBytes

        var completed = 0
        var completedBytes = 0L
        if (canResume) {
            completed = prior?.completedSegments ?: 0
            completedBytes = prior?.completedBytes ?: 0L
            // Drop bytes from a segment that was interrupted mid-write.
            RandomAccessFile(outputFile, "rw").use { it.setLength(completedBytes) }
        } else {
            deletePartState(outputFile)
            outputFile.delete()
            outputFile.parentFile?.mkdirs()
            writePartState(outputFile, DownloadPartState.Hls(identity, segmentUrls.size, 0, 0L))
        }
        onProgress(completed.toFloat() / segmentUrls.size.toFloat())

        for (index in completed until segmentUrls.size) {
            val segUrl = segmentUrls[index]
            cancellation.ensureActive()
            var written = false
            var lastError: Exception? = null
            var attempt = 0
            while (attempt < 3 && !written) {
                attempt++
                try {
                    // Rewind to the last fully written segment before each attempt.
                    RandomAccessFile(outputFile, "rw").use { it.setLength(completedBytes) }

                    val req = Request.Builder()
                        .url(segUrl)
                        .header("User-Agent", iosUserAgent)
                        .get()
                        .build()

                    okHttpClient.newCall(req).executeCancellable(cancellation).use { resp ->
                        if (!resp.isSuccessful) {
                            throw IllegalStateException("HTTP ${resp.code} fetching HLS segment ${index + 1}/${segmentUrls.size}")
                        }
                        val body = resp.body
                            ?: throw IllegalStateException("Empty HLS segment ${index + 1}/${segmentUrls.size}")

                        var segmentBytes = 0L
                        FileOutputStream(outputFile, true).use { out ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(256 * 1024)
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    cancellation.ensureActive()
                                    out.write(buffer, 0, read)
                                    segmentBytes += read.toLong()
                                }
                            }
                        }
                        completedBytes += segmentBytes
                    }
                    writePartState(
                        outputFile,
                        DownloadPartState.Hls(identity, segmentUrls.size, index + 1, completedBytes)
                    )
                    written = true
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    cancellation.ensureActive()
                    lastError = e
                    if (attempt < 3) delay(250L * attempt)
                }
            }
            if (!written) {
                throw lastError ?: IllegalStateException("Failed HLS segment ${index + 1}/${segmentUrls.size}")
            }
            onProgress((index + 1).toFloat() / segmentUrls.size.toFloat())
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun remuxTracksToMp4(
        videoRaw: File?,
        audioRaw: File?,
        outMp4: File,
        cancellation: DownloadCancellation
    ): Int = withContext(Dispatchers.IO) {
        cancellation.ensureActive()
        // A previous attempt may have left a partially muxed output behind.
        outMp4.delete()

        val extractorVideo = if (videoRaw != null && videoRaw.exists()) MediaExtractor().apply { setDataSource(videoRaw.absolutePath) } else null
        val extractorAudio = if (audioRaw != null && audioRaw.exists()) MediaExtractor().apply { setDataSource(audioRaw.absolutePath) } else null

        val videoMime = extractorVideo?.let { ext ->
            (0 until ext.trackCount).mapNotNull {
                runCatching { ext.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }.getOrNull()
            }.find { it.startsWith("video/") }
        }.orEmpty()

        val audioMime = extractorAudio?.let { ext ->
            (0 until ext.trackCount).mapNotNull {
                runCatching { ext.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }.getOrNull()
            }.find { it.startsWith("audio/") }
        }.orEmpty()

        val isWebm = videoMime.contains("vp9", ignoreCase = true) ||
                videoMime.contains("vp8", ignoreCase = true) ||
                audioMime.contains("opus", ignoreCase = true) ||
                audioMime.contains("vorbis", ignoreCase = true)

        val outputFormat = if (isWebm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4

        var muxerStarted = false
        var muxerClosed = false
        val muxer: DownloadMuxerWrapper = if (isWebm) {
            PlatformMuxerWrapper(MediaMuxer(outMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM))
        } else {
            Media3MuxerWrapper(MediaMuxerCompat(outMp4.absolutePath, MediaMuxerCompat.OUTPUT_FORMAT_MP4))
        }

        try {
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()

            extractorVideo?.let { ext ->
                for (i in 0 until ext.trackCount) {
                    val format = ext.getTrackFormat(i)
                    if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                        ext.selectTrack(i)
                        videoTrackIndex = muxer.addTrack(format)
                        break
                    }
                }
            }

            extractorAudio?.let { ext ->
                for (i in 0 until ext.trackCount) {
                    val format = ext.getTrackFormat(i)
                    if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        ext.selectTrack(i)
                        audioTrackIndex = muxer.addTrack(format)
                        break
                    }
                }
            }

            if (videoTrackIndex == -1 && audioTrackIndex == -1) {
                throw IllegalStateException("No video or audio tracks found in downloaded streams")
            }

            muxer.start()
            muxerStarted = true

            // Mux video samples
            if (extractorVideo != null && videoTrackIndex >= 0) {
                while (true) {
                    cancellation.ensureActive()
                    buffer.clear()
                    info.offset = 0
                    info.size = extractorVideo.readSampleData(buffer, 0)
                    if (info.size < 0) break

                    info.presentationTimeUs = extractorVideo.sampleTime
                    val videoSampleFlags = extractorVideo.sampleFlags
                    info.flags = if ((videoSampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }

                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)

                    muxer.writeSampleData(videoTrackIndex, buffer, info)
                    extractorVideo.advance()
                }
            }

            // Mux audio samples
            if (extractorAudio != null && audioTrackIndex >= 0) {
                while (true) {
                    cancellation.ensureActive()
                    buffer.clear()
                    info.offset = 0
                    info.size = extractorAudio.readSampleData(buffer, 0)
                    if (info.size < 0) break

                    info.presentationTimeUs = extractorAudio.sampleTime
                    val audioSampleFlags = extractorAudio.sampleFlags
                    info.flags = if ((audioSampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }

                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)

                    muxer.writeSampleData(audioTrackIndex, buffer, info)
                    extractorAudio.advance()
                }
            }

            muxer.stop()
            muxerClosed = true
            outputFormat
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val topTrace = e.stackTrace.firstOrNull()?.let { "${it.className.substringAfterLast('.')}.${it.methodName}(:${it.lineNumber})" }
            val msg = e.message?.takeIf { !it.isBlank() } ?: e.cause?.message?.takeIf { !it.isBlank() } ?: topTrace ?: e.toString()
            val errorDetails = "[Muxing] Failed: ${e.javaClass.simpleName} - $msg"
            Log.e("FrontPorchDownload", errorDetails, e)
            outMp4.delete()
            videoRaw?.delete()
            audioRaw?.delete()
            throw IllegalStateException(errorDetails, e)
        } finally {
            runCatching { extractorVideo?.release() }
            runCatching { extractorAudio?.release() }
            runCatching { if (muxerStarted && !muxerClosed) muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private suspend fun extractFrameFromVideo(videoFile: File, videoId: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoFile.absolutePath)
            val bitmap = retriever.frameAtTime ?: return@runCatching null
            retriever.release()

            val thumbFile = File(thumbsDir, "$videoId.jpg")
            FileOutputStream(thumbFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            thumbFile
        }.getOrNull()
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "").trim().take(120)
    }

    private suspend fun saveToChosenFolder(sourceFile: File, displayName: String, extension: String, mimeType: String): SavedFileResult = withContext(Dispatchers.IO) {
        val targetName = "$displayName.$extension"
        val customUri = settingsStore.getCustomFolderUri()

        if (customUri != null) {
            runCatching {
                val docDir = DocumentFile.fromTreeUri(context, customUri)
                if (docDir != null && docDir.canWrite()) {
                    docDir.findFile(targetName)?.delete()
                    val newDoc = docDir.createFile(mimeType, targetName)
                    if (newDoc != null) {
                        val pfd = context.contentResolver.openFileDescriptor(newDoc.uri, "rwt")
                        if (pfd != null) {
                            pfd.use { descriptor ->
                                FileOutputStream(descriptor.fileDescriptor).use { out ->
                                    sourceFile.inputStream().use { input -> input.copyTo(out, bufferSize = 256 * 1024) }
                                }
                            }
                        } else {
                            context.contentResolver.openOutputStream(newDoc.uri)?.use { out ->
                                sourceFile.inputStream().use { input -> input.copyTo(out, bufferSize = 256 * 1024) }
                            }
                        }
                        return@withContext SavedFileResult(newDoc.uri.toString(), sourceFile.length())
                    }
                }
            }.onFailure { e ->
                Log.e("FrontPorchDownload", "SAF save failed for $targetName: ${e.message}", e)
            }
        }

        // Save via MediaStore (supports Android 10+ Scoped Storage without permission errors)
        runCatching {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/FrontPorch"

            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf(targetName, "$relativePath%")
            runCatching { context.contentResolver.delete(collection, selection, selectionArgs) }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, targetName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val itemUri = context.contentResolver.insert(collection, values)
            if (itemUri != null) {
                val pfd = context.contentResolver.openFileDescriptor(itemUri, "rwt")
                if (pfd != null) {
                    pfd.use { descriptor ->
                        FileOutputStream(descriptor.fileDescriptor).use { out ->
                            sourceFile.inputStream().use { input -> input.copyTo(out, bufferSize = 256 * 1024) }
                        }
                    }
                } else {
                    context.contentResolver.openOutputStream(itemUri)?.use { out ->
                        sourceFile.inputStream().use { input -> input.copyTo(out, bufferSize = 256 * 1024) }
                    }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(itemUri, values, null, null)
                return@withContext SavedFileResult(itemUri.toString(), sourceFile.length())
            }
        }.onFailure { e ->
            Log.e("FrontPorchDownload", "MediaStore save failed for $targetName: ${e.message}", e)
        }

        // Fallback: standard file I/O to public downloads or app-specific downloads
        val downloadsDir = runCatching {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FrontPorch").apply { mkdirs() }
        }.getOrNull()?.takeIf { (it.exists() || it.mkdirs()) && it.canWrite() }
            ?: File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "FrontPorch").apply { mkdirs() }

        val targetFile = File(downloadsDir, targetName)
        sourceFile.copyTo(targetFile, overwrite = true, bufferSize = 256 * 1024)
        SavedFileResult(targetFile.absolutePath, targetFile.length())
    }

    private data class SavedFileResult(val uri: String, val sizeBytes: Int64)
}

private typealias Int64 = Long

/** Bridges NewPipeExtractor's Downloader API to the app's shared OkHttp client. */
private class NewPipeOkHttpDownloader(
    private val client: OkHttpClient
) : Downloader() {
    companion object {
        // StreamInfo.getInfo() runs synchronously on the caller's thread, so a ThreadLocal
        // lets the extractor's own requests join the cancellation of the active download.
        private val activeCancellation = ThreadLocal<DownloadCancellation>()

        fun setCancellation(cancellation: DownloadCancellation?) {
            if (cancellation == null) activeCancellation.remove()
            else activeCancellation.set(cancellation)
        }
    }

    override fun execute(request: NewPipeRequest): NewPipeResponse {
        val method = request.httpMethod().uppercase()
        val data = request.dataToSend()
        val requestBody = if (data != null && data.isNotEmpty() && method != "GET" && method != "HEAD") {
            val contentType = request.headers()["Content-Type"]?.firstOrNull()
                ?.toMediaTypeOrNull()
                ?: "application/json".toMediaType()
            data.toRequestBody(contentType)
        } else {
            null
        }

        val builder = Request.Builder()
            .url(request.url())
            .method(method, requestBody)

        request.headers().forEach { (name, values) ->
            if (name.equals("Content-Length", ignoreCase = true)) return@forEach
            if (name.equals("Content-Type", ignoreCase = true) && requestBody != null) return@forEach
            values.forEach { builder.addHeader(name, it) }
        }

        val call = client.newCall(builder.build())
        val cancellation = activeCancellation.get()
        if (cancellation != null) cancellation.register(call)
        try {
            call.execute().use { resp ->
                return NewPipeResponse(
                    resp.code,
                    resp.message,
                    resp.headers.toMultimap(),
                    resp.body?.string().orEmpty(),
                    resp.request.url.toString()
                )
            }
        } finally {
            if (cancellation != null) cancellation.unregister(call)
        }
    }
}

private interface DownloadMuxerWrapper {
    fun addTrack(format: MediaFormat): Int
    fun start()
    fun writeSampleData(trackIndex: Int, byteBuffer: java.nio.ByteBuffer, bufferInfo: MediaCodec.BufferInfo)
    fun stop()
    fun release()
}

private class PlatformMuxerWrapper(private val muxer: MediaMuxer) : DownloadMuxerWrapper {
    override fun addTrack(format: MediaFormat): Int = muxer.addTrack(format)
    override fun start() = muxer.start()
    override fun writeSampleData(trackIndex: Int, byteBuffer: java.nio.ByteBuffer, bufferInfo: MediaCodec.BufferInfo) =
        muxer.writeSampleData(trackIndex, byteBuffer, bufferInfo)
    override fun stop() = muxer.stop()
    override fun release() = muxer.release()
}

@OptIn(UnstableApi::class)
private class Media3MuxerWrapper(private val muxer: MediaMuxerCompat) : DownloadMuxerWrapper {
    override fun addTrack(format: MediaFormat): Int = muxer.addTrack(format)
    override fun start() = muxer.start()
    override fun writeSampleData(trackIndex: Int, byteBuffer: java.nio.ByteBuffer, bufferInfo: MediaCodec.BufferInfo) =
        muxer.writeSampleData(trackIndex, byteBuffer, bufferInfo)
    override fun stop() = muxer.stop()
    override fun release() = muxer.release()
}
