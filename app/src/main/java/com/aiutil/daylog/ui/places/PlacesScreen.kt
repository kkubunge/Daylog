package com.aiutil.daylog.ui.places

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiutil.daylog.model.DayRecord
import com.aiutil.daylog.model.PlaceVisit
import com.aiutil.daylog.ui.calendar.SavePlaceAliasDialog
import com.aiutil.daylog.ui.components.PlaceVisitTimelineItem
import com.aiutil.daylog.ui.components.PlacesInlineMapView
import com.aiutil.daylog.ui.components.openAllPlacesInExternalMap
import com.aiutil.daylog.util.DateUtils

/**
 * 이동 장소 전용 화면:
 * 날짜를 선택하면 해당 날짜의 모든 이동·체류 장소를 지도에 바로 띄우고 상세 목록을 표시한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(
    todayKey: String,
    selectedDateKey: String,
    recordsByDate: Map<String, DayRecord>,
    stayThresholdMin: Int = 3,
    onSelectDate: (String) -> Unit,
    onRefreshDate: (String) -> Unit,
    onSaveAlias: (name: String, visit: PlaceVisit) -> Unit,
    onClassifyPlaceWithAi: (PlaceVisit) -> Unit,
    onOpenPlacesMap: (title: String, places: List<PlaceVisit>) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    var showDatePicker by remember { mutableStateOf(false) }
    var focusedPinIdx by remember(selectedDateKey) { mutableStateOf<Int?>(null) }
    var focusTrigger by remember(selectedDateKey) { mutableStateOf(0) }
    var aliasTargetVisit by remember { mutableStateOf<PlaceVisit?>(null) }

    val selectedRecord = recordsByDate[selectedDateKey]
    val dayPlaces = selectedRecord?.places.orEmpty()
    val recentPlaceDates = remember(recordsByDate) {
        recordsByDate.values
            .filter { it.places.isNotEmpty() }
            .sortedByDescending { it.dateKey }
            .take(12)
            .map { it.dateKey }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. 날짜 선택 바 (< 날짜 달력 > + 장소 기록 있는 최근 날짜 칩)
        item(key = "places_date_selector") {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        IconButton(
                            onClick = { onSelectDate(DateUtils.daysAgoKey(selectedDateKey, 1)) },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = "이전 날짜")
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showDatePicker = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.DateRange,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = DateUtils.formatKoreanDate(selectedDateKey),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    val nextKey = DateUtils.daysAgoKey(selectedDateKey, -1)
                                    if (nextKey <= todayKey) onSelectDate(nextKey)
                                },
                                enabled = selectedDateKey < todayKey,
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(Icons.Default.ChevronRight, contentDescription = "다음 날짜")
                            }
                            IconButton(
                                onClick = { onRefreshDate(selectedDateKey) },
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "새로고침", modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    if (recentPlaceDates.isNotEmpty()) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            recentPlaceDates.forEach { dKey ->
                                FilterChip(
                                    selected = dKey == selectedDateKey,
                                    onClick = { onSelectDate(dKey) },
                                    label = {
                                        val count = recordsByDate[dKey]?.places?.size ?: 0
                                        Text(
                                            text = "${dKey.takeLast(5)} (${count})",
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. 선택한 날짜의 이동 · 체류 지도 바로 표시
        item(key = "places_inline_map") {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        val totalDist = dayPlaces.sumOf { it.distanceFromPrevMeters }
                        val stayCount = dayPlaces.count { it.isStayWithThreshold(stayThresholdMin) }
                        Text(
                            text = if (dayPlaces.isNotEmpty()) {
                                "총 ${dayPlaces.size}곳 (체류 $stayCount · 경유 ${dayPlaces.size - stayCount})" +
                                    if (totalDist > 0) " · 이동 ${DateUtils.formatDistanceKorean(totalDist)}" else ""
                            } else {
                                "선택 날짜 지도"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (dayPlaces.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = { openAllPlacesInExternalMap(context, dayPlaces) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("구글맵", style = MaterialTheme.typography.labelSmall)
                                }
                                IconButton(
                                    onClick = {
                                        onOpenPlacesMap(
                                            "${DateUtils.formatShortKoreanDate(selectedDateKey)} 이동 지도",
                                            dayPlaces,
                                        )
                                    },
                                    modifier = Modifier.size(30.dp),
                                ) {
                                    Icon(Icons.Default.OpenInFull, contentDescription = "지도 크게 보기", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }

                    PlacesInlineMapView(
                        places = dayPlaces,
                        stayThresholdMin = stayThresholdMin,
                        focusIndex = focusedPinIdx,
                        focusRequestKey = focusTrigger,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp),
                    )
                }
            }
        }

        // 3. 선택한 날짜의 장소 상세 목록 (누르면 지도가 해당 위치 핀으로 포커싱)
        item(key = "day_places_list_header") {
            Text(
                text = "${DateUtils.formatShortKoreanDate(selectedDateKey)} 방문 및 체류 장소 (${dayPlaces.size})",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        if (dayPlaces.isEmpty()) {
            item(key = "day_places_empty") {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(18.dp),
                    ) {
                        Text(
                            text = "선택한 날짜에 이동 · 체류 장소 기록이 없습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = { onRefreshDate(selectedDateKey) }) {
                            Text("이 날 데이터 불러오기")
                        }
                    }
                }
            }
        } else {
            items(dayPlaces.size, key = { idx -> "${dayPlaces[idx].id}_$idx" }) { idx ->
                val visit = dayPlaces[idx]
                PlaceVisitTimelineItem(
                    visit = visit,
                    isLast = idx == dayPlaces.lastIndex,
                    stayThresholdMin = stayThresholdMin,
                    onOpenMap = {
                        focusedPinIdx = idx
                        focusTrigger++
                    },
                    onSaveAlias = { v -> aliasTargetVisit = v },
                    onClassifyWithAi = onClassifyPlaceWithAi,
                )
            }
        }

        item(key = "places_bottom_spacer") {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        val todayUtcMillis = remember(todayKey) { DateUtils.dateKeyToUtcMillis(todayKey) }
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = DateUtils.dateKeyToUtcMillis(selectedDateKey),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayUtcMillis
            },
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { ms ->
                            onSelectDate(DateUtils.utcMillisToDateKey(ms))
                        }
                        showDatePicker = false
                    },
                ) { Text("확인") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("취소") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    val targetVisit = aliasTargetVisit
    if (targetVisit != null) {
        SavePlaceAliasDialog(
            initialName = targetVisit.placeName,
            address = targetVisit.address,
            onDismiss = { aliasTargetVisit = null },
            onSave = { name ->
                onSaveAlias(name, targetVisit)
                aliasTargetVisit = null
            },
        )
    }
}
