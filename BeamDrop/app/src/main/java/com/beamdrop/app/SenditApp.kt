package com.beamdrop.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.beamdrop.app.i18n.I18n
import com.beamdrop.app.i18n.tr

/**
 * Holds the one [BeamDropViewModel] for the whole process, so an upload or download keeps
 * running (with [TransferService]) even after the screen is closed or swiped away.
 */
class SenditApp : Application() {

    val viewModel: BeamDropViewModel by lazy { BeamDropViewModel(this) }

    override fun onCreate() {
        super.onCreate()
        I18n.lang = I18n.resolve(com.beamdrop.app.data.SettingsStore(this).language)
        CrashReporter.install(this)
        createChannels()
        InboxWorker.schedule(this)
        viewModel.startInboxWatcher()
        viewModel.sendPendingCrash()
    }

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, tr("Transfers in progress"), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, tr("Finished transfers"), NotificationManager.IMPORTANCE_DEFAULT)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_INBOX, tr("Files sent to you"), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        const val CHANNEL_PROGRESS = "transfers"
        const val CHANNEL_DONE = "transfers_done"
        const val CHANNEL_INBOX = "inbox"
    }
}
