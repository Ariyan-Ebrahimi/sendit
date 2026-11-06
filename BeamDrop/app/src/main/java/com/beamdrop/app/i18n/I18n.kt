package com.beamdrop.app.i18n

import java.util.Locale

/**
 * Tiny translation layer: every user-visible English string goes through [t]. The English text is
 * looked up in [Fa] or [Tr] for the current language; anything missing simply stays English.
 * Placeholders use String.format style (%s, %d).
 */
object I18n {
    /** "en" | "fa" | "tr" */
    @Volatile
    var lang: String = "en"

    /** Persian is the only right-to-left language. */
    val persian: Boolean get() = lang == "fa"

    /** Setting "system" | "en" | "fa" | "tr" → the language to show. */
    fun resolve(setting: String): String = when (setting) {
        "en", "fa", "tr" -> setting
        else -> when (Locale.getDefault().language) {
            "fa", "ps" -> "fa"
            "tr" -> "tr"
            else -> "en"
        }
    }

    fun t(en: String, vararg args: Any?): String {
        val map = when (lang) {
            "fa" -> Fa.strings
            "tr" -> Tr.strings
            else -> null
        }
        val text = map?.get(en) ?: en
        return if (args.isEmpty()) text else runCatching { String.format(Locale.US, text, *args) }.getOrDefault(text)
    }
}

/** Shorthand used all over the UI. */
fun tr(en: String, vararg args: Any?): String = I18n.t(en, *args)
