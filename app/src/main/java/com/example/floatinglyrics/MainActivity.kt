package com.example.floatinglyrics

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class PlaybackNotificationListener : NotificationListenerService()

class MainActivity : ComponentActivity() {
    private val lyricsPreferences by lazy { LyricsSettings.preferences(this) }
    private var settings by mutableStateOf(LyricsSettings())
    private var playback by mutableStateOf(PlaybackInfo())
    private var accessibilityAccess by mutableStateOf(false)
    private val playbackObserver: PlaybackObserver by lazy {
        PlaybackObserver(this) {
            val current = playbackObserver.song
            playback = PlaybackInfo(playbackObserver.hasAccess, current?.title, current?.artist)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settings = LyricsSettings.read(lyricsPreferences)
        setContent {
            DisposableEffect(lifecycle) {
                val listener = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> playbackObserver.start()
                        Lifecycle.Event.ON_STOP -> playbackObserver.stop()
                        else -> Unit
                    }
                }
                lifecycle.addObserver(listener)
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) playbackObserver.start()
                onDispose {
                    lifecycle.removeObserver(listener)
                    playbackObserver.stop()
                }
            }
            LyricsAppScreen(
                settings = settings,
                title = playback.title,
                artist = playback.artist,
                notificationAccess = playback.hasAccess,
                accessibilityAccess = accessibilityAccess,
                onSettings = ::updateSettings,
                openNotifications = {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
                openAccessibility = {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        settings = LyricsSettings.read(lyricsPreferences)
        val manager = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        accessibilityAccess = manager.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { service ->
            val info = service.resolveInfo.serviceInfo
            info.packageName == packageName &&
                info.name == LyricsOverlayService::class.java.name
        }
    }

    private fun updateSettings(value: LyricsSettings) {
        if (settings == value) return
        settings = value
        value.save(lyricsPreferences)
    }

    private data class PlaybackInfo(
        val hasAccess: Boolean = false,
        val title: String? = null,
        val artist: String? = null
    )
}
