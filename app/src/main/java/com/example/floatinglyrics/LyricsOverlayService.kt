package com.example.floatinglyrics

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.view.ViewConfiguration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.SharedPreferences
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import android.text.TextUtils
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.MotionEvent
import android.widget.ScrollView
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.IOException
import org.json.JSONException
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import android.widget.ImageButton
import android.widget.FrameLayout
import android.view.View
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf

internal class LyricsLookupException(message: String) : IOException(message)

class LyricsOverlayService : AccessibilityService() {
    companion object {
        val lyricsWarning = mutableStateOf("")
        val translationWarning = mutableStateOf("")
        val translationStatus = mutableStateOf("")
        val fullLyrics = mutableStateOf("")
        val lastCheck = mutableStateOf("아직 유튜브 뮤직 화면을 감지하지 않았어요.")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cache = object : android.util.LruCache<String, Lyrics>(512 * 1024) {
        override fun sizeOf(key: String, value: Lyrics): Int =
            (value.plain.length * 2 + value.lines.sumOf { it.text.length * 2 + 32 }).coerceAtLeast(1)
    }
    private val diskCache by lazy { LyricsDiskCache(this) }
    private val missingLyrics = android.util.LruCache<String, Long>(32)
    private val playbackObserver: PlaybackObserver by lazy {
        PlaybackObserver(this) { updateLyrics() }
    }
    private var lookup: Job? = null
    private var translationJob: Job? = null
    private val translations = mutableMapOf<String, String>()
    private val translatedSongs = object : android.util.LruCache<String, Map<String, String>>(512 * 1024) {
        override fun sizeOf(key: String, value: Map<String, String>): Int =
            value.entries.sumOf { (it.key.length + it.value.length) * 2 + 32 }.coerceAtLeast(1)
    }
    private var song: Song? = null
    private var lyrics: Lyrics? = null
    private var status = ""
    private var translationWarningShown = false
    private var translationRetryAt = 0L
    private var translationRetried = false
    private val tick = object : Runnable {
        override fun run() {
            if (overlay == null) return
            updateLyrics()
        }
    }
    private val recentChecks = ArrayDeque<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private var overlay: ScrollView? = null
    private var coverWindow: FrameLayout? = null
    private var lyricsButton: ImageButton? = null
    private var pendingTapOffset: Int? = null
    private var popupButton: ImageButton? = null
    private var popupMode = false
    private var overlayLabel: TextView? = null
    private var coverBounds: Rect? = null
    private val lyricsPreferences by lazy { LyricsSettings.preferences(this) }
    private var settings = LyricsSettings()
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val updated = LyricsSettings.read(lyricsPreferences)
        if (settings != updated) {
            if (settings.textSize != updated.textSize || !settings.autoScroll && updated.autoScroll) {
                followedLine = -1
            }
            val translationChanged = settings.translationEnabled != updated.translationEnabled
            settings = updated
            if (translationChanged) {
                resetTranslation()
                val current = song
                val loaded = lyrics
                if (current != null && loaded != null) startTranslation(current, loaded)
            }
            applyOverlayMode()
            updateCoverBackground()
            refreshOverlay()
            updateLyrics()
        }
    }
    private var lyricsVisible = false
    private var displayedLyrics: Lyrics? = null
    private var displayedSong: Song? = null
    private var displayedTranslationCount = 0
    private var lineRanges = emptyList<IntRange?>()
    private var highlightedLine = -1
    private var followedLine = -1
    private var touchStarted = false
    private var touchStartY = 0f
    private var manualScrollUntil = 0L
    private val activeColor = ForegroundColorSpan(Color.WHITE)
    private val activeWeight = StyleSpan(Typeface.BOLD)
    private val refresh = Runnable { refreshOverlay() }
    private val youtubeMusic = "com.google.android.apps.youtube.music"
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) hideOverlay() else refreshOverlay()
        }
    }

    override fun onServiceConnected() {
        settings = LyricsSettings.read(lyricsPreferences)
        lyricsPreferences.registerOnSharedPreferenceChangeListener(settingsListener)
        if (!screenReceiverRegistered) {
            ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiverRegistered = true
        }
        refreshOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val fromYouTubeMusic = event.packageName?.toString() == youtubeMusic
        if ((event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) && !fromYouTubeMusic
        ) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            refreshOverlay()
        } else if (!handler.hasCallbacks(refresh)) {
            handler.postDelayed(refresh, 180)
        }
    }

    override fun onInterrupt() {
        handler.removeCallbacks(refresh)
        hideOverlay()
    }

    override fun onDestroy() {
        onInterrupt()
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        lyricsPreferences.unregisterOnSharedPreferenceChangeListener(settingsListener)
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshOverlay() {
        handler.removeCallbacks(refresh)
        refreshPlayerSurface()
    }

    private fun refreshPlayerSurface() {
        if (!settings.enabled) {
            hideOverlay()
            return
        }
        if (!getSystemService(PowerManager::class.java).isInteractive ||
            getSystemService(KeyguardManager::class.java).isKeyguardLocked
        ) {
            hideOverlay()
            return
        }
        // An explicitly opened popup can continue outside the player. Its two
        // player buttons are hidden by syncLyricsButton().
        if (popupMode) {
            updateLyrics()
            return
        }
        val root = windows.firstOrNull {
            it.isActive && it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
        }?.root ?: rootInActiveWindow
        val player = root?.takeIf { it.packageName?.toString() == youtubeMusic }
            ?.let(PlayerLayoutDetector::inspect)
        if (player?.expanded != true) {
            recordCheck("전체 재생화면 아님 · 가사창과 버튼 숨김")
            // Hide immediately on Home, Search, Library, mini-player or another
            // app. Keep the user's open/closed choice for returning to the player.
            hideOverlay(preserveOpenState = true)
            return
        }
        recordCheck(if (player.playlistVisible) "재생목록 · 고정 가사창 위쪽 절반 사용"
            else "전체 재생화면 · 저장한 고정 위치 사용")
        showOverlay(fixedPlayerBounds(player.playlistVisible))
    }

    private fun fixedPlayerBounds(playlistVisible: Boolean = false): Rect {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.density
        val inset = (20 * density).toInt().coerceAtMost(width / 4)
        val top = (120 * density).toInt().coerceAtMost(height / 4)
        val bottomInset = (32 * density).toInt()
        val side = minOf(width - inset * 2, height - top - bottomInset).coerceAtLeast(1)
        val left = (width - side) / 2
        // The screen dimensions are the only geometry input. User offsets are
        // applied by coverLayout(); player artwork and sheet positions never are.
        val panelHeight = if (playlistVisible) (side / 2).coerceAtLeast(1) else side
        return Rect(left, top, left + side, top + panelHeight)
    }

    private fun recordCheck(message: String) {
        if (recentChecks.lastOrNull() == message) return
        if (recentChecks.size == 3) recentChecks.removeFirst()
        recentChecks.addLast(message)
        lastCheck.value = recentChecks.reversed().joinToString("\n\n")
    }

    private fun showOverlay(bounds: Rect) {
        val existing = coverWindow
        if (existing != null) {
            if (coverBounds != bounds) {
                coverBounds = Rect(bounds)
                windowManager.updateViewLayout(existing, coverLayout(bounds))
                followedLine = -1
            }
            syncLyricsButton()
            return
        }

        val padding = (16 * resources.displayMetrics.density).toInt()
        val label = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = settings.textSize.toFloat()
            setTextColor(Color.WHITE)
            setPadding(padding, padding, padding, padding)
            setOnClickListener {
                if (popupMode) {
                    leavePopup()
                    return@setOnClickListener
                }
                if (!lyricsVisible || (!settings.seekOnLyricTap && !settings.useLyricsButton)) {
                    setLyricsVisible(!lyricsVisible)
                } else if (settings.seekOnLyricTap) {
                    seekTappedLyric()
                }
            }
        }
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        label.setOnTouchListener(lyricTapListener(touchSlop))
        val cover = object : ScrollView(this) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchStarted = true
                        touchStartY = event.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (kotlin.math.abs(event.y - touchStartY) > touchSlop) {
                            manualScrollUntil = SystemClock.elapsedRealtime() + 5000
                            if (settings.autoScroll) followedLine = -1
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        touchStarted = false
                        scheduleLyricsUpdate()
                    }
                }
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) {
                    updateLyricsPadding()
                    followedLine = -1
                    overlayLabel?.doOnLayout { updateLyrics() }
                }
            }
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            importantForAccessibility = ScrollView.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(label, android.widget.FrameLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            ))
        }
        val popupDrag = popupDragListener(touchSlop)
        val window = object : FrameLayout(this) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                return if (popupMode) popupDrag.onTouch(this, event) else super.dispatchTouchEvent(event)
            }
        }.apply {
            background = GradientDrawable().apply {
                cornerRadius = 12 * resources.displayMetrics.density
                setColor(Color.TRANSPARENT)
            }
            clipToOutline = true
            addView(cover, FrameLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            ))

        }
        try {
            windowManager.addView(window, coverLayout(bounds))
            coverWindow = window
            updateCoverBackground()
            overlay = cover
            overlayLabel = label
            coverBounds = Rect(bounds)
            applyCoverVisibility()
            syncLyricsButton()
            if (lyricsVisible) playbackObserver.start()
            updateLyrics()
        } catch (error: WindowManager.BadTokenException) {
            recordCheck("가사창 열기 실패: ${error.javaClass.simpleName}")
            android.util.Log.w("LyricsOverlay", "Overlay window unavailable", error)
        }
    }

    private fun coverLayout(
        bounds: Rect,
        offsetX: Int = settings.coverOffsetX,
        offsetY: Int = settings.coverOffsetY
    ) = WindowManager.LayoutParams(
        bounds.width(), bounds.height(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        val metrics = resources.displayMetrics
        gravity = Gravity.TOP or Gravity.LEFT
        x = (bounds.left + (offsetX * metrics.density).toInt())
            .coerceIn(0, (metrics.widthPixels - bounds.width()).coerceAtLeast(0))
        y = (bounds.top + 2 + (offsetY * metrics.density).toInt())
            .coerceIn(0, (metrics.heightPixels - fixedPlayerBounds().height()).coerceAtLeast(0))
    }

    private fun popupDragListener(touchSlop: Int): View.OnTouchListener {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        return View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val position = view.layoutParams as WindowManager.LayoutParams
                    downX = event.rawX
                    downY = event.rawY
                    startX = position.x
                    startY = position.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (moved || kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) {
                        moved = true
                        windowManager.updateViewLayout(view, popupLayout(startX + dx.toInt(), startY + dy.toInt(), view.height))
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        val position = view.layoutParams as WindowManager.LayoutParams
                        val density = resources.displayMetrics.density
                        lyricsPreferences.edit()
                            .putInt("popupOffsetX", kotlin.math.round(position.x / density).toInt())
                            .putInt("popupOffsetY", kotlin.math.round(position.y / density).toInt())
                            .apply()
                    } else overlayLabel?.performClick()
                }
                MotionEvent.ACTION_CANCEL -> windowManager.updateViewLayout(view, popupLayout())
            }
            true
        }
    }

    private fun popupLayout(
        offsetX: Int = (settings.popupOffsetX * resources.displayMetrics.density).toInt(),
        offsetY: Int = (settings.popupOffsetY * resources.displayMetrics.density).toInt(),
        measuredHeight: Int = 0
    ): WindowManager.LayoutParams {
        val metrics = resources.displayMetrics
        val width = (metrics.widthPixels * 0.9f).toInt()
        val height = if (measuredHeight > 0) measuredHeight
            else ((32 + settings.popupLines * settings.popupTextSize * 1.5f) * metrics.density).toInt()
        val maxX = ((metrics.widthPixels - width) / 2).coerceAtLeast(0)
        val maxY = ((metrics.heightPixels - height) / 2).coerceAtLeast(0)
        return WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            x = offsetX.coerceIn(-maxX, maxX)
            y = offsetY.coerceIn(-maxY, maxY)
        }
    }

    private fun applyOverlayMode() {
        val window = coverWindow ?: return
        val cover = overlay ?: return
        val label = overlayLabel ?: return
        val params = if (popupMode) popupLayout() else coverBounds?.let { coverLayout(it) } ?: return
        windowManager.updateViewLayout(window, params)
        cover.layoutParams = FrameLayout.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            if (popupMode) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT
        )
        label.textSize = (if (popupMode) settings.popupTextSize else settings.textSize).toFloat()
        label.maxLines = if (popupMode) settings.popupLines else Int.MAX_VALUE
        label.ellipsize = if (popupMode) TextUtils.TruncateAt.END else null
        cover.isVerticalScrollBarEnabled = !popupMode
        applyCoverVisibility()
        syncLyricsButton()
        displayedLyrics = null
        displayedSong = null
        updateCoverBackground()
    }

    private fun leavePopup() {
        popupMode = false
        applyOverlayMode()
        refreshOverlay()
        updateLyrics()
    }


    private fun setLyricsVisible(visible: Boolean) {
        lyricsVisible = visible
        pendingTapOffset = null
        if (visible) followedLine = -1
        applyCoverVisibility()
        updateCoverBackground()
        syncLyricsButton()
        if (visible) {
            playbackObserver.start()
            val current = song
            val loaded = lyrics
            if (current != null && loaded != null && translationJob?.isActive != true && translations.isEmpty()) {
                resetTranslation()
                startTranslation(current, loaded)
            }
        } else {
            playbackObserver.stop()
            lookup?.cancel()
            translationJob?.cancel()
            if (lyrics == null) song = null
        }
        updateLyrics()
    }

    private fun applyCoverVisibility() {
        coverWindow?.visibility = if (lyricsVisible || !settings.useLyricsButton || popupMode)
            View.VISIBLE else View.GONE
    }

    private fun syncLyricsButton(position: WindowManager.LayoutParams? = null) {
        if (popupMode || (!settings.useLyricsButton && !lyricsVisible)) {
            lyricsButton?.visibility = View.GONE
            popupButton?.visibility = View.GONE
            return
        }
        val window = coverWindow ?: return
        val frame = position ?: window.layoutParams as WindowManager.LayoutParams
        val density = resources.displayMetrics.density
        val width = (60 * density).toInt()
        val height = (44 * density).toInt()
        val inset = (4 * density).toInt()
        val params = WindowManager.LayoutParams(
            width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (frame.x + frame.width - width - inset).coerceAtLeast(0)
            y = (frame.y + frame.height - height - inset).coerceAtLeast(0)
        }
        val existing = lyricsButton
        if (existing == null) {
            val button = ImageButton(this).apply {
                setColorFilter(Color.WHITE)
                val padding = (12 * density).toInt()
                setPadding(padding, padding, padding, padding)
                background = GradientDrawable().apply {
                    cornerRadius = 22 * density
                    setColor(0xE6282828.toInt())
                }
                isClickable = true
                isFocusable = false
                setOnClickListener { setLyricsVisible(!lyricsVisible) }
            }
            try {
                windowManager.addView(button, params)
                lyricsButton = button
            } catch (error: WindowManager.BadTokenException) {
                android.util.Log.w("LyricsOverlay", "Lyrics button unavailable", error)
                return
            }
        } else {
            val previous = existing.layoutParams as WindowManager.LayoutParams
            if (previous.x != params.x || previous.y != params.y) {
                windowManager.updateViewLayout(existing, params)
            }
            existing.visibility = View.VISIBLE
        }
        lyricsButton?.apply {
            setImageResource(if (lyricsVisible) R.drawable.ic_lyrics_close else R.drawable.ic_lyrics_open)
            contentDescription = if (lyricsVisible) "Eom 가사 닫기" else "Eom 가사 열기"
        }
        if (popupButton == null) {
            val button = ImageButton(this).apply {
                setImageResource(R.drawable.ic_lyrics_popup)
                setColorFilter(Color.WHITE)
                val padding = (12 * density).toInt()
                setPadding(padding, padding, padding, padding)
                background = GradientDrawable().apply {
                    cornerRadius = 22 * density
                    setColor(0xE6282828.toInt())
                }
                contentDescription = "작은 가사 팝업으로 전환"
                setOnClickListener {
                    popupMode = true
                    applyOverlayMode()
                    updateLyrics()
                }
            }
            try {
                windowManager.addView(button, WindowManager.LayoutParams().apply {
                    copyFrom(params)
                    y = (params.y - height - inset).coerceAtLeast(0)
                })
                popupButton = button
            } catch (error: WindowManager.BadTokenException) {
                android.util.Log.w("LyricsOverlay", "Popup button unavailable", error)
            }
        } else {
            val above = WindowManager.LayoutParams().apply {
                copyFrom(params)
                y = (params.y - height - inset).coerceAtLeast(0)
            }
            val previous = popupButton!!.layoutParams as WindowManager.LayoutParams
            if (previous.x != above.x || previous.y != above.y) {
                windowManager.updateViewLayout(popupButton, above)
            }
        }
        popupButton?.visibility = if (lyricsVisible) View.VISIBLE else View.GONE
    }

    private fun lyricTapListener(touchSlop: Int): View.OnTouchListener {
        var downX = 0f
        var downY = 0f
        var downTime = 0L
        var moved = false
        return View.OnTouchListener { view, event ->
            if (popupMode) return@OnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downTime = event.eventTime
                    moved = false
                    pendingTapOffset = null
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.x - downX) > touchSlop ||
                        kotlin.math.abs(event.y - downY) > touchSlop) moved = true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && event.eventTime - downTime < ViewConfiguration.getLongPressTimeout()) {
                        val label = view as TextView
                        pendingTapOffset = lyricOffset(label, event.x, event.y)
                        view.performClick()
                        pendingTapOffset = null
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    moved = true
                    pendingTapOffset = null
                }
            }
            true
        }
    }

    private fun lyricOffset(label: TextView, x: Float, y: Float): Int? {
        val layout = label.layout ?: return null
        val localX = x - label.totalPaddingLeft + label.scrollX
        val localY = y - label.totalPaddingTop + label.scrollY
        if (localY < 0 || localY >= layout.height) return null
        val row = layout.getLineForVertical(localY.toInt())
        if (localX < layout.getLineLeft(row) || localX > layout.getLineRight(row)) return null
        return layout.getOffsetForHorizontal(row, localX)
    }

    private fun seekTappedLyric() {
        val offset = pendingTapOffset ?: return
        val loaded = lyrics ?: return
        val displayed = displayedSong ?: return
        if (displayedLyrics !== loaded || loaded.lines.isEmpty()) {
            Toast.makeText(this, "시간 정보가 있는 가사에서 이동할 수 있어요.", Toast.LENGTH_SHORT).show()
            return
        }
        val lineIndex = LyricsInteraction.lineAt(offset, lineRanges) ?: return
        val target = loaded.lines.getOrNull(lineIndex)?.timeMs ?: return
        val manager = getSystemService(MediaSessionManager::class.java)
        val session = try {
            manager.getActiveSessions(ComponentName(this, PlaybackNotificationListener::class.java))
                .firstOrNull { it.packageName == youtubeMusic }
        } catch (_: SecurityException) { null } ?: return
        val metadata = session.metadata ?: return
        val title = metadata.description?.title?.toString().orEmpty()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.description?.subtitle?.toString().orEmpty()
        val mediaId = sequenceOf(
            metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI),
            session.extras?.getString("videoId")
        ).firstNotNullOfOrNull(YouTubeMusicLyrics::videoId)
        val current = Song(title, artist, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION), mediaId)
        if (!SongMatcher.sameRecording(current, displayed) || !SongMatcher.sameRecording(current, song)) {
            updateLyrics()
            return
        }
        val state = session.playbackState
        if (state == null || state.actions and PlaybackState.ACTION_SEEK_TO == 0L) {
            Toast.makeText(this, "현재 재생에서는 구간 이동을 지원하지 않아요.", Toast.LENGTH_SHORT).show()
            return
        }
        val duration = current.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        try {
            session.transportControls.seekTo(target.coerceIn(0, duration))
            manualScrollUntil = 0
            followedLine = -1
            handler.removeCallbacks(refresh)
            handler.postDelayed(refresh, 120)
        } catch (_: SecurityException) {
            Toast.makeText(this, "재생 위치를 옮길 수 없어요. 알림 접근 권한을 확인해 주세요.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateLyrics() {
        renderLyrics()
        scheduleLyricsUpdate()
    }

    private fun renderLyrics() {
        val label = overlayLabel ?: return
        if (!getSystemService(PowerManager::class.java).isInteractive ||
            getSystemService(KeyguardManager::class.java).isKeyguardLocked
        ) {
            hideOverlay()
            return
        }

        if (!lyricsVisible) {
            showMessage("")
            return
        }
        if (!playbackObserver.hasAccess) {
            clearSong("Floating Lyrics의 알림 접근을 허용해 주세요.")
            return
        }
        val current = playbackObserver.song
        if (current == null) {
            clearSong("현재 곡 정보를 기다리는 중…")
            return
        }
        if (!SongMatcher.sameRecording(song, current)) startLookup(current)
        val loaded = lyrics
        if (loaded == null) {
            showMessage(lyricsWarning.value.ifBlank { status }, warning = lyricsWarning.value.isNotBlank())
            return
        }
        val position = playbackPosition()
        val activeLine = if (position == null) -1 else LyricsTiming.indexAt(loaded.lines, position)
        if (popupMode) {
            showMessage(popupText(loaded, activeLine))
        } else {
            if (displayedLyrics !== loaded || displayedSong != current ||
                displayedTranslationCount != translations.size
            ) showAllLyrics(label, current, loaded)
            highlightLine(label, activeLine)
        }
        if (translationRetryAt > 0 && SystemClock.elapsedRealtime() >= translationRetryAt) {
            translationRetryAt = 0
            translationRetried = true
            translationWarning.value = ""
            startTranslation(current, loaded)
        }
        val warning = translationWarning.value
        if (warning.isNotBlank() && !translationWarningShown) {
            translationWarningShown = true
            Toast.makeText(this, warning, Toast.LENGTH_LONG).show()
        }
    }

    private fun playbackPosition(): Long? {
        val state = playbackObserver.state ?: return null
        if (state.position < 0) return null
        val speed = state.playbackSpeed
        val elapsed = if (state.state == PlaybackState.STATE_PLAYING &&
            state.lastPositionUpdateTime > 0 && speed.isFinite()
        ) ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0) * speed).toLong()
            else 0L
        val duration = playbackObserver.song?.durationMs?.takeIf { it > 0 } ?: Long.MAX_VALUE
        return (state.position + elapsed).coerceIn(0, duration)
    }

    private fun scheduleLyricsUpdate() {
        handler.removeCallbacks(tick)
        if (overlay == null || !lyricsVisible) return
        val now = SystemClock.elapsedRealtime()
        var nextUpdate = Long.MAX_VALUE
        val state = playbackObserver.state
        val position = playbackPosition()
        val lines = lyrics?.lines
        if (state?.state == PlaybackState.STATE_PLAYING && position != null &&
            state.playbackSpeed.isFinite() && state.playbackSpeed > 0 && !lines.isNullOrEmpty()
        ) {
            val nextLine = lines.getOrNull(LyricsTiming.indexAt(lines, position) + 1)
            if (nextLine != null) {
                val wait = kotlin.math.ceil((nextLine.timeMs - position) / state.playbackSpeed.toDouble())
                    .toLong().coerceIn(16L, 86_400_000L)
                nextUpdate = now + wait
            }
        }
        if (settings.autoScroll && !popupMode && !touchStarted && manualScrollUntil > now) {
            nextUpdate = minOf(nextUpdate, manualScrollUntil)
        }
        if (translationRetryAt > now) nextUpdate = minOf(nextUpdate, translationRetryAt)
        if (nextUpdate != Long.MAX_VALUE) handler.postDelayed(tick, (nextUpdate - now).coerceAtLeast(16))
    }

    private fun popupText(loaded: Lyrics, activeLine: Int): String {
        if (activeLine < 0 && loaded.lines.isNotEmpty()) return "♪"
        val rows = if (loaded.lines.isEmpty()) loaded.plain.lineSequence()
            else loaded.lines.asSequence().drop(activeLine).map { it.text }
        return rows.map(String::trim).filter(String::isNotBlank).flatMap { original ->
            sequenceOf(original, translations[original]).filterNotNull()
        }.take(settings.popupLines).joinToString("\n").ifBlank { "♪" }
    }

    private fun showAllLyrics(label: TextView, current: Song, loaded: Lyrics) {
        val newSong = displayedSong != current
        val rows = if (loaded.lines.isEmpty()) loaded.plain.lines() else loaded.lines.map { it.text }
        val text = SpannableStringBuilder()
        lineRanges = rows.map { original ->
            if (original.isBlank()) {
                if (loaded.lines.isEmpty()) text.append('\n')
                null
            } else {
                val start = text.length
                text.append(original)
                translations[original.trim()]?.let { text.append('\n').append(it) }
                val range = start until text.length
                text.append("\n\n")
                range
            }
        }
        displayedLyrics = loaded
        label.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        updateLyricsPadding()
        label.setTextColor(if (loaded.lines.isEmpty()) Color.WHITE else 0xFFBDBDBD.toInt())
        label.setText(text, TextView.BufferType.SPANNABLE)
        displayedSong = current
        displayedTranslationCount = translations.size
        highlightedLine = -1
        followedLine = -1
        if (newSong) {
            manualScrollUntil = 0
            overlay?.scrollTo(0, 0)
        }
        label.doOnLayout { updateLyrics() }
    }

    private fun updateCoverBackground() {
        val shade = if (lyricsVisible) Color.argb((if (popupMode) settings.popupDarkness else settings.darkness) * 255 / 100, 0, 0, 0)
            else Color.TRANSPARENT
        (coverWindow?.background as? GradientDrawable)?.setColor(shade)
    }

    private fun updateLyricsPadding() {
        val label = overlayLabel ?: return
        val viewport = overlay?.height ?: return
        val vertical = if (displayedLyrics != null) viewport / 2 else label.paddingLeft
        if (label.paddingTop != vertical) {
            label.setPadding(label.paddingLeft, vertical, label.paddingRight, vertical)
        }
    }

    private fun highlightLine(label: TextView, index: Int) {
        val text = label.text as? Spannable ?: return
        val range = lineRanges.getOrNull(index)
        if (index != highlightedLine) {
            text.removeSpan(activeColor)
            text.removeSpan(activeWeight)
            if (range != null) {
                text.setSpan(activeColor, range.first, range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(activeWeight, range.first, range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            highlightedLine = index
        }
        val cover = overlay ?: return
        val target = if (range != null) index else if (index < 0) lineRanges.indexOfFirst { it != null } else -1
        val targetRange = lineRanges.getOrNull(target)
        if (targetRange == null || target == followedLine || (!settings.autoScroll && followedLine >= 0) || touchStarted ||
            SystemClock.elapsedRealtime() < manualScrollUntil
        ) return

        val layout = label.layout ?: return
        if (cover.height <= 0 || cover.isLayoutRequested || label.isLayoutRequested) return
        val top = layout.getLineTop(layout.getLineForOffset(targetRange.first))
        val bottom = layout.getLineBottom(layout.getLineForOffset(targetRange.last))
        val destination = (label.totalPaddingTop + (top + bottom - cover.height) / 2).coerceAtLeast(0)
        if (followedLine < 0) cover.scrollTo(0, destination) else cover.smoothScrollTo(0, destination)
        followedLine = target
    }

    private fun showMessage(message: String, warning: Boolean = false) {
        val label = overlayLabel ?: return
        if (displayedLyrics != null || label.text.toString() != message) {
            label.gravity = Gravity.CENTER
            label.setPadding(label.paddingLeft, label.paddingLeft, label.paddingRight, label.paddingLeft)
            label.setTextColor(if (warning) 0xFFFFD9A8.toInt() else Color.WHITE)
            label.text = message
            overlay?.scrollTo(0, 0)
        }
        displayedLyrics = null
        displayedSong = null
    }

    private fun clearSong(message: String) {
        resetTranslation()
        lyricsWarning.value = ""
        lookup?.cancel()
        song = null
        lyrics = null
        fullLyrics.value = ""
        showMessage(message)
    }

    private fun startLookup(current: Song, force: Boolean = false) {
        lookup?.cancel()
        resetTranslation()
        lyricsWarning.value = ""
        song = current
        lyrics = null
        fullLyrics.value = ""
        val key = SongMatcher.recordingKey(current)
        if (force) {
            cache.remove(key)
            missingLyrics.remove(key)
            translatedSongs.remove(key)
        } else {
            cache.get(key)?.let {
                acceptLyrics(current, it)
                return
            }
        }
        status = "가사 찾는 중…"
        lookup = scope.launch {
            try {
                if (force) diskCache.remove(current)
                else diskCache.read(current)?.let {
                    acceptLyrics(current, it)
                    return@launch
                }
                if (!force && (missingLyrics.get(key) ?: 0) > SystemClock.elapsedRealtime()) {
                    lyricsWarning.value = "이 곡과 일치하는 가사를 찾지 못했어요. 새로고침으로 다시 검색할 수 있어요."
                    return@launch
                }
                val result = searchSources(current) { preview -> acceptLyrics(current, preview) }
                if (result == null) {
                    missingLyrics.put(key, SystemClock.elapsedRealtime() + 300_000)
                    lyricsWarning.value = "이 곡과 일치하는 가사를 찾지 못했어요."
                } else {
                    acceptLyrics(current, result)
                    diskCache.write(current, result)
                }
            } catch (error: IOException) {
                coroutineContext.ensureActive()
                lyricsWarning.value = error.message?.takeIf { error is LyricsLookupException }
                    ?: "가사 서버에 연결할 수 없어요. 새로고침으로 다시 시도해 주세요."
            } catch (_: JSONException) {
                coroutineContext.ensureActive()
                lyricsWarning.value = "가사 서버의 응답을 읽을 수 없어요."
            } finally {
                val request = coroutineContext[kotlinx.coroutines.Job]
                if (lookup == request) lookup = null
                if (request?.isActive == true) updateLyrics()
            }
        }
    }

    private fun acceptLyrics(current: Song, result: Lyrics) {
        if (!SongMatcher.sameRecording(song, current)) return
        val previous = lyrics
        lyrics = result
        cache.put(SongMatcher.recordingKey(current), result)
        lyricsWarning.value = ""
        if (previous == null || previous.plain != result.plain) {
            resetTranslation()
            startTranslation(current, result)
        }
        updateLyrics()
    }

    private suspend fun searchSources(current: Song, onPlain: (Lyrics) -> Unit): Lyrics? = coroutineScope {
        val domestic = Regex("[가-힣]").containsMatchIn(current.title + current.artist)
        val providers = buildList<suspend (Song) -> Lyrics?> {
            add(Lrclib::find)
            if (current.videoId != null) add(YouTubeMusicLyrics::find)
            if (domestic) {
                add(BugsLyrics::find)
                add(LyricRadar::find)
                add(GenieLyrics::find)
            } else {
                add(LyricRadar::find)
                add(BugsLyrics::find)
                add(GenieLyrics::find)
            }
        }
        val results = Channel<Pair<Int, Result<Lyrics?>>>(providers.size)
        val requests = providers.mapIndexed { priority, provider ->
            launch(Dispatchers.Default) {
                // Give fast sources a head start before opening more connections.
                delay((priority / 2) * 450L)
                val result = try {
                    Result.success(provider(current))
                } catch (error: IOException) {
                    Result.failure(error)
                } catch (error: JSONException) {
                    Result.failure(error)
                }
                results.send(priority to result)
            }
        }
        var fallback: Lyrics? = null
        var fallbackPriority = Int.MAX_VALUE
        var failed = 0
        var received = 0
        try {
            val synced = withTimeoutOrNull(16_000L) {
                repeat(providers.size) {
                    val (priority, result) = results.receive()
                    received++
                    val candidate = result.getOrElse { failed++; null }
                    if (candidate != null) {
                        if (candidate.lines.isNotEmpty()) return@withTimeoutOrNull candidate
                        if (priority < fallbackPriority) {
                            val first = fallback == null
                            fallback = candidate
                            fallbackPriority = priority
                            if (first) onPlain(candidate)
                        }
                    }
                }
                null
            }
            synced ?: fallback ?: when {
                received < providers.size ->
                    throw LyricsLookupException("가사 검색 시간이 초과됐어요. 새로고침으로 다시 시도해 주세요.")
                failed > 0 ->
                    throw LyricsLookupException("가사 검색 중 일부 출처에 연결하지 못했어요. 새로고침으로 다시 시도해 주세요.")
                else -> null
            }
        } finally {
            requests.forEach { it.cancel() }
            results.cancel()
        }
    }

    private fun resetTranslation() {
        translationJob?.cancel()
        translations.clear()
        translationStatus.value = ""
        translationWarning.value = ""
        translationWarningShown = false
        translationRetryAt = 0
        translationRetried = false
    }

    private fun startTranslation(current: Song, loaded: Lyrics) {
        translationJob?.cancel()
        fun updateFullLyrics() {
            val text = loaded.plain.lineSequence().joinToString("\n") { original ->
                translations[original.trim()]?.let { "$original\n$it\n" } ?: original
            }
            fullLyrics.value = "${current.title} · ${current.artist}\n\n$text"
        }

        if (!settings.translationEnabled || !lyricsVisible || overlay == null) {
            updateFullLyrics()
            return
        }
        val saved = translatedSongs.get(SongMatcher.recordingKey(current))
        if (saved != null) {
            translations.putAll(saved)
            updateFullLyrics()
            return
        }
        updateFullLyrics()
        val source = KoreanTranslation.sourceLanguage(loaded) ?: return
        translationStatus.value = "온라인으로 한국어 가사를 번역하는 중…"
        translationJob = scope.launch {
            try {
                translations.putAll(KoreanTranslation.translate(applicationContext, loaded, source))
                if (translations.isEmpty()) {
                    translationWarning.value = "한국어 번역 결과가 없어요. 원문 가사를 표시해요."
                } else {
                    translatedSongs.put(SongMatcher.recordingKey(current), translations.toMap())
                }
                translationStatus.value = ""
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                translationStatus.value = ""
                val retryAfter = KoreanTranslation.retryAfter()
                if (!translationRetried && retryAfter > SystemClock.elapsedRealtime() &&
                    (error is TranslationHttpException || error is TranslationCooldownException)
                ) translationRetryAt = retryAfter
                translationWarning.value = when (error) {
                    is TranslationCooldownException -> "번역 서버 요청이 제한되어 잠시 쉬고 있어요."
                    is TranslationHttpException -> when (error.status) {
                        in 300..399, 403, 429 -> "번역 서버가 요청을 제한했어요 (HTTP ${error.status})."
                        else -> "번역 서버 오류 (HTTP ${error.status})."
                    }
                    is java.net.SocketTimeoutException -> "번역 서버 응답 시간이 초과됐어요."
                    is IOException -> "번역 서버에 연결하거나 가사 응답을 처리하지 못했어요."
                    is JSONException -> "번역 서버 응답 형식이 달라졌어요."
                    else -> "온라인 번역을 완료하지 못했어요."
                } + " 원문 가사를 표시해요."
                android.util.Log.w("LyricsOverlay", "Translation unavailable", error)
            }
            updateFullLyrics()
            updateLyrics()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            cache.evictAll()
            translatedSongs.evictAll()
        }
    }

    private fun hideOverlay(preserveOpenState: Boolean = false) {
        playbackObserver.stop()
        handler.removeCallbacks(tick)
        if (!preserveOpenState) {
            lookup?.cancel()
            translationJob?.cancel()
            lyricsVisible = false
        }
        lyricsButton?.let { windowManager.removeViewImmediate(it) }
        lyricsButton = null
        popupButton?.let { windowManager.removeViewImmediate(it) }
        popupButton = null
        val cover = coverWindow ?: return
        coverWindow = null
        popupMode = false
        overlay = null
        overlayLabel = null
        displayedLyrics = null
        displayedSong = null
        lineRanges = emptyList()
        touchStarted = false
        manualScrollUntil = 0
        coverBounds = null
        pendingTapOffset = null
        handler.removeCallbacks(tick)
        if (!preserveOpenState && lyrics == null) {
            lookup?.cancel()
            song = null
        }
        windowManager.removeViewImmediate(cover)
    }
}
