package com.aiutil.daylog.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aiutil.daylog.model.DaylogSettings
import com.aiutil.daylog.model.DraftToneStyle
import com.aiutil.daylog.model.PermissionStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: DaylogSettings,
    permissionStatus: PermissionStatus,
    hasSampleEntries: Boolean,
    isBatchCollecting: Boolean,
    onDismiss: () -> Unit,
    onRequestRuntimePermissions: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onBatchCollectDays: (days: Int) -> Unit,
    onUpdateSettings: ((DaylogSettings) -> DaylogSettings) -> Unit,
    onExportJsonBackup: () -> Unit,
    onClearSampleEntries: () -> Unit,
    onClearAllEntries: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var apiKeyDraft by remember(settings.geminiApiKey) { mutableStateOf(settings.geminiApiKey) }
    var showApiKey by remember { mutableStateOf(false) }
    var showClearAllConfirm by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "settings_header") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "데이로그 설정 및 데이터 연결",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "닫기")
                    }
                }
            }

            // 1. 폰 데이터 권한 연결 상태
            item(key = "permissions_section") {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "폰 데이터 접근 권한",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        PermissionStatusRow("📷 사진 (갤러리 자동 선별)", permissionStatus.photosGranted)
                        PermissionStatusRow("📞 통화 기록 & 연락처 이름", permissionStatus.callLogGranted && permissionStatus.contactsGranted)
                        PermissionStatusRow("💬 문자 메시지 (일상 대화)", permissionStatus.smsGranted)
                        PermissionStatusRow("📱 SNS 알림 실시간 수집", permissionStatus.snsNotificationEnabled)

                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = onRequestRuntimePermissions,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("사진·통화·문자 허용", style = MaterialTheme.typography.labelMedium)
                            }
                            OutlinedButton(
                                onClick = onOpenNotificationListenerSettings,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("SNS 알림 접근", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Text(
                            text = "※ 모든 데이터는 내 폰 안에만 저장되며 외부 서버로 전송되지 않습니다.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 2. 지난 일상 일괄 자동 취합
            item(key = "batch_collect_section") {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "과거 기록 한 번에 자동 채우기",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Text(
                            text = "폰에 남아있는 최근 사진·통화·문자 기록을 스캔해 지난 날짜들의 짧은 일기 베이스를 한꺼번에 만들어줍니다. (이미 직접 고친 날은 그대로 유지)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { onBatchCollectDays(7) },
                                enabled = !isBatchCollecting,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (isBatchCollecting) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                } else {
                                    Text("최근 7일 취합")
                                }
                            }
                            OutlinedButton(
                                onClick = { onBatchCollectDays(30) },
                                enabled = !isBatchCollecting,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("최근 30일 취합")
                            }
                        }
                    }
                }
            }

            // 3. 자동 취합 세부 옵션
            item(key = "collection_options") {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "수집 항목 및 요약 스타일",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        SettingSwitchRow(
                            title = "사진 자동 선별",
                            checked = settings.autoCollectPhotos,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(autoCollectPhotos = v) } },
                        )
                        SettingSwitchRow(
                            title = "통화 내역 자동 취합",
                            checked = settings.autoCollectCalls,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(autoCollectCalls = v) } },
                        )
                        SettingSwitchRow(
                            title = "문자 메시지 자동 취합",
                            checked = settings.autoCollectSms,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(autoCollectSms = v) } },
                        )
                        SettingSwitchRow(
                            title = "광고·인증번호·결제 스팸 필터",
                            checked = settings.filterSpamSms,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(filterSpamSms = v) } },
                        )
                        SettingSwitchRow(
                            title = "SNS 알림 자동 수집 (카톡·인스타 등)",
                            checked = settings.autoCollectSns,
                            onCheckedChange = { v -> onUpdateSettings { it.copy(autoCollectSns = v) } },
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                        Text("대표 이미지 자동 선별 장수", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1, 2).forEach { count ->
                                FilterChip(
                                    selected = settings.maxHeroPhotos == count,
                                    onClick = { onUpdateSettings { it.copy(maxHeroPhotos = count) } },
                                    label = { Text("임팩트 ${count}장") },
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text("자동 초안 기본 문체", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DraftToneStyle.entries.forEach { tone ->
                                FilterChip(
                                    selected = settings.defaultToneStyle == tone,
                                    onClick = { onUpdateSettings { it.copy(defaultToneStyle = tone) } },
                                    label = { Text(tone.label, style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                        }
                    }
                }
            }

            // 4. 선택적 Gemini AI 한 줄 다듬기
            item(key = "gemini_section") {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Gemini AI 한 줄 다듬기 (선택)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "키를 비워두어도 온디바이스 자동 요약으로 동작하며, 키를 넣으면 상세 화면에서 AI 문장 다듬기 버튼을 쓸 수 있어요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = apiKeyDraft,
                            onValueChange = { apiKeyDraft = it },
                            label = { Text("Gemini API 키") },
                            singleLine = true,
                            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { showApiKey = !showApiKey }) {
                                    Text(if (showApiKey) "숨김" else "보기", style = MaterialTheme.typography.labelSmall)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(
                                onClick = {
                                    onUpdateSettings { it.copy(geminiApiKey = apiKeyDraft.trim()) }
                                },
                            ) {
                                Text("API 키 저장")
                            }
                        }
                    }
                }
            }

            // 5. 백업 및 초기화
            item(key = "data_manage_section") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onExportJsonBackup,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("전체 일기 JSON 백업 공유하기")
                    }
                    if (hasSampleEntries) {
                        OutlinedButton(
                            onClick = onClearSampleEntries,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("안내용 예시 일기만 지우기")
                        }
                    }
                    TextButton(
                        onClick = { showClearAllConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("전체 기록 초기화", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            item(key = "settings_bottom_spacer") {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("모든 일기 기록을 지울까요?") },
            text = { Text("저장된 일기와 수집된 SNS 알림 기록이 모두 삭제됩니다. 폰의 원본 사진·통화·문자는 삭제되지 않습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearAllConfirm = false
                    onClearAllEntries()
                }) {
                    Text("전체 삭제", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) {
                    Text("취소")
                }
            },
        )
    }
}

@Composable
private fun PermissionStatusRow(label: String, granted: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        Surface(
            color = if (granted) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(999.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                if (granted) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = if (granted) "연결됨" else "권한 필요",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (granted) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = title, style = MaterialTheme.typography.bodySmall)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
