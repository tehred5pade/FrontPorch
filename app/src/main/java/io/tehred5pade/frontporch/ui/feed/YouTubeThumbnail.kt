package io.tehred5pade.frontporch.ui.feed

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object ThumbnailMemoryCache {
    private val cache = LruCache<String, ImageBitmap>(120)
    fun get(videoId: String): ImageBitmap? = cache.get(videoId)
    fun put(videoId: String, bmp: ImageBitmap) {
        cache.put(videoId, bmp)
    }
}

@Composable
fun YouTubeThumbnail(
    videoId: String,
    contentDescription: String,
    okHttpClient: OkHttpClient,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(videoId) { mutableStateOf(ThumbnailMemoryCache.get(videoId)) }

    LaunchedEffect(videoId) {
        if (bitmap != null) return@LaunchedEffect

        val loaded = withContext(Dispatchers.IO) {
            val urls = listOf(
                "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                "https://i.ytimg.com/vi/$videoId/mqdefault.jpg",
                "https://img.youtube.com/vi/$videoId/0.jpg"
            )
            for (url in urls) {
                val bytes = runCatching {
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 15)")
                        .get()
                        .build()
                    okHttpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) resp.body?.bytes() else null
                    }
                }.getOrNull()

                if (bytes != null && bytes.isNotEmpty()) {
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (decoded != null) {
                        return@withContext decoded.asImageBitmap()
                    }
                }
            }
            null
        }

        if (loaded != null) {
            ThumbnailMemoryCache.put(videoId, loaded)
            bitmap = loaded
        }
    }

    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        val currentBitmap = bitmap
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
