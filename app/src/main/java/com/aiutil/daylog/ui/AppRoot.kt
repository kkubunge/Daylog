package com.aiutil.daylog.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.Surface
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
import com.aiutil.daylog.location.LocationTrackerService
import com.aiutil.daylog.model.ActivityItem
import com.aiutil.daylog.model.ActivityType
import com.aiutil.daylog.model.DayPhoto
import com.aiutil.daylog.model.PermissionStatus
import com.aiutil.daylog.model.PlaceSource
import com.aiutil.daylog.model.PlaceVisit
import com.aiutil.daylog.model.SnsPlatform
import com.aiutil.daylog.ui.calendar.CalendarScreen
import com.aiutil.daylog.ui.components.PhotoLightboxDialog
import com.aiutil.daylog.ui.editor.DayDetailSheet
import com.aiutil.daylog.ui.feed.FeedScreen
import com.aiutil.daylog.ui.places.PlacesScreen
import com.aiutil.daylog.ui.settings.SettingsSheet
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
    CALENDAR("달력"),
    FEED("전체 기록"),
    PLACES("이동 장소"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    permissionStatus: PermissionStatus,
    sharedPayload: SharedIncomingPayload?,
    onConsumeSharedPayload: () -> Unit,
    onRequestRuntimePermissions: () -> Unit,
    onRequestBackgroundLocation: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val records by App.daylogs.records.collectAsState()
    val settings by App.settings.settings.collectAsState()
    val snsCaptures by App.snsCaptures.captures.collectAsState()
    val aliases by App.locationVisits.aliases.collectAsState()
    val isTrackingRunning by LocationTrackerService.isRunning.collectAsState()

    val todayKey = DateUtils.todayKey()
    val recordsByDate = remember(records) { records.associateBy { it.dateKey } }

    var activeTab by rememberSaveable { mutableStateOf(DaylogTab.CALENDAR) }
    var selectedDateKey by rememberSaveable { mutableStateOf(todayKey) }
    var detailSheetDateKey by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var lightboxPhoto by remember { mutableStateOf<DayPhoto?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    var autoScannedDates by remember { mutableStateOf(setOf<String>()) }

    fun scanDate(dateKey: String, showToast: Boolean = false) {
        if (!permissionStatus.anyDataGranted && !permissionStatus.snsNotificationEnabled) {
            if (showToast) {
                onRequestRuntimePermissions()
                scope.launch {
                    snackbarHostState.showSnackbar("사진·통화·문자·위치 권한을 허용하면 데이터를 불러올 수 있습니다.")
                }
            }
            return
        }
        scope.launch {
            isScanning = true
            try {
                val existing = App.daylogs.getRecord(dateKey)
                val updated = App.collector.collectDate(dateKey, existing, settings)
                App.daylogs.upsertRecord(updated)
                autoScannedDates = autoScannedDates + dateKey
                if (showToast) {
                    snackbarHostState.showSnackbar(
                        "${DateUtils.formatShortKoreanDate(dateKey)} 조회 완료 (장소 ${updated.places.size} · 사진 ${updated.photos.size} · 활동 ${updated.activities.size})"
                    )
                }
            } finally {
                isScanning = false
            }
        }
    }

    fun selectAndLoadDate(dateKey: String) {
        selectedDateKey = dateKey
        if (dateKey !in autoScannedDates) {
            scanDate(dateKey, showToast = false)
        }
    }

    fun batchScanRecentDays(days: Int) {
        if (!permissionStatus.anyDataGranted && !permissionStatus.snsNotificationEnabled) {
            onRequestRuntimePermissions()
            return
        }
        if (isScanning) return
        scope.launch {
            isScanning = true
            try {
                val keys = (0 until days).map { DateUtils.daysAgoKey(todayKey, it) }
                val existingMap = records.associateBy { it.dateKey }
                val scanned = App.collector.collectBatch(keys, existingMap, settings)
                val nonEmpty = scanned.filter { it.hasAnyData }
                if (nonEmpty.isNotEmpty()) {
                    App.daylogs.upsertAll(nonEmpty)
                }
                autoScannedDates = autoScannedDates + keys
                snackbarHostState.showSnackbar("최근 ${days}일 중 ${nonEmpty.size}일의 데이터를 불러왔습니다.")
            } finally {
                isScanning = false
            }
        }
    }

    fun checkInCurrentLocation(targetDateKey: String) {
        if (!permissionStatus.locationGranted) {
            onRequestRuntimePermissions()
            scope.launch { snackbarHostState.showSnackbar("위치 권한을 허용한 뒤 다시 시도해 주세요.") }
            return
        }
        scope.launch {
            isScanning = true
            try {
                val loc = withContext(Dispatchers.IO) { readBestLastLocation(context) }
                if (loc == null) {
                    snackbarHostState.showSnackbar("현재 GPS 좌표를 가져오지 못했습니다. 위치 서비스(GPS)를 켜주세요.")
                    return@launch
                }
                val now = System.currentTimeMillis()
                val geo = App.geocoder.reverseGeocode(loc.latitude, loc.longitude)
                val updatedVisits = App.locationVisits.recordLocationSample(
                    dateKey = targetDateKey,
                    timestampMs = now,
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    placeName = geo.placeName,
                    address = geo.address,
                    source = PlaceSource.CHECK_IN,
                )
                App.daylogs.updatePlacesForDate(targetDateKey, updatedVisits, aliases)
                snackbarHostState.showSnackbar("위치 기록됨: ${geo.placeName}")
            } finally {
                isScanning = false
            }
        }
    }

    fun toggleLocationTracking() {
        if (!permissionStatus.locationGranted) {
            onRequestRuntimePermissions()
            return
        }
        if (isTrackingRunning) {
            LocationTrackerService.stop(context)
            App.settings.update { it.copy(trackLocation = false) }
            scope.launch { snackbarHostState.showSnackbar("백그라운드 위치 기록을 중지했습니다.") }
        } else {
            App.settings.update { it.copy(trackLocation = true) }
            LocationTrackerService.start(context)
            scope.launch { snackbarHostState.showSnackbar("이동 및 체류 장소 자동 기록을 시작했습니다.") }
        }
    }

    // 앱 진입 또는 권한 획득 시 오늘 날짜 자동 조회
    LaunchedEffect(permissionStatus.anyDataGranted, todayKey) {
        if (permissionStatus.anyDataGranted && todayKey !in autoScannedDates) {
            scanDate(todayKey, showToast = false)
        }
    }

    // SNS 알림 수신 시 오늘 날짜 활동에 즉시 반영
    LaunchedEffect(snsCaptures.firstOrNull()?.id, snsCaptures.size, todayKey) {
        val todaySns = snsCaptures.filter { it.dateKey == todayKey }
        if (todaySns.isEmpty()) return@LaunchedEffect
        val existingIds = App.daylogs.getRecord(todayKey)?.activities.orEmpty().map { it.id }.toSet()
        todaySns.filter { it.id !in existingIds }.forEach { item ->
            App.daylogs.addActivity(todayKey, item)
        }
    }

    // 외부 앱에서 공유하기 수신 시 오늘 날짜 데이터에 추가
    LaunchedEffect(sharedPayload) {
        val payload = sharedPayload ?: return@LaunchedEffect
        onConsumeSharedPayload()
        val now = System.currentTimeMillis()

        if (payload.text.isNotBlank()) {
            val clean = payload.text.replace(Regex("\\s+"), " ").trim().take(160)
            App.daylogs.addActivity(
                dateKey = todayKey,
                item = ActivityItem(
                    id = "share_${now}_${clean.hashCode()}",
                    dateKey = todayKey,
                    timestampMs = now,
                    type = ActivityType.SNS,
                    snsPlatform = SnsPlatform.OTHER,
                    title = "공유 기록",
                    detail = clean,
                ),
            )
        }
        if (payload.imageUris.isNotEmpty()) {
            val savedPhotos = withContext(Dispatchers.IO) {
                payload.imageUris.mapNotNull { raw ->
                    copySharedImage(context, Uri.parse(raw), now)
                }
            }
            if (savedPhotos.isNotEmpty()) {
                val cur = App.daylogs.getRecord(todayKey) ?: com.aiutil.daylog.model.DayRecord(dateKey = todayKey)
                val merged = (cur.photos + savedPhotos).distinctBy { it.uri }.sortedBy { it.dateTakenMs }
                App.daylogs.upsertRecord(cur.copy(photos = merged, updatedAtMs = now))
            }
        }
        selectedDateKey = todayKey
        activeTab = DaylogTab.CALENDAR
        snackbarHostState.showSnackbar("공유한 데이터를 오늘 날짜에 추가했습니다.")
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "데이로그",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    Surface(
                        color = if (isTrackingRunning) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .clickable { activeTab = DaylogTab.PLACES },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Place,
                                contentDescription = null,
                                tint = if (isTrackingRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = if (isTrackingRunning) "GPS ON" else "GPS OFF",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isTrackingRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { scanDate(selectedDateKey, showToast = true) }, enabled = !isScanning) {
                        Icon(Icons.Default.Refresh, contentDescription = "선택 날짜 새로고침")
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
                    selected = activeTab == DaylogTab.CALENDAR,
                    onClick = { activeTab = DaylogTab.CALENDAR },
                    icon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                    label = { Text(DaylogTab.CALENDAR.label) },
                )
                NavigationBarItem(
                    selected = activeTab == DaylogTab.FEED,
                    onClick = { activeTab = DaylogTab.FEED },
                    icon = { Icon(Icons.Default.FormatListBulleted, contentDescription = null) },
                    label = { Text(DaylogTab.FEED.label) },
                )
                NavigationBarItem(
                    selected = activeTab == DaylogTab.PLACES,
                    onClick = { activeTab = DaylogTab.PLACES },
                    icon = { Icon(Icons.Default.Place, contentDescription = null) },
                    label = { Text(DaylogTab.PLACES.label) },
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
                DaylogTab.CALENDAR -> CalendarScreen(
                    todayKey = todayKey,
                    selectedDateKey = selectedDateKey,
                    recordsByDate = recordsByDate,
                    isScanning = isScanning,
                    permissionStatus = permissionStatus,
                    onSelectDate = { dateKey -> selectAndLoadDate(dateKey) },
                    onRefreshDate = { dateKey -> scanDate(dateKey, showToast = true) },
                    onOpenFullDetail = { dateKey -> detailSheetDateKey = dateKey },
                    onCheckInCurrentLocation = { dateKey -> checkInCurrentLocation(dateKey) },
                    onAddNote = { dateKey, title, detail ->
                        val now = System.currentTimeMillis()
                        val ts = if (dateKey == todayKey) now else DateUtils.dayRangeMs(dateKey).first + 12 * 3600_000L
                        App.daylogs.addActivity(
                            dateKey,
                            ActivityItem(
                                id = "note_${now}_${detail.hashCode()}",
                                dateKey = dateKey,
                                timestampMs = ts,
                                type = ActivityType.NOTE,
                                title = title,
                                detail = detail,
                            ),
                        )
                    },
                    onDeleteNote = { dateKey, activityId -> App.daylogs.deleteActivity(dateKey, activityId) },
                    onSavePlaceAlias = { name, visit ->
                        App.locationVisits.saveAlias(name, visit.latitude, visit.longitude)
                        App.daylogs.refreshAliasesAcrossRecords(App.locationVisits.aliases.value)
                        scope.launch { snackbarHostState.showSnackbar("장소 이름 '${name}'을 저장했습니다.") }
                    },
                    onPhotoClick = { photo -> lightboxPhoto = photo },
                    onOpenPermissions = { showSettings = true },
                )

                DaylogTab.FEED -> FeedScreen(
                    records = records,
                    onSelectDateInCalendar = { dateKey ->
                        selectAndLoadDate(dateKey)
                        activeTab = DaylogTab.CALENDAR
                    },
                    onOpenDetail = { dateKey -> detailSheetDateKey = dateKey },
                    onOpenSettings = { showSettings = true },
                )

                DaylogTab.PLACES -> PlacesScreen(
                    records = records,
                    aliases = aliases,
                    isTrackingRunning = isTrackingRunning,
                    hasLocationPermission = permissionStatus.locationGranted,
                    onToggleTracking = { toggleLocationTracking() },
                    onCheckInNow = { checkInCurrentLocation(todayKey) },
                    onSaveAlias = { name, visit ->
                        App.locationVisits.saveAlias(name, visit.latitude, visit.longitude)
                        App.daylogs.refreshAliasesAcrossRecords(App.locationVisits.aliases.value)
                        scope.launch { snackbarHostState.showSnackbar("장소 이름 '${name}'을 저장했습니다.") }
                    },
                    onDeleteAlias = { aliasId -> App.locationVisits.deleteAlias(aliasId) },
                    onOpenDate = { dateKey ->
                        selectAndLoadDate(dateKey)
                        activeTab = DaylogTab.CALENDAR
                    },
                )
            }
        }
    }

    val sheetDate = detailSheetDateKey
    if (sheetDate != null) {
        DayDetailSheet(
            dateKey = sheetDate,
            record = recordsByDate[sheetDate],
            isScanning = isScanning,
            onDismiss = { detailSheetDateKey = null },
            onRefreshDate = { scanDate(sheetDate, showToast = true) },
            onSaveQuickNote = { note ->
                App.daylogs.updateQuickNote(sheetDate, note)
                scope.launch { snackbarHostState.showSnackbar("메모를 저장했습니다.") }
            },
            onAddActivityNote = { title, detail ->
                val now = System.currentTimeMillis()
                val ts = if (sheetDate == todayKey) now else DateUtils.dayRangeMs(sheetDate).first + 12 * 3600_000L
                App.daylogs.addActivity(
                    sheetDate,
                    ActivityItem(
                        id = "note_${now}_${detail.hashCode()}",
                        dateKey = sheetDate,
                        timestampMs = ts,
                        type = ActivityType.NOTE,
                        title = title,
                        detail = detail,
                    ),
                )
            },
            onDeleteActivity = { activityId -> App.daylogs.deleteActivity(sheetDate, activityId) },
            onSavePlaceAlias = { name, visit ->
                App.locationVisits.saveAlias(name, visit.latitude, visit.longitude)
                App.daylogs.refreshAliasesAcrossRecords(App.locationVisits.aliases.value)
                scope.launch { snackbarHostState.showSnackbar("장소 이름 '${name}'을 저장했습니다.") }
            },
            onDeleteRecord = {
                App.daylogs.deleteRecord(sheetDate)
                detailSheetDateKey = null
            },
            onPhotoClick = { photo -> lightboxPhoto = photo },
        )
    }

    if (showSettings) {
        SettingsSheet(
            settings = settings,
            permissionStatus = permissionStatus,
            isTrackingRunning = isTrackingRunning,
            isBatchScanning = isScanning,
            onDismiss = { showSettings = false },
            onRequestRuntimePermissions = onRequestRuntimePermissions,
            onRequestBackgroundLocation = onRequestBackgroundLocation,
            onOpenNotificationListenerSettings = {
                runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            },
            onToggleTrackingService = { toggleLocationTracking() },
            onBatchScanDays = { days -> batchScanRecentDays(days) },
            onUpdateSettings = { transform -> App.settings.update(transform) },
            onExportJsonBackup = { scope.launch { shareBackupJson(context) } },
            onClearAllRecords = {
                App.daylogs.clearAll()
                App.snsCaptures.clearAll()
                App.locationVisits.clearAll()
                autoScannedDates = emptySet()
                showSettings = false
                scope.launch { snackbarHostState.showSnackbar("모든 기록을 초기화했습니다.") }
            },
        )
    }

    val photoToShow = lightboxPhoto
    if (photoToShow != null) {
        PhotoLightboxDialog(
            photo = photoToShow,
            onDismiss = { lightboxPhoto = null },
        )
    }
}

@SuppressLint("MissingPermission")
private fun readBestLastLocation(context: Context): Location? {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers = lm.getProviders(true)
    var best: Location? = null
    for (provider in providers) {
        val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
        if (best == null || loc.time > best.time) {
            best = loc
        }
    }
    return best
}

private fun copySharedImage(context: Context, uri: Uri, ts: Long): DayPhoto? {
    return runCatching {
        val dir = File(context.filesDir, "shared_images").apply { mkdirs() }
        val target = File(dir, "share_${ts}_${uri.toString().hashCode()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        val finalUri = if (target.exists() && target.length() > 0L) Uri.fromFile(target).toString() else uri.toString()
        DayPhoto(
            uri = finalUri,
            displayName = target.name,
            folderName = "공유됨",
            dateTakenMs = ts,
        )
    }.getOrNull()
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
        putExtra(Intent.EXTRA_SUBJECT, "데이로그 데이터 백업 (${DateUtils.todayKey()})")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(shareIntent, "데이로그 백업 내보내기"))
    }
}
