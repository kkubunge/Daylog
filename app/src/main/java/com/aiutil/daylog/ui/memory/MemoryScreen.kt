package com.aiutil.daylog.ui.memory

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiutil.daylog.data.MemoryRecallEngine
import com.aiutil.daylog.model.DayEntry
import com.aiutil.daylog.model.DayStats
import com.aiutil.daylog.model.MemoryCard
import com.aiutil.daylog.ui.components.HeroPhotoPair
import com.aiutil.daylog.ui.components.SourceBadgeRow
import com.aiutil.daylog.ui.timeline.DayMagazineCard
import com.aiutil.daylog.util.DateUtils

@Composable
fun MemoryScreen(
    todayKey: String,
    allEntries: List<DayEntry>,
    memories: List<MemoryCard>,
    stats: DayStats,
    onOpenDayDetail: (String) -> Unit,
    onToggleBookmark: (String) -> Unit,
    onPhotoClick: (String) -> Unit,
) {
    var randomMemory by remember(todayKey, allEntries.size) {
        mutableStateOf(MemoryRecallEngine.pickRandomMemory(todayKey, allEntries))
    }

    val bookmarkedEntries = remember(allEntries) {
        allEntries.filter { it.isBookmarked }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. 랜덤 추억 뽑기 카드
        item(key = "random_memory") {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.52f),
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
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
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "랜덤 추억 회상",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Button(
                            onClick = {
                                randomMemory = MemoryRecallEngine.pickRandomMemory(
                                    todayKey = todayKey,
                                    allEntries = allEntries,
                                    excludeDateKey = randomMemory?.entry?.dateKey,
                                ) ?: MemoryRecallEngine.pickRandomMemory(todayKey, allEntries)
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.Default.Casino, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("다른 추억 꺼내기", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    val picked = randomMemory
                    if (picked == null) {
                        Text(
                            text = "과거 날짜에 기록이 쌓이면 어느 날의 기억을 랜덤으로 꺼내 보여드릴게요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val e = picked.entry
                        Surface(
                            onClick = { onOpenDayDetail(e.dateKey) },
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(16.dp),
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
                                        text = "${picked.badgeText} · ${DateUtils.formatKoreanDate(e.dateKey)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(text = e.mood.emoji, style = MaterialTheme.typography.titleMedium)
                                }
                                if (e.heroPhotoUris.isNotEmpty()) {
                                    HeroPhotoPair(
                                        uris = e.heroPhotoUris,
                                        onPhotoClick = onPhotoClick,
                                        singleHeight = 165.dp,
                                        pairHeight = 135.dp,
                                    )
                                }
                                if (e.headline.isNotBlank()) {
                                    Text(
                                        text = e.headline,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                if (e.shortNote.isNotBlank()) {
                                    Text(
                                        text = e.shortNote,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                SourceBadgeRow(entry = e)
                            }
                        }
                    }
                }
            }
        }

        // 2. 나의 일상 발자취 통계
        item(key = "stats_card") {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "나의 데이로그 발자취",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatMiniBox("기록한 날", "${stats.totalDays}일", Modifier.weight(1f))
                        StatMiniBox("대표 사진", "${stats.totalHeroPhotos}장", Modifier.weight(1f))
                        StatMiniBox("책갈피", "${stats.bookmarkedDays}일", Modifier.weight(1f))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatMiniBox("통화 조각", "${stats.totalCalls}건", Modifier.weight(1f))
                        StatMiniBox("문자 조각", "${stats.totalSms}건", Modifier.weight(1f))
                        StatMiniBox("SNS 조각", "${stats.totalSns}건", Modifier.weight(1f))
                    }
                }
            }
        }

        // 3. 시간 여행 (1주 전 / 1개월 전 / 1년 전 오늘)
        if (memories.isNotEmpty()) {
            item(key = "time_travel_header") {
                Text(
                    text = "시간 여행 카드",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            items(memories, key = { "mem_${it.badgeText}_${it.entry.dateKey}" }) { card ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "✨ ${card.badgeText}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    DayMagazineCard(
                        entry = card.entry,
                        onClick = { onOpenDayDetail(card.entry.dateKey) },
                        onToggleBookmark = { onToggleBookmark(card.entry.dateKey) },
                        onPhotoClick = onPhotoClick,
                    )
                }
            }
        }

        // 4. 책갈피한 소중한 날들
        if (bookmarkedEntries.isNotEmpty()) {
            item(key = "bookmarked_header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "책갈피한 소중한 날들",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            items(bookmarkedEntries, key = { "bm_${it.dateKey}" }) { entry ->
                DayMagazineCard(
                    entry = entry,
                    onClick = { onOpenDayDetail(entry.dateKey) },
                    onToggleBookmark = { onToggleBookmark(entry.dateKey) },
                    onPhotoClick = onPhotoClick,
                )
            }
        }

        item(key = "mem_bottom_space") {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatMiniBox(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
