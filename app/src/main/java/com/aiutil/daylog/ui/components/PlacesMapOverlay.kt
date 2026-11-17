package com.aiutil.daylog.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

private object OsmTileLoader {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val semaphore = Semaphore(4)
    private val cache = object : LruCache<String, ImageBitmap>(280) {}
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
            // 1순위: 512x512 고해상도(@2x) OSM 기반 레티나 타일, 2순위: 표준 OSM 타일
            val urls = listOf(
                "https://maps.wikimedia.org/osm-intl/$z/$wrappedX/$y@2x.png",
                "https://tile.openstreetmap.org/$z/$wrappedX/$y.png",
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
                        if (bytes.size < 250) return@use null
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

// 정규화 Web Mercator 좌표 (0.0 .. 1.0)
private fun lngToWorldX(lng: Double): Double = ((lng + 180.0) / 360.0).coerceIn(0.0, 1.0)

private fun latToWorldY(lat: Double): Double {
    val clamped = lat.coerceIn(-85.0511, 85.0511)
    val rad = Math.toRadians(clamped)
    return ((1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0).coerceIn(0.0, 1.0)
}

private fun fitWorldBounds(
    places: List<PlaceVisit>,
    widthPx: Float,
    heightPx: Float,
    baseTilePx: Float,
): Triple<Double, Double, Double> {
    if (places.isEmpty()) {
        return Triple(lngToWorldX(126.9780), latToWorldY(37.5665), 12.0)
    }
    val minWx = places.minOf { lngToWorldX(it.longitude) }
    val maxWx = places.maxOf { lngToWorldX(it.longitude) }
    val minWy = places.minOf { latToWorldY(it.latitude) }
    val maxWy = places.maxOf { latToWorldY(it.latitude) }
    val centerWx = (minWx + maxWx) / 2.0
    val centerWy = (minWy + maxWy) / 2.0

    val spanX = (maxWx - minWx).coerceAtLeast(1e-6)
    val spanY = (maxWy - minWy).coerceAtLeast(1e-6)
    if (places.size == 1 || (spanX < 1e-5 && spanY < 1e-5)) {
        return Triple(centerWx, centerWy, 15.2)
    }

    val availW = (widthPx * 0.76f).coerceAtLeast(120f)
    val availH = (heightPx * 0.70f).coerceAtLeast(120f)
    val zoomX = ln(availW / (baseTilePx * spanX)) / ln(2.0)
    val zoomY = ln(availH / (baseTilePx * spanY)) / ln(2.0)
    val fitZ = minOf(zoomX, zoomY).coerceIn(4.0, 16.0)
    return Triple(centerWx, centerWy, fitZ)
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

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "지도 닫기")
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
                onPinClick = { idx -> selectedIdx = idx },
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
 * 512x512 고해상도 레티나(@2x) 타일 + 부드러운 2손가락 핀치 줌/팬을 지원하는 네이티브 Compose 지도.
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
    // 512px @2x 레티나 타일 기준 210dp (고해상도 화면에서도 픽셀 뭉개짐 없이 칼같은 선명도 유지)
    val baseTilePx = with(density) { 210.dp.toPx() }

    BoxWithConstraints(
        modifier = modifier
            .clipToBounds()
            .background(Color(0xFFEAECEF)),
    ) {
        val widthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val heightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)

        var zoomLevel by remember(validPlaces) { mutableDoubleStateOf(13.0) }
        var centerWx by remember(validPlaces) { mutableDoubleStateOf(lngToWorldX(validPlaces.first().longitude)) }
        var centerWy by remember(validPlaces) { mutableDoubleStateOf(latToWorldY(validPlaces.first().latitude)) }
        var selectedIdx by remember(validPlaces) { mutableIntStateOf(0) }
        var tileRedrawTick by remember { mutableIntStateOf(0) }

        LaunchedEffect(validPlaces, widthPx, heightPx) {
            val (cWx, cWy, fitZ) = fitWorldBounds(validPlaces, widthPx, heightPx, baseTilePx)
            centerWx = cWx
            centerWy = cWy
            zoomLevel = fitZ
            selectedIdx = 0
        }

        LaunchedEffect(focusIndex, focusRequestKey) {
            val idx = focusIndex ?: return@LaunchedEffect
            if (idx in validPlaces.indices) {
                selectedIdx = idx
                centerWx = lngToWorldX(validPlaces[idx].longitude)
                centerWy = latToWorldY(validPlaces[idx].latitude)
                if (zoomLevel < 14.5) zoomLevel = 14.5
            }
        }

        // floor(zoomLevel) 정수 타일을 항상 1.0x~2.0x 범위에서 그려 축소/고화질 유지
        val tileZ = floor(zoomLevel).toInt().coerceIn(3, 18)
        val worldSizePx = baseTilePx * 2.0.pow(zoomLevel)
        val tileDisplayPx = (worldSizePx / (1 shl tileZ).toDouble()).toFloat()
        val halfW = widthPx / 2f
        val halfH = heightPx / 2f

        val cxTile = centerWx * (1 shl tileZ)
        val cyTile = centerWy * (1 shl tileZ)
        val minTileX = floor(cxTile - halfW / tileDisplayPx).toInt() - 1
        val maxTileX = ceil(cxTile + halfW / tileDisplayPx).toInt() + 1
        val minTileY = floor(cyTile - halfH / tileDisplayPx).toInt().coerceAtLeast(0)
        val maxTileY = ceil(cyTile + halfH / tileDisplayPx).toInt().coerceAtMost((1 shl tileZ) - 1)

        LaunchedEffect(tileZ, minTileX, maxTileX, minTileY, maxTileY) {
            for (tx in minTileX..maxTileX) {
                for (ty in minTileY..maxTileY) {
                    if (OsmTileLoader.getCached(tileZ, tx, ty) == null) {
                        launch {
                            val loaded = OsmTileLoader.loadTile(tileZ, tx, ty)
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
                .pointerInput(validPlaces, widthPx, heightPx) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        val oldWorldPx = baseTilePx * 2.0.pow(zoomLevel)
                        val anchorWx = centerWx + (centroid.x - halfW) / oldWorldPx
                        val anchorWy = centerWy + (centroid.y - halfH) / oldWorldPx

                        val newZoom = (zoomLevel + ln(gestureZoom.toDouble()) / ln(2.0)).coerceIn(3.5, 18.2)
                        val newWorldPx = baseTilePx * 2.0.pow(newZoom)

                        centerWx = (anchorWx - (centroid.x + pan.x - halfW) / newWorldPx).coerceIn(0.0, 1.0)
                        centerWy = (anchorWy - (centroid.y + pan.y - halfH) / newWorldPx).coerceIn(0.02, 0.98)
                        zoomLevel = newZoom
                    }
                },
        ) {
            @Suppress("UNUSED_VARIABLE")
            val redraw = tileRedrawTick

            // 1. 타일 로딩 전 은은한 그리드
            val gridStep = tileDisplayPx / 2f
            if (gridStep >= 16f) {
                var gx = 0f
                while (gx < size.width) {
                    drawLine(Color.Black.copy(alpha = 0.05f), Offset(gx, 0f), Offset(gx, size.height), 1f)
                    gx += gridStep
                }
                var gy = 0f
                while (gy < size.height) {
                    drawLine(Color.Black.copy(alpha = 0.05f), Offset(0f, gy), Offset(size.width, gy), 1f)
                    gy += gridStep
                }
            }

            // 2. 고화질 레티나 타일 그리기 (FilterQuality.High)
            val tileIntSize = IntSize(ceil(tileDisplayPx).toInt() + 1, ceil(tileDisplayPx).toInt() + 1)
            for (tx in minTileX..maxTileX) {
                for (ty in minTileY..maxTileY) {
                    val bmp = OsmTileLoader.getCached(tileZ, tx, ty) ?: continue
                    val left = (halfW + (tx - cxTile) * tileDisplayPx).roundToInt()
                    val top = (halfH + (ty - cyTile) * tileDisplayPx).roundToInt()
                    drawImage(
                        image = bmp,
                        dstOffset = IntOffset(left, top),
                        dstSize = tileIntSize,
                        filterQuality = FilterQuality.High,
                    )
                }
            }

            // 3. 이동 경로 점선 연결
            if (validPlaces.size > 1) {
                val points = validPlaces.map { v ->
                    Offset(
                        x = (halfW + (lngToWorldX(v.longitude) - centerWx) * worldSizePx).toFloat(),
                        y = (halfH + (latToWorldY(v.latitude) - centerWy) * worldSizePx).toFloat(),
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

        // 4. 번호 마커 핀
        validPlaces.forEachIndexed { idx, visit ->
            val px = (halfW + (lngToWorldX(visit.longitude) - centerWx) * worldSizePx).toFloat()
            val py = (halfH + (latToWorldY(visit.latitude) - centerWy) * worldSizePx).toFloat()
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

        // 5. 선택된 핀 말풍선 요약
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

        // 6. 우측 하단 줌(+/-) · 전체 맞춤 버튼
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp),
        ) {
            MapControlButton(
                icon = Icons.Default.Add,
                contentDescription = "확대",
                onClick = { zoomLevel = (zoomLevel + 1.0).coerceAtMost(18.2) },
            )
            MapControlButton(
                icon = Icons.Default.Remove,
                contentDescription = "축소",
                onClick = { zoomLevel = (zoomLevel - 1.0).coerceAtLeast(3.5) },
            )
            MapControlButton(
                icon = Icons.Default.CenterFocusStrong,
                contentDescription = "전체 위치 맞춤",
                onClick = {
                    val (cWx, cWy, fitZ) = fitWorldBounds(validPlaces, widthPx, heightPx, baseTilePx)
                    centerWx = cWx
                    centerWy = cWy
                    zoomLevel = fitZ
                },
            )
        }
    }
}

@Composable
private fun MapControlButton(
    icon: ImageVector,
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
