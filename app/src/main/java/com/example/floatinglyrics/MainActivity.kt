package com.example.floatinglyrics

import android.content.ComponentName
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.floatinglyrics.ui.theme.FloatingLyricsTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

class PlaybackNotificationListener : NotificationListenerService()

class MainActivity : ComponentActivity() {
    private val lyricsPreferences by lazy { LyricsSettings.preferences(this) }
    private var settings by mutableStateOf(LyricsSettings())
    private var playback by mutableStateOf(PlaybackInfo())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settings = LyricsSettings.read(lyricsPreferences)
        setContent {
            LaunchedEffect(Unit) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        playback = readPlayback()
                        delay(1000)
                    }
                }
            }
            FloatingLyricsTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier.padding(innerPadding).verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("가사창 설정", style = MaterialTheme.typography.headlineSmall)
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Text("가사창 사용")
                            Switch(checked = settings.enabled, onCheckedChange = {
                                updateSettings(settings.copy(enabled = it))
                            })
                        }
                        Text("가사 크기 · ${settings.textSize} sp")
                        Slider(
                            value = settings.textSize.toFloat(),
                            onValueChange = { updateSettings(settings.copy(textSize = it.roundToInt())) },
                            valueRange = 12f..28f,
                            steps = 15
                        )
                        Text("배경 어둡기 · ${settings.darkness}%")
                        Slider(
                            value = settings.darkness.toFloat(),
                            onValueChange = { updateSettings(settings.copy(darkness = it.roundToInt())) },
                            valueRange = 25f..95f
                        )
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Text("현재 가사 줄 자동 따라가기")
                            Switch(checked = settings.autoScroll, onCheckedChange = {
                                updateSettings(settings.copy(autoScroll = it))
                            })
                        }
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Text("한국어 번역 표시")
                            Switch(checked = settings.translationEnabled, onCheckedChange = {
                                updateSettings(settings.copy(translationEnabled = it))
                            })
                        }
                        Button(onClick = {
                            updateSettings(settings.copy(coverOffsetX = 0, coverOffsetY = 0))
                        }) { Text("가사창 위치 초기화") }
                        Text("작은 팝업 설정", style = MaterialTheme.typography.titleLarge)
                        Text("표시 줄 수 · ${settings.popupLines}줄")
                        Slider(
                            value = settings.popupLines.toFloat(),
                            onValueChange = { updateSettings(settings.copy(popupLines = it.roundToInt())) },
                            valueRange = 1f..4f,
                            steps = 2
                        )
                        Text("팝업 글자 크기 · ${settings.popupTextSize} sp")
                        Slider(
                            value = settings.popupTextSize.toFloat(),
                            onValueChange = { updateSettings(settings.copy(popupTextSize = it.roundToInt())) },
                            valueRange = 12f..28f,
                            steps = 15
                        )
                        Text("팝업 배경 어둡기 · ${settings.popupDarkness}%")
                        Slider(
                            value = settings.popupDarkness.toFloat(),
                            onValueChange = { updateSettings(settings.copy(popupDarkness = it.roundToInt())) },
                            valueRange = 0f..95f
                        )
                        Text("YouTube Music", style = MaterialTheme.typography.titleLarge)
                        Button(onClick = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }) {
                            Text("가사창 접근성 설정 열기")
                        }
                        Text(
                            "1. 위 버튼을 눌러 접근성 설정을 열어요.\n" +
                                "2. 설치된 앱 → Floating Lyrics 가사창 → 사용을 켜고 허용을 눌러요.\n" +
                                "3. 이 앱의 ‘가사창 사용’을 켜고, 유튜브 뮤직의 ‘지금 재생 중’에서 앨범 커버를 눌러요.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        val lyricsWarning = LyricsOverlayService.lyricsWarning.value
                        val translationWarning = LyricsOverlayService.translationWarning.value
                        val warning = lyricsWarning.ifBlank {
                            if (settings.translationEnabled) translationWarning else ""
                        }
                        if (warning.isNotBlank()) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Text(warning, modifier = Modifier.padding(12.dp))
                            }
                        }
                        val translationStatus = LyricsOverlayService.translationStatus.value
                        if (settings.translationEnabled && translationStatus.isNotBlank()) Text(translationStatus)
                        val fullLyrics = LyricsOverlayService.fullLyrics.value
                        if (fullLyrics.isNotBlank()) {
                            Text("전체 가사", style = MaterialTheme.typography.titleLarge)
                            Text(fullLyrics)
                        }
                        Text(LyricsOverlayService.lastCheck.value)
                        when {
                            !playback.hasAccess -> {
                                Text("현재 곡을 읽으려면 Floating Lyrics의 알림 접근을 허용해 주세요.")
                                Button(onClick = {
                                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                }) {
                                    Text("알림 접근 설정 열기")
                                }
                            }
                            playback.title.isNullOrBlank() ->
                                Text("YouTube Music에서 곡을 재생한 뒤 이 화면으로 돌아오세요.")
                            else -> {
                                Text(playback.title.orEmpty(), style = MaterialTheme.typography.titleLarge)
                                playback.artist?.takeIf { it.isNotBlank() }?.let { Text(it) }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        settings = LyricsSettings.read(lyricsPreferences)
    }

    private fun updateSettings(value: LyricsSettings) {
        if (settings == value) return
        settings = value
        value.save(lyricsPreferences)
    }

    private fun readPlayback(): PlaybackInfo {
        val listener = ComponentName(this, PlaybackNotificationListener::class.java)
        val manager = getSystemService(MediaSessionManager::class.java)
        val sessions = try {
            manager.getActiveSessions(listener)
        } catch (_: SecurityException) {
            return PlaybackInfo()
        }
        val metadata = sessions.firstOrNull {
            it.packageName == "com.google.android.apps.youtube.music"
        }?.metadata
        return PlaybackInfo(
            hasAccess = true,
            title = metadata?.description?.title?.toString(),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.description?.subtitle?.toString()
        )
    }

    private data class PlaybackInfo(
        val hasAccess: Boolean = false,
        val title: String? = null,
        val artist: String? = null
    )
}









