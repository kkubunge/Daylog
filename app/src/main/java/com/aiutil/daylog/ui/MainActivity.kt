package com.aiutil.daylog.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.aiutil.daylog.App
import com.aiutil.daylog.location.LocationTrackerService
import com.aiutil.daylog.model.PermissionStatus
import com.aiutil.daylog.ui.theme.DaylogTheme

/**
 * 데이로그 메인 액티비티.
 * 사진(+EXIF 위치), 통화, 문자, 위치(GPS/백그라운드), SNS 알림 접근 상태를 관리한다.
 */
class MainActivity : ComponentActivity() {

    private var permissionStatus by mutableStateOf(PermissionStatus())
    private var sharedPayload by mutableStateOf<SharedIncomingPayload?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissions()
        syncLocationServiceState()
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshPermissions()
        syncLocationServiceState()
        handleIncomingShareIntent(intent)

        setContent {
            DaylogTheme {
                AppRoot(
                    permissionStatus = permissionStatus,
                    sharedPayload = sharedPayload,
                    onConsumeSharedPayload = { sharedPayload = null },
                    onRequestRuntimePermissions = { requestAllRuntimePermissions() },
                    onRequestBackgroundLocation = { requestBackgroundLocationPermission() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        syncLocationServiceState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun refreshPermissions() {
        val collector = App.collector
        val locationGranted = LocationTrackerService.hasLocationPermission(this)
        val bgGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            locationGranted
        }
        val postNotifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val snsEnabled = NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)

        permissionStatus = PermissionStatus(
            photosGranted = collector.hasPhotoPermission(),
            audioGranted = collector.hasAudioPermission(),
            mediaLocationGranted = collector.hasMediaLocationPermission(),
            callLogGranted = collector.hasCallLogPermission(),
            contactsGranted = collector.hasContactsPermission(),
            smsGranted = collector.hasSmsPermission(),
            calendarGranted = collector.hasCalendarPermission(),
            locationGranted = locationGranted,
            backgroundLocationGranted = bgGranted,
            notificationPostGranted = postNotifGranted,
            snsNotificationEnabled = snsEnabled,
        )
    }

    private fun syncLocationServiceState() {
        val shouldTrack = App.settings.settings.value.trackLocation
        if (shouldTrack && LocationTrackerService.hasLocationPermission(this)) {
            LocationTrackerService.start(this)
        }
    }

    private fun requestAllRuntimePermissions() {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                }
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACCESS_MEDIA_LOCATION)
            }
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.READ_CALENDAR)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }.toTypedArray()
        permissionLauncher.launch(perms)
    }

    private fun requestBackgroundLocationPermission() {
        if (!LocationTrackerService.hasLocationPermission(this)) {
            requestAllRuntimePermissions()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+에서는 백그라운드 위치 단독 요청 또는 앱 설정 화면 안내
                runCatching {
                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }.onFailure {
                    openAppDetailsSettings()
                }
            } else {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    private fun openAppDetailsSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(intent) }
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val streamUri = IntentCompat.getParcelableExtra(
                    intent,
                    Intent.EXTRA_STREAM,
                    Uri::class.java,
                )
                val uris = if (streamUri != null) listOf(streamUri.toString()) else emptyList()
                if (text.isNotBlank() || uris.isNotEmpty()) {
                    sharedPayload = SharedIncomingPayload(text = text, imageUris = uris)
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val streams = IntentCompat.getParcelableArrayListExtra(
                    intent,
                    Intent.EXTRA_STREAM,
                    Uri::class.java,
                ).orEmpty()
                val uris = streams.map { it.toString() }
                if (text.isNotBlank() || uris.isNotEmpty()) {
                    sharedPayload = SharedIncomingPayload(text = text, imageUris = uris)
                }
            }
        }
    }
}
