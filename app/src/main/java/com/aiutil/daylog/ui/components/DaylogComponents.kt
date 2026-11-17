package com.aiutil.daylog.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aiutil.daylog.collector.SmsSpamFilter
import com.aiutil.daylog.model.ActivityItem
import com.aiutil.daylog.model.ActivityType
import com.aiutil.daylog.model.DayPhoto
import com.aiutil.daylog.model.PlaceCategory
import com.aiutil.daylog.model.PlaceVisit
import com.aiutil.daylog.util.DateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 기기 사진 URI를 비동기로 디코딩·캐시해 깔끔하게 표시한다.
 */
@Composable
fun DaylogPhotoImage(
    uri: String,
    modifier: Modifier = Modifier,
    maxDim: Int = 520,
) {
    val context = LocalContext.current
    var bitmap by remember(uri, maxDim) {
        mutableStateOf(PhotoImageCache.getCached(uri, maxDim))
    }

    LaunchedEffect(uri, maxDim) {
        if (uri.isBlank() || bitmap != null) return@LaunchedEffect
        delay(30L)
        bitmap = withContext(Dispatchers.IO) {
            PhotoImageCache.load(context, uri, maxDim)
        }
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded,
                contentDescription = "촬영 사진",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Default.PhotoCamera,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * 사진을 탭했을 때 크게 보며 좌우 스와이프(스왑)로 다른 사진을 넘길 수 있는 라이트박스 다이얼로그.
 */
@Composable
fun PhotoLightboxDialog(
    photos: List<DayPhoto>,
    initialIndex: Int,
    hasGeminiKey: Boolean = false,
    isAnalyzing: Boolean = false,
    onAnalyzePhoto: ((DayPhoto) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    if (photos.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val safeInitial = initialIndex.coerceIn(0, photos.lastIndex)
    val pagerState = rememberPagerState(initialPage = safeInitial, pageCount = { photos.size })
    val currentPhoto = photos[pagerState.currentPage.coerceIn(0, photos.lastIndex)]

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f))
                .clickable(onClick = onDismiss),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val pagePhoto = photos[page]
                var fullBitmap by remember(pagePhoto.uri) { mutableStateOf<ImageBitmap?>(null) }
                LaunchedEffect(pagePhoto.uri) {
                    fullBitmap = withContext(Dispatchers.IO) {
                        PhotoImageCache.load(context, pagePhoto.uri, 1600)
                    }
                }
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    val loaded = fullBitmap
                    if (loaded != null) {
                        Image(
                            bitmap = loaded,
                            contentDescription = pagePhoto.displayName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp, vertical = 72.dp),
                        )
                    }
                }
            }

            // 상단 페이지 표시 & 좌우 이동 버튼 · 닫기 버튼
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${photos.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (photos.size > 1) {
                        IconButton(
                            onClick = {
                                val prev = (pagerState.currentPage - 1).coerceAtLeast(0)
                                scope.launch { pagerState.animateScrollToPage(prev) }
                            },
                            enabled = pagerState.currentPage > 0,
                            modifier = Modifier.background(Color.White.copy(alpha = 0.14f), CircleShape),
                        ) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = "이전 사진", tint = Color.White)
                        }
                        IconButton(
                            onClick = {
                                val next = (pagerState.currentPage + 1).coerceAtMost(photos.lastIndex)
                                scope.launch { pagerState.animateScrollToPage(next) }
                            },
                            enabled = pagerState.currentPage < photos.lastIndex,
                            modifier = Modifier.background(Color.White.copy(alpha = 0.14f), CircleShape),
                        ) {
                            Icon(Icons.Default.ChevronRight, contentDescription = "다음 사진", tint = Color.White)
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.background(Color.White.copy(alpha = 0.14f), CircleShape),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "닫기", tint = Color.White)
                    }
                }
            }

            // 하단 촬영 시각 · 도시/장소 · AI 분석 바
            Surface(
                color = Color.Black.copy(alpha = 0.78f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clickable(enabled = false) {},
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${DateUtils.formatTimeHm(currentPhoto.dateTakenMs)} · ${currentPhoto.folderName.ifBlank { currentPhoto.displayName }}",
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            val locText = listOf(currentPhoto.cityName, currentPhoto.placeLabel)
                                .filter { it.isNotBlank() }
                                .distinct()
                                .joinToString(" · ")
                            if (locText.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "위치: $locText",
                                    color = Color.White.copy(alpha = 0.8f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }

                        if (onAnalyzePhoto != null) {
                            TextButton(
                                onClick = { onAnalyzePhoto(currentPhoto) },
                                enabled = !isAnalyzing,
                            ) {
                                if (isAnalyzing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(15.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (currentPhoto.aiCaption.isBlank()) "AI 내용 읽기" else "다시 읽기",
                                    color = if (hasGeminiKey) Color.White else Color.White.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }

                    if (currentPhoto.aiCaption.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = currentPhoto.aiCaption,
                            color = Color.White.copy(alpha = 0.92f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 이동 및 체류 장소 타임라인 카드.
 * 방문 도시 이름, 추정 주변 상호, 카테고리(식당·병원·카페·업무 등)를 함께 보여준다.
 */
@Composable
fun PlaceVisitTimelineItem(
    visit: PlaceVisit,
    isLast: Boolean,
    onOpenMap: (PlaceVisit) -> Unit,
    onSaveAlias: (PlaceVisit) -> Unit,
    onClassifyWithAi: ((PlaceVisit) -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (visit.distanceFromPrevMeters > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 15.dp, top = 2.dp, bottom = 4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(14.dp)
                        .background(MaterialTheme.colorScheme.outline),
                )
                Spacer(modifier = Modifier.width(14.dp))
                Text(
                    text = "이동 ${DateUtils.formatDistanceKorean(visit.distanceFromPrevMeters)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenMap(visit) },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                // 좌측 타임라인 도트
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(24.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (visit.isStay) 10.dp else 7.dp)
                            .clip(CircleShape)
                            .background(
                                if (visit.isStay) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            ),
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val timeText = if (visit.endMs > visit.startMs + 60_000L) {
                            "${DateUtils.formatTimeHm(visit.startMs)} ~ ${DateUtils.formatTimeHm(visit.endMs)} · ${DateUtils.formatStayMinutes(visit.durationMinutes)}"
                        } else {
                            "${DateUtils.formatTimeHm(visit.startMs)} · ${visit.source.label}"
                        }
                        Text(
                            text = timeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f, fill = false),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )

                        // 도시 이름 + 장소 카테고리 배지
                        val cityCategoryBadge = buildList {
                            if (visit.cityName.isNotBlank()) add(visit.cityName)
                            if (visit.category != PlaceCategory.GENERAL) add(visit.category.label)
                        }.joinToString(" · ")
                        if (cityCategoryBadge.isNotBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Text(
                                    text = cityCategoryBadge,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(3.dp))
                    val mainTitle = if (visit.nearbyPoi.isNotBlank() && !visit.placeName.contains(visit.nearbyPoi)) {
                        "${visit.nearbyPoi} · ${visit.placeName}"
                    } else {
                        visit.placeName
                    }
                    Text(
                        text = mainTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (visit.address.isNotBlank() && visit.address != visit.placeName) {
                        Text(
                            text = visit.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (onClassifyWithAi != null) {
                    IconButton(
                        onClick = { onClassifyWithAi(visit) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "AI 주변 상호 추정",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                IconButton(
                    onClick = { onSaveAlias(visit) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.BookmarkAdd,
                        contentDescription = "장소 이름 저장",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp),
                    )
                }
                IconButton(
                    onClick = { onOpenMap(visit) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = "지도에서 보기",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }

        if (!isLast) {
            Box(
                modifier = Modifier
                    .padding(start = 15.dp)
                    .width(1.dp)
                    .height(6.dp)
                    .background(MaterialTheme.colorScheme.outline),
            )
        }
    }
}

/**
 * 시간순 활동 내역(통화 · 결제 · 문자 · SNS · 메모)을 깔끔한 모노톤 행으로 표시한다.
 */
@Composable
fun ActivityLogRow(
    item: ActivityItem,
    isAiBusy: Boolean = false,
    onSummarizeCall: ((ActivityItem) -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val icon = when (item.type) {
        ActivityType.PLACE -> Icons.Default.LocationOn
        ActivityType.PHOTO -> Icons.Default.PhotoCamera
        ActivityType.PAYMENT -> Icons.Default.ReceiptLong
        ActivityType.CALL -> Icons.Default.Call
        ActivityType.SMS -> Icons.Default.Sms
        ActivityType.SNS -> Icons.Default.Forum
        ActivityType.NOTE -> Icons.Default.EditNote
    }
    val typeLabel = if (item.type == ActivityType.SNS) item.snsPlatform.displayName else item.type.label

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = DateUtils.formatTimeHm(item.timestampMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(46.dp),
                )

                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = typeLabel,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(15.dp),
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            text = typeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (item.detail.isNotBlank()) {
                        Text(
                            text = item.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (item.type == ActivityType.PAYMENT && item.amountWon > 0L) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = SmsSpamFilter.formatWon(item.amountWon),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                if (item.type == ActivityType.CALL && item.audioUri.isNotBlank() && onSummarizeCall != null) {
                    TextButton(
                        onClick = { onSummarizeCall(item) },
                        enabled = !isAiBusy,
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = if (item.aiCallSummary.isBlank()) "녹음 요약" else "다시",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                if (onDelete != null && item.type == ActivityType.NOTE) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "메모 삭제",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            if (item.aiCallSummary.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 46.dp),
                ) {
                    Text(
                        text = item.aiCallSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}

/**
 * 선택한 날짜의 데이터 건수를 한눈에 보여주는 깔끔한 모노톤 요약 바.
 */
@Composable
fun SummaryMetricStrip(
    placeCount: Int,
    photoCount: Int,
    paymentCount: Int,
    callCount: Int,
    smsCount: Int,
    snsCount: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        MetricBox("장소", placeCount, Modifier.weight(1f))
        MetricBox("사진", photoCount, Modifier.weight(1f))
        MetricBox("결제", paymentCount, Modifier.weight(1f))
        MetricBox("통화", callCount, Modifier.weight(1f))
        MetricBox("문자", smsCount, Modifier.weight(1f))
        MetricBox("SNS", snsCount, Modifier.weight(1f))
    }
}

@Composable
private fun MetricBox(label: String, count: Int, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (count > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun openLocationInMap(context: Context, latitude: Double, longitude: Double, label: String) {
    val encoded = Uri.encode(label.ifBlank { "위치" })
    val geoUri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude($encoded)")
    val intent = Intent(Intent.ACTION_VIEW, geoUri)
    runCatching { context.startActivity(intent) }
}

private object PhotoImageCache {

    private val decodeSemaphore = Semaphore(3)

    private val cache = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 6).toInt().coerceAtLeast(8192)
    ) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            (value.width * value.height * 4) / 1024
    }

    fun getCached(uriString: String, maxDim: Int): ImageBitmap? {
        if (uriString.isBlank()) return null
        return cache.get("$maxDim:$uriString")
    }

    suspend fun load(context: Context, uriString: String, maxDim: Int): ImageBitmap? {
        val key = "$maxDim:$uriString"
        cache.get(key)?.let { return it }

        return decodeSemaphore.withPermit {
            cache.get(key)?.let { return@withPermit it }
            val uri = Uri.parse(uriString)
            val raw = loadThumbnailQ(context, uri, maxDim) ?: decodeSampledBitmap(context, uri, maxDim)
            raw?.asImageBitmap()?.also { cache.put(key, it) }
        }
    }

    private fun loadThumbnailQ(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (uri.scheme != "content") return null
        return runCatching {
            context.contentResolver.loadThumbnail(uri, Size(maxDim, maxDim), null)
        }.getOrNull()
    }

    private fun decodeSampledBitmap(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        return runCatching {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOpts)
            }
            val srcW = boundsOpts.outWidth
            val srcH = boundsOpts.outHeight
            if (srcW <= 0 || srcH <= 0) return@runCatching null

            var sampleSize = 1
            while (srcW / sampleSize > maxDim * 2 || srcH / sampleSize > maxDim * 2) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            }
        }.getOrNull()
    }
}
