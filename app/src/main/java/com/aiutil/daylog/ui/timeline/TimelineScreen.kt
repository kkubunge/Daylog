package com.aiutil.daylog.ui.timeline

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiutil.daylog.model.DayEntry
import com.aiutil.daylog.model.MemoryCard
import com.aiutil.daylog.model.MoodTag
import com.aiutil.daylog.model.PermissionStatus
import com.aiutil.daylog.model.SourceType
import com.aiutil.daylog.ui.components.DaylogPhotoImage
import com.aiutil.daylog.ui.components.HeroPhotoPair
import com.aiutil.daylog.ui.components.MoodSelectorRow
import com.aiutil.daylog.ui.components.SourceBadgeRow
import com.aiutil.daylog.util.DateUtils

private enum class TimelineFilter(val label: String) {
    ALL("전체"),
    PHOTO("📷 사진"),
    CALL("📞 통화"),
    SMS("💬 문자"),
    SNS("📱 SNS"),
    BOOKMARK("⭐ 책갈피"),
}

@Composable
fun TimelineScreen(
    todayKey: String,
    todayEntry: DayEntry?,
    pastEntries: List<DayEntry>,
    memories: List<MemoryCard>,
    isCollectingToday: Boolean,
    permissionStatus: PermissionStatus,
    onAutoCollectToday: () -> Unit,
    onSaveTodayQuickNote: (headline: String, shortNote: String, mood: MoodTag) -> Unit,
    onOpenDayDetail: (String) -> Unit,
    onToggleBookmark: (String) -> Unit,
    onPhotoClick: (String) -> Unit,
    onOpenPermissionsOrSettings: () -> Unit,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var activeFilter by rememberSaveable { mutableStateOf(TimelineFilter.ALL) }

    val filteredPast = remember(pastEntries, searchQuery, activeFilter) {
        val q = searchQuery.trim().lowercase()
        pastEntries.filter { entry ->
            val matchesFilter = when (activeFilter) {
                TimelineFilter.ALL -> true
                TimelineFilter.PHOTO -> entry.heroPhotoUris.isNotEmpty() || entry.candidatePhotos.isNotEmpty()
                TimelineFilter.CALL -> entry.moments.any { !it.isHidden && it.sourceType == SourceType.CALL }
                TimelineFilter.SMS -> entry.moments.any { !it.isHidden && it.sourceType == SourceType.SMS }
                TimelineFilter.SNS -> entry.moments.any { !it.isHidden && it.sourceType == SourceType.SNS }
                TimelineFilter.BOOKMARK -> entry.isBookmarked
            }
            if (!matchesFilter) return@filter false
            if (q.isEmpty()) return@filter true

            entry.headline.lowercase().contains(q) ||
                entry.shortNote.lowercase().contains(q) ||
                entry.dateKey.contains(q) ||
                entry.moments.any { !it.isHidden && (it.title.lowercase().contains(q) || it.summary.lowercase().contains(q)) }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. 권한 안내 배너 (아직 아무 런타임 권한도 허용되지 않은 경우)
        if (!permissionStatus.anyRuntimeGranted) {
            item(key = "permission_banner") {
                PermissionHintBanner(onOpenSettings = onOpenPermissionsOrSettings)
            }
        }

        // 2. 과거 추억 회상 가로 캐러셀
        if (memories.isNotEmpty()) {
            item(key = "memory_carousel_header") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "다시 꺼내보는 과거의 오늘",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            item(key = "memory_carousel") {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(memories, key = { "${it.badgeText}_${it.entry.dateKey}" }) { memory ->
                        MemoryPreviewChipCard(
                            memory = memory,
                            onClick = { onOpenDayDetail(memory.entry.dateKey) },
                        )
                    }
                }
            }
        }

        // 3. 오늘의 짧은 일기 베이스 카드
        item(key = "today_card") {
            TodayQuickDiaryCard(
                todayKey = todayKey,
                entry = todayEntry,
                isCollecting = isCollectingToday,
                onAutoCollectToday = onAutoCollectToday,
                onSaveQuickNote = onSaveTodayQuickNote,
                onOpenDetail = { onOpenDayDetail(todayKey) },
                onToggleBookmark = { onToggleBookmark(todayKey) },
                onPhotoClick = onPhotoClick,
            )
        }

        // 4. 과거 기록 검색 및 필터
        item(key = "search_and_filter") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "지나간 하루들",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp),
                )
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("사람 이름, 카톡/문자 내용, 메모 검색…", style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TimelineFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = filter == activeFilter,
                            onClick = { activeFilter = filter },
                            label = { Text(filter.label, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
            }
        }

        // 5. 날짜별 일기 피드
        if (filteredPast.isEmpty()) {
            item(key = "empty_past") {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(
                            text = "조건에 맞는 지나간 일기가 아직 없어요",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "설정에서 '최근 7일/30일 폰 데이터 자동 취합'을 실행하면 기존 사진·통화·문자 기록으로 지난 날 일기도 한 번에 채워집니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(onClick = onOpenPermissionsOrSettings) {
                            Text("과거 기록 자동 취합하러 가기")
                        }
                    }
                }
            }
        } else {
            items(filteredPast, key = { it.dateKey }) { entry ->
                DayMagazineCard(
                    entry = entry,
                    onClick = { onOpenDayDetail(entry.dateKey) },
                    onToggleBookmark = { onToggleBookmark(entry.dateKey) },
                    onPhotoClick = onPhotoClick,
                )
            }
        }

        item(key = "bottom_spacer") {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PermissionHintBanner(onOpenSettings: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "폰 데이터 자동 취합 시작하기",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "사진·통화·문자·SNS 권한을 연결하면 오늘 일기 베이스가 자동으로 채워져요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                )
            }
            TextButton(onClick = onOpenSettings) {
                Text("연결하기", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MemoryPreviewChipCard(
    memory: MemoryCard,
    onClick: () -> Unit,
) {
    val entry = memory.entry
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.width(236.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        text = memory.badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Text(text = entry.mood.emoji, style = MaterialTheme.typography.titleSmall)
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (entry.heroPhotoUris.isNotEmpty()) {
                DaylogPhotoImage(
                    uri = entry.heroPhotoUris.first(),
                    maxDim = 480,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(92.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(
                text = entry.headline.ifBlank { DateUtils.formatShortKoreanDate(entry.dateKey) },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = entry.shortNote.ifBlank { "조각 ${entry.moments.size}개가 담긴 하루" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TodayQuickDiaryCard(
    todayKey: String,
    entry: DayEntry?,
    isCollecting: Boolean,
    onAutoCollectToday: () -> Unit,
    onSaveQuickNote: (headline: String, shortNote: String, mood: MoodTag) -> Unit,
    onOpenDetail: () -> Unit,
    onToggleBookmark: () -> Unit,
    onPhotoClick: (String) -> Unit,
) {
    var headline by remember(entry?.dateKey, entry?.updatedAtMs) {
        mutableStateOf(entry?.headline.orEmpty())
    }
    var shortNote by remember(entry?.dateKey, entry?.updatedAtMs) {
        mutableStateOf(entry?.shortNote.orEmpty())
    }
    var mood by remember(entry?.dateKey, entry?.updatedAtMs) {
        mutableStateOf(entry?.mood ?: MoodTag.CALM)
    }

    val isDirty = headline != entry?.headline.orEmpty() ||
        shortNote != entry?.shortNote.orEmpty() ||
        mood != (entry?.mood ?: MoodTag.CALM)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 상단 날짜 & 자동 취합 액션
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "TODAY · ${DateUtils.formatShortKoreanDate(todayKey)}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "오늘의 짧은 기록",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                IconButton(onClick = onToggleBookmark) {
                    Icon(
                        imageVector = if (entry?.isBookmarked == true) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = "책갈피",
                        tint = if (entry?.isBookmarked == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Button(
                    onClick = onAutoCollectToday,
                    enabled = !isCollecting,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (isCollecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("자동 취합", style = MaterialTheme.typography.labelMedium)
                }
            }

            // 대표 이미지 1~2장
            val heroes = entry?.heroPhotoUris.orEmpty()
            if (heroes.isNotEmpty()) {
                HeroPhotoPair(
                    uris = heroes,
                    onPhotoClick = onPhotoClick,
                )
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenDetail() },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(vertical = 16.dp, horizontal = 12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "임팩트 있는 오늘 사진 1~2장 고르기 (또는 자동 취합)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 기분 이모지 선택
            MoodSelectorRow(
                selectedMood = mood,
                onSelect = { mood = it },
            )

            // 짧은 제목 & 1~3줄 끄적이기
            OutlinedTextField(
                value = headline,
                onValueChange = { headline = it },
                placeholder = { Text("오늘 하루 한 줄 제목 (예: 퇴근길 노을이 예뻤던 날)", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = shortNote,
                onValueChange = { shortNote = it },
                placeholder = { Text("길지 않게 1~3줄만 소소하게 끄적여보세요. 자동 취합을 누르면 기본 베이스를 만들어줘요.", style = MaterialTheme.typography.bodySmall) },
                minLines = 2,
                maxLines = 5,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            if (entry != null) {
                SourceBadgeRow(entry = entry)
            }

            // 하단 저장 & 상세/조각 편집 버튼
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onOpenDetail,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("사진·통화·문자·SNS 조각", style = MaterialTheme.typography.labelMedium)
                }

                Button(
                    onClick = { onSaveQuickNote(headline, shortNote, mood) },
                    enabled = isDirty || headline.isNotBlank() || shortNote.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isDirty) "저장하기" else "저장됨", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun DayMagazineCard(
    entry: DayEntry,
    onClick: () -> Unit,
    onToggleBookmark: () -> Unit,
    onPhotoClick: (String) -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = entry.mood.emoji,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = DateUtils.formatKoreanDate(entry.dateKey),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (entry.isSample) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Text(
                                    text = "예시",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                    if (entry.headline.isNotBlank()) {
                        Text(
                            text = entry.headline,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(
                    onClick = onToggleBookmark,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = if (entry.isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = "책갈피",
                        tint = if (entry.isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (entry.heroPhotoUris.isNotEmpty()) {
                HeroPhotoPair(
                    uris = entry.heroPhotoUris,
                    onPhotoClick = onPhotoClick,
                    singleHeight = 172.dp,
                    pairHeight = 142.dp,
                )
            }

            if (entry.shortNote.isNotBlank()) {
                Text(
                    text = entry.shortNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            SourceBadgeRow(entry = entry)
        }
    }
}
