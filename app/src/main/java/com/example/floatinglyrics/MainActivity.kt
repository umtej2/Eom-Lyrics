package com.example.floatinglyrics

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class PlaybackNotificationListener : NotificationListenerService()

class MainActivity : ComponentActivity() {
    private val lyricsPreferences by lazy { LyricsSettings.preferences(this) }
    private var settings by mutableStateOf(LyricsSettings())
    private var notificationAccess by mutableStateOf(false)
    private var accessibilityAccess by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val systemBars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = systemBars, navigationBarStyle = systemBars)
        settings = LyricsSettings.read(lyricsPreferences)
        setContent {
            LyricsAppScreen(
                settings = settings,
                notificationAccess = notificationAccess,
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
        notificationAccess = getSystemService(android.app.NotificationManager::class.java)
            .isNotificationListenerAccessGranted(android.content.ComponentName(this, PlaybackNotificationListener::class.java))
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
}
