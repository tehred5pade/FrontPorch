package io.tehred5pade.frontporch.ui.feed

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import io.tehred5pade.frontporch.data.innertube.FeedVideoItem

fun handleVideoMenuAction(
    context: Context,
    video: FeedVideoItem,
    action: VideoMenuAction,
    onOpenPlaylistDialog: (FeedVideoItem) -> Unit,
    onQueueDownload: (FeedVideoItem) -> Unit,
    onStartBackgroundPlay: (FeedVideoItem) -> Unit,
    onStartPopupPlay: (FeedVideoItem) -> Unit
) {
    val watchUrl = "https://www.youtube.com/watch?v=${video.videoId}"
    val shareUrl = "https://youtu.be/${video.videoId}"

    when (action) {
        VideoMenuAction.BACKGROUND -> {
            onStartBackgroundPlay(video)
        }
        VideoMenuAction.POPUP -> {
            onStartPopupPlay(video)
        }
        VideoMenuAction.DOWNLOAD -> {
            onQueueDownload(video)
            Toast.makeText(context, "Queued download: ${video.title}", Toast.LENGTH_SHORT).show()
        }
        VideoMenuAction.ADD_TO_PLAYLIST -> {
            onOpenPlaylistDialog(video)
        }
        VideoMenuAction.SHARE -> {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, video.title)
                putExtra(Intent.EXTRA_TEXT, shareUrl)
            }
            val chooser = Intent.createChooser(shareIntent, "Share video").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(chooser) }
        }
        VideoMenuAction.OPEN_IN_BROWSER -> {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(watchUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(browserIntent) }
                .onFailure { Toast.makeText(context, "No app available to open link", Toast.LENGTH_SHORT).show() }
        }
    }
}
