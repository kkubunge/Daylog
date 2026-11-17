package com.aiutil.daylog.ui.places

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiutil.daylog.model.DayRecord
import com.aiutil.daylog.model.PlaceVisit
import com.aiutil.daylog.model.SavedPlaceAlias
import com.aiutil.daylog.ui.calendar.SavePlaceAliasDialog
import com.aiutil.daylog.ui.components.PlaceVisitTimelineItem
import com.aiutil.daylog.ui.components.openLocationInMap
import com.aiutil.daylog.util.DateUtils

@Composable
fun PlacesScreen(
    records: List<DayRecord>,
    aliases: List<SavedPlaceAlias>,
    isTrackingRunning: Boolean,
    hasLocationPermission: Boolean,
    stayThresholdMin: Int = 3,
    onToggleTracking: () -> Unit,
    onCheckInNow: () -> Unit,
    onSaveAlias: (name: String, visit: PlaceVisit) -> Unit,
    onClassifyPlaceWithAi: (PlaceVisit) -> Unit,
    onDeleteAlias: (aliasId: String) -> Unit,
    onOpenDate: (String) -> Unit,
) {
    val context = LocalContext.current
    var aliasTargetVisit by remember { mutableStateOf<PlaceVisit?>(null) }

    val recentVisits = remember(records) {
        records
            .flatMap { it.places }
            .sortedByDescending { it.startMs }
            .take(50)
    }

    val topPlaces = remember(records) {
        records
            .flatMap { it.places }
            .groupBy { it.placeName }
            .map { (name, list) ->
                Triple(name, list.size, list.sumOf { it.durationMinutes })
            }
            .sortedByDescending { it.second * 10 + it.third }
            .take(8)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. GPS 위치 추적 상태 컨트롤 카드
        item(key = "location_status_card") {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isTrackingRunning) "이동·체류 GPS 자동 기록 중" else "GPS 자동 기록 꺼짐",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = if (hasLocationPermission) {
                                    "150m 이상 이동 시 새 장소를 기록하고 체류 시간을 자동 계산합니다."
                                } else {
                                    "위치 권한을 허용하면 백그라운드 이동·체류 장소를 자동으로 남깁니다."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onToggleTracking,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = if (isTrackingRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isTrackingRunning) "기록 중지" else "자동 기록 시작")
                        }
                        OutlinedButton(
                            onClick = onCheckInNow,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("현재 위치 기록")
                        }
                    }
                }
            }
        }

        // 2. 저장된 장소 별칭 (집, 회사 등)
        if (aliases.isNotEmpty()) {
            item(key = "aliases_header") {
                Text(
                    text = "등록된 장소 이름 (${aliases.size})",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            items(aliases, key = { it.id }) { alias ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
                        IconButton(onClick = { onDeleteAlias(alias.id) }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "별칭 삭제", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        // 3. 자주 머문 장소 요약
        if (topPlaces.isNotEmpty()) {
            item(key = "top_places_header") {
                Text(
                    text = "자주 방문한 장소",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            item(key = "top_places_list") {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        topPlaces.forEach { (name, count, totalMins) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = if (totalMins > 0) "${count}회 · ${DateUtils.formatStayMinutes(totalMins)}" else "${count}회",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. 최근 방문 · 체류 기록
        item(key = "recent_visits_header") {
            Text(
                text = "최근 방문 및 체류 장소 (${recentVisits.size})",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        if (recentVisits.isEmpty()) {
            item(key = "recent_visits_empty") {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "아직 기록된 위치가 없습니다. '자동 기록 시작'을 켜거나 사진 EXIF 위치가 포함된 날짜를 달력에서 눌러 보세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        } else {
            items(recentVisits, key = { it.id }) { visit ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = DateUtils.formatShortKoreanDate(visit.dateKey),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .clickable { onOpenDate(visit.dateKey) },
                    )
                    PlaceVisitTimelineItem(
                        visit = visit,
                        isLast = true,
                        stayThresholdMin = stayThresholdMin,
                        onOpenMap = { v -> openLocationInMap(context, v.latitude, v.longitude, v.placeName) },
                        onSaveAlias = { v -> aliasTargetVisit = v },
                        onClassifyWithAi = onClassifyPlaceWithAi,
                    )
                }
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
            onSave = { aliasName ->
                onSaveAlias(aliasName, targetVisit)
                aliasTargetVisit = null
            },
        )
    }
}
