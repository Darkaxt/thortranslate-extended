package com.kanjilens.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoSourceSelectorTest {

    @Test
    fun `selects the strongest supported script-compatible candidate`() {
        val selected = AutoSourceSelector.select(
            listOf(
                OcrCandidate(OcrScript.LATIN, listOf("Start Game"), "en", 0.91f),
                OcrCandidate(OcrScript.JAPANESE, listOf("Start Garne"), "en", 0.44f),
            )
        )

        assertEquals("en", selected?.languageTag)
        assertEquals(OcrScript.LATIN, selected?.script)
    }

    @Test
    fun `rejects a detected language incompatible with the recognizer script`() {
        val selected = AutoSourceSelector.select(
            listOf(OcrCandidate(OcrScript.LATIN, listOf("日本語"), "ja", 0.99f))
        )

        assertNull(selected)
    }

    @Test
    fun `rejects low-confidence and empty candidates`() {
        assertNull(
            AutoSourceSelector.select(
                listOf(OcrCandidate(OcrScript.LATIN, listOf("Start"), "en", 0.20f))
            )
        )
        assertNull(AutoSourceSelector.select(emptyList()))
    }
}
