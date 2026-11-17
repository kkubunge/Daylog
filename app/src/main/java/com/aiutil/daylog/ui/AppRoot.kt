package com.aiutil.daylog.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.aiutil.daylog.App
import com.aiutil.daylog.data.MemoryRecallEngine
import com.aiutil.daylog.model.CapturedMoment
import com.aiutil.daylog.model.DayEntry
import com.aiutil.daylog.model.MoodTag
import com.aiutil.daylog.model.PermissionStatus
import com.aiutil.daylog.model.SnsPlatform
import com.aiutil.daylog.model.SourceType
import com.aiutil.daylog.ui.calendar.CalendarScreen
import com.aiutil.daylog.ui.components.PhotoLightboxDialog
import com.aiutil.daylog.ui.editor.DayDetailSheet
import com.aiutil.daylog.ui.memory.MemoryScreen
import com.aiutil.daylog.ui.settings.SettingsSheet
import com.aiutil.daylog.ui.timeline.TimelineScreen
import com.aiutil.daylog.util.DateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class SharedIncomingPayload(
    val text: String,
    val imageUris: List<String>,
)

private enum class DaylogTab(val label: String) {
    TIMELINE("일상 기록"),
    CALENDAR("포토 달력"),
    MEMORY("추억 회상"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    permissionStatus: PermissionStatus,
    sharedPayload: SharedIncomingPayload?,
    onConsumeSharedPayload: () -> Unit,
    onRequestRuntimePermissions: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val entries by App.daylogs.entries.collectAsState()
    val settings by App.settings.settings.collectAsState()
    val snsCaptures by App.snsCaptures.captures.collectAsState()

    val todayKey = DateUtils.todayKey()
    val entriesByDate = remember(entries) { entries.associateBy { it.dateKey } }
    val todayEntry = entriesByDate[todayKey]
    val pastEntries = remember(entries, todayKey) { entries.filter { it.dateKey != todayKey } }
    val memories = remember(todayKey, entries) { MemoryRecallEngine.buildMemories(todayKey, entries) }
    val stats = remember(entries) { MemoryRecallEngine.computeStats(entries) }
    val hasSampleEntries = remember(entries) { entries.any { it.isSample } }

    var activeTab by rememberSaveable { mutableStateOf(DaylogTab.TIMELINE) }
    var editingDateKey by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var lightboxUri by remember { mutableStateOf<String?>(null) }
    var isCollecting by remember { mutableStateOf(false) }
    var pickingForDateKey by remember { mutableStateOf<String?>(null) }

    // 갤러리 사진 1~2장 직접 고르기 런처
    val galleryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(2),
    ) { uris ->
        val targetDate = pickingForDateKey ?: todayKey
        pickingForDateKey = null
        if (uris.isEmpty()) return@rememberLauncherForActivityResult

        scope.launch {
            val savedUris = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    persistOrCopyImageUri(context, uri)
                }
            }
            if (savedUris.isNotEmpty()) {
                val existing = App.daylogs.getEntry(targetDate)?.heroPhotoUris.orEmpty()
                val merged = (existing + savedUris).distinct().takeLast(settings.maxHeroPhotos)
                App.daylogs.setHeroPhotos(targetDate, merged, settings.maxHeroPhotos)
                snackbarHostState.showSnackbar("대표 사진 ${merged.size}장을 반영했어요.")
            }
        }
    }

    // 백그라운드 SNS 알림 캡처가 들어올 때 오늘 일기 조각에 자동 반영
    LaunchedEffect(snsCaptures.firstOrNull()?.id, snsCaptures.size, todayKey) {
        val todaySns = snsCaptures.filter { it.dateKey == todayKey }
        if (todaySns.isEmpty()) return@LaunchedEffect
        val existing = App.daylogs.getEntry(todayKey)
        val existingIds = existing?.moments.orEmpty().map { it.id }.toSet()
        val missing = todaySns.filter { it.id !in existingIds }
        if (missing.isNotEmpty()) {
            missing.forEach { App.daylogs.addMoment(todayKey, it) }
        }
    }

    // 외부 앱(인스타/카톡/갤러리 등)에서 공유하기 수신 시 오늘 일기에 추가
    LaunchedEffect(sharedPayload) {
        val payload = sharedPayload ?: return@LaunchedEffect
        onConsumeSharedPayload()

        val now = System.currentTimeMillis()
        if (payload.text.isNotBlank()) {
            val clean = payload.text.replace(Regex("\\s+"), " ").trim().take(120)
            App.daylogs.addMoment(
                dateKey = todayKey,
                moment = CapturedMoment(
                    id = "share_text_${now}_${clean.hashCode()}",
                    dateKey = todayKey,
                    timestampMs = now,
                    sourceType = SourceType.SNS,
                    snsPlatform = SnsPlatform.OTHER,
                    title = "공유로 남긴 기록",
                    summary = clean,
                ),
            )
        }
        if (payload.imageUris.isNotEmpty()) {
            val savedUris = withContext(Dispatchers.IO) {
                payload.imageUris.mapNotNull { raw ->
                    persistOrCopyImageUri(context, Uri.parse(raw))
                }
            }
            if (savedUris.isNotEmpty()) {
                val existing = App.daylogs.getEntry(todayKey)?.heroPhotoUris.orEmpty()
                val merged = (existing + savedUris).distinct().takeLast(settings.maxHeroPhotos)
                App.daylogs.setHeroPhotos(todayKey, merged, settings.maxHeroPhotos)
            }
        }
        editingDateKey = todayKey
        snackbarHostState.showSnackbar("공유한 내용을 오늘 일기에 담았어요.")
    }

    fun collectForDate(dateKey: String, forceRewrite: Boolean = false) {
        if (!permissionStatus.anyRuntimeGranted && !permissionStatus.snsNotificationEnabled) {
            onRequestRuntimePermissions()
            scope.launch {
                snackbarHostState.showSnackbar("사진·통화·문자 권한을 허용하면 자동으로 일기 베이스가 채워져요.")
            }
            return
        }
        if (isCollecting) return
        scope.launch {
            isCollecting = true
            try {
                val existing = App.daylogs.getEntry(dateKey)
                val updated = App.collector.buildOrRefreshEntry(
                    dateKey = dateKey,
                    currentEntry = existing,
                    settings = settings,
                    forceRewriteText = forceRewrite,
                )
                App.daylogs.upsertEntry(updated)
                val photoCount = updated.heroPhotoUris.size
                val momentCount = updated.moments.count { !it.isHidden }
                snackbarHostState.showSnackbar(
                    "${DateUtils.formatShortKoreanDate(dateKey)} 취합 완료 (대표 사진 ${photoCount}장 · 조각 ${momentCount}건)"
                )
            } finally {
                isCollecting = false
            }
        }
    }

    fun batchCollectRecentDays(days: Int) {
        if (!permissionStatus.anyRuntimeGranted && !permissionStatus.snsNotificationEnabled) {
            onRequestRuntimePermissions()
            scope.launch {
                snackbarHostState.showSnackbar("먼저 사진·통화·문자 권한을 허용해 주세요.")
            }
            return
        }
        if (isCollecting) return
        scope.launch {
            isCollecting = true
            try {
                val keys = (0 until days).map { offset -> DateUtils.daysAgoKey(todayKey, offset) }
                val existingMap = entries.associateBy { it.dateKey }
                val created = App.collector.buildOrRefreshBatch(
                    dateKeys = keys,
                    existingMap = existingMap,
                    settings = settings,
                    forceRewriteText = false,
                )
                if (created.isNotEmpty()) {
                    App.daylogs.upsertAll(created)
                    snackbarHostState.showSnackbar("최근 ${days}일 중 ${created.size}일치 일기 베이스를 만들었어요.")
                } else {
                    snackbarHostState.showSnackbar("최근 ${days}일간 취합할 사진·통화·문자 기록을 찾지 못했어요.")
                }
            } finally {
                isCollecting = false
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.MenuBook,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "데이로그",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { collectForDate(todayKey) },
                        enabled = !isCollecting,
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = "오늘 자동 취합")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "설정")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = activeTab == DaylogTab.TIMELINE,
                    onClick = { activeTab = DaylogTab.TIMELINE },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text(DaylogTab.TIMELINE.label) },
                )
                NavigationBarItem(
                    selected = activeTab == DaylogTab.CALENDAR,
                    onClick = { activeTab = DaylogTab.CALENDAR },
                    icon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                    label = { Text(DaylogTab.CALENDAR.label) },
                )
                NavigationBarItem(
                    selected = activeTab == DaylogTab.MEMORY,
                    onClick = { activeTab = DaylogTab.MEMORY },
                    icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                    label = { Text(DaylogTab.MEMORY.label) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (activeTab) {
                DaylogTab.TIMELINE -> TimelineScreen(
                    todayKey = todayKey,
                    todayEntry = todayEntry,
                    pastEntries = pastEntries,
                    memories = memories,
                    isCollectingToday = isCollecting,
                    permissionStatus = permissionStatus,
                    onAutoCollectToday = { collectForDate(todayKey) },
                    onSaveTodayQuickNote = { headline, shortNote, mood ->
                        App.daylogs.updateUserText(todayKey, headline, shortNote, mood)
                        scope.launch { snackbarHostState.showSnackbar("오늘 기록을 저장했어요.") }
                    },
                    onOpenDayDetail = { dateKey -> editingDateKey = dateKey },
                    onToggleBookmark = { dateKey -> App.daylogs.toggleBookmark(dateKey) },
                    onPhotoClick = { uri -> lightboxUri = uri },
                    onOpenPermissionsOrSettings = { showSettings = true },
                )

                DaylogTab.CALENDAR -> CalendarScreen(
                    todayKey = todayKey,
                    entriesByDate = entriesByDate,
                    onOpenDayDetail = { dateKey -> editingDateKey = dateKey },
                    onCollectDate = { dateKey -> collectForDate(dateKey) },
                    onPhotoClick = { uri -> lightboxUri = uri },
                )

                DaylogTab.MEMORY -> MemoryScreen(
                    todayKey = todayKey,
                    allEntries = entries,
                    memories = memories,
                    stats = stats,
                    onOpenDayDetail = { dateKey -> editingDateKey = dateKey },
                    onToggleBookmark = { dateKey -> App.daylogs.toggleBookmark(dateKey) },
                    onPhotoClick = { uri -> lightboxUri = uri },
                )
            }
        }
    }

    // 특정 날짜 상세 편집 시트
    val targetEditKey = editingDateKey
    if (targetEditKey != null) {
        val targetEntry = entriesByDate[targetEditKey]
        DayDetailSheet(
            dateKey = targetEditKey,
            entry = targetEntry,
            maxHeroPhotos = settings.maxHeroPhotos,
            hasGeminiKey = settings.geminiApiKey.isNotBlank(),
            isBusy = isCollecting,
            onDismiss = { editingDateKey = null },
            onSaveText = { headline, shortNote, mood ->
                App.daylogs.updateUserText(targetEditKey, headline, shortNote, mood)
                editingDateKey = null
                scope.launch { snackbarHostState.showSnackbar("일기를 저장했어요.") }
            },
            onSetHeroPhotos = { uris ->
                App.daylogs.setHeroPhotos(targetEditKey, uris, settings.maxHeroPhotos)
            },
            onPickGalleryPhotos = {
                pickingForDateKey = targetEditKey
                galleryPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onRecollectPhoneData = { collectForDate(targetEditKey, forceRewrite = true) },
            onApplyLocalTone = { tone ->
                val base = targetEntry ?: DayEntry(dateKey = targetEditKey)
                val (newHead, newNote) = App.aiComposer.applyLocalTone(base, tone)
                App.daylogs.upsertEntry(
                    base.copy(
                        headline = newHead,
                        shortNote = newNote,
                        isSample = false,
                        updatedAtMs = System.currentTimeMillis(),
                    )
                )
            },
            onPolishWithGemini = {
                val base = targetEntry ?: DayEntry(dateKey = targetEditKey)
                scope.launch {
                    isCollecting = true
                    try {
                        val polished = App.aiComposer.polishWithGemini(base, settings.geminiApiKey)
                        if (polished != null) {
                            App.daylogs.upsertEntry(
                                base.copy(
                                    headline = polished.first,
                                    shortNote = polished.second,
                                    isUserEdited = true,
                                    isSample = false,
                                    updatedAtMs = System.currentTimeMillis(),
                                )
                            )
                            snackbarHostState.showSnackbar("AI가 일기를 자연스럽게 다듬었어요.")
                        } else {
                            snackbarHostState.showSnackbar("AI 호출에 실패했어요. API 키와 네트워크를 확인해 주세요.")
                        }
                    } finally {
                        isCollecting = false
                    }
                }
            },
            onToggleBookmark = { App.daylogs.toggleBookmark(targetEditKey) },
            onAddManualMoment = { sourceType, snsPlatform, title, summary ->
                val now = System.currentTimeMillis()
                val momentTs = if (targetEditKey == todayKey) {
                    now
                } else {
                    DateUtils.dayRangeMs(targetEditKey).first + 18 * 3600_000L
                }
                App.daylogs.addMoment(
                    dateKey = targetEditKey,
                    moment = CapturedMoment(
                        id = "manual_${now}_${summary.hashCode()}",
                        dateKey = targetEditKey,
                        timestampMs = momentTs,
                        sourceType = sourceType,
                        snsPlatform = snsPlatform,
                        title = title,
                        summary = summary,
                    ),
                )
            },
            onToggleMomentHidden = { momentId ->
                App.daylogs.toggleMomentHidden(targetEditKey, momentId)
            },
            onDeleteMoment = { momentId ->
                App.daylogs.deleteMoment(targetEditKey, momentId)
            },
            onDeleteEntry = {
                App.daylogs.deleteEntry(targetEditKey)
                editingDateKey = null
                scope.launch { snackbarHostState.showSnackbar("해당 날짜 기록을 삭제했어요.") }
            },
            onPhotoClick = { uri -> lightboxUri = uri },
        )
    }

    // 설정 및 권한 대시보드 시트
    if (showSettings) {
        SettingsSheet(
            settings = settings,
            permissionStatus = permissionStatus,
            hasSampleEntries = hasSampleEntries,
            isBatchCollecting = isCollecting,
            onDismiss = { showSettings = false },
            onRequestRuntimePermissions = onRequestRuntimePermissions,
            onOpenNotificationListenerSettings = {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                runCatching { context.startActivity(intent) }
            },
            onBatchCollectDays = { days -> batchCollectRecentDays(days) },
            onUpdateSettings = { transform -> App.settings.update(transform) },
            onExportJsonBackup = {
                scope.launch {
                    shareBackupJson(context)
                }
            },
            onClearSampleEntries = {
                App.daylogs.clearSampleEntries()
                scope.launch { snackbarHostState.showSnackbar("안내용 예시 일기를 지웠어요.") }
            },
            onClearAllEntries = {
                App.daylogs.clearAll()
                App.snsCaptures.clearAll()
                showSettings = false
                scope.launch { snackbarHostState.showSnackbar("모든 데이로그 기록을 초기화했어요.") }
            },
        )
    }

    // 사진 크게 보기 라이트박스
    val fullUri = lightboxUri
    if (fullUri != null) {
        PhotoLightboxDialog(
            uri = fullUri,
            onDismiss = { lightboxUri = null },
        )
    }
}

private fun persistOrCopyImageUri(context: Context, uri: Uri): String? {
    // 1. Photo Picker / SAF URI 영구 읽기 권한 시도
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }
    if (uri.scheme == "file") return uri.toString()

    // 2. 임시 권한 URI(예: 외부 앱 공유)는 앱 내부 폴더로 복사해 안전하게 보관
    return runCatching {
        val dir = File(context.filesDir, "shared_images").apply { mkdirs() }
        val target = File(dir, "img_${System.currentTimeMillis()}_${uri.toString().hashCode()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        if (target.exists() && target.length() > 0L) Uri.fromFile(target).toString() else uri.toString()
    }.getOrElse { uri.toString() }
}

private suspend fun shareBackupJson(context: Context) {
    val file = withContext(Dispatchers.IO) {
        val jsonText = App.daylogs.exportJson()
        val out = File(context.cacheDir, "daylog_backup_${DateUtils.todayKey()}.json")
        out.writeText(jsonText, Charsets.UTF_8)
        out
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "데이로그 일기 백업 (${DateUtils.todayKey()})")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(shareIntent, "일기 백업 파일 내보내기"))
    }
}
