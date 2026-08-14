package com.kanjilens.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineLanguageCatalogTest {

    @Test
    fun `Auto is the first source choice`() {
        assertEquals("auto", OfflineLanguageCatalog.AUTO)
        assertEquals("Auto", OfflineLanguageCatalog.sourceChoices.first().displayName)
    }

    @Test
    fun `source languages map to their OCR scripts`() {
        assertEquals(OcrScript.JAPANESE, OfflineLanguageCatalog.requireSource("ja").script)
        assertEquals(OcrScript.LATIN, OfflineLanguageCatalog.requireSource("ro").script)
        assertEquals(OcrScript.DEVANAGARI, OfflineLanguageCatalog.requireSource("hi").script)
        assertEquals(OcrScript.CHINESE, OfflineLanguageCatalog.requireSource("zh").script)
        assertEquals(OcrScript.KOREAN, OfflineLanguageCatalog.requireSource("ko").script)
    }

    @Test
    fun `translation targets include languages without supported screenshot OCR`() {
        assertTrue(OfflineLanguageCatalog.targets.any { it.tag == "ar" })
        assertFalse(OfflineLanguageCatalog.sourceChoices.any { it.tag == "ar" })
    }

    @Test
    fun `display names fall back to English`() {
        assertEquals("English", OfflineLanguageCatalog.displayName("en"))
        assertEquals("English", OfflineLanguageCatalog.displayName("missing"))
    }
}
