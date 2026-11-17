package com.aiutil.daylog.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.IntentCompat
import com.aiutil.daylog.App
import com.aiutil.daylog.model.PermissionStatus
import com.aiutil.daylog.ui.theme.DaylogTheme

/**
 * 데이로그 메인 액티비티.
 * 사진·통화·문자·연락처 런타임 권한, SNS 알림 접근 상태, 외부 앱 공유 수신을 관리한다.
 */
class MainActivity : ComponentActivity() {

    private var permissionStatus by mutableStateOf(PermissionStatus())
    private var sharedPayload by mutableStateOf<SharedIncomingPayload?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshPermissions()
        handleIncomingShareIntent(intent)

        setContent {
            DaylogTheme {
                AppRoot(
                    permissionStatus = permissionStatus,
                    sharedPayload = sharedPayload,
                    onConsumeSharedPayload = { sharedPayload = null },
                    onRequestRuntimePermissions = { requestAllRuntimePermissions() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun refreshPermissions() {
        val collector = App.collector
        val snsEnabled = NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)
        permissionStatus = PermissionStatus(
            photosGranted = collector.hasPhotoPermission(),
            callLogGranted = collector.hasCallLogPermission(),
            contactsGranted = collector.hasContactsPermission(),
            smsGranted = collector.hasSmsPermission(),
            snsNotificationEnabled = snsEnabled,
        )
    }

    private fun requestAllRuntimePermissions() {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                }
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.READ_SMS)
        }.toTypedArray()
        permissionLauncher.launch(perms)
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
                val uris = streams.take(2).map { it.toString() }
                if (text.isNotBlank() || uris.isNotEmpty()) {
                    sharedPayload = SharedIncomingPayload(text = text, imageUris = uris)
                }
            }
        }
    }
}
