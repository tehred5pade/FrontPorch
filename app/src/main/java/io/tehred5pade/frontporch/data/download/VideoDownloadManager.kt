package io.tehred5pade.frontporch.data.download

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
import io.tehred5pade.frontporch.service.DownloadForegroundService
import io.tehred5pade.frontporch.data.innertube.FeedVideoItem
import io.tehred5pade.frontporch.data.local.DownloadedVideoDao
import io.tehred5pade.frontporch.data.local.DownloadedVideoEntity
import io.tehred5pade.frontporch.data.sponsorblock.SponsorBlockPreferences
import io.tehred5pade.frontporch.data.sponsorblock.SponsorCategory
import io.tehred5pade.frontporch.data.sponsorblock.SponsorSegment
import io.tehred5pade.frontporch.data.sponsorblock.SponsorSkipBehavior
import io.tehred5pade.frontporch.ui.downloads.DownloadQuality
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
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

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
                        muxedUrl = capturedMuxedUrl ?: capturedHlsUrl,
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
                        val url = request?.url?.toString().orEmpty()
                        if (url.contains("/youtubei/v1/player")) {
                            runCatching {
                                // Request intercepting happens off the main thread; read response body via OkHttp
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
                                                    if (mime.startsWith("audio/mp4") && aUrl.isNotBlank()) {
                                                        capturedAudioUrl = aUrl
                                                        break
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    mainHandler.post { tryComplete() }
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
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        val reqBuilder = Request.Builder().url(url)
        if (userAgent.isNotBlank()) reqBuilder.header("User-Agent", userAgent)
        if (!cookieHeader.isNullOrBlank()) reqBuilder.header("Cookie", cookieHeader)
        val request = reqBuilder.get().build()

        okHttpClient.newCall(request).executeCancellable(cancellation).use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code} downloading stream")
            val body = response.body ?: throw IllegalStateException("Empty response body")
            val totalBytes = body.contentLength().takeIf { it > 0L } ?: 10_000_000L
            var downloadedBytes = 0L

            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        cancellation.ensureActive()
                        output.write(buffer, 0, read)
                        downloadedBytes += read.toLong()
                        onProgress((downloadedBytes.toFloat() / totalBytes.toFloat()).coerceAtMost(0.99f))
                    }
                }
            }
        }
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
            downloadDirectStream(playlistUrl, iosUserAgent, null, outputFile, cancellation, onProgress)
            return@withContext
        }

        FileOutputStream(outputFile).use { out ->
            segmentUrls.forEachIndexed { index, segUrl ->
                cancellation.ensureActive()
                val req = Request.Builder()
                    .url(segUrl)
                    .header("User-Agent", iosUserAgent)
                    .get()
                    .build()

                okHttpClient.newCall(req).executeCancellable(cancellation).use { resp ->
                    if (resp.isSuccessful) {
                        resp.body?.byteStream()?.use { it.copyTo(out) }
                    }
                }
                onProgress((index + 1).toFloat() / segmentUrls.size.toFloat())
            }
        }
    }

    private suspend fun fetchSponsorBlockSegmentsUs(
        videoId: String,
        categories: List<String>,
        cancellation: DownloadCancellation
    ): List<SponsorSegment> = withContext(Dispatchers.IO) {
        val catJson = JSONArray(categories).toString()
        val encoded = URLEncoder.encode(catJson, "UTF-8")
        val url = "https://sponsor.ajay.app/api/skipSegments?videoID=$videoId&categories=$encoded"

        val req = Request.Builder().url(url).get().build()
        runCatching {
            okHttpClient.newCall(req).executeCancellable(cancellation).use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val arr = JSONArray(resp.body?.string().orEmpty())
                val list = mutableListOf<SponsorSegment>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val cat = SponsorCategory.fromApiKey(obj.optString("category")) ?: continue
                    val times = obj.optJSONArray("segment") ?: continue
                    if (times.length() < 2) continue
                    val start = times.optDouble(0, 0.0).toFloat()
                    val end = times.optDouble(1, 0.0).toFloat()
                    if (end > start + 0.3f) {
                        list.add(SponsorSegment(obj.optString("UUID"), start, end, cat))
                    }
                }
                list.sortedBy { it.startSec }
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun remuxTracksToMp4(
        videoRaw: File?,
        audioRaw: File?,
        outMp4: File,
        skipSegments: List<SponsorSegment> = emptyList(),
        cancellation: DownloadCancellation
    ) = withContext(Dispatchers.IO) {
        cancellation.ensureActive()
        val muxer = MediaMuxer(outMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val extractorVideo = if (videoRaw != null && videoRaw.exists()) MediaExtractor().apply { setDataSource(videoRaw.absolutePath) } else null
        val extractorAudio = if (audioRaw != null && audioRaw.exists()) MediaExtractor().apply { setDataSource(audioRaw.absolutePath) } else null

        try {
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            val buffer = ByteBuffer.allocate(256 * 1024)
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

            muxer.start()

            // Mux video samples with SponsorBlock segment trimming
            if (extractorVideo != null && videoTrackIndex >= 0) {
                var outputPresentationTimeUs = 0L
                var lastInputTimeUs = 0L

                while (true) {
                    cancellation.ensureActive()
                    info.offset = 0
                    info.size = extractorVideo.readSampleData(buffer, 0)
                    if (info.size < 0) break

                    info.presentationTimeUs = extractorVideo.sampleTime
                    info.flags = extractorVideo.sampleFlags
                    lastInputTimeUs = info.presentationTimeUs

                    val currentSec = info.presentationTimeUs / 1_000_000f
                    val matchingSkip = skipSegments.firstOrNull { currentSec >= it.startSec && currentSec < it.endSec }

                    if (matchingSkip != null) {
                        // Skip this sample; advance extractor
                        extractorVideo.advance()
                        continue
                    }

                    // Adjust timestamps if segments were removed
                    val trimmedOffsetUs = skipSegments.filter { it.endSec <= currentSec }.sumOf { ((it.endSec - it.startSec) * 1_000_000f).toLong() }
                    info.presentationTimeUs = (info.presentationTimeUs - trimmedOffsetUs).coerceAtLeast(outputPresentationTimeUs)
                    outputPresentationTimeUs = info.presentationTimeUs

                    muxer.writeSampleData(videoTrackIndex, buffer, info)
                    extractorVideo.advance()
                }
            }

            // Mux audio samples
            if (extractorAudio != null && audioTrackIndex >= 0) {
                var outputAudioTimeUs = 0L
                while (true) {
                    cancellation.ensureActive()
                    info.offset = 0
                    info.size = extractorAudio.readSampleData(buffer, 0)
                    if (info.size < 0) break

                    info.presentationTimeUs = extractorAudio.sampleTime
                    info.flags = extractorAudio.sampleFlags

                    val currentSec = info.presentationTimeUs / 1_000_000f
                    if (skipSegments.any { currentSec >= it.startSec && currentSec < it.endSec }) {
                        extractorAudio.advance()
                        continue
                    }

                    val trimmedOffsetUs = skipSegments.filter { it.endSec <= currentSec }.sumOf { ((it.endSec - it.startSec) * 1_000_000f).toLong() }
                    info.presentationTimeUs = (info.presentationTimeUs - trimmedOffsetUs).coerceAtLeast(outputAudioTimeUs)
                    outputAudioTimeUs = info.presentationTimeUs

                    muxer.writeSampleData(audioTrackIndex, buffer, info)
                    extractorAudio.advance()
                }
            }

        } finally {
            runCatching { extractorVideo?.release() }
            runCatching { extractorAudio?.release() }
            runCatching {
                muxer.stop()
                muxer.release()
            }
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
            val docDir = DocumentFile.fromTreeUri(context, customUri)
            if (docDir != null && docDir.canWrite()) {
                val newDoc = docDir.createFile(mimeType, targetName)
                if (newDoc != null) {
                    context.contentResolver.openOutputStream(newDoc.uri)?.use { out ->
                        sourceFile.inputStream().use { it.copyTo(out) }
                    }
                    return@withContext SavedFileResult(newDoc.uri.toString(), sourceFile.length())
                }
            }
        }

        // Fallback to standard Environment.DIRECTORY_DOWNLOADS/FrontPorch
        val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FrontPorch").apply { mkdirs() }
        val targetFile = File(downloadsDir, targetName)
        sourceFile.copyTo(targetFile, overwrite = true)
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
