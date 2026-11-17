package com.aiutil.daylog.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 위도/경도 좌표를 한국어 도시명·장소명·주소·주변 상호(POI)로 변환하는 역지오코딩 도우미.
 * Android Geocoder + OpenStreetMap Overpass 반경 180m POI 검색 + Nominatim을 결합한다.
 */
class GeocodingHelper(private val context: Context) {

    data class GeoLabel(
        val cityName: String,
        val placeName: String,
        val nearbyPoi: String,
        val osmCategoryHint: String,
        val address: String,
    )

    private val cache = ConcurrentHashMap<String, GeoLabel>()
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    fun clearCache() {
        cache.clear()
    }

    suspend fun reverseGeocode(latitude: Double, longitude: Double): GeoLabel {
        val key = String.format(Locale.US, "%.4f,%.4f", latitude, longitude)
        cache[key]?.let { return it }

        // 1. Android 내장 Geocoder로 한국어 도시/주소 조회
        val addressObj = if (Geocoder.isPresent()) {
            withTimeoutOrNull(3500L) { fetchFirstAddress(latitude, longitude) }
        } else {
            null
        }
        val base = if (addressObj != null) {
            formatKoreanAddress(addressObj, latitude, longitude)
        } else {
            GeoLabel(
                cityName = "",
                placeName = GeoUtils.formatCoordFallback(latitude, longitude),
                nearbyPoi = "",
                osmCategoryHint = "",
                address = "",
            )
        }

        // 2. Overpass API로 반경 180m 이내 실제 상호·시설(POI) 검색
        val overpassPoi = withTimeoutOrNull(3500L) {
            fetchNearestOverpassPoi(latitude, longitude)
        }

        // 3. Overpass에서 못 찾은 경우 Nominatim reverse 보조 조회
        val osmReverse = if (overpassPoi == null) {
            withTimeoutOrNull(2500L) { fetchOsmNominatim(latitude, longitude) }
        } else {
            null
        }

        val mergedCity = base.cityName.ifBlank { osmReverse?.cityName.orEmpty() }
        val mergedPoi = overpassPoi?.name?.takeIf { it.isNotBlank() }
            ?: osmReverse?.nearbyPoi?.takeIf { it.isNotBlank() }
            ?: base.nearbyPoi
        val mergedCategoryHint = overpassPoi?.categoryHint?.takeIf { it.isNotBlank() }
            ?: osmReverse?.categoryHint.orEmpty()

        val mergedPlaceName = when {
            mergedPoi.isNotBlank() -> mergedPoi
            base.placeName.isNotBlank() -> base.placeName
            else -> osmReverse?.displayName.orEmpty()
        }.ifBlank { GeoUtils.formatCoordFallback(latitude, longitude) }

        val merged = GeoLabel(
            cityName = mergedCity,
            placeName = mergedPlaceName,
            nearbyPoi = mergedPoi,
            osmCategoryHint = mergedCategoryHint,
            address = base.address.ifBlank { osmReverse?.displayName.orEmpty() },
        )
        cache[key] = merged
        return merged
    }

    private data class PoiCandidate(
        val name: String,
        val categoryHint: String,
        val distanceMeters: Double,
        val priority: Int,
    )

    private suspend fun fetchNearestOverpassPoi(latitude: Double, longitude: Double): PoiCandidate? =
        withContext(Dispatchers.IO) {
            runCatching {
                val query = String.format(
                    Locale.US,
                    "[out:json][timeout:4];(node(around:180,%.6f,%.6f)[\"name\"];way(around:180,%.6f,%.6f)[\"name\"];);out center 20;",
                    latitude,
                    longitude,
                    latitude,
                    longitude,
                )
                val body = FormBody.Builder().add("data", query).build()
                val request = Request.Builder()
                    .url("https://overpass-api.de/api/interpreter")
                    .header("User-Agent", "DaylogAndroidApp/2.0 (personal-lifelog)")
                    .post(body)
                    .build()
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val raw = resp.body?.string() ?: return@use null
                    parseNearestOverpassPoi(raw, latitude, longitude)
                }
            }.getOrNull()
        }

    private fun parseNearestOverpassPoi(rawJson: String, lat: Double, lng: Double): PoiCandidate? {
        return runCatching {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val elements = root["elements"]?.jsonArray ?: return null
            val poiTagKeys = listOf("amenity", "tourism", "shop", "leisure", "office", "healthcare", "craft", "building")

            elements.mapNotNull { el ->
                val obj = el.jsonObject
                val tags = obj["tags"]?.jsonObject ?: return@mapNotNull null
                val name = tags["name:ko"]?.jsonPrimitive?.content?.trim()
                    ?: tags["name"]?.jsonPrimitive?.content?.trim()
                    ?: return@mapNotNull null
                if (name.isBlank()) return@mapNotNull null

                // 단순 도로명이나 행정 경계는 제외하고 실제 시설/상호를 우선 선택
                val matchedHint = poiTagKeys.firstNotNullOfOrNull { key ->
                    val v = tags[key]?.jsonPrimitive?.content?.trim()
                    if (!v.isNullOrBlank() && v != "yes") "$key:$v" else null
                } ?: if (tags.containsKey("building") || tags.containsKey("landuse")) {
                    "building:yes"
                } else {
                    return@mapNotNull null
                }

                val eLat = obj["lat"]?.jsonPrimitive?.doubleOrNull
                    ?: obj["center"]?.jsonObject?.get("lat")?.jsonPrimitive?.doubleOrNull
                    ?: return@mapNotNull null
                val eLng = obj["lon"]?.jsonPrimitive?.doubleOrNull
                    ?: obj["center"]?.jsonObject?.get("lon")?.jsonPrimitive?.doubleOrNull
                    ?: return@mapNotNull null

                val dist = GeoUtils.distanceMeters(lat, lng, eLat, eLng)
                val priority = when {
                    tags.containsKey("tourism") || tags.containsKey("amenity") || tags.containsKey("shop") || tags.containsKey("leisure") || tags.containsKey("healthcare") -> 1
                    tags.containsKey("office") -> 2
                    else -> 3
                }
                PoiCandidate(name = name, categoryHint = matchedHint, distanceMeters = dist, priority = priority)
            }.minWithOrNull(compareBy<PoiCandidate> { it.priority }.thenBy { it.distanceMeters })
        }.getOrNull()
    }

    private data class OsmInfo(
        val cityName: String,
        val nearbyPoi: String,
        val categoryHint: String,
        val displayName: String,
    )

    private suspend fun fetchOsmNominatim(latitude: Double, longitude: Double): OsmInfo? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = String.format(
                    Locale.US,
                    "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%.6f&lon=%.6f&zoom=18&addressdetails=1&accept-language=ko",
                    latitude,
                    longitude,
                )
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "DaylogAndroidApp/2.0 (personal-lifelog)")
                    .get()
                    .build()
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val raw = resp.body?.string() ?: return@use null
                    val obj = json.parseToJsonElement(raw).jsonObject
                    val name = obj["name"]?.jsonPrimitive?.content?.trim().orEmpty()
                    val category = obj["category"]?.jsonPrimitive?.content?.trim().orEmpty()
                    val type = obj["type"]?.jsonPrimitive?.content?.trim().orEmpty()
                    val displayName = obj["display_name"]?.jsonPrimitive?.content?.trim().orEmpty()
                    val addrObj = obj["address"]?.jsonObject

                    val city = listOf("city", "town", "county", "province")
                        .firstNotNullOfOrNull { k -> addrObj?.get(k)?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotBlank) }
                        .orEmpty()

                    // 도로/행정구역 자체 이름은 상호명(POI)으로 오인하지 않도록 제외
                    val isRoadOrAdmin = category in setOf("highway", "boundary", "place")
                    OsmInfo(
                        cityName = simplifyCityName(city),
                        nearbyPoi = if (isRoadOrAdmin) "" else name,
                        categoryHint = "$category:$type",
                        displayName = displayName,
                    )
                }
            }.getOrNull()
        }

    private suspend fun fetchFirstAddress(latitude: Double, longitude: Double): Address? {
        val geocoder = Geocoder(context, Locale.KOREAN)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    geocoder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                }.onFailure {
                    if (cont.isActive) cont.resume(null)
                }
            }
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
                }.getOrNull()
            }
        }
    }

    private fun formatKoreanAddress(addr: Address, lat: Double, lng: Double): GeoLabel {
        val fullLine = addr.getAddressLine(0)?.replace("대한민국", "")?.trim().orEmpty()
        val admin = addr.adminArea.orEmpty()
        val locality = addr.locality ?: addr.subAdminArea.orEmpty()
        val dong = addr.subLocality ?: addr.thoroughfare.orEmpty()
        val feature = addr.featureName.orEmpty()

        val city = simplifyCityName(locality.ifBlank { admin })
        val shortParts = listOf(locality, dong)
            .filter { it.isNotBlank() }
            .distinct()
        val shortName = when {
            shortParts.isNotEmpty() -> shortParts.joinToString(" ")
            admin.isNotBlank() -> admin
            feature.isNotBlank() -> feature
            fullLine.isNotBlank() -> fullLine.take(28)
            else -> GeoUtils.formatCoordFallback(lat, lng)
        }

        val isFeatureJustAdminOrNumber = feature.isBlank() ||
            feature.all { it.isDigit() || it == '-' } ||
            feature == locality ||
            feature == dong ||
            feature == admin
        val featurePoi = if (!isFeatureJustAdminOrNumber) feature else ""

        return GeoLabel(
            cityName = city,
            placeName = shortName,
            nearbyPoi = featurePoi,
            osmCategoryHint = "",
            address = fullLine.ifBlank { listOf(admin, locality, dong, feature).filter { it.isNotBlank() }.joinToString(" ") },
        )
    }

    companion object {
        internal fun simplifyCityName(raw: String): String {
            val clean = raw.trim()
            if (clean.isBlank()) return ""
            return clean
                .replace("특별시", "시")
                .replace("광역시", "시")
                .replace("특별자치시", "시")
                .replace("특별자치도", "")
                .trim()
        }
    }
}
