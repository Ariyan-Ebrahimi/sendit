package com.beamdrop.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class I18nTest {
    private val ph = Regex("%[sd%]")

    /** A translation with different placeholders would crash or show wrong values in String.format. */
    @Test
    fun translationsKeepPlaceholders() {
        for ((name, map) in listOf("fa" to Fa.strings, "tr" to Tr.strings)) {
            for ((en, text) in map) {
                assertEquals("$name: $en", ph.findAll(en).map { it.value }.toList(), ph.findAll(text).map { it.value }.toList())
            }
        }
    }

    @Test
    fun turkishCoversPersianKeys() {
        val missing = Fa.strings.keys - Tr.strings.keys
        assertTrue("missing in Turkish: $missing", missing.isEmpty())
    }

    @Test
    fun languageSwitch() {
        I18n.lang = "tr"
        assertEquals("Gönder", tr("Send"))
        assertEquals("%d dosya".format(3), tr("%d files", 3))
        I18n.lang = "en"
        assertEquals("Send", tr("Send"))
    }
}
