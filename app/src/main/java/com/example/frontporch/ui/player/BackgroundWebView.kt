package com.example.frontporch.ui.player

import android.content.Context
import android.view.View
import android.webkit.WebView

class BackgroundWebView(context: Context) : WebView(context) {
    var keepAliveInBackground: Boolean = true

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (keepAliveInBackground && visibility != View.VISIBLE) {
            // Chromium suspends its AudioTrack when the window is hidden. Report it as
            // visible and keep timers running so background playback stays audible.
            super.onWindowVisibilityChanged(View.VISIBLE)
            resumeTimers()
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }

    override fun onPause() {
        if (keepAliveInBackground) {
            // Keep Chromium's audio renderer alive when app is minimized or screen locks.
            onResume()
            resumeTimers()
        } else {
            super.onPause()
        }
    }
}
