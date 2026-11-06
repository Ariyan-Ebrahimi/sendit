package com.beamdrop.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.beamdrop.app.i18n.tr

/** Opens a received file in whatever app can show it (gallery, video player, PDF reader...). */
fun openFile(context: Context, uri: String, mimeType: String) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(uri), mimeType.ifBlank { "*/*" })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(Intent.createChooser(intent, tr("Open with")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, tr("No app on this phone can open this file."), Toast.LENGTH_SHORT).show()
    }
}
