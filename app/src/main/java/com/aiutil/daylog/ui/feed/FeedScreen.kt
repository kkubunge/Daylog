package com.aiutil.daylog.ui.feed

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiutil.daylog.model.ActivityType
import com.aiutil.daylog.model.DayRecord
import com.aiutil.daylog.ui.components.SummaryMetricStrip
import com.aiutil.daylog.util.DateUtils

private enum class FeedFilter(val label: String) {
    ALL("전체"),
    PLACE("장소"),
    PHOTO("사진"),
    CALL("통화"),
    SMS("문자"),
    SNS("SNS"),
}

@Composable
fun FeedScreen(
    records: List<DayRecord>,
    onSelectDateInCalendar: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(FeedFilter.ALL) }

    val filtered = remember(records, query, filter) {
        val q = query.trim().lowercase()
        records.filter { rec ->
            if (!rec.hasAnyData) return@filter false
            val matchType = when (filter) {
                FeedFilter.ALL -> true
                FeedFilter.PLACE -> rec.places.isNotEmpty()
                FeedFilter.PHOTO -> rec.photos.isNotEmpty()
                FeedFilter.CALL -> rec.activities.any { it.type == ActivityType.CALL }
                FeedFilter.SMS -> rec.activities.any { it.type == ActivityType.SMS }
                FeedFilter.SNS -> rec.activities.any { it.type == ActivityType.SNS }
            }
            if (!matchType) return@filter false
            if (q.isEmpty()) return@filter true

            rec.dateKey.contains(q) ||
                rec.quickNote.lowercase().contains(q) ||
                rec.places.any { it.placeName.lowercase().contains(q) || it.address.lowercase().contains(q) } ||
                rec.photos.any { it.placeLabel.lowercase().contains(q) || it.displayName.lowercase().contains(q) } ||
                rec.activities.any { it.title.lowercase().contains(q) || it.detail.lowercase().contains(q) }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "feed_search") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("장소, 연락처, 문자·SNS 내용, 날짜 검색…", style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FeedFilter.entries.forEach { f ->
                        FilterChip(
                            selected = f == filter,
                            onClick = { filter = f },
                            label = { Text(f.label, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            item(key = "feed_empty") {
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
                        Text("조회된 날짜별 데이터가 없습니다", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "달력에서 날짜를 누르거나 설정에서 '최근 7일/30일 일괄 스캔'을 실행하면 기록이 쌓입니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(onClick = onOpenSettings) {
                            Text("일괄 스캔 및 설정 열기")
                        }
                    }
                }
            }
        } else {
            items(filtered, key = { it.dateKey }) { rec ->
                val calls = rec.activities.count { it.type == ActivityType.CALL }
                val sms = rec.activities.count { it.type == ActivityType.SMS }
                val sns = rec.activities.count { it.type == ActivityType.SNS }

                Card(
                    onClick = { onOpenDetail(rec.dateKey) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(14.dp),
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
                            Text(
                                text = DateUtils.formatKoreanDate(rec.dateKey),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            if (rec.totalMoveMeters > 0) {
                                Text(
                                    text = "이동 ${DateUtils.formatDistanceKorean(rec.totalMoveMeters)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        SummaryMetricStrip(
                            placeCount = rec.places.size,
                            photoCount = rec.photos.size,
                            callCount = calls,
                            smsCount = sms,
                            snsCount = sns,
                        )

                        if (rec.places.isNotEmpty()) {
                            val routeText = rec.places.joinToString(" → ") { it.placeName }
                            Text(
                                text = "경로: $routeText",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedButton(
                                onClick = { onSelectDateInCalendar(rec.dateKey) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Text("달력에서 보기", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
