package com.aiutil.daylog.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiutil.daylog.model.DateScanLog
import com.aiutil.daylog.model.DaylogSettings
import com.aiutil.daylog.model.PermissionStatus
import com.aiutil.daylog.model.SavedPlaceAlias
import com.aiutil.daylog.model.ScheduledRunLog
import com.aiutil.daylog.util.DateUtils
import java.util.Locale

private enum class SettingsGroup(val title: String) {
    ACCESS("데이터 접근 권한"),
    AUTO_RECORD("자동 기록"),
    DATA_AND_PLACES("수집 및 장소 관리"),
    AI_AND_BACKUP("AI · 백업"),
}

private enum class SettingsSubPage(
    val group: SettingsGroup,
    val title: String,
    val description: String,
) {
    PERMISSIONS(
        group = SettingsGroup.ACCESS,
        title = "권한 설정",
        description = "사진 · 통화 · 문자 · 달력 · 위치 · SNS 알림 접근 허용",
    ),
    GPS_TRACKING(
        group = SettingsGroup.AUTO_RECORD,
        title = "GPS 설정",
        description = "백그라운드 자동 기록 · 확인 주기 · 체류 시간 기준",
    ),
    SCHEDULED_SCAN(
        group = SettingsGroup.AUTO_RECORD,
        title = "예약 불러오기 설정",
        description = "지정 시각 과거 내역 자동 백그라운드 수집",
    ),
    COLLECTION_ITEMS(
        group = SettingsGroup.DATA_AND_PLACES,
        title = "수집 항목 설정",
        description = "사진 · 결제 · 통화 · 문자 · 구글 달력 · SNS",
    ),
    BATCH_AND_HISTORY(
        group = SettingsGroup.DATA_AND_PLACES,
        title = "과거 불러오기 · 이력",
        description = "기간 선택 일괄 스캔 및 수집 성공 여부 달력",
    ),
    PLACE_ALIASES(
        group = SettingsGroup.DATA_AND_PLACES,
        title = "등록된 장소 이름",
        description = "집 · 회사 등 저장된 별칭 및 인식 반경 관리",
    ),
    GEMINI_AI(
        group = SettingsGroup.AI_AND_BACKUP,
        title = "Gemini AI 설정",
        description = "API 키 관리 (브리핑 · 검색 · 통화요약 · 사진인식)",
    ),
    BACKUP_RESET(
        group = SettingsGroup.AI_AND_BACKUP,
        title = "백업 및 초기화",
        description = "전체 기록 JSON 내보내기 및 앱 데이터 초기화",
    ),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    todayKey: String,
    settings: DaylogSettings,
    permissionStatus: PermissionStatus,
    isTrackingRunning: Boolean,
    isBatchScanning: Boolean,
    aliases: List<SavedPlaceAlias> = emptyList(),
    scanLogsByDate: Map<String, DateScanLog> = emptyMap(),
    scheduledRuns: List<ScheduledRunLog> = emptyList(),
    onDismiss: () -> Unit,
    onRequestRuntimePermissions: () -> Unit,
    onRequestBackgroundLocation: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onToggleTrackingService: () -> Unit,
    onCheckInNow: () -> Unit = {},
    onUpdateAlias: (aliasId: String, name: String, radiusMeters: Int) -> Unit = { _, _, _ -> },
    onDeleteAlias: (aliasId: String) -> Unit = {},
    onBatchScanRange: (startDateKey: String, endDateKey: String) -> Unit,
    onRunScheduledScanNow: () -> Unit = {},
    onUpdateSettings: ((DaylogSettings) -> DaylogSettings) -> Unit,
    onExportJsonBackup: () -> Unit,
    onClearAllRecords: () -> Unit,
) {
    var activePage by rememberSaveable { mutableStateOf<SettingsSubPage?>(null) }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var showRangePicker by remember { mutableStateOf(false) }
    var showCustomStayDialog by remember { mutableStateOf(false) }
    var editingAlias by remember { mutableStateOf<SavedPlaceAlias?>(null) }
    var batchStartKey by remember(todayKey) { mutableStateOf(DateUtils.daysAgoKey(todayKey, 6)) }
    var batchEndKey by remember(todayKey) { mutableStateOf(todayKey) }
    var apiKeyDraft by remember(settings.geminiApiKey) { mutableStateOf(settings.geminiApiKey) }
    var showApiKey by remember { mutableStateOf(false) }

    BackHandler {
        if (activePage != null) {
            activePage = null
        } else {
            onDismiss()
        }
    }

    val currentSubPage = activePage

    // 아래로 스와이프해도 실수로 닫히지 않도록 전체화면 고정 상단바 레이아웃 사용
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            // 상단 바 (1차 메뉴 <-> 서브 메뉴 내비게이션)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (currentSubPage != null) {
                    IconButton(onClick = { activePage = null }) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "설정 목록으로")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentSubPage.group.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = currentSubPage.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                } else {
                    Text(
                        text = "Daylog 설정",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 6.dp),
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "닫기")
                }
            }
            HorizontalDivider()

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (currentSubPage == null) {
                    // 1차 메뉴 그룹 목록
                    SettingsGroup.entries.forEach { group ->
                        val pages = SettingsSubPage.entries.filter { it.group == group }
                        item(key = "group_${group.name}") {
                            SettingsGroupCard(
                                groupTitle = group.title,
                                pages = pages,
                                summaryOf = { page ->
                                    buildSubPageSummary(
                                        page = page,
                                        settings = settings,
                                        permissionStatus = permissionStatus,
                                        isTrackingRunning = isTrackingRunning,
                                        isBatchScanning = isBatchScanning,
                                        aliases = aliases,
                                        scanLogsByDate = scanLogsByDate,
                                    )
                                },
                                onSelectPage = { page -> activePage = page },
                            )
                        }
                    }
                } else {
                    // 선택된 서브 메뉴 화면
                    when (currentSubPage) {
                        SettingsSubPage.PERMISSIONS -> {
                            item(key = "sub_perms") {
                                PermissionsSubPage(
                                    permissionStatus = permissionStatus,
                                    onRequestRuntimePermissions = onRequestRuntimePermissions,
                                    onRequestBackgroundLocation = onRequestBackgroundLocation,
                                    onOpenNotificationListenerSettings = onOpenNotificationListenerSettings,
                                )
                            }
                        }

                        SettingsSubPage.GPS_TRACKING -> {
                            item(key = "sub_gps") {
                                GpsTrackingSubPage(
                                    settings = settings,
                                    isTrackingRunning = isTrackingRunning,
                                    onToggleTrackingService = onToggleTrackingService,
                                    onUpdateSettings = onUpdateSettings,
                                    onShowCustomStayDialog = { showCustomStayDialog = true },
                                    onCheckInNow = onCheckInNow,
                                )
                            }
                        }

                        SettingsSubPage.SCHEDULED_SCAN -> {
                            item(key = "sub_sched") {
                                ScheduledAutoScanCard(
                                    todayKey = todayKey,
                                    settings = settings,
                                    onUpdateSettings = onUpdateSettings,
                                    onRunScheduledScanNow = onRunScheduledScanNow,
                                )
                            }
                        }

                        SettingsSubPage.COLLECTION_ITEMS -> {
                            item(key = "sub_collect") {
                                CollectionItemsSubPage(
                                    settings = settings,
                                    onUpdateSettings = onUpdateSettings,
                                )
                            }
                        }

                        SettingsSubPage.BATCH_AND_HISTORY -> {
                            item(key = "sub_batch") {
                                BatchScanSubPage(
                                    todayKey = todayKey,
                                    batchStartKey = batchStartKey,
                                    batchEndKey = batchEndKey,
                                    isBatchScanning = isBatchScanning,
                                    onSelectPreset = { s, e ->
                                        batchStartKey = s
                                        batchEndKey = e
                                    },
                                    onOpenRangePicker = { showRangePicker = true },
                                    onBatchScanRange = onBatchScanRange,
                                )
                            }
                            item(key = "sub_scan_calendar") {
                                ScanStatusCalendarCard(
                                    todayKey = todayKey,
                                    scanLogsByDate = scanLogsByDate,
                                    scheduledRuns = scheduledRuns,
                                )
                            }
                        }

                        SettingsSubPage.PLACE_ALIASES -> {
                            item(key = "sub_aliases") {
                                PlaceAliasesSubPage(
                                    aliases = aliases,
                                    onEditAlias = { alias -> editingAlias = alias },
                                    onDeleteAlias = onDeleteAlias,
                                )
                            }
                        }

                        SettingsSubPage.GEMINI_AI -> {
                            item(key = "sub_gemini") {
                                GeminiAiSubPage(
                                    apiKeyDraft = apiKeyDraft,
                                    showApiKey = showApiKey,
                                    onApiKeyChange = { apiKeyDraft = it },
                                    onToggleShowApiKey = { showApiKey = !showApiKey },
                                    onSaveApiKey = {
                                        onUpdateSettings { it.copy(geminiApiKey = apiKeyDraft.trim()) }
                                    },
                                )
                            }
                        }

                        SettingsSubPage.BACKUP_RESET -> {
                            item(key = "sub_backup") {
                                BackupAndResetSubPage(
                                    onExportJsonBackup = onExportJsonBackup,
                                    onRequestClearAll = { showClearAllConfirm = true },
                                )
                            }
                        }
                    }
                }

                item(key = "settings_bottom") {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    if (showCustomStayDialog) {
        var stayMinutesDraft by remember(settings.stayThresholdMin) {
            mutableStateOf(settings.stayThresholdMin.toString())
        }
        val parsedMin = stayMinutesDraft.trim().toIntOrNull()
        val isValidMin = parsedMin != null && parsedMin in 1..360
        AlertDialog(
            onDismissRequest = { showCustomStayDialog = false },
            title = { Text("체류 기준 시간 설정") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "입력한 시간(분) 이상 머물면 타임라인에 큰 점(체류 장소)으로 표시합니다. (1~360분)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = stayMinutesDraft,
                        onValueChange = { input ->
                            stayMinutesDraft = input.filter { it.isDigit() }.take(3)
                        },
                        label = { Text("체류 기준 (분)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (parsedMin != null && isValidMin) {
                            onUpdateSettings { it.copy(stayThresholdMin = parsedMin) }
                        }
                        showCustomStayDialog = false
                    },
                    enabled = isValidMin,
                ) {
                    Text("적용")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomStayDialog = false }) { Text("취소") }
            },
        )
    }

    if (showRangePicker) {
        val todayUtcMillis = remember(todayKey) { DateUtils.dateKeyToUtcMillis(todayKey) }
        val rangeState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = DateUtils.dateKeyToUtcMillis(batchStartKey),
            initialSelectedEndDateMillis = DateUtils.dateKeyToUtcMillis(batchEndKey),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayUtcMillis
            },
        )
        DatePickerDialog(
            onDismissRequest = { showRangePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val sMillis = rangeState.selectedStartDateMillis
                        val eMillis = rangeState.selectedEndDateMillis ?: sMillis
                        if (sMillis != null && eMillis != null) {
                            val sKey = DateUtils.utcMillisToDateKey(sMillis)
                            val eKey = DateUtils.utcMillisToDateKey(eMillis)
                            if (sKey <= eKey) {
                                batchStartKey = sKey
                                batchEndKey = eKey
                            } else {
                                batchStartKey = eKey
                                batchEndKey = sKey
                            }
                        }
                        showRangePicker = false
                    },
                    enabled = rangeState.selectedStartDateMillis != null,
                ) {
                    Text("적용")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRangePicker = false }) { Text("취소") }
            },
        ) {
            DateRangePicker(
                state = rangeState,
                title = {
                    Text(
                        text = "불러올 시작일과 종료일 선택 (최대 180일)",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 24.dp, top = 16.dp, end = 12.dp),
                    )
                },
                headline = {
                    val sLabel = rangeState.selectedStartDateMillis?.let { DateUtils.utcMillisToDateKey(it) } ?: "시작일"
                    val eLabel = rangeState.selectedEndDateMillis?.let { DateUtils.utcMillisToDateKey(it) } ?: "종료일"
                    Text(
                        text = "$sLabel ~ $eLabel",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 24.dp, end = 12.dp, bottom = 12.dp),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(480.dp),
            )
        }
    }

    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("저장된 모든 기록을 초기화할까요?") },
            text = { Text("앱에 저장된 GPS 이동 기록, SNS 알림 기록, 메모가 모두 삭제됩니다. 폰 원본 데이터는 유지됩니다.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearAllConfirm = false
                    onClearAllRecords()
                }) {
                    Text("초기화", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) { Text("취소") }
            },
        )
    }

    val targetEditAlias = editingAlias
    if (targetEditAlias != null) {
        var aliasNameInput by remember(targetEditAlias.id) { mutableStateOf(targetEditAlias.name) }
        var aliasRadiusInput by remember(targetEditAlias.id) { mutableIntStateOf(targetEditAlias.radiusMeters) }
        AlertDialog(
            onDismissRequest = { editingAlias = null },
            title = { Text("등록된 장소 수정") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = aliasNameInput,
                        onValueChange = { aliasNameInput = it },
                        label = { Text("장소 이름") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "자동 인식 반경 (주변 상가가 가까우면 30~50m로 좁혀 주세요)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(30, 50, 80, 150, 300).forEach { r ->
                            FilterChip(
                                selected = aliasRadiusInput == r,
                                onClick = { aliasRadiusInput = r },
                                label = { Text("${r}m") },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUpdateAlias(targetEditAlias.id, aliasNameInput.trim(), aliasRadiusInput)
                        editingAlias = null
                    },
                    enabled = aliasNameInput.isNotBlank(),
                ) {
                    Text("저장")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingAlias = null }) { Text("취소") }
            },
        )
    }
}

private fun buildSubPageSummary(
    page: SettingsSubPage,
    settings: DaylogSettings,
    permissionStatus: PermissionStatus,
    isTrackingRunning: Boolean,
    isBatchScanning: Boolean,
    aliases: List<SavedPlaceAlias>,
    scanLogsByDate: Map<String, DateScanLog>,
): String {
    return when (page) {
        SettingsSubPage.PERMISSIONS -> {
            val grantedCount = listOf(
                permissionStatus.photosGranted,
                permissionStatus.audioGranted,
                permissionStatus.mediaLocationGranted,
                permissionStatus.callLogGranted && permissionStatus.contactsGranted,
                permissionStatus.smsGranted,
                permissionStatus.calendarGranted,
                permissionStatus.locationGranted,
                permissionStatus.backgroundLocationGranted,
                permissionStatus.snsNotificationEnabled,
            ).count { it }
            "허용 ${grantedCount}/9"
        }

        SettingsSubPage.GPS_TRACKING -> {
            val status = if (isTrackingRunning) "기록 중 · ${settings.locationIntervalMin}분" else "꺼짐"
            "$status · 체류 ${settings.stayThresholdMin}분"
        }

        SettingsSubPage.SCHEDULED_SCAN -> {
            if (settings.autoScanEnabled) {
                String.format(Locale.US, "켜짐 · 매일 %02d:%02d", settings.autoScanHour, settings.autoScanMinute)
            } else {
                "꺼짐"
            }
        }

        SettingsSubPage.COLLECTION_ITEMS -> {
            val enabledCount = listOf(
                settings.collectPhotos,
                settings.collectPayments,
                settings.collectCalls,
                settings.collectSms,
                settings.collectCalendar,
                settings.collectSns,
            ).count { it }
            "${enabledCount}/6 항목 켜짐"
        }

        SettingsSubPage.BATCH_AND_HISTORY -> {
            if (isBatchScanning) "불러오는 중..." else "이력 ${scanLogsByDate.size}일"
        }

        SettingsSubPage.PLACE_ALIASES -> {
            if (aliases.isEmpty()) "없음" else "${aliases.size}곳"
        }

        SettingsSubPage.GEMINI_AI -> {
            if (settings.geminiApiKey.isNotBlank()) "연결됨" else "미설정"
        }

        SettingsSubPage.BACKUP_RESET -> "JSON · 초기화"
    }
}

@Composable
private fun SettingsGroupCard(
    groupTitle: String,
    pages: List<SettingsSubPage>,
    summaryOf: (SettingsSubPage) -> String,
    onSelectPage: (SettingsSubPage) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = groupTitle,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                pages.forEachIndexed { idx, page ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectPage(page) }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = page.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = page.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                text = summaryOf(page),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    if (idx < pages.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                            modifier = Modifier.padding(horizontal = 14.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionsSubPage(
    permissionStatus: PermissionStatus,
    onRequestRuntimePermissions: () -> Unit,
    onRequestBackgroundLocation: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("데이터 접근 권한", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            PermissionStatusRow("사진 조회", permissionStatus.photosGranted)
            PermissionStatusRow("통화 녹음 오디오 조회", permissionStatus.audioGranted)
            PermissionStatusRow("사진 내 GPS 위치 읽기", permissionStatus.mediaLocationGranted)
            PermissionStatusRow("통화 기록 & 연락처", permissionStatus.callLogGranted && permissionStatus.contactsGranted)
            PermissionStatusRow("문자 메시지", permissionStatus.smsGranted)
            PermissionStatusRow("구글 달력 일정", permissionStatus.calendarGranted)
            PermissionStatusRow("위치 (GPS 이동·체류)", permissionStatus.locationGranted)
            PermissionStatusRow("백그라운드 위치 (항상 허용)", permissionStatus.backgroundLocationGranted)
            PermissionStatusRow("SNS 알림 수신", permissionStatus.snsNotificationEnabled)

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onRequestRuntimePermissions,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("기본 권한 허용", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onRequestBackgroundLocation,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("백그라운드 위치", style = MaterialTheme.typography.labelMedium)
                }
            }
            OutlinedButton(
                onClick = onOpenNotificationListenerSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("SNS 알림 접근 설정 열기")
            }
        }
    }
}

@Composable
private fun GpsTrackingSubPage(
    settings: DaylogSettings,
    isTrackingRunning: Boolean,
    onToggleTrackingService: () -> Unit,
    onUpdateSettings: ((DaylogSettings) -> DaylogSettings) -> Unit,
    onShowCustomStayDialog: () -> Unit,
    onCheckInNow: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("이동 · 체류 장소 자동 기록", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (isTrackingRunning) "현재 백그라운드에서 기록 중" else "중지됨",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onToggleTrackingService) {
                    Text(if (isTrackingRunning) "중지" else "시작")
                }
            }

            HorizontalDivider()

            Text("GPS 확인 주기", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1, 3, 5, 10).forEach { mins ->
                    FilterChip(
                        selected = settings.locationIntervalMin == mins,
                        onClick = { onUpdateSettings { it.copy(locationIntervalMin = mins) } },
                        label = { Text("${mins}분") },
                    )
                }
            }

            HorizontalDivider()

            Text("체류 장소 기준 시간 (큰 점 표시)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "${settings.stayThresholdMin}분 이상 머물거나 같은 곳에서 사진 2장 이상 촬영 시 큰 점으로 표시합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val stayPresets = listOf(1, 3, 5, 10, 15, 30)
            val isCustomStay = settings.stayThresholdMin !in stayPresets
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                stayPresets.forEach { mins ->
                    FilterChip(
                        selected = settings.stayThresholdMin == mins,
                        onClick = { onUpdateSettings { it.copy(stayThresholdMin = mins) } },
                        label = { Text("${mins}분") },
                    )
                }
                FilterChip(
                    selected = isCustomStay,
                    onClick = onShowCustomStayDialog,
                    label = {
                        Text(if (isCustomStay) "직접 (${settings.stayThresholdMin}분)" else "직접 입력")
                    },
                )
            }

            SettingSwitchRow(
                title = "사진 EXIF GPS로 과거 방문 장소 복원",
                checked = settings.extractPhotoGps,
                onCheckedChange = { v -> onUpdateSettings { it.copy(extractPhotoGps = v) } },
            )

            OutlinedButton(
                onClick = onCheckInNow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("현재 위치 지금 기록")
            }
        }
    }
}

@Composable
private fun CollectionItemsSubPage(
    settings: DaylogSettings,
    onUpdateSettings: ((DaylogSettings) -> DaylogSettings) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("데이터 조회 항목", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            SettingSwitchRow(
                title = "사진 조회",
                checked = settings.collectPhotos,
                onCheckedChange = { v -> onUpdateSettings { it.copy(collectPhotos = v) } },
            )
            SettingSwitchRow(
                title = "사진 목록에서 스크린샷 제외",
                checked = settings.excludeScreenshots,
                onCheckedChange = { v -> onUpdateSettings { it.copy(excludeScreenshots = v) } },
            )
            SettingSwitchRow(
                title = "결제 내역 따로 기록 (카드·계좌 문자/알림)",
                checked = settings.collectPayments,
                onCheckedChange = { v -> onUpdateSettings { it.copy(collectPayments = v) } },
            )
            SettingSwitchRow(
                title = "통화 내역 조회",
                checked = settings.collectCalls,
                onCheckedChange = { v -> onUpdateSettings { it.copy(collectCalls = v) } },
            )
            SettingSwitchRow(
                title = "통화 녹음 파일 자동 매칭 (AI 요약용)",
                checked = settings.matchCallRecordings,
                onCheckedChange = { v -> onUpdateSettings { it.copy(matchCallRecordings = v) } },
            )
            SettingSwitchRow(
                title = "문자 내역 조회",
                checked = settings.collectSms,
                onCheckedChange = { v -> onUpdateSettings { it.copy(collectSms = v) } },
            )
            SettingSwitchRow(
                title = "광고·인증번호·택배 스팸 문자 제외",
                checked = settings.filterSpamSms,
                onCheckedChange = { v -> onUpdateSettings { it.copy(filterSpamSms = v) } },
            )
            SettingSwitchRow(
                title = "SNS 알림 수집",
                checked = settings.collectSns,
                onCheckedChange = { v -> onUpdateSettings { it.copy(collectSns = v) } },
            )
            SettingSwitchRow(
                title = "구글 달력 일정 연동 (달력 · 메모 표시)",
                checked = settings.collectCalendar,
                onCheckedChange = { v -> onUpdateSettings { it.copy(collectCalendar = v) } },
            )
        }
    }
}

@Composable
private fun BatchScanSubPage(
    todayKey: String,
    batchStartKey: String,
    batchEndKey: String,
    isBatchScanning: Boolean,
    onSelectPreset: (startKey: String, endKey: String) -> Unit,
    onOpenRangePicker: () -> Unit,
    onBatchScanRange: (startDateKey: String, endDateKey: String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("과거 날짜 상세 불러오기", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                text = "원하는 시작일·종료일을 직접 고르거나 빠른 기간을 선택해 한꺼번에 스캔합니다 (최대 180일).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val presets = remember(todayKey) {
                listOf(
                    "최근 7일" to (DateUtils.daysAgoKey(todayKey, 6) to todayKey),
                    "최근 30일" to (DateUtils.daysAgoKey(todayKey, 29) to todayKey),
                    "이번 달" to (DateUtils.startOfMonthKey(todayKey, 0) to todayKey),
                    "지난 달" to (DateUtils.startOfMonthKey(todayKey, 1) to DateUtils.endOfMonthKey(todayKey, 1)),
                    "최근 90일" to (DateUtils.daysAgoKey(todayKey, 89) to todayKey),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                presets.forEach { (label, range) ->
                    val isSelected = batchStartKey == range.first && batchEndKey == range.second
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectPreset(range.first, range.second) },
                        label = { Text(label) },
                    )
                }
                FilterChip(
                    selected = presets.none { it.second.first == batchStartKey && it.second.second == batchEndKey },
                    onClick = onOpenRangePicker,
                    label = { Text("직접 선택") },
                )
            }

            val selectedDaysCount = remember(batchStartKey, batchEndKey) {
                DateUtils.dateKeysBetween(batchStartKey, batchEndKey, maxDays = 180).size
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenRangePicker),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Default.DateRange,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Column {
                            Text(
                                text = "$batchStartKey ~ $batchEndKey",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "총 ${selectedDaysCount}일간 데이터 스캔 (눌러서 날짜 변경)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        text = "달력",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Button(
                onClick = { onBatchScanRange(batchStartKey, batchEndKey) },
                enabled = !isBatchScanning && selectedDaysCount > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isBatchScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("데이터 불러오는 중...")
                } else {
                    Text("선택 기간 불러오기 (${selectedDaysCount}일)")
                }
            }
        }
    }
}

@Composable
private fun PlaceAliasesSubPage(
    aliases: List<SavedPlaceAlias>,
    onEditAlias: (SavedPlaceAlias) -> Unit,
    onDeleteAlias: (aliasId: String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "등록된 장소 이름 (${aliases.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            if (aliases.isEmpty()) {
                Text(
                    text = "아직 등록된 장소 이름이 없습니다. 장소 카드의 북마크 아이콘으로 집·회사 등을 지정할 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                aliases.forEach { alias ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(alias.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "반경 ${alias.radiusMeters}m 자동 매칭",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onEditAlias(alias) }) {
                            Icon(Icons.Default.EditNote, contentDescription = "장소 이름·반경 수정", modifier = Modifier.size(19.dp))
                        }
                        IconButton(onClick = { onDeleteAlias(alias.id) }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "장소 이름 삭제", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GeminiAiSubPage(
    apiKeyDraft: String,
    showApiKey: Boolean,
    onApiKeyChange: (String) -> Unit,
    onToggleShowApiKey: () -> Unit,
    onSaveApiKey: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Gemini AI 도우미 (선택)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                text = "API 키를 넣으면 일자별 핵심 브리핑, 자연어 기록 검색(예: 지난주에 간 곳), 통화 녹음·사진 내용 읽기를 쓸 수 있습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = apiKeyDraft,
                onValueChange = onApiKeyChange,
                label = { Text("Gemini API 키") },
                singleLine = true,
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = onToggleShowApiKey) {
                        Text(if (showApiKey) "숨김" else "보기", style = MaterialTheme.typography.labelSmall)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onSaveApiKey) {
                    Text("API 키 저장")
                }
            }
        }
    }
}

@Composable
private fun BackupAndResetSubPage(
    onExportJsonBackup: () -> Unit,
    onRequestClearAll: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("백업 및 초기화", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                text = "수집된 데이로그 기록을 JSON 파일로 내보내거나 앱 내부 저장 기록만 초기화할 수 있습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onExportJsonBackup,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("전체 기록 JSON 내보내기")
            }
            TextButton(
                onClick = onRequestClearAll,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("저장된 데이로그 기록 초기화", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun PermissionStatusRow(label: String, granted: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        Surface(
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(999.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                if (granted) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                }
                Text(
                    text = if (granted) "허용됨" else "미허용",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (granted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
