package com.aiutil.daylog.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * 위도/경도 좌표를 한국어 장소명과 주소로 변환하는 역지오코딩 도우미.
 * 같은 지역 좌표는 캐시해 불필요한 네트워크 호출을 줄인다.
 */
class GeocodingHelper(private val context: Context) {

    data class GeoLabel(
        val placeName: String,
        val address: String,
    )

    private val cache = ConcurrentHashMap<String, GeoLabel>()

    suspend fun reverseGeocode(latitude: Double, longitude: Double): GeoLabel {
        val key = String.format(Locale.US, "%.3f,%.3f", latitude, longitude)
        cache[key]?.let { return it }

        if (!Geocoder.isPresent()) {
            return GeoLabel(
                placeName = GeoUtils.formatCoordFallback(latitude, longitude),
                address = "",
            )
        }

        val addressObj = withTimeoutOrNull(4000L) {
            fetchFirstAddress(latitude, longitude)
        }

        val label = if (addressObj != null) {
            formatKoreanAddress(addressObj, latitude, longitude)
        } else {
            GeoLabel(
                placeName = GeoUtils.formatCoordFallback(latitude, longitude),
                address = "",
            )
        }
        cache[key] = label
        return label
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

        return GeoLabel(
            placeName = shortName,
            address = fullLine.ifBlank { listOf(admin, locality, dong, feature).filter { it.isNotBlank() }.joinToString(" ") },
        )
    }
}
