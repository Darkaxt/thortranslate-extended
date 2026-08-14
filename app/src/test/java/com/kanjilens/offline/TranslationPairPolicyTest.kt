package com.kanjilens.offline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationPairPolicyTest {

    @Test
    fun `same language passes recognized text through`() {
        assertFalse(TranslationPairPolicy.requiresTranslation("ko", "ko"))
    }

    @Test
    fun `different languages require a translation pair`() {
        assertTrue(TranslationPairPolicy.requiresTranslation("ja", "en"))
    }
}
