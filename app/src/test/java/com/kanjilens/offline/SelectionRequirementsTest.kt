package com.kanjilens.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionRequirementsTest {

    @Test
    fun `Auto requires only the selected target before capture`() {
        val requirements = SelectionRequirements.resolve("auto", "en")

        assertEquals(setOf(ModelKey.Translation("en")), requirements)
    }

    @Test
    fun `explicit source requires OCR and both translation languages`() {
        val requirements = SelectionRequirements.resolve("ja", "ro")

        assertTrue(requirements.contains(ModelKey.Ocr(OcrScript.JAPANESE)))
        assertTrue(requirements.contains(ModelKey.Translation("ja")))
        assertTrue(requirements.contains(ModelKey.Translation("ro")))
    }

    @Test
    fun `same source and target does not require translation models`() {
        val requirements = SelectionRequirements.resolve("ko", "ko")

        assertEquals(setOf(ModelKey.Ocr(OcrScript.KOREAN)), requirements)
        assertFalse(requirements.any { it is ModelKey.Translation })
    }
}
