package com.aiutil.daylog.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.MotionEvent
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aiutil.daylog.model.PlaceVisit
import com.aiutil.daylog.util.DateUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class MapPinPayload(
    val index: Int,
    val title: String,
    val subtitle: String,
    val lat: Double,
    val lng: Double,
    val isStay: Boolean,
)

/**
 * 그날(또는 최근) 이동 및 체류 장소를 모두 하나의 인터랙티브 지도 위에 순서·동선과 함께 표시한다.
 */
@SuppressLint("SetJavaScriptEnabled")
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
    var selectedIdx by remember(validPlaces) { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val mapHtml = remember(validPlaces, stayThresholdMin) {
        buildPlacesMapHtml(validPlaces, stayThresholdMin)
    }

    BackHandler(onBack = onDismiss)

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webViewRef?.stopLoading()
                webViewRef?.destroy()
            }
            webViewRef = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // 상단 헤더 바
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

        // 중앙 인터랙티브 지도 영역
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (validPlaces.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "지도에 표시할 좌표 데이터가 없습니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadsImagesAutomatically = true
                            settings.cacheMode = WebSettings.LOAD_DEFAULT
                            webViewClient = WebViewClient()
                            loadDataWithBaseURL("https://localhost/", mapHtml, "text/html", "UTF-8", null)
                            webViewRef = this
                        }
                    },
                    update = { view ->
                        webViewRef = view
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // 하단 장소 선택 가로 카드 스트립 (누르면 지도 해당 핀으로 이동)
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
                                    webViewRef?.evaluateJavascript("if (window.focusPlace) window.focusPlace($idx);", null)
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
 * 탭/카드 내부에 바로 삽입해 선택한 날짜의 이동·체류 장소를 지도로 띄우는 인라인 WebView 지도.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun PlacesInlineMapView(
    places: List<PlaceVisit>,
    stayThresholdMin: Int = 3,
    focusIndex: Int? = null,
    modifier: Modifier = Modifier,
) {
    val validPlaces = remember(places) {
        places.filter { !(it.latitude == 0.0 && it.longitude == 0.0) }
    }
    val mapHtml = remember(validPlaces, stayThresholdMin) {
        buildPlacesMapHtml(validPlaces, stayThresholdMin)
    }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var lastLoadedHtml by remember { mutableStateOf("") }

    LaunchedEffect(focusIndex) {
        val idx = focusIndex ?: return@LaunchedEffect
        if (idx in validPlaces.indices) {
            webViewRef?.evaluateJavascript("if (window.focusPlace) window.focusPlace($idx);", null)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webViewRef?.stopLoading()
                webViewRef?.destroy()
            }
            webViewRef = null
        }
    }

    if (validPlaces.isEmpty()) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "이 날짜에 지도에 표시할 좌표가 없습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    webViewClient = WebViewClient()
                    setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        false
                    }
                    lastLoadedHtml = mapHtml
                    loadDataWithBaseURL("https://localhost/", mapHtml, "text/html", "UTF-8", null)
                    webViewRef = this
                }
            },
            update = { view ->
                webViewRef = view
                if (lastLoadedHtml != mapHtml) {
                    lastLoadedHtml = mapHtml
                    view.loadDataWithBaseURL("https://localhost/", mapHtml, "text/html", "UTF-8", null)
                }
            },
            modifier = modifier,
        )
    }
}

fun openAllPlacesInExternalMap(context: android.content.Context, places: List<PlaceVisit>) {
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

private fun buildPlacesMapHtml(places: List<PlaceVisit>, stayThresholdMin: Int): String {
    val pins = places.mapIndexed { idx, v ->
        val mainName = listOf(v.nearbyPoi, v.placeName).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        val timeRange = if (v.endMs > v.startMs + 60_000L) {
            "${DateUtils.formatTimeHm(v.startMs)} ~ ${DateUtils.formatTimeHm(v.endMs)} (${DateUtils.formatStayMinutes(v.durationMinutes)})"
        } else {
            "${DateUtils.formatTimeHm(v.startMs)} · ${v.source.label}"
        }
        val extra = listOf(v.cityName, v.address).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        MapPinPayload(
            index = idx + 1,
            title = mainName.ifBlank { "장소 ${idx + 1}" },
            subtitle = if (extra.isNotBlank()) "$timeRange<br/>$extra" else timeRange,
            lat = v.latitude,
            lng = v.longitude,
            isStay = v.isStayWithThreshold(stayThresholdMin),
        )
    }
    val jsonPins = Json.encodeToString(pins)

    return """
        <!DOCTYPE html>
        <html lang="ko">
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=yes" />
          <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
          <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
          <style>
            html, body, #map { height: 100%; width: 100%; margin: 0; padding: 0; background: #141619; font-family: sans-serif; }
            .pin-stay {
              width: 28px; height: 28px; border-radius: 50%;
              background: #111827; color: #ffffff; border: 2px solid #ffffff;
              display: flex; align-items: center; justify-content: center;
              font-weight: 700; font-size: 12px; box-shadow: 0 2px 6px rgba(0,0,0,0.45);
            }
            .pin-pass {
              width: 22px; height: 22px; border-radius: 50%;
              background: #6b7280; color: #ffffff; border: 1.5px solid #ffffff;
              display: flex; align-items: center; justify-content: center;
              font-weight: 600; font-size: 11px; box-shadow: 0 2px 4px rgba(0,0,0,0.35);
            }
            #fallback {
              display: none; height: 100%; width: 100%; color: #e5e7eb;
              flex-direction: column; align-items: center; justify-content: center; text-align: center; padding: 16px; box-sizing: border-box;
            }
          </style>
        </head>
        <body>
          <div id="map"></div>
          <div id="fallback">
            <canvas id="routeCanvas" style="width:100%;height:100%;"></canvas>
          </div>
          <script>
            var pins = $jsonPins;
            var markers = [];
            var mapObj = null;

            function esc(str) {
              return String(str).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
            }

            if (typeof L !== 'undefined' && pins.length > 0) {
              mapObj = L.map('map', { zoomControl: true });
              L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
                maxZoom: 19,
                attribution: '© OpenStreetMap'
              }).addTo(mapObj);

              var latlngs = [];
              pins.forEach(function(p) {
                var ll = [p.lat, p.lng];
                latlngs.push(ll);
                var cls = p.isStay ? 'pin-stay' : 'pin-pass';
                var sz = p.isStay ? 28 : 22;
                var icon = L.divIcon({
                  className: '',
                  html: '<div class="' + cls + '">' + p.index + '</div>',
                  iconSize: [sz, sz],
                  iconAnchor: [sz / 2, sz / 2]
                });
                var marker = L.marker(ll, { icon: icon }).addTo(mapObj);
                marker.bindPopup('<b>' + p.index + '. ' + esc(p.title) + '</b><br/><span style="font-size:12px;color:#555">' + p.subtitle + '</span>');
                markers.push(marker);
              });

              if (latlngs.length > 1) {
                L.polyline(latlngs, { color: '#2563eb', weight: 3.5, opacity: 0.85, dashArray: '6, 6' }).addTo(mapObj);
                mapObj.fitBounds(L.latLngBounds(latlngs), { padding: [38, 38], maxZoom: 16 });
              } else {
                mapObj.setView(latlngs[0], 15);
              }
            } else {
              document.getElementById('map').style.display = 'none';
              var fb = document.getElementById('fallback');
              fb.style.display = 'flex';
              drawFallback();
            }

            function drawFallback() {
              var canvas = document.getElementById('routeCanvas');
              var rect = canvas.getBoundingClientRect();
              canvas.width = rect.width * window.devicePixelRatio;
              canvas.height = rect.height * window.devicePixelRatio;
              var ctx = canvas.getContext('2d');
              ctx.scale(window.devicePixelRatio, window.devicePixelRatio);
              if (pins.length === 0) return;

              var minLat = pins[0].lat, maxLat = pins[0].lat, minLng = pins[0].lng, maxLng = pins[0].lng;
              pins.forEach(function(p) {
                if (p.lat < minLat) minLat = p.lat;
                if (p.lat > maxLat) maxLat = p.lat;
                if (p.lng < minLng) minLng = p.lng;
                if (p.lng > maxLng) maxLng = p.lng;
              });
              var pad = 48;
              var w = Math.max(rect.width - pad * 2, 1);
              var h = Math.max(rect.height - pad * 2, 1);
              var spanLat = Math.max(maxLat - minLat, 0.002);
              var spanLng = Math.max(maxLng - minLng, 0.002);

              function toXY(p) {
                var x = pad + ((p.lng - minLng) / spanLng) * w;
                var y = pad + (1 - (p.lat - minLat) / spanLat) * h;
                return [x, y];
              }

              if (pins.length > 1) {
                ctx.strokeStyle = '#60a5fa';
                ctx.lineWidth = 2.5;
                ctx.setLineDash([6, 5]);
                ctx.beginPath();
                pins.forEach(function(p, i) {
                  var xy = toXY(p);
                  if (i === 0) ctx.moveTo(xy[0], xy[1]); else ctx.lineTo(xy[0], xy[1]);
                });
                ctx.stroke();
                ctx.setLineDash([]);
              }

              pins.forEach(function(p) {
                var xy = toXY(p);
                ctx.beginPath();
                ctx.arc(xy[0], xy[1], p.isStay ? 12 : 9, 0, Math.PI * 2);
                ctx.fillStyle = p.isStay ? '#ffffff' : '#9ca3af';
                ctx.fill();
                ctx.fillStyle = '#111827';
                ctx.font = 'bold 11px sans-serif';
                ctx.textAlign = 'center';
                ctx.textBaseline = 'middle';
                ctx.fillText(String(p.index), xy[0], xy[1]);
              });
            }

            window.focusPlace = function(idx) {
              if (!mapObj || !markers[idx]) return;
              var ll = markers[idx].getLatLng();
              mapObj.setView(ll, Math.max(mapObj.getZoom(), 15), { animate: true });
              markers[idx].openPopup();
            };
          </script>
        </body>
        </html>
    """.trimIndent()
}
