package com.example.floatinglyrics

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper

internal class PlaybackObserver(context: Context, private val onChanged: () -> Unit) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, PlaybackNotificationListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    var hasAccess = false
        private set
    var controller: MediaController? = null
        private set
    var song: Song? = null
        private set
    var state: PlaybackState? = null
        private set

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = snapshot()
        override fun onPlaybackStateChanged(state: PlaybackState?) = snapshot()
        override fun onExtrasChanged(extras: Bundle?) = snapshot()
        override fun onSessionDestroyed() = bind(null)
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { bind(it) }

    fun start() {
        if (started) return
        try {
            manager.addOnActiveSessionsChangedListener(sessions, component, handler)
            started = true
            hasAccess = true
            bind(manager.getActiveSessions(component))
        } catch (_: SecurityException) {
            stop()
            hasAccess = false
            onChanged()
        }
    }

    fun stop() {
        if (started) manager.removeOnActiveSessionsChangedListener(sessions)
        started = false
        controller?.unregisterCallback(callback)
        controller = null
        song = null
        state = null
    }

    private fun bind(sessions: List<MediaController>?) {
        val next = sessions?.firstOrNull { it.packageName == "com.google.android.apps.youtube.music" }
        if (next?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = next
            next?.registerCallback(callback, handler)
        }
        snapshot()
    }

    private fun snapshot() {
        val metadata = controller?.metadata
        val title = metadata?.description?.title?.toString().orEmpty()
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.description?.subtitle?.toString().orEmpty()
        val videoId = sequenceOf(
            metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI),
            controller?.extras?.getString("videoId")
        ).firstNotNullOfOrNull(YouTubeMusicLyrics::videoId)
        song = if (title.isBlank() || artist.isBlank()) null else Song(
            title, artist, metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0, videoId
        )
        state = controller?.playbackState
        onChanged()
    }
}
