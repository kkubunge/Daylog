package com.aiutil.daylog.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aiutil.daylog.collector.ScheduledScanPlanner
import com.aiutil.daylog.model.DateScanLog
import com.aiutil.daylog.model.DaylogSettings
import com.aiutil.daylog.model.ScanStatus
import com.aiutil.daylog.model.ScheduledRunLog
import com.aiutil.daylog.util.DateUtils
import java.util.Locale

private val ScanSuccessGreen = Color(0xFF2E7D32)
private val ScanFailedRed = Color(0xFFC62828)

/**
 * 매일 정해진 시각에 과거 데이터를 지정 일수씩 백그라운드로 불러오는 예약 설정 카드.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledAutoScanCard(
    todayKey: String,
    settings: DaylogSettings,
    onUpdateSettings: ((DaylogSettings) -> DaylogSettings) -> Unit,
    onRunScheduledScanNow: () -> Unit,
) {
    var showTimePicker by remember { mutableStateOf(false) }
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showChunkDaysDialog by remember { mutableStateOf(false) }

    val nextWindow = remember(settings, todayKey) {
        ScheduledScanPlanner.computeNextWindow(settings, todayKey)
    }

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
                    Text(
                        text = "예약 백그라운드 불러오기",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (settings.autoScanEnabled) {
                            "매일 ${formatKoreanHourMinute(settings.autoScanHour, settings.autoScanMinute)} 자동 실행"
                        } else {
                            "지정 시각에 과거 내역 자동 수집 (꺼짐)"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.autoScanEnabled,
                    onCheckedChange = { enabled ->
                        onUpdateSettings { it.copy(autoScanEnabled = enabled) }
                    },
                )
            }

            HorizontalDivider()

            // 1. 실행 시각 선택
            Text("실행 시각", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showTimePicker = true },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccessTime,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = formatKoreanHourMinute(settings.autoScanHour, settings.autoScanMinute),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = "시각 변경",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            val timePresets = listOf(0 to 0, 2 to 0, 3 to 0, 4 to 0, 6 to 0)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                timePresets.forEach { (h, m) ->
                    val selected = settings.autoScanHour == h && settings.autoScanMinute == m
                    FilterChip(
                        selected = selected,
                        onClick = {
                            onUpdateSettings { it.copy(autoScanHour = h, autoScanMinute = m) }
                        },
                        label = { Text(String.format(Locale.US, "%02d:%02d", h, m)) },
                    )
                }
            }

            // 2. 불러오기 시작 기준일 & 1회 일수
            Text("시작 기준일 · 1회 일수", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val isTodayAnchor = settings.autoScanStartDateKey.isBlank()
                FilterChip(
                    selected = isTodayAnchor,
                    onClick = {
                        onUpdateSettings {
                            it.copy(autoScanStartDateKey = "", autoScanCursorEndKey = "")
                        }
                    },
                    label = { Text("실행일 기준") },
                )
                FilterChip(
                    selected = !isTodayAnchor,
                    onClick = { showStartDatePicker = true },
                    leadingIcon = {
                        Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(15.dp))
                    },
                    label = {
                        Text(
                            if (isTodayAnchor) "특정 날짜부터" else "${settings.autoScanStartDateKey}부터"
                        )
                    },
                )
            }

            val chunkPresets = listOf(30, 60, 100, 180)
            val isCustomChunk = settings.autoScanChunkDays !in chunkPresets
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                chunkPresets.forEach { days ->
                    FilterChip(
                        selected = settings.autoScanChunkDays == days,
                        onClick = {
                            onUpdateSettings {
                                it.copy(autoScanChunkDays = days, autoScanCursorEndKey = "")
                            }
                        },
                        label = { Text("${days}일씩") },
                    )
                }
                FilterChip(
                    selected = isCustomChunk,
                    onClick = { showChunkDaysDialog = true },
                    label = {
                        Text(if (isCustomChunk) "직접 (${settings.autoScanChunkDays}일)" else "일수 직접 입력")
                    },
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("매일 이어서 더 과거로 불러오기", style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = if (settings.autoScanStepBackDaily) {
                            "예: 오늘 ${settings.autoScanChunkDays}일 → 내일 그 이전 ${settings.autoScanChunkDays}일"
                        } else {
                            "매일 동일한 기준 기간만 새로고침"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.autoScanStepBackDaily,
                    onCheckedChange = { stepBack ->
                        onUpdateSettings {
                            it.copy(
                                autoScanStepBackDaily = stepBack,
                                autoScanCursorEndKey = if (stepBack) it.autoScanCursorEndKey else "",
                            )
                        }
                    },
                )
            }

            // 다음 예약 구간 안내
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "다음 대상 구간: ${nextWindow.startDateKey} ~ ${nextWindow.endDateKey} (${nextWindow.dateKeys.size}일간)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onRunScheduledScanNow,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("지금 백그라운드 실행")
                }
                if (settings.autoScanCursorEndKey.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            onUpdateSettings { it.copy(autoScanCursorEndKey = "") }
                        },
                    ) {
                        Text("구간 리셋")
                    }
                }
            }
        }
    }

    if (showTimePicker) {
        val timeState = rememberTimePickerState(
            initialHour = settings.autoScanHour.coerceIn(0, 23),
            initialMinute = settings.autoScanMinute.coerceIn(0, 59),
            is24Hour = false,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("예약 실행 시각 선택") },
            text = {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TimePicker(state = timeState)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUpdateSettings {
                            it.copy(
                                autoScanHour = timeState.hour,
                                autoScanMinute = timeState.minute,
                            )
                        }
                        showTimePicker = false
                    },
                ) { Text("적용") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("취소") }
            },
        )
    }

    if (showStartDatePicker) {
        val todayUtcMillis = remember(todayKey) { DateUtils.dateKeyToUtcMillis(todayKey) }
        val initKey = settings.autoScanStartDateKey.ifBlank { todayKey }
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = DateUtils.dateKeyToUtcMillis(initKey),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayUtcMillis
            },
        )
        DatePickerDialog(
            onDismissRequest = { showStartDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ms = pickerState.selectedDateMillis
                        if (ms != null) {
                            val selected = DateUtils.utcMillisToDateKey(ms)
                            onUpdateSettings {
                                it.copy(autoScanStartDateKey = selected, autoScanCursorEndKey = "")
                            }
                        }
                        showStartDatePicker = false
                    },
                ) { Text("적용") }
            },
            dismissButton = {
                TextButton(onClick = { showStartDatePicker = false }) { Text("취소") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showChunkDaysDialog) {
        var chunkDraft by remember(settings.autoScanChunkDays) {
            mutableStateOf(settings.autoScanChunkDays.toString())
        }
        val parsed = chunkDraft.trim().toIntOrNull()
        val isValid = parsed != null && parsed in 1..365
        AlertDialog(
            onDismissRequest = { showChunkDaysDialog = false },
            title = { Text("1회 불러올 일수 설정") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "예약 실행 1회당 과거 며칠치를 불러올지 입력하세요. (1~365일)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = chunkDraft,
                        onValueChange = { input -> chunkDraft = input.filter { it.isDigit() }.take(3) },
                        label = { Text("일수") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (parsed != null && isValid) {
                            onUpdateSettings {
                                it.copy(autoScanChunkDays = parsed, autoScanCursorEndKey = "")
                            }
                        }
                        showChunkDaysDialog = false
                    },
                    enabled = isValid,
                ) { Text("적용") }
            },
            dismissButton = {
                TextButton(onClick = { showChunkDaysDialog = false }) { Text("취소") }
            },
        )
    }
}

/**
 * 설정 화면 내부에 표시되는 월별 데이터 불러오기(스캔) 성공/실패 현황 달력 카드.
 */
@Composable
fun ScanStatusCalendarCard(
    todayKey: String,
    scanLogsByDate: Map<String, DateScanLog>,
    scheduledRuns: List<ScheduledRunLog>,
) {
    val initYm = remember(todayKey) { DateUtils.yearMonthOf(todayKey) }
    var viewYear by remember { mutableIntStateOf(initYm.first) }
    var viewMonth by remember { mutableIntStateOf(initYm.second) }
    var selectedDateKey by remember { mutableStateOf(todayKey) }

    val (leadingEmpty, monthKeys) = remember(viewYear, viewMonth) {
        DateUtils.monthGridDays(viewYear, viewMonth)
    }

    val monthLogs = remember(monthKeys, scanLogsByDate) {
        monthKeys.mapNotNull { scanLogsByDate[it] }
    }
    val successCount = monthLogs.count { it.status == ScanStatus.SUCCESS }
    val emptyCount = monthLogs.count { it.status == ScanStatus.EMPTY }
    val failedCount = monthLogs.count { it.status == ScanStatus.FAILED }

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
                text = "불러오기 성공 여부 달력",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )

            // 월 네비게이션 + 요약 카운트
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            if (viewMonth == 1) {
                                viewYear -= 1
                                viewMonth = 12
                            } else {
                                viewMonth -= 1
                            }
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "이전 달")
                    }
                    Text(
                        text = "${viewYear}년 ${viewMonth}월",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(
                        onClick = {
                            if (viewMonth == 12) {
                                viewYear += 1
                                viewMonth = 1
                            } else {
                                viewMonth += 1
                            }
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "다음 달")
                    }
                }

                Text(
                    text = "성공 $successCount · 빈날 $emptyCount · 실패 $failedCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 범례
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegendDotLabel(color = ScanSuccessGreen, label = "성공(데이터)")
                LegendDotLabel(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f), label = "성공(빈날)")
                LegendDotLabel(color = ScanFailedRed, label = "실패")
            }

            // 요일 헤더
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { idx, w ->
                    Text(
                        text = w,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (idx == 0) ScanFailedRed.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 달력 7열 그리드
            val cells: List<String?> = remember(leadingEmpty, monthKeys) {
                val list = mutableListOf<String?>()
                repeat(leadingEmpty) { list.add(null) }
                list.addAll(monthKeys)
                while (list.size % 7 != 0) list.add(null)
                list
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                cells.chunked(7).forEach { week ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        week.forEach { key ->
                            if (key == null) {
                                Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
                            } else {
                                val log = scanLogsByDate[key]
                                val isSelected = key == selectedDateKey
                                val dayNum = key.takeLast(2).toIntOrNull()?.toString().orEmpty()
                                val dotColor = when (log?.status) {
                                    ScanStatus.SUCCESS -> ScanSuccessGreen
                                    ScanStatus.EMPTY -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                    ScanStatus.FAILED -> ScanFailedRed
                                    null -> null
                                }
                                val cellBg = when {
                                    isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                    log?.status == ScanStatus.SUCCESS -> ScanSuccessGreen.copy(alpha = 0.10f)
                                    log?.status == ScanStatus.FAILED -> ScanFailedRed.copy(alpha = 0.10f)
                                    else -> Color.Transparent
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(cellBg)
                                        .then(
                                            if (isSelected) {
                                                Modifier.border(
                                                    1.dp,
                                                    MaterialTheme.colorScheme.primary,
                                                    RoundedCornerShape(8.dp),
                                                )
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .clickable { selectedDateKey = key },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = dayNum,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (key == todayKey || isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (log?.status == ScanStatus.FAILED) ScanFailedRed else MaterialTheme.colorScheme.onSurface,
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        if (dotColor != null) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(dotColor),
                                            )
                                        } else {
                                            Spacer(modifier = Modifier.height(6.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 선택한 날짜의 상세 성공 여부 표시
            val selectedLog = scanLogsByDate[selectedDateKey]
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (selectedLog == null) {
                        Text(
                            text = "${DateUtils.formatKoreanDate(selectedDateKey)} · 아직 조회 기록 없음",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val modeLabel = if (selectedLog.isScheduled) "백그라운드 예약" else "직접 조회"
                        val statusColor = when (selectedLog.status) {
                            ScanStatus.SUCCESS -> ScanSuccessGreen
                            ScanStatus.FAILED -> ScanFailedRed
                            ScanStatus.EMPTY -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "${DateUtils.formatShortKoreanDate(selectedDateKey)} · ${selectedLog.status.label}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = statusColor,
                            )
                            Text(
                                text = "$modeLabel · ${DateUtils.formatTimeHm(selectedLog.scannedAtMs)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (selectedLog.status == ScanStatus.FAILED) {
                            Text(
                                text = "사유: ${selectedLog.errorMessage.ifBlank { "알 수 없는 오류" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = ScanFailedRed,
                            )
                        } else {
                            Text(
                                text = "장소 ${selectedLog.placeCount} · 사진 ${selectedLog.photoCount} · 결제 ${selectedLog.paymentCount} · 전체 활동 ${selectedLog.activityCount}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // 최근 백그라운드 예약 실행 이력
            if (scheduledRuns.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = "최근 예약 실행 로그",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                scheduledRuns.take(4).forEach { run ->
                    val runTimeLabel = "${DateUtils.dateKeyFromMs(run.runAtMs).takeLast(5)} ${DateUtils.formatTimeHm(run.runAtMs)}"
                    Text(
                        text = "• [$runTimeLabel] ${run.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (run.failedDays > 0 && run.successDays == 0) ScanFailedRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendDotLabel(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatKoreanHourMinute(hour: Int, minute: Int): String {
    val safeH = hour.coerceIn(0, 23)
    val safeM = minute.coerceIn(0, 59)
    val period = if (safeH < 12) "오전" else "오후"
    val h12 = when {
        safeH == 0 -> 12
        safeH > 12 -> safeH - 12
        else -> safeH
    }
    return String.format(Locale.KOREAN, "%s %02d:%02d", period, h12, safeM)
}
