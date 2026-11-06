package com.beamdrop.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.beamdrop.app.ui.BeamDropApp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.beamdrop.app.ui.theme.BeamDropTheme

class MainActivity : androidx.fragment.app.FragmentActivity() {

    /** App lock: shown until the user unlocks; locks again after 30 s in the background. */
    private val locked = androidx.compose.runtime.mutableStateOf(false)
    private var backgroundSince = 0L

    // One shared ViewModel for the whole process, so transfers survive this screen closing.
    private val viewModel: BeamDropViewModel get() = (application as SenditApp).viewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        // Request highest smooth refresh rate (e.g. 120Hz) on supported displays
        runCatching {
            val params = window.attributes
            params.preferredRefreshRate = 120f
            window.attributes = params
        }
        requestLegacyStoragePermission()
        requestNotificationPermission()
        handleShareIntent(intent)
        handleOpenIntent(intent)
        handleWidgetIntent(intent)
        if (viewModel.appLock.value && savedInstanceState == null) locked.value = true
        setContent {
            val language by viewModel.language.collectAsState()
            val theme by viewModel.theme.collectAsState()
            val accent by viewModel.accent.collectAsState()
            val isLocked by locked
            val persian = com.beamdrop.app.i18n.I18n.resolve(language) == "fa"
            val dark = when (theme) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            com.beamdrop.app.ui.theme.Palette.apply(dark, com.beamdrop.app.ui.theme.Accent.of(accent))
            androidx.compose.runtime.SideEffect {
                val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
            key(language, dark, accent) {
                CompositionLocalProvider(LocalLayoutDirection provides if (persian) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    BeamDropTheme {
                        if (isLocked) {
                            com.beamdrop.app.ui.screens.LockScreen(onUnlock = { AppLock.prompt(this) { locked.value = false } })
                        } else {
                            BeamDropApp(viewModel)
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        backgroundSince = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        if (viewModel.appLock.value && backgroundSince > 0 && System.currentTimeMillis() - backgroundSince > 30_000) {
            locked.value = true
        }
    }

    /** Home-screen widget buttons: open Send or Receive directly. */
    private fun handleWidgetIntent(intent: Intent?) {
        val target = intent?.getStringExtra(SenditWidget.EXTRA_OPEN) ?: return
        viewModel.requestScreen(target)
        intent.removeExtra(SenditWidget.EXTRA_OPEN)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        handleOpenIntent(intent)
        handleWidgetIntent(intent)
    }

    /**
     * Android 8–9 write received files into the public Downloads folder, which
     * needs the legacy storage permission. Android 10+ uses scoped MediaStore
     * and never asks.
     */
    private fun requestLegacyStoragePermission() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                LEGACY_STORAGE_REQUEST
            )
        }
    }

    /** Android 13+: ask once so progress, "code ready" and "files for you" notifications can show. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
        }
    }

    /** A "someone sent you files" notification was tapped. */
    private fun handleOpenIntent(intent: Intent?) {
        val url = intent?.getStringExtra(InboxWorker.EXTRA_OPEN_URL) ?: return
        viewModel.requestOpenFromNotification(url)
        intent.removeExtra(InboxWorker.EXTRA_OPEN_URL)
    }

    private fun handleShareIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return

        val uris = linkedSetOf<Uri>()
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let { uris += it }
            }
        }
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?.let { uris += it }
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?.filterNotNull()
            ?.forEach { uris += it }

        if (uris.isNotEmpty()) {
            viewModel.stageSharedFiles(uris.toList())
        }
        // Consume the intent so a configuration change does not stage the same files twice.
        intent.action = null
        intent.removeExtra(Intent.EXTRA_STREAM)
    }

    private companion object {
        const val LEGACY_STORAGE_REQUEST = 1001
        const val NOTIFICATION_REQUEST = 1002
    }
}
