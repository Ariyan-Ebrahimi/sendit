package com.beamdrop.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * If Sendit crashes, the error is written to a small file. Next time the app starts it is sent to
 * the server (admin panel → Crashes) and deleted. No personal data is included, only the error.
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                File(app.filesDir, FILE).writeText("Thread: ${thread.name}\n$sw".take(20000))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun pending(context: Context): String? =
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}
