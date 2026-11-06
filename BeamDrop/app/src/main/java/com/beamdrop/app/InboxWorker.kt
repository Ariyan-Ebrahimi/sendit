package com.beamdrop.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.beamdrop.app.api.BeamDropApiClient
import com.beamdrop.app.data.SettingsStore
import com.beamdrop.app.i18n.I18n
import com.beamdrop.app.i18n.tr
import com.beamdrop.app.util.Files
import java.util.concurrent.TimeUnit

/**
 * Every ~15 minutes (when online) asks the server whether someone sent files to this user,
 * and shows a notification for each new one. Tapping it opens the files in Sendit.
 */
class InboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsStore(applicationContext)
        if (!settings.isLoggedIn) return Result.success()
        I18n.lang = I18n.resolve(settings.language)
        val items = runCatching { BeamDropApiClient(applicationContext, settings).inbox() }.getOrElse { return Result.retry() }
        val known = settings.notifiedInboxCodes + settings.openedInboxCodes
        val fresh = items.filterNot { it.code in known }
        fresh.forEach { notify(applicationContext, it.code, it.sender, it.fileCount, it.totalSize, it.url, it.message) }
        // Things that happened to my shares / support replies
        runCatching { BeamDropApiClient(applicationContext, settings).notices(settings.lastNoticeId) }.onSuccess { list ->
            list.forEach { notifyNotice(applicationContext, it) }
            list.maxOfOrNull { it.id }?.let { settings.lastNoticeId = it }
        }
        // Messages from the Sendit admin
        runCatching { BeamDropApiClient(applicationContext, settings).broadcasts(settings.lastBroadcastId) }.onSuccess { list ->
            list.sortedBy { it.id }.forEach { notifyBroadcast(applicationContext, it) }
            list.maxOfOrNull { it.id }?.let { settings.lastBroadcastId = it }
        }
        if (fresh.isNotEmpty()) settings.notifiedInboxCodes = settings.notifiedInboxCodes + fresh.map { it.code }
        scheduleSoon(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "sendit-inbox"
        const val EXTRA_OPEN_URL = "open_url"

        private const val FAST_WORK_NAME = "sendit-inbox-soon"

        /**
         * Android only allows periodic work every 15 minutes, so a one-time check is also queued
         * a few minutes ahead, and each check queues the next one.
         */
        fun scheduleSoon(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<InboxWorker>()
                .setInitialDelay(3, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(FAST_WORK_NAME, androidx.work.ExistingWorkPolicy.REPLACE, request)
        }

        fun schedule(context: Context) {
            scheduleSoon(context)
            val request = PeriodicWorkRequestBuilder<InboxWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** "Downloaded", "expires soon" (with Extend button) and support replies. */
        fun notifyNotice(context: Context, n: com.beamdrop.app.api.Notice) {
            val screen = when (n.kind) { "support" -> "support"; "plan", "plan_end" -> "plans"; else -> "my_shares" }
            val open = PendingIntent.getActivity(
                context, 9000 + (n.id % 1000).toInt(),
                Intent(context, MainActivity::class.java)
                    .putExtra(SenditWidget.EXTRA_OPEN, screen)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val (title, text) = when (n.kind) {
                "downloaded" -> (n.body?.let { tr("%s downloaded your files", it) } ?: tr("Your files were downloaded")) to
                    ((n.name?.substringAfterLast('/') ?: "") + if (n.count > 1) " +${n.count - 1}" else "")
                "expiring" -> tr("Your share expires soon") to tr("Code %s · tap Extend to keep it 1 more day", n.code ?: "")
                "support" -> tr("Sendit support replied") to (n.body ?: "")
                "plan" -> tr("Your %s plan ends soon", n.name ?: "Sendit") to tr("It ends on %s. Tap to keep it.",
                    n.body?.let { runCatching { java.text.DateFormat.getDateInstance().format(java.util.Date(java.time.Instant.parse(it).toEpochMilli())) }.getOrNull() } ?: "")
                "plan_end" -> tr("Your %s plan has ended", n.name ?: "Sendit") to tr("You're back on the free plan. Tap to get it again.")
                else -> return
            }
            val id = 8000 + (n.code?.hashCode() ?: n.id.toInt()).let { if (n.kind == "support") 1 else it and 0xFFFF }
            val builder = NotificationCompat.Builder(context, SenditApp.CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(open)
            if (n.kind == "expiring" && n.code != null) {
                val extend = PendingIntent.getBroadcast(
                    context, n.code.hashCode(),
                    Intent(context, ExtendReceiver::class.java).putExtra(ExtendReceiver.EXTRA_CODE, n.code).putExtra(ExtendReceiver.EXTRA_NOTIFICATION, id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                builder.addAction(0, tr("Extend 1 day"), extend)
            }
            val manager = NotificationManagerCompat.from(context)
            if (manager.areNotificationsEnabled()) runCatching { manager.notify(id, builder.build()) }
        }

        fun notifyBroadcast(context: Context, b: com.beamdrop.app.api.Broadcast) {
            val open = PendingIntent.getActivity(
                context, 7000 + b.id,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(context, SenditApp.CHANNEL_INBOX)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(b.title)
                .setContentText(b.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(b.body))
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            val manager = NotificationManagerCompat.from(context)
            if (manager.areNotificationsEnabled()) runCatching { manager.notify(7000 + b.id, n) }
        }

        fun notify(context: Context, code: String, sender: String, count: Int, size: Long, url: String, message: String = "") {
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_OPEN_URL, url)
            val pending = PendingIntent.getActivity(
                context, code.hashCode(), open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val text = if (count == 1) tr("1 file · %s · tap to download", Files.formatBytes(size))
            else tr("%d files · %s · tap to download", count, Files.formatBytes(size))
            val notification = NotificationCompat.Builder(context, SenditApp.CHANNEL_INBOX)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(tr("%s sent you files", sender))
                .setContentText(if (message.isNotBlank()) message else text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(if (message.isNotBlank()) "$message\n$text" else text))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pending)
                .build()
            val manager = NotificationManagerCompat.from(context)
            if (manager.areNotificationsEnabled()) runCatching { manager.notify(code.hashCode(), notification) }
        }
    }
}
