package com.beamdrop.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.beamdrop.app.api.BeamDropApiClient
import com.beamdrop.app.data.SettingsStore
import com.beamdrop.app.i18n.I18n
import com.beamdrop.app.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** "Extend 1 day" button on the "expires soon" notification. */
class ExtendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra(EXTRA_CODE) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION, 0)
        val pending = goAsync()
        val settings = SettingsStore(context)
        I18n.lang = I18n.resolve(settings.language)
        CoroutineScope(Dispatchers.IO).launch {
            val ok = runCatching { BeamDropApiClient(context, settings).extendShare(code, 24) }.isSuccess
            val n = NotificationCompat.Builder(context, SenditApp.CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(if (ok) tr("Extended by 1 day") else tr("Couldn't extend"))
                .setContentText(if (ok) tr("Code %s works for another day.", code) else tr("Open Sendit and try again from My shares."))
                .setAutoCancel(true)
                .setTimeoutAfter(8000)
                .build()
            val manager = NotificationManagerCompat.from(context)
            if (manager.areNotificationsEnabled()) runCatching { manager.notify(notificationId, n) }
            pending.finish()
        }
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_NOTIFICATION = "notification"
    }
}
