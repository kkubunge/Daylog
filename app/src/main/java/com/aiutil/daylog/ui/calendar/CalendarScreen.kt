package com.aiutil.daylog.ui.calendar

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.aiutil.daylog.model.DayEntry
import com.aiutil.daylog.ui.components.HeroPhotoPair
import com.aiutil.daylog.ui.components.SourceBadgeRow
import com.aiutil.daylog.util.DateUtils

@Composable
fun CalendarScreen(
    todayKey: String,
    entriesByDate: Map<String, DayEntry>,
    onOpenDayDetail: (String) -> Unit,
    onCollectDate: (String) -> Unit,
    onPhotoClick: (String) -> Unit,
) {
    val initialYm = remember(todayKey) { DateUtils.yearMonthOf(todayKey) }
    var year by rememberSaveable { mutableIntStateOf(initialYm.first) }
    var month by rememberSaveable { mutableIntStateOf(initialYm.second) }
    var selectedDateKey by rememberSaveable { mutableStateOf(todayKey) }

    val (leadingEmpty, dayKeys) = remember(year, month) {
        DateUtils.monthGridDays(year, month)
    }

    val totalCells = leadingEmpty + dayKeys.size
    val rows = (totalCells + 6) / 7
    val selectedEntry = entriesByDate[selectedDateKey]

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 월 이동 헤더
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
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
                    )

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

                Spacer(modifier = Modifier.height(6.dp))

                // 요일 라벨
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { idx, label ->
                        Text(
                            text = label,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = when (idx) {
                                0 -> MaterialTheme.colorScheme.primary
                                6 -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 달력 날짜 그리드
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
                                val entry = entriesByDate[dateKey]
                                val isSelected = dateKey == selectedDateKey
                                val isToday = dateKey == todayKey
                                val hasPhoto = entry?.heroPhotoUris?.isNotEmpty() == true

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(0.95f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            when {
                                                isSelected -> MaterialTheme.colorScheme.primaryContainer
                                                entry != null -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                                else -> Color.Transparent
                                            }
                                        )
                                        .then(
                                            if (isToday && !isSelected) {
                                                Modifier.border(
                                                    1.2.dp,
                                                    MaterialTheme.colorScheme.primary,
                                                    RoundedCornerShape(10.dp),
                                                )
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .clickable { selectedDateKey = dateKey }
                                        .padding(2.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = dayNum.toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                        if (entry != null) {
                                            Text(
                                                text = entry.mood.emoji,
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                            if (hasPhoto) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(5.dp)
                                                        .clip(CircleShape)
                                                        .background(MaterialTheme.colorScheme.primary),
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

        // 선택된 날짜의 요약 카드
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(18.dp),
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
                    Text(
                        text = DateUtils.formatKoreanDate(selectedDateKey),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (selectedEntry != null) {
                        Text(text = "${selectedEntry.mood.emoji} ${selectedEntry.mood.label}", style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (selectedEntry == null) {
                    Text(
                        text = "이 날의 기록이 아직 없어요. 폰에 남아있는 사진·통화·문자로 자동 취합하거나 직접 짧은 일기를 남겨보세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onCollectDate(selectedDateKey) },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("이 날 폰 데이터 취합")
                        }
                        OutlinedButton(onClick = { onOpenDayDetail(selectedDateKey) }) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("직접 작성")
                        }
                    }
                } else {
                    if (selectedEntry.heroPhotoUris.isNotEmpty()) {
                        HeroPhotoPair(
                            uris = selectedEntry.heroPhotoUris,
                            onPhotoClick = onPhotoClick,
                            singleHeight = 165.dp,
                            pairHeight = 135.dp,
                        )
                    }
                    if (selectedEntry.headline.isNotBlank()) {
                        Text(
                            text = selectedEntry.headline,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (selectedEntry.shortNote.isNotBlank()) {
                        Text(
                            text = selectedEntry.shortNote,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    SourceBadgeRow(entry = selectedEntry)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(onClick = { onOpenDayDetail(selectedDateKey) }) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("이 날 일기 편집")
                        }
                    }
                }
            }
        }
    }
}
