package com.aiutil.daylog.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aiutil.daylog.model.CapturedMoment
import com.aiutil.daylog.model.DayEntry
import com.aiutil.daylog.model.MoodTag
import com.aiutil.daylog.model.SourceType
import com.aiutil.daylog.util.DateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 1장이면 와이드 폴라로이드, 2장이면 나란히 2분할로 임팩트 있는 사진을 보여준다.
 */
@Composable
fun HeroPhotoPair(
    uris: List<String>,
    onPhotoClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleHeight: Dp = 196.dp,
    pairHeight: Dp = 160.dp,
) {
    val clean = uris.filter { it.isNotBlank() }.take(2)
    if (clean.isEmpty()) return

    if (clean.size == 1) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(singleHeight)
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .clickable { onPhotoClick(clean[0]) },
        ) {
            DaylogPhotoImage(
                uri = clean[0],
                modifier = Modifier.fillMaxSize(),
            )
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            clean.forEach { uri ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(pairHeight)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                        .clickable { onPhotoClick(uri) },
                ) {
                    DaylogPhotoImage(
                        uri = uri,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * sample:// 가상 추억 아트와 실제 content://, file:// 이미지를 모두 지원하는 이미지 컴포넌트.
 */
@Composable
fun DaylogPhotoImage(
    uri: String,
    modifier: Modifier = Modifier,
    maxDim: Int = 800,
) {
    if (uri.startsWith("sample://")) {
        SampleIllustrationPhoto(sampleKey = uri, modifier = modifier)
        return
    }

    val context = LocalContext.current
    var bitmap by remember(uri, maxDim) {
        mutableStateOf(PhotoImageCache.getCached(uri, maxDim))
    }

    LaunchedEffect(uri, maxDim) {
        if (uri.isBlank() || bitmap != null) return@LaunchedEffect
        delay(40L)
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
                contentDescription = "일기 대표 사진",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Default.PhotoCamera,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun SampleIllustrationPhoto(
    sampleKey: String,
    modifier: Modifier = Modifier,
) {
    val palette = when (sampleKey) {
        "sample://coffee_morning" -> Triple(
            listOf(Color(0xFFF3DFC1), Color(0xFFDDA15E)),
            Color(0xFF7F4F24),
            "☕ 오후의 카페",
        )
        "sample://ocean_trip" -> Triple(
            listOf(Color(0xFF90CAF9), Color(0xFF2E7D9B)),
            Color(0xFF154360),
            "🌊 1년 전 바다",
        )
        else -> Triple(
            listOf(Color(0xFFF6BD60), Color(0xFFC86D51)),
            Color(0xFF5D2E20),
            "🌅 퇴근길 노을",
        )
    }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(palette.first),
            )
            // 따뜻한 태양/조명 원
            drawCircle(
                color = Color.White.copy(alpha = 0.45f),
                radius = size.minDimension * 0.16f,
                center = Offset(size.width * 0.72f, size.height * 0.3f),
            )
            // 부드러운 언덕/파도 실루엣
            val path = Path().apply {
                moveTo(0f, size.height * 0.75f)
                cubicTo(
                    size.width * 0.25f,
                    size.height * 0.50f,
                    size.width * 0.45f,
                    size.height * 0.62f,
                    size.width * 0.62f,
                    size.height * 0.72f,
                )
                cubicTo(
                    size.width * 0.78f,
                    size.height * 0.82f,
                    size.width * 0.90f,
                    size.height * 0.72f,
                    size.width,
                    size.height * 0.64f,
                )
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(path = path, color = palette.second.copy(alpha = 0.42f))
        }

        Surface(
            color = Color.Black.copy(alpha = 0.35f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
        ) {
            Text(
                text = palette.third,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * 사진을 탭했을 때 크게 감상하는 라이트박스 다이얼로그.
 */
@Composable
fun PhotoLightboxDialog(
    uri: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            if (uri.startsWith("sample://")) {
                SampleIllustrationPhoto(
                    sampleKey = uri,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .height(340.dp)
                        .clip(RoundedCornerShape(18.dp)),
                )
            } else {
                val context = LocalContext.current
                var fullBitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
                LaunchedEffect(uri) {
                    fullBitmap = withContext(Dispatchers.IO) {
                        PhotoImageCache.load(context, uri, 1600)
                    }
                }
                val loaded = fullBitmap
                if (loaded != null) {
                    Image(
                        bitmap = loaded,
                        contentDescription = "확대 사진",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                    )
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "닫기",
                    tint = Color.White,
                )
            }
        }
    }
}

/**
 * 하루에 수집된 사진·통화·문자·SNS 개수를 작고 깔끔한 배지로 표시한다.
 */
@Composable
fun SourceBadgeRow(
    entry: DayEntry,
    modifier: Modifier = Modifier,
) {
    val visible = entry.moments.filterNot { it.isHidden }
    val photoCount = maxOf(entry.candidatePhotos.size, entry.heroPhotoUris.size)
    val callCount = visible.count { it.sourceType == SourceType.CALL }
    val smsCount = visible.count { it.sourceType == SourceType.SMS }
    val snsCount = visible.count { it.sourceType == SourceType.SNS }
    val manualCount = visible.count { it.sourceType == SourceType.MANUAL }

    if (photoCount == 0 && callCount == 0 && smsCount == 0 && snsCount == 0 && manualCount == 0) {
        return
    }

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (photoCount > 0) SourceCountPill("📷 사진 $photoCount")
        if (callCount > 0) SourceCountPill("📞 통화 $callCount")
        if (smsCount > 0) SourceCountPill("💬 문자 $smsCount")
        if (snsCount > 0) SourceCountPill("📱 SNS $snsCount")
        if (manualCount > 0) SourceCountPill("✏️ 메모 $manualCount")
    }
}

@Composable
private fun SourceCountPill(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun MoodSelectorRow(
    selectedMood: MoodTag,
    onSelect: (MoodTag) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MoodTag.entries.forEach { mood ->
            FilterChip(
                selected = mood == selectedMood,
                onClick = { onSelect(mood) },
                label = { Text("${mood.emoji} ${mood.label}", style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

/**
 * 상세 편집 화면에서 통화·문자·SNS·메모 조각 하나를 보여주는 행.
 */
@Composable
fun MomentRowItem(
    moment: CapturedMoment,
    onAppendToNote: ((CapturedMoment) -> Unit)? = null,
    onToggleHide: ((CapturedMoment) -> Unit)? = null,
    onDelete: ((CapturedMoment) -> Unit)? = null,
) {
    val icon = when (moment.sourceType) {
        SourceType.PHOTO -> Icons.Default.PhotoCamera
        SourceType.CALL -> Icons.Default.Call
        SourceType.SMS -> Icons.Default.Sms
        SourceType.SNS -> Icons.Default.Forum
        SourceType.MANUAL -> Icons.Default.EditNote
    }
    val typeLabel = if (moment.sourceType == SourceType.SNS) {
        moment.snsPlatform.displayName
    } else {
        moment.sourceType.label
    }
    val alpha = if (moment.isHidden) 0.45f else 1f

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f * alpha),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = typeLabel,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "$typeLabel · ${moment.title}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = DateUtils.formatTimeHm(moment.timestampMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = moment.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
            if (onAppendToNote != null || onToggleHide != null || onDelete != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onAppendToNote != null && !moment.isHidden) {
                        TextButton(onClick = { onAppendToNote(moment) }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("본문에 넣기", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (onToggleHide != null) {
                        IconButton(onClick = { onToggleHide(moment) }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = if (moment.isHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (moment.isHidden) "다시 포함" else "요약에서 숨기기",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (onDelete != null) {
                        IconButton(onClick = { onDelete(moment) }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "조각 삭제",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
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
