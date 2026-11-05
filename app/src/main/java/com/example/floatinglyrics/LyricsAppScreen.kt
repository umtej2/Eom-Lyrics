package com.example.floatinglyrics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val AppColors = darkColorScheme(
    primary = Color(0xFFFF5252), onPrimary = Color.Black,
    secondary = Color(0xFFE6E6E6), background = Color(0xFF0D0D0D),
    surface = Color(0xFF0D0D0D), surfaceVariant = Color(0xFF202020),
    onSurface = Color(0xFFF5F5F5), onSurfaceVariant = Color(0xFFA8A8A8),
    outline = Color(0xFF383838)
)

@Composable
internal fun LyricsAppScreen(
    settings: LyricsSettings,
    title: String?, artist: String?, notificationAccess: Boolean, accessibilityAccess: Boolean,
    onSettings: (LyricsSettings) -> Unit,
    openNotifications: () -> Unit, openAccessibility: () -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    MaterialTheme(colorScheme = AppColors) {
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF141414), tonalElevation = 0.dp) {
                    val labels = listOf("홈", "설정", "앱 정보")
                    val icons = listOf(R.drawable.ic_app_home, R.drawable.ic_app_settings, R.drawable.ic_app_info)
                    labels.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = tab == index, onClick = { tab = index },
                            icon = { Icon(painterResource(icons[index]), contentDescription = null) },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.White, selectedTextColor = Color.White,
                                indicatorColor = Color(0xFF353535), unselectedIconColor = Color(0xFF999999)
                            )
                        )
                    }
                }
            }
        ) { padding ->
            // Each tab keeps its own scroll position.
            val homeScroll = rememberScrollState()
            val settingsScroll = rememberScrollState()
            val aboutScroll = rememberScrollState()
            Column(
                Modifier.fillMaxSize().padding(padding)
                    .verticalScroll(when (tab) { 0 -> homeScroll; 1 -> settingsScroll; else -> aboutScroll })
                    .padding(horizontal = 20.dp).padding(top = 22.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = Color(0xFFFF3030), shape = RoundedCornerShape(12.dp)) {
                        Icon(painterResource(R.drawable.ic_lyrics_open), null,
                            Modifier.padding(10.dp).size(24.dp), tint = Color.White)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Eom Lyrics", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Caption(when (tab) { 0 -> "음악에 가사를 더하다"; 1 -> "나에게 맞는 가사창"; else -> "앱 정보와 도움말" })
                    }
                }
                when (tab) {
                    0 -> {
                        Panel("지금 재생 중") {
                            Text(title?.takeIf(String::isNotBlank) ?: "음악을 재생해 주세요",
                                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Caption(artist?.takeIf(String::isNotBlank) ?: "YouTube Music에서 재생한 곡이 여기에 표시돼요.")
                            HorizontalDivider(color = Color(0xFF333333))
                            Toggle("가사창 사용", "설정한 방식으로 가사를 열 수 있어요.", settings.enabled) {
                                onSettings(settings.copy(enabled = it))
                            }
                            Caption(if (settings.useLyricsButton) "우하단 가사 아이콘을 눌러 열고 닫아요."
                                else "앨범 커버를 눌러 열고, 우하단 닫기 아이콘으로 닫아요.")
                        }
                        Panel("시작 준비") {
                            PermissionRow("알림 접근", "현재 곡과 재생 위치를 읽어요.", notificationAccess, openNotifications)
                            HorizontalDivider(color = Color(0xFF333333))
                            PermissionRow("가사창 접근성", "재생 화면 위에 가사창을 표시해요.", accessibilityAccess, openAccessibility)
                            Caption("접근성 설정 → 설치된 앱 → Floating Lyrics 가사창 → 사용을 켜 주세요. 제한된 설정이 뜨면 앱 정보의 ⋮ 메뉴에서 제한된 설정을 허용해 주세요.")
                        }
                        Panel("이렇게 사용해 보세요") {
                            Text("가사 줄을 누르면 그 부분으로", fontWeight = FontWeight.SemiBold)
                            Caption("시간 정보가 있는 가사를 짧게 누르면 해당 구간으로 이동해요. 끌어서 스크롤할 수도 있어요.")
                            Text("작은 팝업은 닫기 아이콘 위에", fontWeight = FontWeight.SemiBold)
                            Caption("팝업 아이콘으로 작은 창을 열고, 끌어서 위치를 옮겨요. 일반 화면과 재생목록 화면을 전환해도 가사 켜짐 상태는 유지돼요.")
                        }
                        val warning = LyricsOverlayService.lyricsWarning.value.ifBlank {
                            if (settings.translationEnabled) LyricsOverlayService.translationWarning.value else ""
                        }
                        if (warning.isNotBlank()) {
                            Panel("확인이 필요해요") { Text(warning, color = Color(0xFFFFB4AB)) }
                        }
                        if (settings.translationEnabled && LyricsOverlayService.translationStatus.value.isNotBlank()) {
                            Caption(LyricsOverlayService.translationStatus.value)
                        }
                        ExpandablePanel("전체 가사", initiallyExpanded = false) {
                            Text(LyricsOverlayService.fullLyrics.value.ifBlank { "가사를 불러오면 여기에 표시돼요." }, lineHeight = 28.sp)
                        }
                    }
                    1 -> {
                        Panel("미리보기") {
                            Surface(color = Color.Black.copy(alpha = settings.darkness / 100f),
                                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("음악이 흐르는 이 순간", fontSize = settings.textSize.sp,
                                        fontWeight = FontWeight.Bold, color = Color.White)
                                    if (settings.translationEnabled) Caption("가사와 번역이 함께 보여요")
                                    Text("다음 가사가 이어져요", fontSize = settings.textSize.sp, color = Color.Gray,
                                        modifier = Modifier.padding(top = 12.dp))
                                }
                            }
                        }
                        ExpandablePanel("가사창과 조작", initiallyExpanded = true) {
                            Toggle("가사창 사용", "가사창 기능을 켜거나 꺼요.", settings.enabled) { onSettings(settings.copy(enabled = it)) }
                            Text("가사 여는 방식", fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = !settings.useLyricsButton,
                                    onClick = { onSettings(settings.copy(useLyricsButton = false)) }, label = { Text("앨범 커버") })
                                FilterChip(selected = settings.useLyricsButton,
                                    onClick = { onSettings(settings.copy(useLyricsButton = true)) }, label = { Text("가사 버튼") })
                            }
                            Toggle("가사 눌러 구간 이동", "시간 정보와 재생 위치 이동을 지원하는 곡에서 사용할 수 있어요.", settings.seekOnLyricTap) {
                                onSettings(settings.copy(seekOnLyricTap = it))
                            }
                            Toggle("현재 가사 따라가기", "재생 중인 줄을 자동으로 보여줘요.", settings.autoScroll) { onSettings(settings.copy(autoScroll = it)) }
                            Caption("저장한 고정 위치를 사용해요. 재생목록이 펼쳐지면 가사창의 위쪽 절반만 사용하고, 다시 내리면 원래 높이로 돌아와요. 터치 영역과 버튼도 함께 바뀌어요.")
                        }
                        ExpandablePanel("글자와 배경", initiallyExpanded = true) {
                            SettingSlider("글자 크기", "${settings.textSize} sp", settings.textSize, 12f..28f, 15) { onSettings(settings.copy(textSize = it)) }
                            SettingSlider("배경 어둡기", "${settings.darkness}%", settings.darkness, 25f..95f) { onSettings(settings.copy(darkness = it)) }
                        }
                        ExpandablePanel("가사창 위치") {
                            SettingSlider("가로", "${settings.coverOffsetX} dp", settings.coverOffsetX, -150f..150f) { onSettings(settings.copy(coverOffsetX = it)) }
                            SettingSlider("세로", "${settings.coverOffsetY} dp", settings.coverOffsetY, -200f..200f) { onSettings(settings.copy(coverOffsetY = it)) }
                            Caption("화면 기준 기본 위치에서 조정해요. 왼쪽·위는 음수, 오른쪽·아래는 양수예요. 저장한 위치와 버튼 배치는 다음에도 유지돼요.")
                            TextButton(onClick = { onSettings(settings.copy(coverOffsetX = 0, coverOffsetY = 0)) }) { Text("위치 초기화") }
                        }
                        ExpandablePanel("작은 팝업") {
                            SettingSlider("표시 줄 수", "${settings.popupLines}줄", settings.popupLines, 1f..4f, 2) { onSettings(settings.copy(popupLines = it)) }
                            SettingSlider("글자 크기", "${settings.popupTextSize} sp", settings.popupTextSize, 12f..28f, 15) { onSettings(settings.copy(popupTextSize = it)) }
                            SettingSlider("배경 어둡기", "${settings.popupDarkness}%", settings.popupDarkness, 0f..95f) { onSettings(settings.copy(popupDarkness = it)) }
                            TextButton(onClick = { onSettings(settings.copy(popupOffsetX = 0, popupOffsetY = 0)) }) { Text("팝업 위치 초기화") }
                        }
                        ExpandablePanel("번역") {
                            Toggle("한국어 번역 표시", "지원되는 외국어 가사 아래에 번역을 표시해요.", settings.translationEnabled) { onSettings(settings.copy(translationEnabled = it)) }
                            Caption("새 번역에는 인터넷 연결이 필요해요. 번역을 불러오지 못해도 원문은 계속 표시돼요.")
                        }
                    }
                    else -> {
                        Panel("Eom Lyrics") {
                            Text("YouTube Music과 함께 쓰는 가사창", style = MaterialTheme.typography.titleMedium)
                            Caption("앨범 커버 위 가사, 구간 이동, 작은 팝업과 한국어 번역을 제공해요. YouTube Music과 별도로 동작하는 앱이에요.")
                        }
                        Panel("출처와 크레딧") {
                            Text("가사", fontWeight = FontWeight.SemiBold)
                            Caption("LRCLIB · 벅스 · 지니 · LyricRadar · YouTube Music\n곡과 출처에 따라 가사와 시간 정보 제공 여부가 달라요.")
                            Text("번역", fontWeight = FontWeight.SemiBold)
                            Caption("Google 번역 웹 서비스 기반. 번역 엔진 교체는 아직 적용되지 않았어요.")
                            Text("개발 구성", fontWeight = FontWeight.SemiBold)
                            Caption("Kotlin · Android Jetpack Compose · Material 3 · Kotlin Coroutines\n가사 조회 방식은 친구의 Floating Lyrics 확장 프로그램을 참고했어요.")
                        }
                        ExpandablePanel("진단 정보") {
                            Text(LyricsOverlayService.lastCheck.value)
                            Caption("문제가 생겼을 때 이 내용과 곡 제목을 함께 알려 주세요.")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium, lineHeight = 21.sp)
}

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color(0xFF1C1C1C), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun ExpandablePanel(title: String, initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Surface(color = Color(0xFF1C1C1C), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(if (expanded) "−" else "+", fontSize = 22.sp)
            }
            if (expanded) Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        }
    }
}

@Composable
private fun Toggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Caption(description)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingSlider(title: String, label: String, value: Int, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title)
            Text(label, color = Color(0xFFCCCCCC))
        }
        Slider(value = value.toFloat().coerceIn(range.start, range.endInclusive),
            onValueChange = { onChange(it.roundToInt()) }, valueRange = range, steps = steps)
    }
}

@Composable
private fun PermissionRow(title: String, description: String, granted: Boolean, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onClick) { Text(if (granted) "허용됨 · 설정" else "설정하기") }
        }
        Caption(description)
    }
}
