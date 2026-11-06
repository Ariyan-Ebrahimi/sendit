package com.beamdrop.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.beamdrop.app.data.CloudPhase
import com.beamdrop.app.data.CloudShareUi
import com.beamdrop.app.data.Direction
import com.beamdrop.app.data.TransferStatus
import com.beamdrop.app.data.TransferUi
import com.beamdrop.app.i18n.tr
import com.beamdrop.app.util.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps uploads and downloads alive when Sendit is in the background, showing their progress in a
 * notification. When a share is ready it posts "Code ready" with a Share button; when files
 * arrive it posts "Files received" with an Open button.
 */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watchJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastCloudPhase: CloudPhase? = null
    private var lastTransferStatus: TransferStatus? = null

    private val vm get() = (application as SenditApp).viewModel

    override fun onBind(intent: Intent?): IBinder? = null

    // Android 15 caps dataSync services at 6 h/day; not stopping when told crashes the app.
    override fun onTimeout(startId: Int, fgsType: Int) = stopEverything()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val first = progressNotification(tr("Sendit"), tr("Starting…"), 0, true)
        ServiceCompat.startForeground(
            this, ID_PROGRESS, first,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sendit:transfer")
                .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
        if (watchJob == null) {
            watchJob = scope.launch {
                combine(vm.cloudShare, vm.transfer) { c, t -> c to t }.collect { (c, t) -> onState(c, t) }
            }
        }
        return START_NOT_STICKY
    }

    private fun onState(cloud: CloudShareUi?, transfer: TransferUi?) {
        val manager = NotificationManagerCompat.from(this)

        // Finished events (posted as their own notifications so they stay after the service stops).
        if (cloud?.phase != lastCloudPhase) {
            if (lastCloudPhase == CloudPhase.UPLOADING && cloud?.phase == CloudPhase.READY) postShareReady(cloud)
            if (lastCloudPhase == CloudPhase.UPLOADING && cloud?.phase == CloudPhase.FAILED) {
                postDone(ID_CLOUD_DONE, tr("Upload failed"), cloud.error ?: tr("Something went wrong."), null)
            }
            lastCloudPhase = cloud?.phase
        }
        if (transfer?.status != lastTransferStatus) {
            val wasActive = lastTransferStatus == TransferStatus.ACTIVE || lastTransferStatus == TransferStatus.PAUSED
            if (wasActive && transfer?.direction == Direction.RECEIVE && transfer.status == TransferStatus.COMPLETE) postReceived(transfer)
            if (wasActive && transfer?.status == TransferStatus.FAILED) {
                postDone(ID_TRANSFER_DONE, tr("Transfer failed"), transfer.error ?: tr("Something went wrong."), null)
            }
            lastTransferStatus = transfer?.status
        }

        // Ongoing progress.
        val uploading = cloud?.phase == CloudPhase.PREPARING || cloud?.phase == CloudPhase.UPLOADING
        val moving = transfer?.status == TransferStatus.ACTIVE || transfer?.status == TransferStatus.PAUSED
        val waitingNearby = transfer?.status == TransferStatus.WAITING
        val notification = when {
            uploading && cloud != null -> when {
                cloud.phase == CloudPhase.PREPARING -> progressNotification(tr("Making files smaller…"), tr("This can take a moment for videos."), 0, true)
                cloud.waiting -> progressNotification(tr("Waiting for internet…"), tr("The upload will continue by itself."), (cloud.progress * 100).toInt(), false)
                else -> progressNotification(
                    tr("Uploading %d%%", (cloud.progress * 100).toInt()),
                    "${Files.formatBytes(cloud.bytesDone)} / ${Files.formatBytes(cloud.totalBytes)}",
                    (cloud.progress * 100).toInt(), false
                )
            }
            moving && transfer != null -> {
                val pct = (transfer.progress * 100).toInt()
                val title = when {
                    transfer.waiting -> tr("Waiting for internet…")
                    transfer.direction == Direction.SEND -> tr("Sending %d%%", pct)
                    else -> tr("Receiving %d%%", pct)
                }
                progressNotification(title, "${Files.formatBytes(transfer.bytesDone)} / ${Files.formatBytes(transfer.totalBytes)}", pct, false)
            }
            waitingNearby -> progressNotification(tr("Ready for a nearby phone"), tr("Keep both phones on the same Wi-Fi."), 0, false, showProgress = false)
            else -> null
        }
        if (notification == null) {
            stopEverything()
        } else if (manager.areNotificationsEnabled()) {
            runCatching { manager.notify(ID_PROGRESS, notification) }
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun progressNotification(title: String, text: String, percent: Int, indeterminate: Boolean, showProgress: Boolean = true): Notification =
        NotificationCompat.Builder(this, SenditApp.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .apply { if (showProgress) setProgress(100, percent, indeterminate) }
            .build()

    private fun postShareReady(cloud: CloudShareUi) {
        val code = cloud.code ?: return
        val text = tr("I sent you files with Sendit.\n\nDownload: %s\n\nOr open the Sendit app and enter the code: %s", cloud.url ?: "", code)
        val share = Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
            tr("Share Sendit link")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val sharePending = PendingIntent.getActivity(this, 1, share, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val body = cloud.recipientName?.let { tr("Sent to %s. Code: %s", it, code) } ?: tr("Code: %s · tap Share to send it", code)
        post(ID_CLOUD_DONE, NotificationCompat.Builder(this, SenditApp.CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(tr("Ready to share"))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .addAction(0, tr("Share"), sharePending)
            .build())
    }

    private fun postReceived(transfer: TransferUi) {
        // Secret files live in private storage: only Sendit's own viewer may open them.
        val first = if (transfer.viewOnce) null else transfer.files.firstOrNull { it.savedUri != null }
        val open = first?.let {
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(it.savedUri), it.mimeType.ifBlank { "*/*" })
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val text = if (transfer.files.size == 1) transfer.files.first().name
        else tr("%d files · saved to Downloads/Sendit", transfer.files.size)
        postDone(ID_TRANSFER_DONE, tr("Files received"), text, open)
    }

    private fun postDone(id: Int, title: String, text: String, openIntent: Intent?) {
        val builder = NotificationCompat.Builder(this, SenditApp.CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
        if (openIntent != null) {
            builder.addAction(0, tr("Open"), PendingIntent.getActivity(this, 2, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        post(id, builder.build())
    }

    private fun post(id: Int, notification: Notification) {
        val manager = NotificationManagerCompat.from(this)
        if (manager.areNotificationsEnabled()) runCatching { manager.notify(id, notification) }
    }

    private fun stopEverything() {
        runCatching { wakeLock?.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { wakeLock?.release() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ID_PROGRESS = 41
        private const val ID_CLOUD_DONE = 42
        private const val ID_TRANSFER_DONE = 43

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java)) }
        }
    }
}
