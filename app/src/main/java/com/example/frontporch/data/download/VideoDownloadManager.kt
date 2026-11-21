package com.example.frontporch.data.download

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.media.*
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.documentfile.provider.DocumentFile
import com.example.frontporch.service.DownloadForegroundService
import com.example.frontporch.data.innertube.FeedVideoItem
import com.example.frontporch.data.local.DownloadedVideoDao
import com.example.frontporch.data.local.DownloadedVideoEntity
import com.example.frontporch.data.sponsorblock.SponsorBlockPreferences
import com.example.frontporch.data.sponsorblock.SponsorCategory
import com.example.frontporch.data.sponsorblock.SponsorSkipBehavior
import com.example.frontporch.ui.downloads.DownloadQuality
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.Call
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat as NewPipeMediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NewPipeRequest
import org.schabi.newpipe.extractor.downloader.Response as NewPipeResponse
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

data class ActiveDownloadItem(
    val videoId: String,
    val title: String,
    val channelName: String,
    val qualityLabel: String,
    val progress: Float,
    val statusText: String
)

/**
 * Coroutine cancellation alone cannot interrupt the blocking OkHttp/MediaMuxer work in a
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

class VideoDownloadManager(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val dao: DownloadedVideoDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settingsStore = DownloadSettingsStore(context)

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

    private val thumbsDir: File
        get() = File(context.filesDir, "download_thumbs").apply { mkdirs() }

    private val tempWorkDir: File
        get() = File(context.cacheDir, "download_tmp").apply { mkdirs() }

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
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                val isVideo = mime.startsWith("video/")
                val isAudio = mime.startsWith("audio/")
                if (!isVideo && !isAudio) continue
                if (audioOnly && isVideo) continue
                extractor.selectTrack(i)
                if (extractor.sampleTime >= 0L) hasSamples = true
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
        _activeDownloads.update { it - videoId }
        runCatching {
            File(tempWorkDir, "${videoId}_v.mp4").delete()
            File(tempWorkDir, "${videoId}_a.m4a").delete()
            File(tempWorkDir, "${videoId}_out.mp4").delete()
        }
    }

    fun enqueueVideos(videos: List<FeedVideoItem>, quality: DownloadQuality, preferredAudioLanguage: String = "en") {
        val newDownloads = videos.filterNot { _activeDownloads.value.containsKey(it.videoId) }
        if (newDownloads.isEmpty()) return

        // Publish the active state before starting the service so its first notification has
        // something to show. The manager remains the single source of truth for progress and
        // cancellation; the service only keeps the process alive after the user leaves the task.
        newDownloads.forEach { video ->
            updateProgress(video, quality.label, 0.02f, "Resolving ${quality.label}...")
        }
        DownloadForegroundService.start(context.applicationContext)

        newDownloads.forEach { video ->
            val job = scope.launch {
                downloadSingle(video, quality, preferredAudioLanguage)
            }
            downloadJobs[video.videoId] = job
            job.invokeOnCompletion {
                downloadJobs.remove(video.videoId)
            }
        }
    }

    private fun updateProgress(
        video: FeedVideoItem,
        qualityLabel: String,
        progress: Float,
        statusText: String
    ) {
        _activeDownloads.update { current ->
            current + (
                video.videoId to ActiveDownloadItem(
                    videoId = video.videoId,
                    title = video.title,
                    channelName = video.channelName,
                    qualityLabel = qualityLabel,
                    progress = progress.coerceIn(0f, 1f),
                    statusText = statusText
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

                val audio = info.audioStreams
                    .filter { it.format == NewPipeMediaFormat.M4A }
                    .filter { stream ->
                        val lang = stream.audioLocale?.language.orEmpty()
                        preferredAudioLanguage.isBlank() || preferredAudioLanguage == "auto" || lang.equals(preferredAudioLanguage, ignoreCase = true)
                    }
                    .maxByOrNull { it.averageBitrate }
                    ?: info.audioStreams
                        .filter { it.format == NewPipeMediaFormat.M4A }
                        .maxByOrNull { it.averageBitrate }

                if (quality.audioOnly) {
                    val audioUrl = audio?.url
                    return@withContext audioUrl?.let {
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

                // H.264/MP4 video-only stream plus a separate M4A track for higher qualities.
                val video = info.videoOnlyStreams
                    .filter { it.format == NewPipeMediaFormat.MPEG_4 }
                    .filter { it.codec.orEmpty().startsWith("avc", ignoreCase = true) }
                    .filter { it.height > 0 && it.height <= targetHeight }
                    .maxWithOrNull(
                        compareBy<org.schabi.newpipe.extractor.stream.VideoStream> { it.height }
                            .thenBy { if (quality.targetFps > 0) (if (it.fps >= quality.targetFps) 1 else 0) else it.fps }
                            .thenBy { it.fps }
                            .thenBy { it.bitrate }
                    )

                if (video != null && audio != null) {
                    return@withContext ResolvedQualityStreams(
                        title, channelName, video.url, audio.url, null, "", null
                    )
                }

                null
            }.getOrElse {
                null
            }
        } finally {
            NewPipeOkHttpDownloader.setCancellation(null)
        }
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

    private suspend fun downloadSingle(video: FeedVideoItem, quality: DownloadQuality, preferredAudioLanguage: String = "en") {
        val cancellation = DownloadCancellation().also { downloadCancellations[video.videoId] = it }
        val rawVideoFile = File(tempWorkDir, "${video.videoId}_v.mp4")
        val rawAudioFile = File(tempWorkDir, "${video.videoId}_a.m4a")
        val tempMuxed = File(tempWorkDir, "${video.videoId}_out.mp4")

        try {
            cancellation.ensureActive()
            updateProgress(video, quality.label, 0.04f, "Resolving stream...")

            // 1. Primary: NewPipeExtractor resolves direct, decipher-signed MP4 URLs.
            var resolved = resolveViaNewPipe(video.videoId, quality, cancellation, preferredAudioLanguage)
            cancellation.ensureActive()

            if (resolved == null) {
                // 2. Fallback: iOS HLS manifest
                val hlsInfo = fetchIosHlsManifest(video.videoId, cancellation)
                if (hlsInfo != null) {
                    downloadFromHls(video, quality, hlsInfo, rawVideoFile, rawAudioFile, tempMuxed, cancellation)
                    return
                }

                // 3. Last resort: extract ytInitialPlayerResponse + live PO token (pot) via mobile web session
                updateProgress(video, quality.label, 0.04f, "Authorizing stream...")
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
                downloadDirectStream(targetAudio, resolvedStreams.userAgent, resolvedStreams.cookieHeader, rawAudioFile, cancellation) { p ->
                    updateProgress(video, quality.label, 0.08f + p * 0.85f, "Downloading audio ${(p * 100).toInt()}%")
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

            val finalTempFile: File = if (resolvedStreams.videoUrl != null && resolvedStreams.audioUrl != null) {
                downloadDirectStream(resolvedStreams.videoUrl, resolvedStreams.userAgent, resolvedStreams.cookieHeader, rawVideoFile, cancellation) { p ->
                    updateProgress(video, quality.label, 0.08f + p * 0.60f, "Downloading video ${(p * 100).toInt()}%")
                }
                downloadDirectStream(resolvedStreams.audioUrl, resolvedStreams.userAgent, resolvedStreams.cookieHeader, rawAudioFile, cancellation) { p ->
                    updateProgress(video, quality.label, 0.68f + p * 0.20f, "Downloading audio ${(p * 100).toInt()}%")
                }
                val autoSkipCategories = SponsorBlockPreferences.categoryBehaviors.value
                    .filterValues { it == SponsorSkipBehavior.AUTO_SKIP }
                    .keys
                    .filter { it != SponsorCategory.POI_HIGHLIGHT }
                    .map { it.apiKey }

                val skipSegments = if (SponsorBlockPreferences.isEnabled.value && autoSkipCategories.isNotEmpty()) {
                    updateProgress(video, quality.label, 0.88f, "Checking SponsorBlock...")
                    fetchSponsorBlockSegmentsUs(
                        videoId = video.videoId,
                        categories = autoSkipCategories,
                        cancellation = cancellation
                    )
                } else {
                    emptyList()
                }
                updateProgress(video, quality.label, 0.90f, "Muxing MP4...")
                remuxTracksToMp4(rawVideoFile, rawAudioFile, tempMuxed, skipSegments, cancellation)
                tempMuxed
            } else if (resolvedStreams.muxedUrl != null) {
                downloadDirectStream(resolvedStreams.muxedUrl, resolvedStreams.userAgent, resolvedStreams.cookieHeader, tempMuxed, cancellation) { p ->
                    updateProgress(video, quality.label, 0.08f + p * 0.84f, "Downloading ${(p * 100).toInt()}%")
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
            val saved = saveToChosenFolder(finalTempFile, sanitizeFileName(displayTitle), "mp4", "video/mp4")

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
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            if (cancellation.isCancelled) throw CancellationException("Download cancelled")
            updateProgress(video, quality.label, 0f, "Failed: ${t.message ?: "Download error"}")
            delay(3000)
        } finally {
            downloadCancellations.remove(video.videoId)
            rawVideoFile.delete()
            rawAudioFile.delete()
            tempMuxed.delete()
            downloadJobs.remove(video.videoId)
            _activeDownloads.update { it - video.videoId }
        }
    }

    private suspend fun downloadFromHls(
        video: FeedVideoItem,
        quality: DownloadQuality,
        hlsInfo: HlsMasterResult,
        rawVideoStream: File,
        rawAudioStream: File,
        tempMuxed: File,
        cancellation: DownloadCancellation
    ) {
        val displayTitle = hlsInfo.title.ifBlank { video.title }
        val displayChannel = hlsInfo.channelName.ifBlank { video.channelName }

        val masterText = httpGetText(hlsInfo.masterManifestUrl, cancellation)
        cancellation.ensureActive()
        val (videoPlaylistUrl, audioPlaylistUrl) = parseMasterM3u8(masterText, quality)

        if (quality.audioOnly) {
            val targetAudioUrl = audioPlaylistUrl ?: videoPlaylistUrl
                ?: throw IllegalStateException("No audio playlist in manifest")
            downloadHlsSegments(targetAudioUrl, rawAudioStream, cancellation) { p ->
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

        downloadHlsSegments(targetVideoUrl, rawVideoStream, cancellation) { p ->
            val weight = if (audioPlaylistUrl != null) 0.65f else 0.85f
            updateProgress(video, quality.label, 0.05f + p * weight, "Downloading video ${(p * 100).toInt()}%")
        }

        if (audioPlaylistUrl != null) {
            downloadHlsSegments(audioPlaylistUrl, rawAudioStream, cancellation) { p ->
                updateProgress(video, quality.label, 0.70f + p * 0.18f, "Downloading audio ${(p * 100).toInt()}%")
            }
        }

        val autoSkipCategories = SponsorBlockPreferences.categoryBehaviors.value
            .filterValues { it == SponsorSkipBehavior.AUTO_SKIP }
            .keys
            .filter { it != SponsorCategory.POI_HIGHLIGHT }
            .map { it.apiKey }

        val skipSegments = if (SponsorBlockPreferences.isEnabled.value && autoSkipCategories.isNotEmpty()) {
            updateProgress(video, quality.label, 0.88f, "Checking SponsorBlock...")
            fetchSponsorBlockSegmentsUs(
                videoId = video.videoId,
                categories = autoSkipCategories,
                cancellation = cancellation
            )
        } else {
            emptyList()
        }
        updateProgress(video, quality.label, 0.90f, "Muxing MP4...")
        remuxTracksToMp4(
            videoRaw = rawVideoStream,
            audioRaw = rawAudioStream.takeIf { it.exists() && it.length() > 0L },
            outMp4 = tempMuxed,
            skipSegments = skipSegments,
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
                        muxedUrl = capturedMuxedUrl,
                        userAgent = safariMobileUserAgent,
                        cookieHeader = cookies
                    )

                    destroyWebViewSafely()
                    if (cont.isActive) cont.resume(resolved) {}
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val uri = request?.url ?: return super.shouldInterceptRequest(view, request)
                        val host = uri.host.orEmpty()
                        val path = uri.path.orEmpty()

                        // Capture live deciphered & PO-token-signed videoplayback URLs from YouTube's player
                        if (host.contains("googlevideo.com") && path.contains("videoplayback")) {
                            // Strip byte-range & rn/rbuf params so we get the clean full-stream URL
                            val cleanUrl = uri.toString()
                                .replace(Regex("[&?]range=[^&]+"), "")
                                .replace(Regex("[&?]rn=[^&]+"), "")
                                .replace(Regex("[&?]rbuf=[^&]+"), "")
                                .replace("?&", "?")

                            val mime = uri.getQueryParameter("mime").orEmpty()
                            val itag = uri.getQueryParameter("itag")?.toIntOrNull() ?: 0

                            // Muxed MP4 itags (18 = 360p, 22 = 720p)
                            if (itag == 18 || itag == 22) {
                                if (capturedMuxedUrl == null) capturedMuxedUrl = cleanUrl
                            } else if (mime.startsWith("video/")) {
                                if (capturedVideoUrl == null) capturedVideoUrl = cleanUrl
                            } else if (mime.startsWith("audio/")) {
                                if (capturedAudioUrl == null) capturedAudioUrl = cleanUrl
                            }

                            mainHandler.post { tryComplete() }
                        } else if (path.contains(".m3u8") && capturedHlsUrl == null) {
                            capturedHlsUrl = uri.toString()
                        }

                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageStarted(
                        view: WebView?,
                        url: String?,
                        favicon: android.graphics.Bitmap?
                    ) {
                        val targetHeight = if (quality.audioOnly) 360 else quality.maxHeight
                        view?.evaluateJavascript(
                            """
                            try {
                                var exp = Date.now() + 2592000000;
                                localStorage.setItem('yt-player-quality', JSON.stringify({
                                    data: JSON.stringify({ quality: $targetHeight, previousQuality: $targetHeight }),
                                    expiration: exp,
                                    creation: Date.now()
                                }));
                            } catch (e) {}
                            """.trimIndent(),
                            null
                        )
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Kick off playback so the player requests the deciphered video + audio streams
                        view?.evaluateJavascript(
                            """
                            (function() {
                                try {
                                    var v = document.querySelector('video');
                                    if (v) { v.muted = true; v.play().catch(function(){}); }
                                    var btn = document.querySelector('.ytp-large-play-button, button[aria-label*="Play"]');
                                    if (btn) btn.click();
                                    var pr = window.ytInitialPlayerResponse;
                                    return JSON.stringify({
                                        title: (pr && pr.videoDetails && pr.videoDetails.title) || "",
                                        author: (pr && pr.videoDetails && pr.videoDetails.author) || ""
                                    });
                                } catch (e) { return "{}"; }
                            })();
                            """.trimIndent()
                        ) { raw ->
                            runCatching {
                                val unescaped = if (raw != null && raw.startsWith("\"")) {
                                    JSONObject("{\"v\":$raw}").optString("v")
                                } else {
                                    raw.orEmpty()
                                }
                                val obj = JSONObject(unescaped)
                                val t = obj.optString("title")
                                val a = obj.optString("author")
                                if (t.isNotBlank()) capturedTitle = t
                                if (a.isNotBlank()) capturedAuthor = a
                            }
                        }
                    }
                }

                cont.invokeOnCancellation {
                    finished = true
                    destroyWebViewSafely()
                }

                webView.loadUrl("https://m.youtube.com/watch?v=$videoId")
            }
        }
    }

    private fun downloadDirectStream(
        baseUrl: String,
        userAgent: String,
        cookieHeader: String?,
        outFile: File,
        cancellation: DownloadCancellation,
        onProgress: (Float) -> Unit
    ) {
        val clen = Uri.parse(baseUrl).getQueryParameter("clen")?.toLongOrNull() ?: -1L
        val chunkSize = 2L * 1024L * 1024L
        var offset = 0L

        FileOutputStream(outFile).use { fos ->
            while (clen < 0 || offset < clen) {
                cancellation.ensureActive()
                val end = if (clen > 0) minOf(offset + chunkSize - 1, clen - 1) else offset + chunkSize - 1
                val sep = if (baseUrl.contains("?")) "&" else "?"
                val rangedUrl = if (clen > 0) "$baseUrl${sep}range=$offset-$end" else baseUrl

                val reqBuilder = Request.Builder().url(rangedUrl)
                if (userAgent.isNotBlank()) {
                    reqBuilder.header("User-Agent", userAgent)
                    reqBuilder.header("Origin", "https://m.youtube.com")
                    reqBuilder.header("Referer", "https://m.youtube.com/")
                    if (!cookieHeader.isNullOrBlank()) reqBuilder.header("Cookie", cookieHeader)
                }
                val req = reqBuilder.get().build()

                okHttpClient.newCall(req).executeCancellable(cancellation).use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    if (bytes.isEmpty()) throw IllegalStateException("Empty response while downloading stream")
                    fos.write(bytes)
                    offset += bytes.size
                    if (clen > 0) {
                        onProgress(offset.toFloat() / clen.toFloat())
                    } else {
                        onProgress(0.8f)
                        return
                    }
                }
            }
        }
    }

    private fun parseMasterM3u8(masterText: String, quality: DownloadQuality): Pair<String?, String?> {
        val lines = masterText.lines().map { it.trim() }
        var bestAudioUrl: String? = null
        var bestAudioBandwidth = -1L

        data class VideoVariant(val height: Int, val bandwidth: Long, val url: String)
        val variants = mutableListOf<VideoVariant>()

        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-MEDIA:") && line.contains("TYPE=AUDIO")) {
                val uri = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                val bw = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                if (!uri.isNullOrBlank() && bw >= bestAudioBandwidth) {
                    bestAudioBandwidth = bw
                    bestAudioUrl = uri
                }
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val resMatch = Regex("""RESOLUTION=\d+x(\d+)""").find(line)
                val height = resMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val bw = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                val nextUrl = lines.getOrNull(i + 1)?.takeIf { it.isNotBlank() && !it.startsWith("#") }
                if (nextUrl != null && height > 0) {
                    variants.add(VideoVariant(height, bw, nextUrl))
                }
            }
        }

        val chosenVideo = variants
            .filter { it.height <= quality.maxHeight }
            .maxWithOrNull(compareBy<VideoVariant> { it.height }.thenBy { it.bandwidth })
            ?: variants.minByOrNull { it.height }

        return chosenVideo?.url to bestAudioUrl
    }

    private fun downloadHlsSegments(
        mediaPlaylistUrl: String,
        outFile: File,
        cancellation: DownloadCancellation,
        onProgress: (Float) -> Unit
    ) {
        val playlistText = httpGetText(mediaPlaylistUrl, cancellation)
        val segmentUrls = mutableListOf<String>()

        for (rawLine in playlistText.lines()) {
            val line = rawLine.trim()
            if (line.startsWith("#EXT-X-MAP:")) {
                val initUri = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                if (!initUri.isNullOrBlank()) {
                    segmentUrls.add(resolveRelativeUrl(mediaPlaylistUrl, initUri))
                }
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                segmentUrls.add(resolveRelativeUrl(mediaPlaylistUrl, line))
            }
        }

        if (segmentUrls.isEmpty()) throw IllegalStateException("Empty HLS segment list")

        FileOutputStream(outFile).use { fos ->
            segmentUrls.forEachIndexed { index, segUrl ->
                cancellation.ensureActive()
                val req = Request.Builder()
                    .url(segUrl)
                    .header("User-Agent", iosUserAgent)
                    .get()
                    .build()

                okHttpClient.newCall(req).executeCancellable(cancellation).use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("Segment HTTP ${resp.code}")
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    fos.write(bytes)
                }
                onProgress((index + 1).toFloat() / segmentUrls.size.toFloat())
            }
        }
    }

    data class SkipSegmentUs(val startUs: Long, val endUs: Long)

    private fun fetchSponsorBlockSegmentsUs(
        videoId: String,
        cancellation: DownloadCancellation,
        categories: List<String> = listOf("sponsor", "selfpromo", "interaction", "intro", "outro", "preview", "music_offtopic")
    ): List<SkipSegmentUs> = runCatching {
        val catJson = JSONArray(categories).toString()
        val url = "https://sponsor.ajay.app/api/skipSegments" +
            "?videoID=$videoId&categories=${java.net.URLEncoder.encode(catJson, "UTF-8")}"

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "FrontPorch/1.0")
            .get()
            .build()

        okHttpClient.newCall(req).executeCancellable(cancellation).use { resp ->
            if (!resp.isSuccessful) return@runCatching emptyList()

            val arr = JSONArray(resp.body?.string().orEmpty())
            val rawRanges = mutableListOf<SkipSegmentUs>()

            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val seg = obj.optJSONArray("segment") ?: continue
                if (seg.length() < 2) continue
                val startUs = (seg.optDouble(0, 0.0) * 1_000_000.0).toLong()
                val endUs = (seg.optDouble(1, 0.0) * 1_000_000.0).toLong()
                if (endUs > startUs + 500_000L) {
                    rawRanges.add(SkipSegmentUs(startUs, endUs))
                }
            }

            val sorted = rawRanges.sortedBy { it.startUs }
            val merged = mutableListOf<SkipSegmentUs>()
            for (range in sorted) {
                val last = merged.lastOrNull()
                if (last != null && range.startUs <= last.endUs) {
                    merged[merged.lastIndex] = SkipSegmentUs(last.startUs, maxOf(last.endUs, range.endUs))
                } else {
                    merged.add(range)
                }
            }
            merged
        }
    }.getOrDefault(emptyList())

    private data class TrackCopyInfo(
        val extractor: MediaExtractor,
        val sourceTrackIndex: Int,
        val muxerTrackIndex: Int,
        val isVideo: Boolean,
        var baseTimeOffsetUs: Long = 0L,
        var lastRawPtsUs: Long = -1L,
        var isEof: Boolean = false
    )

    @Suppress("WrongConstant")
    private fun muxTracksInterleaved(
        muxer: MediaMuxer,
        tracks: List<TrackCopyInfo>,
        skipSegmentsUs: List<LongRange>,
        cancellation: DownloadCancellation,
        maxExpectedBytes: Long,
        maxSampleSize: Int = 1024 * 1024
    ) {
        // A sample larger than the buffer would be silently truncated, corrupting the output.
        val maxTrackSampleSize = tracks.maxOf { track ->
            runCatching {
                track.extractor.getTrackFormat(track.sourceTrackIndex)
                    .getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            }.getOrDefault(0)
        }
        val buffer = ByteBuffer.allocateDirect(maxOf(maxSampleSize, maxTrackSampleSize))
        val bufferInfo = MediaCodec.BufferInfo()
        var writtenBytes = 0L

        tracks.forEach { track ->
            // MediaExtractor requires an explicitly selected track before seekTo/sampleTime/
            // readSampleData work. Without this every read returns -1 and the muxer produces
            // a valid-looking MP4 with empty sample tables (which shows up as 0.0 MB).
            track.extractor.selectTrack(track.sourceTrackIndex)
            track.extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        }

        try {
            while (true) {
                cancellation.ensureActive()
                var activeTrack: TrackCopyInfo? = null
                var lowestPts = Long.MAX_VALUE

                for (track in tracks) {
                    if (track.isEof) continue
                    val sampleTime = track.extractor.sampleTime
                    if (sampleTime < 0) {
                        track.isEof = true
                        continue
                    }

                    val absolutePts = sampleTime + track.baseTimeOffsetUs
                    if (absolutePts < lowestPts) {
                        lowestPts = absolutePts
                        activeTrack = track
                    }
                }

                if (activeTrack == null) break

                buffer.clear()
                val sampleSize = activeTrack.extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) {
                    activeTrack.isEof = true
                    continue
                }

                val rawPtsUs = activeTrack.extractor.sampleTime
                val flags = activeTrack.extractor.sampleFlags

                // YouTube's fMP4 segments reuse the same tfdt, so the extractor's timestamps
                // restart at every fragment boundary. Keep a running offset so the muxed
                // timeline stays monotonic.
                if (activeTrack.lastRawPtsUs >= 0 && rawPtsUs < activeTrack.lastRawPtsUs - 1_000_000L) {
                    activeTrack.baseTimeOffsetUs += activeTrack.lastRawPtsUs
                }
                activeTrack.lastRawPtsUs = rawPtsUs

                val absolutePtsUs = rawPtsUs + activeTrack.baseTimeOffsetUs

                // Drop samples inside SponsorBlock segments. Never seek() here: the raw
                // extractor's timeline is fragment-relative, so seeking with an absolute
                // segment time can rewind it and replay the same samples forever.
                if (skipSegmentsUs.any { absolutePtsUs in it }) {
                    activeTrack.extractor.advance()
                    continue
                }

                // Keep the source presentation timestamps exactly as they are. H.264 uses
                // B-frames, so decode order is intentionally not monotonic in PTS; forcing
                // monotonic timestamps here looks "valid" but throws away frame reordering,
                // which is what produced 20fps-looking, audio-desynced downloads.
                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = absolutePtsUs
                bufferInfo.flags = flags

                muxer.writeSampleData(activeTrack.muxerTrackIndex, buffer, bufferInfo)

                writtenBytes += sampleSize
                if (writtenBytes > maxExpectedBytes) {
                    throw IllegalStateException("Muxing produced unexpectedly large output")
                }

                activeTrack.extractor.advance()
            }
        } finally {
            tracks.forEach { track ->
                runCatching { track.extractor.unselectTrack(track.sourceTrackIndex) }
            }
        }
    }

    @Suppress("WrongConstant")
    private fun remuxTracksToMp4(
        videoRaw: File?,
        audioRaw: File?,
        outMp4: File,
        skipSegments: List<SkipSegmentUs> = emptyList(),
        cancellation: DownloadCancellation
    ) {
        val muxer = MediaMuxer(outMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val extractors = mutableListOf<MediaExtractor>()
        val trackInfos = mutableListOf<TrackCopyInfo>()
        var muxerStarted = false

        try {
            var videoTrackSrc = -1
            var audioTrackSrc = -1

            listOfNotNull(videoRaw, audioRaw).forEach { file ->
                val extractor = MediaExtractor().apply { setDataSource(file.absolutePath) }
                extractors.add(extractor)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    when {
                        mime.startsWith("video/") -> {
                            if (videoTrackSrc < 0) videoTrackSrc = i
                            val dstTrack = muxer.addTrack(format)
                            trackInfos.add(TrackCopyInfo(extractor, i, dstTrack, isVideo = true))
                        }
                        mime.startsWith("audio/") -> {
                            if (audioTrackSrc < 0) audioTrackSrc = i
                            val dstTrack = muxer.addTrack(format)
                            trackInfos.add(TrackCopyInfo(extractor, i, dstTrack, isVideo = false))
                        }
                    }
                }
            }

            if (videoRaw != null) {
                require(videoTrackSrc >= 0) { "No H.264 video track found in downloaded stream" }
            }
            if (audioRaw != null) {
                require(audioTrackSrc >= 0) { "No AAC audio track found in downloaded stream" }
            }
            if (trackInfos.isEmpty()) throw IllegalStateException("No media tracks to mux")
            muxer.start()
            muxerStarted = true

            val skipSegmentsUs = skipSegments.map { it.startUs..it.endUs }
            val maxExpectedBytes = listOfNotNull(videoRaw, audioRaw).sumOf { it.length() } +
                64L * 1024L * 1024L

            muxTracksInterleaved(muxer, trackInfos, skipSegmentsUs, cancellation, maxExpectedBytes)
        } finally {
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            extractors.forEach { runCatching { it.release() } }
            if (!muxerStarted) runCatching { outMp4.delete() }
        }
    }

    private fun httpGetText(url: String, cancellation: DownloadCancellation): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", iosUserAgent)
            .get()
            .build()
        okHttpClient.newCall(req).executeCancellable(cancellation).use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    private fun resolveRelativeUrl(baseUrl: String, candidate: String): String {
        if (candidate.startsWith("http://") || candidate.startsWith("https://")) return candidate
        return java.net.URI(baseUrl).resolve(candidate).toString()
    }

    private data class SavedDownload(val uri: String, val sizeBytes: Long)

    private fun saveToChosenFolder(
        sourceFile: File,
        baseName: String,
        ext: String,
        mimeType: String
    ): SavedDownload {
        val treeUri = settingsStore.getCustomFolderUri()
        if (treeUri != null) {
            val pickedDir = DocumentFile.fromTreeUri(context, treeUri)
            if (pickedDir != null && pickedDir.canWrite()) {
                val fileName = "$baseName.$ext"
                pickedDir.findFile(fileName)?.delete()
                val docFile = pickedDir.createFile(mimeType, baseName)
                    ?: throw IllegalStateException("Could not create file in download folder")
                val out = context.contentResolver.openOutputStream(docFile.uri)
                    ?: throw IllegalStateException("Could not open download folder for writing")
                out.use { stream ->
                    sourceFile.inputStream().use { input -> input.copyTo(stream) }
                    stream.flush()
                }
                val savedSize = runCatching { docFile.length() }.getOrDefault(-1L)
                if (savedSize >= 0 && savedSize != sourceFile.length()) {
                    throw IllegalStateException("Failed to write the complete download file")
                }
                return SavedDownload(docFile.uri.toString(), if (savedSize >= 0) savedSize else sourceFile.length())
            }
        }

        // Default public Downloads/FrontPorch directory
        val defaultDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "FrontPorch"
        ).apply { mkdirs() }
        var dest = File(defaultDir, "$baseName.$ext")
        var n = 2
        while (dest.exists()) {
            dest = File(defaultDir, "$baseName ($n).$ext")
            n++
        }
        sourceFile.copyTo(dest, overwrite = true)
        if (dest.length() != sourceFile.length()) {
            throw IllegalStateException("Failed to write the complete download file")
        }
        return SavedDownload(Uri.fromFile(dest).toString(), dest.length())
    }

    private fun extractFrameFromVideo(videoFile: File, videoId: String): File? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val timeUs = if (durationMs > 8_000L) (durationMs * 0.15).toLong() * 1000L else 1_000_000L
            val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
                ?: return null
            val outFile = File(thumbsDir, "${videoId}_frame.jpg")
            FileOutputStream(outFile).use { frame.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            frame.recycle()
            outFile
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun sanitizeFileName(rawTitle: String): String =
        rawTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_").replace(Regex("\\s+"), " ").trim().take(90)
            .ifBlank { "YouTube_Video" }
}

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
