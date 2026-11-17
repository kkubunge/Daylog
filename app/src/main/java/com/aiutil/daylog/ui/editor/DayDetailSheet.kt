package com.aiutil.daylog.ui.editor

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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiutil.daylog.model.CapturedMoment
import com.aiutil.daylog.model.DayEntry
import com.aiutil.daylog.model.DraftToneStyle
import com.aiutil.daylog.model.MoodTag
import com.aiutil.daylog.model.SnsPlatform
import com.aiutil.daylog.model.SourceType
import com.aiutil.daylog.ui.components.DaylogPhotoImage
import com.aiutil.daylog.ui.components.HeroPhotoPair
import com.aiutil.daylog.ui.components.MomentRowItem
import com.aiutil.daylog.ui.components.MoodSelectorRow
import com.aiutil.daylog.util.DateUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayDetailSheet(
    dateKey: String,
    entry: DayEntry?,
    maxHeroPhotos: Int,
    hasGeminiKey: Boolean,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onSaveText: (headline: String, shortNote: String, mood: MoodTag) -> Unit,
    onSetHeroPhotos: (List<String>) -> Unit,
    onPickGalleryPhotos: () -> Unit,
    onRecollectPhoneData: () -> Unit,
    onApplyLocalTone: (DraftToneStyle) -> Unit,
    onPolishWithGemini: () -> Unit,
    onToggleBookmark: () -> Unit,
    onAddManualMoment: (sourceType: SourceType, snsPlatform: SnsPlatform, title: String, summary: String) -> Unit,
    onToggleMomentHidden: (momentId: String) -> Unit,
    onDeleteMoment: (momentId: String) -> Unit,
    onDeleteEntry: () -> Unit,
    onPhotoClick: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val current = entry ?: DayEntry(dateKey = dateKey)

    var headline by remember(current.dateKey, current.headline) {
        mutableStateOf(current.headline)
    }
    var shortNote by remember(current.dateKey, current.shortNote) {
        mutableStateOf(current.shortNote)
    }
    var mood by remember(current.dateKey, current.mood) {
        mutableStateOf(current.mood)
    }

    var showAddMomentDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

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
            // 상단 날짜 & 북마크/삭제/닫기 바
            item(key = "header") {
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
                        Text(
                            text = if (current.isUserEdited) "직접 다듬은 기록" else "폰 데이터 자동 취합 베이스",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onToggleBookmark) {
                        Icon(
                            imageVector = if (current.isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = "책갈피",
                            tint = if (current.isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (entry != null) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "이 날 기록 삭제",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "닫기")
                    }
                }
            }

            // 1. 임팩트 있는 대표 사진 1~2장 영역
            item(key = "hero_section") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "오늘의 임팩트 사진 (${current.heroPhotoUris.size}/${maxHeroPhotos}장)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        TextButton(onClick = onPickGalleryPhotos) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("갤러리에서 추가", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    if (current.heroPhotoUris.isNotEmpty()) {
                        HeroPhotoPair(
                            uris = current.heroPhotoUris,
                            onPhotoClick = onPhotoClick,
                            singleHeight = 190.dp,
                            pairHeight = 155.dp,
                        )
                        // 선택된 대표 사진 개별 해제 칩
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            current.heroPhotoUris.forEachIndexed { idx, uri ->
                                AssistChip(
                                    onClick = {
                                        onSetHeroPhotos(current.heroPhotoUris.filterNot { it == uri })
                                    },
                                    label = { Text("대표 사진 ${idx + 1} 빼기", style = MaterialTheme.typography.labelSmall) },
                                    trailingIcon = {
                                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                    },
                                )
                            }
                        }
                    }

                    // 폰에서 자동 스캔된 그날의 사진 후보 가로 스트립
                    if (current.candidatePhotos.isNotEmpty()) {
                        Text(
                            text = "이날 찍은 사진 ${current.candidatePhotos.size}장 중 고르기 (임팩트 순)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(current.candidatePhotos, key = { _, p -> p.uri }) { idx, photo ->
                                val isSelected = photo.uri in current.heroPhotoUris
                                Box(
                                    modifier = Modifier
                                        .size(84.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(
                                            width = if (isSelected) 2.5.dp else 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                            shape = RoundedCornerShape(12.dp),
                                        )
                                        .clickable {
                                            val updated = if (isSelected) {
                                                current.heroPhotoUris.filterNot { it == photo.uri }
                                            } else {
                                                (current.heroPhotoUris + photo.uri).takeLast(maxHeroPhotos)
                                            }
                                            onSetHeroPhotos(updated)
                                        },
                                ) {
                                    DaylogPhotoImage(
                                        uri = photo.uri,
                                        maxDim = 320,
                                        modifier = Modifier.matchParentSize(),
                                    )
                                    if (idx < 2 && photo.impactScore >= 55.0) {
                                        Surface(
                                            color = Color.Black.copy(alpha = 0.6f),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier
                                                .align(Alignment.TopStart)
                                                .padding(4.dp),
                                        ) {
                                            Text(
                                                text = "BEST",
                                                color = Color.White,
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                            )
                                        }
                                    }
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "선택됨",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(4.dp)
                                                .size(20.dp)
                                                .background(Color.White, CircleShape),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. 기분 & 짧은 일기 편집
            item(key = "editor_section") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    Text(
                        text = "짧게 끄적이기",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    MoodSelectorRow(
                        selectedMood = mood,
                        onSelect = { mood = it },
                    )
                    OutlinedTextField(
                        value = headline,
                        onValueChange = { headline = it },
                        label = { Text("한 줄 제목") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = shortNote,
                        onValueChange = { shortNote = it },
                        label = { Text("오늘의 짧은 기록 (1~3줄)") },
                        minLines = 3,
                        maxLines = 6,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // 문체 변환 & AI 다듬기 & 폰 재취합 액션
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AssistChip(
                            onClick = onRecollectPhoneData,
                            enabled = !isBusy,
                            leadingIcon = {
                                if (isBusy) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(15.dp))
                                }
                            },
                            label = { Text("폰 데이터 다시 취합", style = MaterialTheme.typography.labelSmall) },
                        )
                        DraftToneStyle.entries.forEach { tone ->
                            AssistChip(
                                onClick = { onApplyLocalTone(tone) },
                                enabled = !isBusy,
                                label = { Text("${tone.label}체로", style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                        if (hasGeminiKey) {
                            AssistChip(
                                onClick = onPolishWithGemini,
                                enabled = !isBusy,
                                leadingIcon = {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(15.dp))
                                },
                                label = { Text("AI 문장 다듬기", style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }

                    Button(
                        onClick = { onSaveText(headline, shortNote, mood) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("이 날의 일기 저장하기")
                    }
                }
            }

            // 3. 수집된 통화 · 문자 · SNS · 메모 조각 타임라인
            item(key = "moments_header") {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "자동 수집 · 기록 조각 (${current.moments.size}건)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    OutlinedButton(
                        onClick = { showAddMomentDialog = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("SNS·메모 추가", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (current.moments.isEmpty()) {
                item(key = "moments_empty") {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "이 날 수집된 통화·문자·SNS 조각이 아직 없어요. 상단의 '폰 데이터 다시 취합'을 누르거나 SNS·메모를 직접 남길 수 있어요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            } else {
                items(current.moments, key = { it.id }) { moment ->
                    MomentRowItem(
                        moment = moment,
                        onAppendToNote = { m ->
                            val addition = "${m.title}: ${m.summary}"
                            shortNote = if (shortNote.isBlank()) {
                                addition
                            } else {
                                "$shortNote\n$addition"
                            }
                        },
                        onToggleHide = { m -> onToggleMomentHidden(m.id) },
                        onDelete = { m -> onDeleteMoment(m.id) },
                    )
                }
            }

            item(key = "detail_bottom_spacer") {
                Spacer(modifier = Modifier.height(28.dp))
            }
        }
    }

    if (showAddMomentDialog) {
        AddMomentDialog(
            onDismiss = { showAddMomentDialog = false },
            onAdd = { sourceType, snsPlatform, title, summary ->
                onAddManualMoment(sourceType, snsPlatform, title, summary)
                showAddMomentDialog = false
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("이 날의 기록을 지울까요?") },
            text = { Text("${DateUtils.formatKoreanDate(dateKey)}에 작성된 일기와 수집 조각이 삭제됩니다.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDeleteEntry()
                }) {
                    Text("삭제", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("취소")
                }
            },
        )
    }
}

@Composable
private fun AddMomentDialog(
    onDismiss: () -> Unit,
    onAdd: (SourceType, SnsPlatform, String, String) -> Unit,
) {
    var selectedType by remember { mutableStateOf(SourceType.SNS) }
    var selectedSns by remember { mutableStateOf(SnsPlatform.INSTAGRAM) }
    var title by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SNS · 일상 조각 직접 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedType == SourceType.SNS,
                        onClick = { selectedType = SourceType.SNS },
                        label = { Text("📱 SNS 기록") },
                    )
                    FilterChip(
                        selected = selectedType == SourceType.MANUAL,
                        onClick = { selectedType = SourceType.MANUAL },
                        label = { Text("✏️ 한 줄 메모") },
                    )
                }

                if (selectedType == SourceType.SNS) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(
                            SnsPlatform.INSTAGRAM,
                            SnsPlatform.KAKAOTALK,
                            SnsPlatform.THREADS,
                            SnsPlatform.X_TWITTER,
                            SnsPlatform.YOUTUBE,
                            SnsPlatform.OTHER,
                        ).forEach { platform ->
                            FilterChip(
                                selected = selectedSns == platform,
                                onClick = { selectedSns = platform },
                                label = { Text(platform.displayName, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(if (selectedType == SourceType.SNS) "누구/어떤 글 (예: 내 스토리, 단톡방)" else "제목/키워드") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("짧은 내용") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalTitle = title.trim().ifBlank {
                        if (selectedType == SourceType.SNS) selectedSns.displayName else "소소한 메모"
                    }
                    onAdd(selectedType, selectedSns, finalTitle, summary.trim())
                },
                enabled = summary.isNotBlank(),
            ) {
                Text("추가하기")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        },
    )
}
