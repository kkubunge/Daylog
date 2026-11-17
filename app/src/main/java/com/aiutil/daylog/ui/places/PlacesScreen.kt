package com.aiutil.daylog.ui.places

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiutil.daylog.model.DayRecord
import com.aiutil.daylog.model.PlaceVisit
import com.aiutil.daylog.ui.calendar.SavePlaceAliasDialog
import com.aiutil.daylog.ui.components.PlaceVisitTimelineItem
import com.aiutil.daylog.ui.components.PlacesInlineMapView
import com.aiutil.daylog.util.DateUtils

/**
 * 이동 장소 전용 화면:
 * 상단 달력에서 날짜를 터치하면 해당 날짜의 이동·체류 장소를 지도에 바로 띄우고 상세 목록을 표시한다.
 */
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
    val initialYm = remember(selectedDateKey) { DateUtils.yearMonthOf(selectedDateKey) }
    var year by rememberSaveable { mutableIntStateOf(initialYm.first) }
    var month by rememberSaveable { mutableIntStateOf(initialYm.second) }
    var calendarExpanded by rememberSaveable { mutableStateOf(true) }

    var focusedPinIdx by remember(selectedDateKey) { mutableStateOf<Int?>(null) }
    var focusTrigger by remember(selectedDateKey) { mutableIntStateOf(0) }
    var aliasTargetVisit by remember { mutableStateOf<PlaceVisit?>(null) }

    val (leadingEmpty, dayKeys) = remember(year, month) {
        DateUtils.monthGridDays(year, month)
    }
    val totalCells = leadingEmpty + dayKeys.size
    val rows = (totalCells + 6) / 7

    val selectedRecord = recordsByDate[selectedDateKey]
    val dayPlaces = selectedRecord?.places.orEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. 상단 월간 달력 카드 (날짜 터치로 즉시 이동 장소 조회)
        item(key = "places_calendar_card") {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    if (month == 1) {
                                        year -= 1
                                        month = 12
                                    } else {
                                        month -= 1
                                    }
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Default.ChevronLeft, contentDescription = "이전 달")
                            }

                            Text(
                                text = "${year}년 ${month}월",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )

                            IconButton(
                                onClick = {
                                    if (month == 12) {
                                        year += 1
                                        month = 1
                                    } else {
                                        month += 1
                                    }
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Default.ChevronRight, contentDescription = "다음 달")
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    val ym = DateUtils.yearMonthOf(todayKey)
                                    year = ym.first
                                    month = ym.second
                                    onSelectDate(todayKey)
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text("오늘", style = MaterialTheme.typography.labelMedium)
                            }
                            IconButton(
                                onClick = { onRefreshDate(selectedDateKey) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "새로고침", modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                onClick = { calendarExpanded = !calendarExpanded },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = if (calendarExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (calendarExpanded) "달력 접기" else "달력 펼치기",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }

                    if (calendarExpanded) {
                        Spacer(modifier = Modifier.height(6.dp))

                        Row(modifier = Modifier.fillMaxWidth()) {
                            listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { idx, w ->
                                Text(
                                    text = w,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (idx == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (idx == 0) FontWeight.SemiBold else FontWeight.Normal,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            for (r in 0 until rows) {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    for (c in 0 until 7) {
                                        val cellIdx = r * 7 + c
                                        val dayIdx = cellIdx - leadingEmpty
                                        if (dayIdx !in dayKeys.indices) {
                                            Spacer(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .aspectRatio(1.05f),
                                            )
                                        } else {
                                            val dKey = dayKeys[dayIdx]
                                            val dayNum = (dayIdx + 1).toString()
                                            val rec = recordsByDate[dKey]
                                            val placeCount = rec?.places?.size ?: 0
                                            val isSelected = dKey == selectedDateKey
                                            val isToday = dKey == todayKey

                                            val bg = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                                            val fg = when {
                                                isSelected -> MaterialTheme.colorScheme.onPrimary
                                                dKey > todayKey -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                                else -> MaterialTheme.colorScheme.onSurface
                                            }
                                            val borderMod = if (isToday && !isSelected) {
                                                Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                                            } else {
                                                Modifier
                                            }

                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .aspectRatio(1.05f)
                                                    .padding(1.5.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(bg)
                                                    .then(borderMod)
                                                    .clickable(enabled = dKey <= todayKey) { onSelectDate(dKey) },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Column(
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.Center,
                                                ) {
                                                    Text(
                                                        text = dayNum,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontWeight = if (isSelected || isToday || placeCount > 0) FontWeight.Bold else FontWeight.Normal,
                                                        color = fg,
                                                    )
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Box(
                                                        modifier = Modifier
                                                            .size(4.dp)
                                                            .clip(CircleShape)
                                                            .background(
                                                                if (placeCount > 0) {
                                                                    if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                                                                } else {
                                                                    Color.Transparent
                                                                }
                                                            ),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "선택됨: ${DateUtils.formatKoreanDate(selectedDateKey)} (달력 펼치기를 눌러 변경)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                        )
                    }
                }
            }
        }

        // 2. 선택한 날짜의 이동 · 체류 지도 바로 표시 (핀치 줌 + 드래그 지원)
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
                                "${DateUtils.formatShortKoreanDate(selectedDateKey)} 지도"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (dayPlaces.isNotEmpty()) {
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
