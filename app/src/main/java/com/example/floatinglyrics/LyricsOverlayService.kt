package com.example.floatinglyrics

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.SharedPreferences
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
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.ImageButton
import android.widget.FrameLayout
import android.view.View
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf

class LyricsOverlayService : AccessibilityService() {
    companion object {
        private val TIME_LABEL = Regex("""-?\d{1,2}(?::\d{2}){1,2}""")
        val lyricsWarning = mutableStateOf("")
        val translationWarning = mutableStateOf("")
        val translationStatus = mutableStateOf("")
        val fullLyrics = mutableStateOf("")
        val lastCheck = mutableStateOf("아직 유튜브 뮤직 화면을 감지하지 않았어요.")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cache = android.util.LruCache<Song, Lyrics>(32)
    private var lookup: Job? = null
    private var translationJob: Job? = null
    private val translations = mutableMapOf<String, String>()
    private val translatedSongs = android.util.LruCache<Song, Map<String, String>>(32)
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
            if (overlay != null) handler.postDelayed(this, 250)
        }
    }
    private val recentChecks = ArrayDeque<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private var overlay: ScrollView? = null
    private var coverWindow: FrameLayout? = null
    private var refreshButton: ImageButton? = null
    private var moveButton: ImageButton? = null
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

    override fun onServiceConnected() {
        settings = LyricsSettings.read(lyricsPreferences)
        lyricsPreferences.registerOnSharedPreferenceChangeListener(settingsListener)
        refreshOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.packageName?.toString() != youtubeMusic
        ) return

        handler.removeCallbacks(refresh)
        refreshOverlay()
        // The window hierarchy may settle after the transition event.
        handler.postDelayed(refresh, 200)
    }

    override fun onInterrupt() {
        handler.removeCallbacks(refresh)
        hideOverlay()
    }

    override fun onDestroy() {
        onInterrupt()
        lyricsPreferences.unregisterOnSharedPreferenceChangeListener(settingsListener)
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshOverlay() {
        if (!settings.enabled) {
            hideOverlay()
            return
        }
        if (popupMode) {
            updateLyrics()
            return
        }
        val root = rootInActiveWindow ?: windows.firstOrNull { it.isActive }?.root
        if (root?.packageName?.toString() == youtubeMusic &&
            getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            isExpandedPlayer(root)
        ) {
            val bounds = findCover(root)
            if (bounds != null) {
                showOverlay(bounds)
            } else {
                recordCheck("전체 플레이어에서 앨범 커버 위치를 찾지 못했어요.")
                hideOverlay()
            }
        } else {
            hideOverlay()
        }
    }

    private fun isExpandedPlayer(root: AccessibilityNodeInfo): Boolean {
        var hasTimeline = false
        var hasTransport = false
        var timeLabels = 0
        val ids = sortedSetOf<String>()
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        nodes.add(root)
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            if (node.isVisibleToUser) {
                val id = node.viewIdResourceName.orEmpty().substringAfterLast('/')
                if (id.isNotEmpty()) ids.add(id)
                val description = node.contentDescription?.toString().orEmpty()
                hasTimeline = hasTimeline ||
                    node.className?.toString() == "android.widget.SeekBar" ||
                    id.contains("seek_bar") || id.contains("seekbar")
                hasTransport = hasTransport ||
                    id.contains("previous") || id.contains("next_button") ||
                    id.contains("next_track") ||
                    description.contains("이전 곡") || description.contains("다음 곡") ||
                    description.contains("previous track", ignoreCase = true) ||
                    description.contains("next track", ignoreCase = true)
                if (TIME_LABEL.matches(node.text?.toString()?.trim().orEmpty())) timeLabels++
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(nodes::addLast)
            }
        }
        val expanded = (hasTimeline && (hasTransport || timeLabels >= 2)) ||
            (hasTransport && timeLabels >= 2)
        recordCheck("전체 플레이어: ${if (expanded) "감지" else "미감지"} · " +
            "재생 막대: $hasTimeline · 이전/다음: $hasTransport · 시간 표시: $timeLabels" +
            "\n화면 요소: ${ids.joinToString().ifEmpty { "없음" }}")
        return expanded
    }

    private fun recordCheck(message: String) {
        if (recentChecks.lastOrNull() == message) return
        if (recentChecks.size == 3) recentChecks.removeFirst()
        recentChecks.addLast(message)
        lastCheck.value = recentChecks.reversed().joinToString("\n\n")
    }

    private fun findCover(root: AccessibilityNodeInfo): Rect? {
        val screen = Rect()
        root.getBoundsInScreen(screen)
        val minimum = screen.width() * 0.55f
        val density = resources.displayMetrics.density
        var best: Rect? = null
        var bestNamed = false
        var titleTop = screen.bottom
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        nodes.add(root)
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            if (node.isVisibleToUser) {
                val id = node.viewIdResourceName.orEmpty().substringAfterLast('/')
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if ((id == "song_title" || id == "track_title" || id == "player_title" ||
                        id == "title") && bounds.top > screen.top + minimum
                ) titleTop = minOf(titleTop, bounds.top)

                val named = listOf("artwork", "album_art", "thumbnail", "cover").any(id::contains)
                val image = node.className?.toString() == "android.widget.ImageView"
                val ratio = bounds.width().toFloat() / bounds.height()
                if ((named || image) && bounds.width() >= minimum && ratio in 0.8f..1.25f &&
                    screen.contains(bounds) && bounds.centerY() < screen.centerY() &&
                    (best == null || (named && !bestNamed) ||
                        (named == bestNamed && bounds.width() > best.width()))
                ) {
                    best = bounds
                    bestNamed = named
                }
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(nodes::addLast)
            }
        }
        if (best != null) return best
        // Some player layouts omit the image from the accessibility tree.
        if (titleTop == screen.bottom) return null
        val inset = (20 * density).toInt()
        val bottom = titleTop - (24 * density).toInt()
        val side = minOf(screen.width() - inset * 2, bottom - screen.top - inset * 2)
        if (side < minimum) return null
        return Rect(screen.centerX() - side / 2, bottom - side,
            screen.centerX() - side / 2 + side, bottom)
    }

    private fun showOverlay(bounds: Rect) {
        val existing = coverWindow
        if (existing != null) {
            if (coverBounds != bounds) {
                windowManager.updateViewLayout(existing, coverLayout(bounds))
                coverBounds = Rect(bounds)
                followedLine = -1
            }
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
                lyricsVisible = !lyricsVisible
                updateCoverBackground()
                updateLyrics()
            }
        }
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
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
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touchStarted = false
                }
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) {
                    updateLyricsPadding()
                    followedLine = -1
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
        val density = resources.displayMetrics.density
        val buttonSize = (44 * density).toInt()
        val iconPadding = (12 * density).toInt()
        val margin = (4 * density).toInt()
        fun control(icon: Int, description: String) = ImageButton(this).apply {
            setImageResource(icon)
            setColorFilter(Color.WHITE)
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x66000000)
            }
            contentDescription = description
            visibility = View.GONE
        }
        val retry = control(R.drawable.ic_lyrics_refresh, "가사 새로고침").apply {
            setOnClickListener {
                val current = song
                if (lyricsVisible && current != null && lookup?.isActive != true) {
                    cache.remove(current)
                    translatedSongs.remove(current)
                    startLookup(current)
                    updateLyrics()
                }
            }
        }
        val move = control(R.drawable.ic_lyrics_move, "가사창 위치 조정").apply {
            setOnClickListener {
                Toast.makeText(this@LyricsOverlayService, "이 버튼을 끌어서 가사창을 옮기세요.", Toast.LENGTH_SHORT).show()
            }
            setOnTouchListener(coverDragListener(touchSlop))
        }
        val popup = control(R.drawable.ic_lyrics_popup, "작은 가사 팝업으로 전환").apply {
            setOnClickListener {
                popupMode = true
                applyOverlayMode()
                updateLyrics()
            }
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
            addView(retry, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.TOP or Gravity.END).apply {
                marginEnd = margin
                topMargin = margin
            })
            addView(move, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.TOP or Gravity.END).apply {
                marginEnd = margin
                topMargin = buttonSize + margin * 2
            })
            addView(popup, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = margin
                bottomMargin = margin
            })
        }
        try {
            windowManager.addView(window, coverLayout(bounds))
            coverWindow = window
            refreshButton = retry
            moveButton = move
            popupButton = popup
            overlay = cover
            overlayLabel = label
            coverBounds = Rect(bounds)
            updateLyrics()
            handler.post(tick)
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
            .coerceIn(0, (metrics.heightPixels - bounds.height()).coerceAtLeast(0))
    }

    private fun coverDragListener(touchSlop: Int): View.OnTouchListener {
        var startX = 0f
        var startY = 0f
        var offsetX = 0
        var offsetY = 0
        var moved = false
        return View.OnTouchListener { view, event ->
            val bounds = coverBounds ?: return@OnTouchListener false
            val window = coverWindow ?: return@OnTouchListener false
            val density = resources.displayMetrics.density
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    offsetX = settings.coverOffsetX
                    offsetY = settings.coverOffsetY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (moved || kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) {
                        moved = true
                        val position = coverLayout(bounds, offsetX + (dx / density).toInt(),
                            offsetY + (dy / density).toInt())
                        windowManager.updateViewLayout(window, position)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        val position = window.layoutParams as WindowManager.LayoutParams
                        settings.copy(
                            coverOffsetX = ((position.x - bounds.left) / density).toInt(),
                            coverOffsetY = ((position.y - bounds.top - 2) / density).toInt()
                        ).save(lyricsPreferences)
                    } else view.performClick()
                }
                MotionEvent.ACTION_CANCEL -> windowManager.updateViewLayout(window, coverLayout(bounds))
            }
            true
        }
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

    private fun updateLyrics() {
        val label = overlayLabel ?: return
        if (!getSystemService(PowerManager::class.java).isInteractive ||
            getSystemService(KeyguardManager::class.java).isKeyguardLocked
        ) {
            hideOverlay()
            return
        }

        val controls = if (lyricsVisible && !popupMode) View.VISIBLE else View.GONE
        refreshButton?.visibility = controls
        moveButton?.visibility = controls
        popupButton?.visibility = controls
        if (!lyricsVisible) {
            showMessage("")
            return
        }
        val manager = getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(this, PlaybackNotificationListener::class.java)
        val session = try {
            manager.getActiveSessions(listener).firstOrNull { it.packageName == youtubeMusic }
        } catch (_: SecurityException) {
            clearSong("Floating Lyrics의 알림 접근을 허용해 주세요.")
            return
        }
        val metadata = session?.metadata
        val title = metadata?.description?.title?.toString().orEmpty()
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.description?.subtitle?.toString().orEmpty()
        if (title.isBlank() || artist.isBlank()) {
            clearSong("현재 곡 정보를 기다리는 중…")
            return
        }
        val videoId = sequenceOf(
            metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI),
            session?.extras?.getString("videoId")
        ).firstNotNullOfOrNull(YouTubeMusicLyrics::videoId)
        val current = Song(title, artist, metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0, videoId)
        if (song != current) startLookup(current)
        val loaded = lyrics
        refreshButton?.apply {
            isEnabled = lookup?.isActive != true
            alpha = if (isEnabled) 1f else 0.45f
        }
        if (loaded == null) {
            showMessage(lyricsWarning.value.ifBlank { status }, warning = lyricsWarning.value.isNotBlank())
            return
        }
        val state = session?.playbackState
        val position = state?.position
        val activeLine = if (position == null || position < 0 || loaded.lines.isEmpty()) {
            -1
        } else {
            val elapsed = if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
                ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0) *
                    state.playbackSpeed).toLong()
            } else 0L
            val index = loaded.lines.binarySearchBy(position + elapsed) { it.timeMs }
            if (index >= 0) index else -index - 2
        }
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
        refreshButton?.apply {
            isEnabled = false
            alpha = 0.45f
        }
        showMessage(message)
    }

    private fun startLookup(current: Song) {
        resetTranslation()
        lyricsWarning.value = ""
        lookup?.cancel()
        song = current
        lyrics = cache.get(current)
        fullLyrics.value = lyrics?.let { "${current.title} · ${current.artist}\n\n${it.plain}" }.orEmpty()
        lyrics?.let {
            startTranslation(current, it)
            return
        }
        status = "가사 찾는 중…"
        lookup = scope.launch {
            val result = try {
                searchSources(current)
            } catch (_: IOException) {
                coroutineContext.ensureActive()
                lyricsWarning.value = "가사 서버에 연결할 수 없어요. 인터넷 연결을 확인해 주세요."
                return@launch
            } catch (_: JSONException) {
                coroutineContext.ensureActive()
                lyricsWarning.value = "가사 서버의 응답을 읽을 수 없어요."
                return@launch
            }
            if (result == null) {
                lyricsWarning.value = "이 곡과 일치하는 가사를 찾지 못했어요."
                return@launch
            }
            cache.put(current, result)
            lyrics = result
            startTranslation(current, result)
        }
    }

    private suspend fun searchSources(current: Song): Lyrics? {
        val providers = buildList<(Song) -> Lyrics?> {
            if (current.videoId != null) add(YouTubeMusicLyrics::find)
            add(Lrclib::find)
            add(BugsLyrics::find)
            add(GenieLyrics::find)
            add(LyricRadar::find)
        }
        val results = Channel<Pair<Int, Result<Lyrics?>>>(providers.size)
        val requests = providers.mapIndexed { priority, provider ->
            scope.launch(Dispatchers.IO) {
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
        var failure: Throwable? = null
        var received = 0
        return try {
            val synced = withTimeoutOrNull(16_000L) {
                repeat(providers.size) {
                    val (priority, result) = results.receive()
                    received++
                    val candidate = result.getOrElse {
                        failure = it
                        null
                    }
                    if (candidate != null) {
                        if (candidate.lines.isNotEmpty()) return@withTimeoutOrNull candidate
                        if (priority < fallbackPriority) {
                            fallback = candidate
                            fallbackPriority = priority
                        }
                    }
                }
                null
            }
            synced ?: fallback ?: failure?.let { throw it }
                ?: if (received < providers.size) throw java.net.SocketTimeoutException("Lyrics lookup timed out") else null
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
        fun updateFullLyrics() {
            val text = loaded.plain.lineSequence().joinToString("\n") { original ->
                translations[original.trim()]?.let { "$original\n$it\n" } ?: original
            }
            fullLyrics.value = "${current.title} · ${current.artist}\n\n$text"
        }

        if (!settings.translationEnabled) {
            updateFullLyrics()
            return
        }
        val saved = translatedSongs.get(current)
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
                    translatedSongs.put(current, translations.toMap())
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
        }
    }

    private fun hideOverlay() {
        val cover = coverWindow ?: return
        coverWindow = null
        refreshButton = null
        moveButton = null
        popupButton = null
        popupMode = false
        overlay = null
        overlayLabel = null
        displayedLyrics = null
        displayedSong = null
        lineRanges = emptyList()
        touchStarted = false
        manualScrollUntil = 0
        coverBounds = null
        lyricsVisible = false
        handler.removeCallbacks(tick)
        if (lyrics == null) {
            lookup?.cancel()
            song = null
        }
        windowManager.removeViewImmediate(cover)
    }
}
























