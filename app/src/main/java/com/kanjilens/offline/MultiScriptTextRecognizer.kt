package com.kanjilens.offline

import android.graphics.Bitmap
import com.google.android.gms.common.api.OptionalModuleApi
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

data class OcrCandidate(
    val script: OcrScript,
    val blocks: List<String>,
    val languageTag: String,
    val languageConfidence: Float,
) {
    val textLength: Int = blocks.sumOf { block -> block.count { !it.isWhitespace() } }
}

data class RecognizedScreenText(
    val sourceLanguageTag: String,
    val script: OcrScript,
    val blocks: List<String>,
)

object AutoSourceSelector {
    private const val MIN_CONFIDENCE = 0.4f

    fun select(candidates: List<OcrCandidate>): OcrCandidate? = candidates
        .asSequence()
        .filter { it.textLength > 0 }
        .filter { it.languageConfidence >= MIN_CONFIDENCE }
        .filter { candidate ->
            OfflineLanguageCatalog.source(candidate.languageTag)?.script == candidate.script
        }
        .maxByOrNull { candidate ->
            candidate.languageConfidence + (candidate.textLength.coerceAtMost(200) / 800f)
        }
}

class OfflineRecognitionException(message: String) : IllegalStateException(message)

class MultiScriptTextRecognizer {
    companion object {
        val BASELINE_SCRIPTS = setOf(OcrScript.LATIN, OcrScript.JAPANESE)
    }

    private val recognizers: Map<OcrScript, TextRecognizer> = mapOf(
        OcrScript.LATIN to TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
        OcrScript.CHINESE to TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build()
        ),
        OcrScript.DEVANAGARI to TextRecognition.getClient(
            DevanagariTextRecognizerOptions.Builder().build()
        ),
        OcrScript.JAPANESE to TextRecognition.getClient(
            JapaneseTextRecognizerOptions.Builder().build()
        ),
        OcrScript.KOREAN to TextRecognition.getClient(
            KoreanTextRecognizerOptions.Builder().build()
        ),
    )

    private val languageIdentifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(0.3f)
            .build()
    )

    suspend fun recognize(bitmap: Bitmap, sourceTag: String): RecognizedScreenText {
        val language = OfflineLanguageCatalog.requireSource(sourceTag)
        val script = requireNotNull(language.script) { "Auto requires recognizeAuto()" }
        val blocks = recognizeBlocks(bitmap, script)
        return RecognizedScreenText(language.tag, script, blocks)
    }

    suspend fun recognizeAuto(
        bitmap: Bitmap,
        availableScripts: Set<OcrScript>,
    ): RecognizedScreenText = coroutineScope {
        val candidates = availableScripts
            .sortedBy(OcrScript::ordinal)
            .map { script ->
                async {
                    val blocks = runCatching { recognizeBlocks(bitmap, script) }.getOrNull()
                        ?: return@async null
                    val identified = languageIdentifier
                        .identifyPossibleLanguages(blocks.joinToString("\n"))
                        .await()
                        .filter { language ->
                            OfflineLanguageCatalog.source(language.languageTag)?.script == script
                        }
                        .maxByOrNull { it.confidence }
                        ?: return@async null
                    OcrCandidate(script, blocks, identified.languageTag, identified.confidence)
                }
            }
            .awaitAll()
            .filterNotNull()

        val selected = AutoSourceSelector.select(candidates)
            ?: throw OfflineRecognitionException(
                "Could not confidently detect the source language. Choose Translate From in Settings."
            )
        RecognizedScreenText(selected.languageTag, selected.script, selected.blocks)
    }

    fun optionalApi(script: OcrScript): OptionalModuleApi? =
        if (script in BASELINE_SCRIPTS) null else recognizers.getValue(script) as OptionalModuleApi

    private suspend fun recognizeBlocks(bitmap: Bitmap, script: OcrScript): List<String> {
        val result = recognizers.getValue(script)
            .process(InputImage.fromBitmap(bitmap, 0))
            .await()
        return result.textBlocks
            .map { it.text.trim() }
            .filter(String::isNotEmpty)
            .ifEmpty { throw OfflineRecognitionException("No text found in screenshot") }
    }

    fun close() {
        recognizers.values.forEach(TextRecognizer::close)
        languageIdentifier.close()
    }
}
