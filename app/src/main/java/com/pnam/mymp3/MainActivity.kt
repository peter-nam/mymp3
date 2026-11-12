package com.pnam.mymp3

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import com.pnam.mymp3.ui.AppRoot
import com.pnam.mymp3.ui.theme.MyMp3Theme

/**
 * The app's only screen.
 *
 * Also the entry point for every way an audio file can arrive: the launcher icon, "Open with…"
 * (ACTION_VIEW) and the Share sheet (ACTION_SEND). The Activity holds no playback state itself.
 */
class MainActivity : ComponentActivity() {

    private val app: MyMp3Application get() = application as MyMp3Application

    private val notificationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Without this on Android 13+ the playback notification is suppressed, and Android is
        // then free to stop the service early — which looks like "music stops when I lock it".
        requestNotificationPermissionIfNeeded()

        app.players.connect()
        handleIntent(intent)

        setContent {
            MyMp3Theme {
                AppRoot()
            }
        }
    }

    /** singleTask means a shared file arrives here rather than starting a second Activity. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return

        persistReadPermission(intent, uri)
        app.players.play(uri)
    }

    /**
     * ACTION_VIEW grants are normally persistable, so the file stays readable after the source
     * app's temporary grant lapses. ACTION_SEND grants often are not — we try and carry on either
     * way, because the file is still readable for as long as the grant lasts.
     */
    private fun persistReadPermission(intent: Intent, uri: Uri) {
        if (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionRequest.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
