package com.aiutil.daylog.ui.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiutil.daylog.collector.SmsSpamFilter
import com.aiutil.daylog.model.ActivityItem
import com.aiutil.daylog.model.ActivityType
import com.aiutil.daylog.model.DayPhoto
import com.aiutil.daylog.model.DayRecord
import com.aiutil.daylog.model.PermissionStatus
import com.aiutil.daylog.model.PlaceVisit
import com.aiutil.daylog.ui.components.ActivityLogRow
import com.aiutil.daylog.ui.components.DaylogPhotoImage
import com.aiutil.daylog.ui.components.PlaceVisitTimelineItem
import com.aiutil.daylog.ui.components.SummaryMetricStrip
import com.aiutil.daylog.ui.components.openLocationInMap
import com.aiutil.daylog.util.DateUtils

private enum class DaySectionFilter(val label: String) {
    ALL("전체"),
    PLACE("장소"),
    PHOTO("사진"),
    PAYMENT("결제"),
    CALL("통화"),
    SMS("문자"),
    SNS("SNS"),
}

@Composable
fun CalendarScreen(
    todayKey: String,
    selectedDateKey: String,
    recordsByDate: Map<String, DayRecord>,
    isScanning: Boolean,
    isAiBusy: Boolean,
    hasGeminiKey: Boolean,
    permissionStatus: PermissionStatus,
    stayThresholdMin: Int = 3,
    onSelectDate: (String) -> Unit,
    onRefreshDate: (String) -> Unit,
    onGenerateAiSummary: (String) -> Unit,
    onOpenFullDetail: (String) -> Unit,
    onCheckInCurrentLocation: (String) -> Unit,
    onAddNote: (dateKey: String, title: String, detail: String) -> Unit,
    onDeleteNote: (dateKey: String, activityId: String) -> Unit,
    onSavePlaceAlias: (name: String, visit: PlaceVisit) -> Unit,
    onClassifyPlaceWithAi: (PlaceVisit) -> Unit,
    onSummarizeCall: (ActivityItem) -> Unit,
    onPhotoClick: (photos: List<DayPhoto>, index: Int) -> Unit,
    onOpenPlacesMap: (title: String, places: List<PlaceVisit>) -> Unit = { _, _ -> },
    onOpenPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val initialYm = remember(selectedDateKey) { DateUtils.yearMonthOf(selectedDateKey) }
    var year by rememberSaveable { mutableIntStateOf(initialYm.first) }
    var month by rememberSaveable { mutableIntStateOf(initialYm.second) }
    var activeFilter by rememberSaveable { mutableStateOf(DaySectionFilter.ALL) }
    var placesExpanded by rememberSaveable { mutableStateOf(true) }
    var activitiesExpanded by rememberSaveable { mutableStateOf(true) }

    var showAddNoteDialog by remember { mutableStateOf(false) }
    var aliasTargetVisit by remember { mutableStateOf<PlaceVisit?>(null) }

    val (leadingEmpty, dayKeys) = remember(year, month) {
        DateUtils.monthGridDays(year, month)
    }
    val totalCells = leadingEmpty + dayKeys.size
    val rows = (totalCells + 6) / 7

    val selectedRecord = recordsByDate[selectedDateKey]
    val places = selectedRecord?.places.orEmpty()
    val photos = selectedRecord?.photos.orEmpty()
    val activities = selectedRecord?.activities.orEmpty()
    val paymentCount = activities.count { it.type == ActivityType.PAYMENT }
    val callCount = activities.count { it.type == ActivityType.CALL }
    val smsCount = activities.count { it.type == ActivityType.SMS }
    val snsCount = activities.count { it.type == ActivityType.SNS }

    val filteredActivities = remember(activities, activeFilter) {
        when (activeFilter) {
            DaySectionFilter.ALL -> activities
            DaySectionFilter.PAYMENT -> activities.filter { it.type == ActivityType.PAYMENT }
            DaySectionFilter.CALL -> activities.filter { it.type == ActivityType.CALL }
            DaySectionFilter.SMS -> activities.filter { it.type == ActivityType.SMS }
            DaySectionFilter.SNS -> activities.filter { it.type == ActivityType.SNS }
            else -> emptyList()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 권한 미연결 시 미니멀 안내 바
        if (!permissionStatus.anyDataGranted) {
            item(key = "perm_hint") {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = "사진·위치·통화·문자 권한을 허용하면 날짜 선택 시 해당 날의 기록을 자동으로 불러옵니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = onOpenPermissions) {
                            Text("권한 설정", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // 1. 상단 깔끔한 모노톤 월간 달력
        item(key = "calendar_card") {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        IconButton(onClick = {
                            if (month == 1) {
                                year -= 1
                                month = 12
                            } else {
                                month -= 1
                            }
                        }) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = "이전 달")
                        }

                        Text(
                            text = "${year}년 ${month}월",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                        )

                        TextButton(onClick = {
                            val ym = DateUtils.yearMonthOf(todayKey)
                            year = ym.first
                            month = ym.second
                            onSelectDate(todayKey)
                        }) {
                            Text("오늘", style = MaterialTheme.typography.labelMedium)
                        }

                        IconButton(onClick = {
                            if (month == 12) {
                                year += 1
                                month = 1
                            } else {
                                month += 1
                            }
                        }) {
                            Icon(Icons.Default.ChevronRight, contentDescription = "다음 달")
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // 요일 헤더
                    Row(modifier = Modifier.fillMaxWidth()) {
                        listOf("일", "월", "화", "수", "목", "금", "토").forEach { dayLabel ->
                            Text(
                                text = dayLabel,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // 날짜 그리드
                    for (row in 0 until rows) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            for (col in 0 until 7) {
                                val cellIndex = row * 7 + col
                                val dayIdx = cellIndex - leadingEmpty
                                if (dayIdx in dayKeys.indices) {
                                    val dateKey = dayKeys[dayIdx]
                                    val dayNum = dayIdx + 1
                                    val rec = recordsByDate[dateKey]
                                    val isSelected = dateKey == selectedDateKey
                                    val isToday = dateKey == todayKey
                                    val dotCount = when {
                                        rec == null || !rec.hasAnyData -> 0
                                        (rec.places.isNotEmpty() && rec.photos.isNotEmpty() && rec.activities.isNotEmpty()) -> 3
                                        (rec.places.isNotEmpty() || rec.photos.isNotEmpty()) && rec.activities.isNotEmpty() -> 2
                                        else -> 1
                                    }

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1.02f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primary
                                                else Color.Transparent
                                            )
                                            .then(
                                                if (isToday && !isSelected) {
                                                    Modifier.border(
                                                        1.dp,
                                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                                        RoundedCornerShape(10.dp),
                                                    )
                                                } else {
                                                    Modifier
                                                }
                                            )
                                            .clickable { onSelectDate(dateKey) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center,
                                        ) {
                                            Text(
                                                text = dayNum.toString(),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                                                color = when {
                                                    isSelected -> MaterialTheme.colorScheme.onPrimary
                                                    col == 0 -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                                                    else -> MaterialTheme.colorScheme.onSurface
                                                },
                                            )
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                                modifier = Modifier.height(5.dp),
                                            ) {
                                                repeat(dotCount) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(4.dp)
                                                            .clip(CircleShape)
                                                            .background(
                                                                if (isSelected) {
                                                                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                                                                } else {
                                                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                                                }
                                                            ),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }

        // 2. 선택한 날짜 헤더 & 액션
        item(key = "selected_header") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = DateUtils.formatKoreanDate(selectedDateKey),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    val subParts = buildList {
                        selectedRecord?.totalMoveMeters?.takeIf { it > 0 }?.let {
                            add("이동 약 ${DateUtils.formatDistanceKorean(it)}")
                        }
                        selectedRecord?.totalPaymentWon?.takeIf { it > 0L }?.let {
                            add("결제 ${SmsSpamFilter.formatWon(it)}")
                        }
                    }
                    Text(
                        text = if (subParts.isNotEmpty()) subParts.joinToString(" · ") else "날짜를 누르면 해당 날의 데이터를 조회합니다",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(onClick = { onRefreshDate(selectedDateKey) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "데이터 새로고침")
                    }
                }

                IconButton(onClick = { onOpenFullDetail(selectedDateKey) }) {
                    Icon(Icons.Default.OpenInFull, contentDescription = "상세 크게 보기")
                }
            }
        }

        // 3. 요약 건수 바
        item(key = "summary_strip") {
            SummaryMetricStrip(
                placeCount = places.size,
                photoCount = photos.size,
                paymentCount = paymentCount,
                callCount = callCount,
                smsCount = smsCount,
                snsCount = snsCount,
            )
        }

        // 4. 섹션 필터 (오늘일 때 현재 위치 기록 빠른 버튼 포함)
        item(key = "filter_and_quick") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectedDateKey == todayKey) {
                    OutlinedButton(
                        onClick = { onCheckInCurrentLocation(selectedDateKey) },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("현재 위치 기록", style = MaterialTheme.typography.labelMedium)
                    }
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DaySectionFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = filter == activeFilter,
                            onClick = { activeFilter = filter },
                            label = { Text(filter.label, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
            }
        }

        // 데이터가 아예 없을 때
        if (selectedRecord == null || !selectedRecord.hasAnyData) {
            item(key = "empty_day") {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(
                            text = "이 날 조회된 기록이 없습니다",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "새로고침을 눌러 폰 데이터를 스캔하거나 메모·현재 위치를 남길 수 있습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(onClick = { onRefreshDate(selectedDateKey) }) {
                            Text("이 날 데이터 불러오기")
                        }
                    }
                }
            }
        } else {
            // 5. 이동 · 체류 장소 타임라인 (전체 지도 보기 + 펼치기/접기)
            if ((activeFilter == DaySectionFilter.ALL || activeFilter == DaySectionFilter.PLACE) && places.isNotEmpty()) {
                item(key = "places_header") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { placesExpanded = !placesExpanded }
                            .padding(vertical = 2.dp),
                    ) {
                        Text(
                            text = "이동 및 체류 장소 (${places.size})",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    onOpenPlacesMap(
                                        "${DateUtils.formatShortKoreanDate(selectedDateKey)} 이동 지도",
                                        places,
                                    )
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp),
                            ) {
                                Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("전체 지도", style = MaterialTheme.typography.labelSmall)
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { placesExpanded = !placesExpanded }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = if (placesExpanded) "접기" else "펼치기",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Icon(
                                    imageVector = if (placesExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (placesExpanded) "접기" else "펼치기",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
                if (placesExpanded) {
                    items(places.size, key = { idx -> places[idx].id }) { idx ->
                        val visit = places[idx]
                        PlaceVisitTimelineItem(
                            visit = visit,
                            isLast = idx == places.lastIndex,
                            stayThresholdMin = stayThresholdMin,
                            onOpenMap = { v -> openLocationInMap(context, v.latitude, v.longitude, v.placeName) },
                            onSaveAlias = { v -> aliasTargetVisit = v },
                            onClassifyWithAi = onClassifyPlaceWithAi,
                        )
                    }
                } else {
                    item(key = "places_collapsed_hint") {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { placesExpanded = true },
                        ) {
                            val preview = places.take(2).joinToString(" → ") { it.nearbyPoi.ifBlank { it.placeName } }
                            Text(
                                text = "${preview}${if (places.size > 2) " 외 ${places.size - 2}곳" else ""} · 눌러서 펼치기",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }

            // 6. 그날의 사진 스트립
            if ((activeFilter == DaySectionFilter.ALL || activeFilter == DaySectionFilter.PHOTO) && photos.isNotEmpty()) {
                item(key = "photos_header") {
                    Text(
                        text = "촬영 · 저장 사진 (${photos.size})",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                item(key = "photos_strip") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(photos, key = { _, p -> p.uri }) { idx, photo ->
                            Column(
                                modifier = Modifier
                                    .width(106.dp)
                                    .clickable { onPhotoClick(photos, idx) },
                            ) {
                                DaylogPhotoImage(
                                    uri = photo.uri,
                                    maxDim = 360,
                                    modifier = Modifier
                                        .size(106.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = DateUtils.formatTimeHm(photo.dateTakenMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val locLabel = listOf(photo.cityName, photo.placeLabel)
                                    .filter { it.isNotBlank() }
                                    .distinct()
                                    .joinToString(" · ")
                                if (locLabel.isNotBlank()) {
                                    Text(
                                        text = locLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 7. 시간순 활동 내역 (펼치기/접기)
            if (filteredActivities.isNotEmpty()) {
                item(key = "activities_header") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { activitiesExpanded = !activitiesExpanded }
                            .padding(vertical = 2.dp),
                    ) {
                        Text(
                            text = "활동 내역 (${filteredActivities.size})",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (activitiesExpanded) "접기" else "펼치기",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Icon(
                                imageVector = if (activitiesExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (activitiesExpanded) "접기" else "펼치기",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                if (activitiesExpanded) {
                    items(filteredActivities, key = { it.id }) { item ->
                        ActivityLogRow(
                            item = item,
                            isAiBusy = isAiBusy,
                            onSummarizeCall = onSummarizeCall,
                            onDelete = if (item.type == ActivityType.NOTE) {
                                { onDeleteNote(selectedDateKey, item.id) }
                            } else {
                                null
                            },
                        )
                    }
                } else {
                    item(key = "activities_collapsed_hint") {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { activitiesExpanded = true },
                        ) {
                            Text(
                                text = "결제·통화·문자·SNS ${filteredActivities.size}건 · 눌러서 펼치기",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        }

        // 8. 맨 밑: AI 데이터 브리핑 + 간단 메모 추가
        if (selectedRecord != null && selectedRecord.hasAnyData) {
            item(key = "ai_briefing_card") {
                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "AI 데이터 브리핑",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            TextButton(
                                onClick = { onGenerateAiSummary(selectedDateKey) },
                                enabled = !isAiBusy,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                if (isAiBusy) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                } else {
                                    Text(
                                        text = when {
                                            !hasGeminiKey -> "API 키 설정"
                                            selectedRecord.aiSummary.isBlank() -> "요약 생성"
                                            else -> "다시 요약"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                        if (selectedRecord.aiSummary.isNotBlank()) {
                            Text(
                                text = selectedRecord.aiSummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        } else {
                            Text(
                                text = "이동 동선, 통화, 문자/SNS 속 핵심 일정·정보만 깔끔하게 요약해 줍니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item(key = "bottom_quick_note_add") {
            OutlinedButton(
                onClick = { showAddNoteDialog = true },
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("간단 메모 추가", style = MaterialTheme.typography.labelMedium)
            }
        }

        item(key = "cal_bottom_spacer") {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showAddNoteDialog) {
        QuickNoteAddDialog(
            onDismiss = { showAddNoteDialog = false },
            onSave = { title, detail ->
                onAddNote(selectedDateKey, title, detail)
                showAddNoteDialog = false
            },
        )
    }

    val targetVisit = aliasTargetVisit
    if (targetVisit != null) {
        SavePlaceAliasDialog(
            initialName = targetVisit.placeName,
            address = targetVisit.address,
            onDismiss = { aliasTargetVisit = null },
            onSave = { aliasName ->
                onSavePlaceAlias(aliasName, targetVisit)
                aliasTargetVisit = null
            },
        )
    }
}

@Composable
fun QuickNoteAddDialog(
    onDismiss: () -> Unit,
    onSave: (title: String, detail: String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("간단 메모 남기기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("항목명 (예: 미팅, 병원, 지출)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = detail,
                    onValueChange = { detail = it },
                    label = { Text("내용") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(title.trim().ifBlank { "메모" }, detail.trim())
                },
                enabled = detail.isNotBlank() || title.isNotBlank(),
            ) {
                Text("저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        },
    )
}

@Composable
fun SavePlaceAliasDialog(
    initialName: String,
    address: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("장소 이름 저장") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "이 반경(150m)에 머무를 때마다 지정한 이름으로 자동 표시됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (address.isNotBlank()) {
                    Text(
                        text = address,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("장소 이름 (예: 집, 회사, 헬스장)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name.trim()) },
                enabled = name.isNotBlank(),
            ) {
                Text("저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        },
    )
}
