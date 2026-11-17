package com.aiutil.daylog.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aiutil.daylog.App
import com.aiutil.daylog.ui.MainActivity
import com.aiutil.daylog.util.DateUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 사용자의 이동 및 체류 장소를 백그라운드에서 저전력으로 기록하는 포그라운드 위치 서비스.
 */
class LocationTrackerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var locationManager: LocationManager? = null
    private var isRegistered = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleNewLocation(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTrackingInternal()
            stopSelf()
            return START_NOT_STICKY
        }

        if (!hasLocationPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification("이동 및 체류 장소를 조용히 기록하고 있습니다.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startTrackingInternal()
        return START_STICKY
    }

    override fun onDestroy() {
        stopTrackingInternal()
        serviceScope.cancel()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startTrackingInternal() {
        if (isRegistered) return
        val lm = locationManager ?: return
        if (!hasLocationPermission(this)) return

        val intervalMin = runCatching { App.settings.settings.value.locationIntervalMin }.getOrDefault(3)
        val minTimeMs = intervalMin.coerceIn(1, 30) * 60_000L
        val minDistanceM = 80f

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                lm.allProviders.contains(LocationManager.FUSED_PROVIDER)
            ) {
                lm.requestLocationUpdates(LocationManager.FUSED_PROVIDER, minTimeMs, minDistanceM, locationListener)
                isRegistered = true
            } else {
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, minTimeMs, minDistanceM, locationListener)
                    isRegistered = true
                }
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, minTimeMs, minDistanceM, locationListener)
                    isRegistered = true
                }
            }
            // 시작 시점의 마지막 알려진 위치 즉시 1회 기록
            val lastLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (lastLoc != null) {
                handleNewLocation(lastLoc)
            }
        }
        _isRunning.value = isRegistered
    }

    private fun stopTrackingInternal() {
        if (isRegistered) {
            runCatching { locationManager?.removeUpdates(locationListener) }
            isRegistered = false
        }
        _isRunning.value = false
    }

    private fun handleNewLocation(location: Location) {
        if (location.hasAccuracy() && location.accuracy > 180f) return
        val ts = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        val dateKey = DateUtils.dateKeyFromMs(ts)

        serviceScope.launch {
            val geo = App.geocoder.reverseGeocode(location.latitude, location.longitude)
            val updatedVisits = App.locationVisits.recordLocationSample(
                dateKey = dateKey,
                timestampMs = ts,
                latitude = location.latitude,
                longitude = location.longitude,
                placeName = geo.placeName,
                address = geo.address,
            )
            // 오늘 기록이 이미 열려 있으면 장소 목록도 즉시 갱신
            App.daylogs.updatePlacesForDate(
                dateKey = dateKey,
                gpsVisits = updatedVisits,
                aliases = App.locationVisits.aliases.value,
            )
            val latestName = updatedVisits.lastOrNull()?.placeName.orEmpty()
            if (latestName.isNotBlank()) {
                val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                mgr?.notify(NOTIFICATION_ID, buildNotification("최근 위치: $latestName"))
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "데이로그 이동·체류 위치 기록",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "하루 동안 머무른 장소와 이동 내역을 기록합니다."
                setShowBadge(false)
            }
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            mgr?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("데이로그 위치 기록 중")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "daylog_location_channel"
        private const val NOTIFICATION_ID = 2041
        private const val ACTION_STOP = "com.aiutil.daylog.STOP_LOCATION_TRACKING"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun hasLocationPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

        fun start(context: Context) {
            if (!hasLocationPermission(context)) return
            val intent = Intent(context, LocationTrackerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocationTrackerService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }
    }
}
