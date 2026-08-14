package com.kanjilens.offline

import android.graphics.Bitmap
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class OfflineTranslationBlock(
    val original: String,
    val translated: String,
)

data class OfflineTranslationResult(
    val sourceLanguageTag: String,
    val targetLanguageTag: String,
    val blocks: List<OfflineTranslationBlock>,
) {
    val plainText: String = blocks.joinToString("\n\n") { block ->
        "${block.original}\n${block.translated}"
    }
}

object TranslationPairPolicy {
    fun requiresTranslation(sourceTag: String, targetTag: String): Boolean =
        sourceTag != targetTag
}

class OfflineTranslator(
    private val textRecognizer: MultiScriptTextRecognizer,
    private val modelManager: OfflineModelManager,
) {
    private val translationMutex = Mutex()
    private var currentPair: Pair<String, String>? = null
    private var currentTranslator: Translator? = null

    suspend fun recognize(bitmap: Bitmap, sourceTag: String): RecognizedScreenText {
        return if (sourceTag == OfflineLanguageCatalog.AUTO) {
            textRecognizer.recognizeAuto(bitmap, modelManager.availableOcrScripts.value)
        } else {
            val script = requireNotNull(OfflineLanguageCatalog.requireSource(sourceTag).script)
            modelManager.ensureOcr(script).await()
            textRecognizer.recognize(bitmap, sourceTag)
        }
    }

    suspend fun translate(
        bitmap: Bitmap,
        sourceTag: String,
        targetTag: String,
        onDownloading: (() -> Unit)? = null,
    ): OfflineTranslationResult = translateRecognized(
        recognize(bitmap, sourceTag),
        targetTag,
        onDownloading,
    )

    suspend fun translateRecognized(
        recognized: RecognizedScreenText,
        targetTag: String,
        onDownloading: (() -> Unit)? = null,
    ): OfflineTranslationResult {
        requireNotNull(OfflineLanguageCatalog.target(targetTag)) {
            "Unsupported translation target: $targetTag"
        }
        if (!TranslationPairPolicy.requiresTranslation(recognized.sourceLanguageTag, targetTag)) {
            return OfflineTranslationResult(
                recognized.sourceLanguageTag,
                targetTag,
                recognized.blocks.map { OfflineTranslationBlock(it, it) },
            )
        }

        val translationStates = modelManager.translationStates.value
        if (translationStates[recognized.sourceLanguageTag] != ModelDownloadState.Ready ||
            translationStates[targetTag] != ModelDownloadState.Ready
        ) {
            withContext(Dispatchers.Main) { onDownloading?.invoke() }
        }
        modelManager.awaitTranslationLanguage(recognized.sourceLanguageTag)
        modelManager.awaitTranslationLanguage(targetTag)

        return translationMutex.withLock {
            val pair = recognized.sourceLanguageTag to targetTag
            if (currentPair != pair || currentTranslator == null) {
                currentTranslator?.close()
                currentTranslator = Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(recognized.sourceLanguageTag)
                        .setTargetLanguage(targetTag)
                        .build()
                )
                currentPair = pair
            }
            val translator = requireNotNull(currentTranslator)
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            val blocks = recognized.blocks.map { original ->
                OfflineTranslationBlock(original, translator.translate(original).await())
            }
            OfflineTranslationResult(recognized.sourceLanguageTag, targetTag, blocks)
        }
    }

    fun close() {
        currentTranslator?.close()
        currentTranslator = null
        currentPair = null
    }
}
