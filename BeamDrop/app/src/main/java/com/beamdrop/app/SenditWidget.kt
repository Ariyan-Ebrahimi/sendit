package com.beamdrop.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home-screen widget with two buttons: Send and Receive. */
class SenditWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_sendit).apply {
            setOnClickPendingIntent(R.id.widget_send, open(context, "send", 1))
            setOnClickPendingIntent(R.id.widget_receive, open(context, "receive", 2))
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    private fun open(context: Context, target: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN, target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    companion object {
        const val EXTRA_OPEN = "open_screen"
    }
}
