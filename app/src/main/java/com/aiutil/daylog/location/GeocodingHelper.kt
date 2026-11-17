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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 위도/경도 좌표를 한국어 도시명·장소명·주소·주변 상호(POI)로 변환하는 역지오코딩 도우미.
 * Android Geocoder와 OpenStreetMap Nominatim을 조합하고 좌표 캐시를 사용한다.
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
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

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

        // 2. OpenStreetMap Nominatim으로 주변 건물/상호(POI) 및 도시명 보강
        val osmInfo = withTimeoutOrNull(2500L) {
            fetchOsmNominatim(latitude, longitude)
        }

        val mergedCity = base.cityName.ifBlank { osmInfo?.cityName.orEmpty() }
        val mergedPoi = osmInfo?.nearbyPoi?.takeIf { it.isNotBlank() } ?: base.nearbyPoi
        val mergedPlaceName = when {
            mergedPoi.isNotBlank() && base.placeName.isNotBlank() && !base.placeName.contains(mergedPoi) ->
                mergedPoi
            else -> base.placeName.ifBlank { osmInfo?.displayName.orEmpty() }
        }.ifBlank { GeoUtils.formatCoordFallback(latitude, longitude) }

        val merged = GeoLabel(
            cityName = mergedCity,
            placeName = mergedPlaceName,
            nearbyPoi = mergedPoi,
            osmCategoryHint = osmInfo?.categoryHint.orEmpty(),
            address = base.address.ifBlank { osmInfo?.displayName.orEmpty() },
        )
        cache[key] = merged
        return merged
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

                    OsmInfo(
                        cityName = simplifyCityName(city),
                        nearbyPoi = name,
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

        // 도로명 숫자만 있는 featureName은 상호로 취급하지 않음
        val featurePoi = if (feature.isNotBlank() && !feature.all { it.isDigit() || it == '-' }) {
            feature
        } else {
            ""
        }

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
