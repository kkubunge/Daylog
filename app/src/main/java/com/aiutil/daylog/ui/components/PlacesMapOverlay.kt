package com.aiutil.daylog.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.aiutil.daylog.model.PlaceVisit
import com.aiutil.daylog.util.DateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan

private object OsmTileLoader {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val semaphore = Semaphore(4)
    private val cache = object : LruCache<String, ImageBitmap>(250) {}
    private val failedKeys = mutableSetOf<String>()

    fun getCached(z: Int, x: Int, y: Int): ImageBitmap? = cache.get("$z/$x/$y")

    suspend fun loadTile(z: Int, x: Int, y: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "$z/$x/$y"
        cache.get(key)?.let { return@withContext it }
        synchronized(failedKeys) {
            if (key in failedKeys) return@withContext null
        }

        val maxIdx = 1 shl z
        if (y < 0 || y >= maxIdx) return@withContext null
        val wrappedX = ((x % maxIdx) + maxIdx) % maxIdx

        semaphore.withPermit {
            cache.get(key)?.let { return@withPermit it }
            val urls = listOf(
                "https://tile.openstreetmap.org/$z/$wrappedX/$y.png",
                "https://a.tile.openstreetmap.fr/osmfr/$z/$wrappedX/$y.png",
            )
            for (url in urls) {
                val bmp = runCatching {
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", "DaylogAndroid/2.0 (https://github.com/kkubunge/Daylog)")
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val bytes = resp.body?.bytes() ?: return@use null
                        if (bytes.size < 200) return@use null
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }
                }.getOrNull()
                if (bmp != null) {
                    cache.put(key, bmp)
                    return@withPermit bmp
                }
            }
            synchronized(failedKeys) { failedKeys.add(key) }
            null
        }
    }
}

private fun lonToTileX(lng: Double, zoom: Int): Double =
    (lng + 180.0) / 360.0 * (1 shl zoom).toDouble()

private fun latToTileY(lat: Double, zoom: Int): Double {
    val clamped = lat.coerceIn(-85.0511, 85.0511)
    val rad = Math.toRadians(clamped)
    return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * (1 shl zoom).toDouble()
}

private fun tileXToLon(tx: Double, zoom: Int): Double =
    tx / (1 shl zoom).toDouble() * 360.0 - 180.0

private fun tileYToLat(ty: Double, zoom: Int): Double {
    val n = PI - 2.0 * PI * ty / (1 shl zoom).toDouble()
    return Math.toDegrees(atan(sinh(n)))
}

private fun fitBoundsParams(
    places: List<PlaceVisit>,
    widthPx: Float,
    heightPx: Float,
    tileSizePx: Float,
): Triple<Double, Double, Int> {
    if (places.isEmpty()) return Triple(37.5665, 126.9780, 12)
    val minLat = places.minOf { it.latitude }
    val maxLat = places.maxOf { it.latitude }
    val minLng = places.minOf { it.longitude }
    val maxLng = places.maxOf { it.longitude }
    val cLat = (minLat + maxLat) / 2.0
    val cLng = (minLng + maxLng) / 2.0

    if (places.size == 1 || (maxLat - minLat < 0.0005 && maxLng - minLng < 0.0005)) {
        return Triple(cLat, cLng, 15)
    }

    val availW = (widthPx * 0.78f).coerceAtLeast(120f)
    val availH = (heightPx * 0.72f).coerceAtLeast(120f)
    for (z in 16 downTo 5) {
        val dx = (lonToTileX(maxLng, z) - lonToTileX(minLng, z)) * tileSizePx
        val dy = (latToTileY(minLat, z) - latToTileY(maxLat, z)) * tileSizePx
        if (dx <= availW && dy <= availH) {
            return Triple(cLat, cLng, z)
        }
    }
    return Triple(cLat, cLng, 5)
}

/**
 * 전체 화면 이동·체류 동선 지도 오버레이.
 */
@Composable
fun PlacesMapOverlay(
    title: String,
    places: List<PlaceVisit>,
    stayThresholdMin: Int = 3,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val validPlaces = remember(places) {
        places.filter { !(it.latitude == 0.0 && it.longitude == 0.0) }
    }
    var selectedIdx by remember(validPlaces) { mutableIntStateOf(if (validPlaces.isNotEmpty()) 0 else -1) }
    var focusTrigger by remember { mutableIntStateOf(0) }

    BackHandler(onBack = onDismiss)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val totalDist = validPlaces.sumOf { it.distanceFromPrevMeters }
                        val stayCount = validPlaces.count { it.isStayWithThreshold(stayThresholdMin) }
                        Text(
                            text = "총 ${validPlaces.size}곳 (체류 $stayCount · 경유 ${validPlaces.size - stayCount})" +
                                if (totalDist > 0) " · 이동 약 ${DateUtils.formatDistanceKorean(totalDist)}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (validPlaces.isNotEmpty()) {
                        TextButton(
                            onClick = { openAllPlacesInExternalMap(context, validPlaces) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("구글맵", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "지도 닫기")
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            PlacesInlineMapView(
                places = validPlaces,
                stayThresholdMin = stayThresholdMin,
                focusIndex = if (selectedIdx in validPlaces.indices) selectedIdx else null,
                focusRequestKey = focusTrigger,
                onPinClick = { idx ->
                    selectedIdx = idx
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (validPlaces.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(validPlaces, key = { idx, v -> "${v.id}_$idx" }) { idx, visit ->
                        val isSelected = idx == selectedIdx
                        val isStay = visit.isStayWithThreshold(stayThresholdMin)
                        val titleLabel = visit.nearbyPoi.ifBlank { visit.placeName }
                        Surface(
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            ),
                            modifier = Modifier
                                .width(172.dp)
                                .clickable {
                                    selectedIdx = idx
                                    focusTrigger++
                                },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                isSelected -> MaterialTheme.colorScheme.onPrimary
                                                isStay -> MaterialTheme.colorScheme.primary
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            }
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "${idx + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = titleLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val sub = buildString {
                                        append(DateUtils.formatTimeHm(visit.startMs))
                                        if (visit.durationMinutes > 0L) {
                                            append(" · ${visit.durationMinutes}분")
                                        }
                                        if (visit.cityName.isNotBlank()) {
                                            append(" · ${visit.cityName}")
                                        }
                                    }
                                    Text(
                                        text = sub,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * WebView 의존 없이 OSM/Carto 지도 타일 + 이동 경로 + 번호 핀을 네이티브 Compose Canvas로 렌더링하는 인터랙티브 지도.
 */
@Composable
fun PlacesInlineMapView(
    places: List<PlaceVisit>,
    stayThresholdMin: Int = 3,
    focusIndex: Int? = null,
    focusRequestKey: Int = 0,
    onPinClick: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val validPlaces = remember(places) {
        places.filter { !(it.latitude == 0.0 && it.longitude == 0.0) }
    }
    if (validPlaces.isEmpty()) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "지도에 표시할 GPS 좌표가 없습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val density = LocalDensity.current
    val tileSizePx = with(density) { 256.dp.toPx() }

    BoxWithConstraints(
        modifier = modifier
            .clipToBounds()
            .background(Color(0xFF1C2127)),
    ) {
        val widthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val heightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)

        var zoom by remember(validPlaces) { mutableIntStateOf(13) }
        var centerLat by remember(validPlaces) { mutableDoubleStateOf(validPlaces.first().latitude) }
        var centerLng by remember(validPlaces) { mutableDoubleStateOf(validPlaces.first().longitude) }
        var selectedIdx by remember(validPlaces) { mutableIntStateOf(0) }
        var tileRedrawTick by remember { mutableIntStateOf(0) }

        LaunchedEffect(validPlaces, widthPx, heightPx) {
            val (cLat, cLng, fitZ) = fitBoundsParams(validPlaces, widthPx, heightPx, tileSizePx)
            centerLat = cLat
            centerLng = cLng
            zoom = fitZ
            selectedIdx = 0
        }

        LaunchedEffect(focusIndex, focusRequestKey) {
            val idx = focusIndex ?: return@LaunchedEffect
            if (idx in validPlaces.indices) {
                selectedIdx = idx
                centerLat = validPlaces[idx].latitude
                centerLng = validPlaces[idx].longitude
                if (zoom < 14) zoom = 14
            }
        }

        val cxTile = lonToTileX(centerLng, zoom)
        val cyTile = latToTileY(centerLat, zoom)
        val halfW = widthPx / 2f
        val halfH = heightPx / 2f

        val minTileX = floor(cxTile - halfW / tileSizePx).toInt() - 1
        val maxTileX = ceil(cxTile + halfW / tileSizePx).toInt() + 1
        val minTileY = floor(cyTile - halfH / tileSizePx).toInt().coerceAtLeast(0)
        val maxTileY = ceil(cyTile + halfH / tileSizePx).toInt().coerceAtMost((1 shl zoom) - 1)

        // 현재 뷰포트 내 지도 타일 병렬 비동기 로드
        LaunchedEffect(zoom, minTileX, maxTileX, minTileY, maxTileY) {
            for (tx in minTileX..maxTileX) {
                for (ty in minTileY..maxTileY) {
                    if (OsmTileLoader.getCached(zoom, tx, ty) == null) {
                        launch {
                            val loaded = OsmTileLoader.loadTile(zoom, tx, ty)
                            if (loaded != null) {
                                tileRedrawTick++
                            }
                        }
                    }
                }
            }
        }

        val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(16f, 10f), 0f) }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(zoom) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val curCx = lonToTileX(centerLng, zoom) - (dragAmount.x / tileSizePx)
                        val curCy = latToTileY(centerLat, zoom) - (dragAmount.y / tileSizePx)
                        centerLng = tileXToLon(curCx, zoom)
                        centerLat = tileYToLat(curCy, zoom).coerceIn(-84.0, 84.0)
                    }
                },
        ) {
            // tileRedrawTick 읽기 (타일 로드 완료 시 Canvas 재그리기 트리거)
            @Suppress("UNUSED_VARIABLE")
            val redraw = tileRedrawTick

            // 1. 은은한 그리드(타일 로딩 전에도 시각적 지도 배경 제공)
            val gridStep = tileSizePx / 2f
            var gx = 0f
            while (gx < size.width) {
                drawLine(Color.White.copy(alpha = 0.05f), Offset(gx, 0f), Offset(gx, size.height), 1f)
                gx += gridStep
            }
            var gy = 0f
            while (gy < size.height) {
                drawLine(Color.White.copy(alpha = 0.05f), Offset(0f, gy), Offset(size.width, gy), 1f)
                gy += gridStep
            }

            // 2. 로드된 실제 OSM/Carto 지도 타일 그리기
            val tileIntSize = IntSize(ceil(tileSizePx).toInt() + 1, ceil(tileSizePx).toInt() + 1)
            for (tx in minTileX..maxTileX) {
                for (ty in minTileY..maxTileY) {
                    val bmp = OsmTileLoader.getCached(zoom, tx, ty) ?: continue
                    val left = (halfW + (tx - cxTile) * tileSizePx).roundToInt()
                    val top = (halfH + (ty - cyTile) * tileSizePx).roundToInt()
                    drawImage(
                        image = bmp,
                        dstOffset = IntOffset(left, top),
                        dstSize = tileIntSize,
                    )
                }
            }

            // 3. 이동 경로 점선 연결
            if (validPlaces.size > 1) {
                val points = validPlaces.map { v ->
                    Offset(
                        x = (halfW + (lonToTileX(v.longitude, zoom) - cxTile) * tileSizePx).toFloat(),
                        y = (halfH + (latToTileY(v.latitude, zoom) - cyTile) * tileSizePx).toFloat(),
                    )
                }
                for (i in 0 until points.lastIndex) {
                    drawLine(
                        color = Color(0xFF1D4ED8),
                        start = points[i],
                        end = points[i + 1],
                        strokeWidth = 6f,
                        cap = StrokeCap.Round,
                        pathEffect = dashEffect,
                    )
                }
            }
        }

        // 4. 클릭 가능한 번호 마커 핀 배치
        validPlaces.forEachIndexed { idx, visit ->
            val px = (halfW + (lonToTileX(visit.longitude, zoom) - cxTile) * tileSizePx).toFloat()
            val py = (halfH + (latToTileY(visit.latitude, zoom) - cyTile) * tileSizePx).toFloat()
            if (px in -40f..(widthPx + 40f) && py in -40f..(heightPx + 40f)) {
                val isSelected = idx == selectedIdx
                val isStay = visit.isStayWithThreshold(stayThresholdMin)
                val pinSizeDp = if (isSelected) 30.dp else if (isStay) 26.dp else 21.dp
                val pinSizePx = with(density) { pinSizeDp.toPx() }
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = (px - pinSizePx / 2f).roundToInt(),
                                y = (py - pinSizePx / 2f).roundToInt(),
                            )
                        }
                        .size(pinSizeDp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isSelected -> Color(0xFF2563EB)
                                isStay -> Color(0xFF111827)
                                else -> Color(0xFF6B7280)
                            }
                        )
                        .border(2.dp, Color.White, CircleShape)
                        .clickable {
                            selectedIdx = idx
                            onPinClick?.invoke(idx)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${idx + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
        }

        // 5. 선택된 핀 말풍선 요약 (좌측 상단 오버랩)
        val activeVisit = validPlaces.getOrNull(selectedIdx)
        if (activeVisit != null) {
            val poiTitle = listOf(activeVisit.nearbyPoi, activeVisit.placeName)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" · ")
            val stayInfo = if (activeVisit.endMs > activeVisit.startMs + 60_000L) {
                "${DateUtils.formatTimeHm(activeVisit.startMs)} ~ ${DateUtils.formatTimeHm(activeVisit.endMs)} · ${DateUtils.formatStayMinutes(activeVisit.durationMinutes)}"
            } else {
                "${DateUtils.formatTimeHm(activeVisit.startMs)} · ${activeVisit.source.label}"
            }
            Surface(
                color = Color.Black.copy(alpha = 0.78f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(
                        text = "${selectedIdx + 1}. $poiTitle",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stayInfo,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            }
        }

        // 6. 우측 하단 줌(+/-) · 전체 맞추기 버튼
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp),
        ) {
            MapControlButton(
                icon = Icons.Default.Add,
                contentDescription = "확대",
                onClick = { if (zoom < 18) zoom++ },
            )
            MapControlButton(
                icon = Icons.Default.Remove,
                contentDescription = "축소",
                onClick = { if (zoom > 3) zoom-- },
            )
            MapControlButton(
                icon = Icons.Default.CenterFocusStrong,
                contentDescription = "전체 위치 맞춤",
                onClick = {
                    val (cLat, cLng, fitZ) = fitBoundsParams(validPlaces, widthPx, heightPx, tileSizePx)
                    centerLat = cLat
                    centerLng = cLng
                    zoom = fitZ
                },
            )
        }
    }
}

@Composable
private fun MapControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.72f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
        modifier = Modifier
            .size(34.dp)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

fun openAllPlacesInExternalMap(context: Context, places: List<PlaceVisit>) {
    if (places.isEmpty()) return
    val url = if (places.size == 1) {
        val p = places.first()
        val label = Uri.encode(p.nearbyPoi.ifBlank { p.placeName })
        "geo:${p.latitude},${p.longitude}?q=${p.latitude},${p.longitude}($label)"
    } else {
        val sampled = if (places.size > 10) {
            places.filterIndexed { index, _ -> index == 0 || index == places.lastIndex || index % ((places.size / 8).coerceAtLeast(1)) == 0 }
                .take(10)
        } else {
            places
        }
        val origin = "${sampled.first().latitude},${sampled.first().longitude}"
        val dest = "${sampled.last().latitude},${sampled.last().longitude}"
        val waypoints = if (sampled.size > 2) {
            sampled.subList(1, sampled.lastIndex).joinToString("|") { "${it.latitude},${it.longitude}" }
        } else {
            ""
        }
        buildString {
            append("https://www.google.com/maps/dir/?api=1&origin=").append(Uri.encode(origin))
            append("&destination=").append(Uri.encode(dest))
            if (waypoints.isNotBlank()) {
                append("&waypoints=").append(Uri.encode(waypoints))
            }
        }
    }
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
