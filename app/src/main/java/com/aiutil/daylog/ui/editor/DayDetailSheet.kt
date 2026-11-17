package com.aiutil.daylog.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.aiutil.daylog.collector.SmsSpamFilter
import com.aiutil.daylog.model.ActivityItem
import com.aiutil.daylog.model.ActivityType
import com.aiutil.daylog.model.DayPhoto
import com.aiutil.daylog.model.DayRecord
import com.aiutil.daylog.model.PlaceVisit
import com.aiutil.daylog.ui.calendar.QuickNoteAddDialog
import com.aiutil.daylog.ui.calendar.SavePlaceAliasDialog
import com.aiutil.daylog.ui.components.ActivityLogRow
import com.aiutil.daylog.ui.components.DaylogPhotoImage
import com.aiutil.daylog.ui.components.PlaceVisitTimelineItem
import com.aiutil.daylog.ui.components.SummaryMetricStrip
import com.aiutil.daylog.ui.components.openLocationInMap
import com.aiutil.daylog.util.DateUtils

/**
 * 선택한 날짜의 전체 데이터(장소·사진 격자·시간순 활동·간단 메모)를 크게 조회하는 상세 시트.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayDetailSheet(
    dateKey: String,
    record: DayRecord?,
    isScanning: Boolean,
    isAiBusy: Boolean,
    stayThresholdMin: Int = 3,
    onDismiss: () -> Unit,
    onRefreshDate: () -> Unit,
    onSaveQuickNote: (String) -> Unit,
    onAddActivityNote: (title: String, detail: String) -> Unit,
    onDeleteActivity: (activityId: String) -> Unit,
    onSavePlaceAlias: (name: String, visit: PlaceVisit) -> Unit,
    onClassifyPlaceWithAi: (PlaceVisit) -> Unit,
    onSummarizeCall: (ActivityItem) -> Unit,
    onDeleteRecord: () -> Unit,
    onPhotoClick: (photos: List<DayPhoto>, index: Int) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val current = record ?: DayRecord(dateKey = dateKey)

    var quickNote by remember(current.dateKey, current.quickNote) {
        mutableStateOf(current.quickNote)
    }
    var showAddNoteDialog by remember { mutableStateOf(false) }
    var aliasTargetVisit by remember { mutableStateOf<PlaceVisit?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val paymentCount = current.activities.count { it.type == ActivityType.PAYMENT }
    val callCount = current.activities.count { it.type == ActivityType.CALL }
    val smsCount = current.activities.count { it.type == ActivityType.SMS }
    val snsCount = current.activities.count { it.type == ActivityType.SNS }
    val photoRows = remember(current.photos) { current.photos.chunked(3) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "detail_header") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = DateUtils.formatKoreanDate(dateKey),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        val subParts = buildList {
                            if (current.totalMoveMeters > 0) {
                                add("총 이동 약 ${DateUtils.formatDistanceKorean(current.totalMoveMeters)}")
                            }
                            if (current.totalPaymentWon > 0L) {
                                add("결제 ${SmsSpamFilter.formatWon(current.totalPaymentWon)}")
                            }
                        }
                        Text(
                            text = if (subParts.isNotEmpty()) subParts.joinToString(" · ") else "일자별 전체 활동 및 위치 내역",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onRefreshDate, enabled = !isScanning) {
                        if (isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "새로고침")
                        }
                    }
                    if (record != null) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "기록 삭제")
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "닫기")
                    }
                }
            }

            item(key = "detail_metrics") {
                SummaryMetricStrip(
                    placeCount = current.places.size,
                    photoCount = current.photos.size,
                    paymentCount = paymentCount,
                    callCount = callCount,
                    smsCount = smsCount,
                    snsCount = snsCount,
                )
            }

            // 1. 이동 및 체류 장소 전체
            if (current.places.isNotEmpty()) {
                item(key = "detail_places_header") {
                    Text(
                        text = "이동 및 체류 장소 (${current.places.size})",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(current.places.size, key = { idx -> "dp_${current.places[idx].id}" }) { idx ->
                    val visit = current.places[idx]
                    PlaceVisitTimelineItem(
                        visit = visit,
                        isLast = idx == current.places.lastIndex,
                        stayThresholdMin = stayThresholdMin,
                        onOpenMap = { v -> openLocationInMap(context, v.latitude, v.longitude, v.placeName) },
                        onSaveAlias = { v -> aliasTargetVisit = v },
                        onClassifyWithAi = onClassifyPlaceWithAi,
                    )
                }
            }

            // 2. 촬영 사진 3열 격자
            if (current.photos.isNotEmpty()) {
                item(key = "detail_photos_header") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                    Text(
                        text = "사진 (${current.photos.size})",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(photoRows.size, key = { r -> "pr_$r" }) { rowIdx ->
                    val rowPhotos = photoRows[rowIdx]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        rowPhotos.forEachIndexed { colIdx, photo ->
                            val flatIndex = rowIdx * 3 + colIdx
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onPhotoClick(current.photos, flatIndex) },
                            ) {
                                DaylogPhotoImage(
                                    uri = photo.uri,
                                    maxDim = 380,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                                )
                                Text(
                                    text = DateUtils.formatTimeHm(photo.dateTakenMs) +
                                        if (photo.placeLabel.isNotBlank()) " · ${photo.placeLabel}" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                        repeat(3 - rowPhotos.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            // 3. 시간순 활동 내역 (통화·문자·SNS·메모)
            item(key = "detail_activities_header") {
                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "시간순 활동 내역 (${current.activities.size})",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    OutlinedButton(
                        onClick = { showAddNoteDialog = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("메모 추가", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (current.activities.isEmpty()) {
                item(key = "detail_activities_empty") {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "기록된 통화·문자·SNS 활동이 없습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            } else {
                items(current.activities, key = { "da_${it.id}" }) { item ->
                    ActivityLogRow(
                        item = item,
                        isAiBusy = isAiBusy,
                        onSummarizeCall = onSummarizeCall,
                        onDelete = if (item.type == ActivityType.NOTE) {
                            { onDeleteActivity(item.id) }
                        } else {
                            null
                        },
                    )
                }
            }

            // 4. 선택적 간단 메모 필드
            item(key = "detail_quick_note") {
                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = quickNote,
                        onValueChange = { quickNote = it },
                        label = { Text("이 날의 참고 메모 (선택)") },
                        minLines = 2,
                        maxLines = 4,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { onSaveQuickNote(quickNote) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("메모 저장")
                    }
                }
            }

            item(key = "detail_bottom") {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showAddNoteDialog) {
        QuickNoteAddDialog(
            onDismiss = { showAddNoteDialog = false },
            onSave = { title, detail ->
                onAddActivityNote(title, detail)
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

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("이 날의 저장된 기록을 지울까요?") },
            text = { Text("${DateUtils.formatKoreanDate(dateKey)}의 조회 캐시와 메모가 삭제됩니다. 폰 원본 사진·통화·문자는 삭제되지 않습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDeleteRecord()
                }) {
                    Text("삭제", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("취소") }
            },
        )
    }
}
