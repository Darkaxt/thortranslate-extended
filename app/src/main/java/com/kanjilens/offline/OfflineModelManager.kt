package com.kanjilens.offline

import android.content.Context
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

sealed interface ModelKey {
    data class Ocr(val script: OcrScript) : ModelKey
    data class Translation(val languageTag: String) : ModelKey
}

object SelectionRequirements {
    fun resolve(sourceTag: String, targetTag: String): Set<ModelKey> {
        if (sourceTag == OfflineLanguageCatalog.AUTO) {
            return setOf(ModelKey.Translation(targetTag))
        }

        val source = OfflineLanguageCatalog.requireSource(sourceTag)
        val script = requireNotNull(source.script)
        if (sourceTag == targetTag) {
            return setOf(ModelKey.Ocr(script))
        }

        return setOf(
            ModelKey.Ocr(script),
            ModelKey.Translation(sourceTag),
            ModelKey.Translation(targetTag),
        )
    }
}

data class NamedModelState(
    val key: ModelKey,
    val label: String,
    val state: ModelDownloadState,
)

data class SelectionModelState(
    val sourceTag: String,
    val targetTag: String,
    val models: List<NamedModelState>,
) {
    val isReady: Boolean = models.all { it.state == ModelDownloadState.Ready }
    val current: NamedModelState? = models.firstOrNull { it.state != ModelDownloadState.Ready }
}

class OfflineModelManager(
    context: Context,
    private val textRecognizer: MultiScriptTextRecognizer,
    private val scope: CoroutineScope,
    initialSourceTag: String = OfflineLanguageCatalog.AUTO,
    initialTargetTag: String = "en",
) {
    private val moduleInstallClient = ModuleInstall.getClient(context)
    private val remoteModelManager = RemoteModelManager.getInstance()

    private val _ocrStates = MutableStateFlow<Map<OcrScript, ModelDownloadState>>(
        MultiScriptTextRecognizer.BASELINE_SCRIPTS.associateWith { ModelDownloadState.Ready }
    )
    val ocrStates: StateFlow<Map<OcrScript, ModelDownloadState>> = _ocrStates

    private val _translationStates = MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())
    val translationStates: StateFlow<Map<String, ModelDownloadState>> = _translationStates

    private val selectedSource = MutableStateFlow(
        OfflineLanguageCatalog.normalizeSourceTag(initialSourceTag)
    )
    private val selectedTarget = MutableStateFlow(
        OfflineLanguageCatalog.target(initialTargetTag)?.tag ?: "en"
    )

    private val ocrJobs = ConcurrentHashMap<OcrScript, Deferred<Unit>>()
    private val translationJobs = ConcurrentHashMap<String, Deferred<Unit>>()

    val availableOcrScripts: StateFlow<Set<OcrScript>> = ocrStates
        .map { states -> states.filterValues { it == ModelDownloadState.Ready }.keys }
        .stateIn(scope, SharingStarted.Eagerly, MultiScriptTextRecognizer.BASELINE_SCRIPTS)

    val selectionState: StateFlow<SelectionModelState> = combine(
        selectedSource,
        selectedTarget,
        ocrStates,
        translationStates,
    ) { source, target, ocr, translation ->
        val models = SelectionRequirements.resolve(source, target).map { key ->
            when (key) {
                is ModelKey.Ocr -> NamedModelState(
                    key,
                    "${key.script.displayName()} recognition",
                    ocr[key.script] ?: ModelDownloadState.NotInstalled,
                )
                is ModelKey.Translation -> NamedModelState(
                    key,
                    "${OfflineLanguageCatalog.displayName(key.languageTag)} translation",
                    translation[key.languageTag] ?: ModelDownloadState.NotInstalled,
                )
            }
        }
        SelectionModelState(source, target, models)
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        SelectionModelState(initialSourceTag, initialTargetTag, emptyList()),
    )

    init {
        refreshAvailability()
        selectSource(initialSourceTag)
        selectTarget(initialTargetTag)
    }

    fun selectSource(tag: String) {
        selectedSource.value = OfflineLanguageCatalog.normalizeSourceTag(tag)
        ensureCurrentSelection()
    }

    fun selectTarget(tag: String) {
        selectedTarget.value = OfflineLanguageCatalog.target(tag)?.tag ?: "en"
        ensureCurrentSelection()
    }

    fun retryCurrentSelection() {
        ensureCurrentSelection(forceRetry = true)
    }

    fun ensureOcr(script: OcrScript, forceRetry: Boolean = false): Deferred<Unit> {
        if (script in MultiScriptTextRecognizer.BASELINE_SCRIPTS) {
            _ocrStates.update { it + (script to ModelDownloadState.Ready) }
            return CompletableDeferred(Unit)
        }
        val state = _ocrStates.value[script]
        if (!forceRetry && state == ModelDownloadState.Ready) return CompletableDeferred(Unit)
        if (!forceRetry) ocrJobs[script]?.let { return it }

        val job = scope.async {
            _ocrStates.update { it + (script to ModelDownloadState.Queued) }
            val api = requireNotNull(textRecognizer.optionalApi(script))
            try {
                if (moduleInstallClient.areModulesAvailable(api).await().areModulesAvailable()) {
                    _ocrStates.update { it + (script to ModelDownloadState.Ready) }
                    return@async
                }

                val completion = CompletableDeferred<Unit>()
                val listener = object : InstallStatusListener {
                    override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                        val stateUpdate = when (update.installState) {
                            ModuleInstallStatusUpdate.InstallState.STATE_PENDING ->
                                ModelDownloadState.Queued
                            ModuleInstallStatusUpdate.InstallState.STATE_DOWNLOADING -> {
                                val progress = update.progressInfo
                                ModelDownloadState.Downloading(
                                    progress?.bytesDownloaded,
                                    progress?.totalBytesToDownload,
                                )
                            }
                            ModuleInstallStatusUpdate.InstallState.STATE_INSTALLING ->
                                ModelDownloadState.Installing
                            ModuleInstallStatusUpdate.InstallState.STATE_DOWNLOAD_PAUSED ->
                                ModelDownloadState.Paused
                            ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED ->
                                ModelDownloadState.Ready
                            ModuleInstallStatusUpdate.InstallState.STATE_CANCELED ->
                                ModelDownloadState.Failed("Download canceled")
                            ModuleInstallStatusUpdate.InstallState.STATE_FAILED ->
                                ModelDownloadState.Failed("Install failed (${update.errorCode})")
                            else -> null
                        }
                        if (stateUpdate != null) {
                            _ocrStates.update { it + (script to stateUpdate) }
                        }
                        when (stateUpdate) {
                            ModelDownloadState.Ready -> completion.complete(Unit)
                            is ModelDownloadState.Failed -> completion.completeExceptionally(
                                IllegalStateException(stateUpdate.message)
                            )
                            else -> Unit
                        }
                        if (stateUpdate == ModelDownloadState.Ready || stateUpdate is ModelDownloadState.Failed) {
                            moduleInstallClient.unregisterListener(this)
                        }
                    }
                }
                val request = ModuleInstallRequest.newBuilder()
                    .addApi(api)
                    .setListener(listener)
                    .build()
                val response = moduleInstallClient.installModules(request).await()
                if (response.areModulesAlreadyInstalled()) {
                    _ocrStates.update { it + (script to ModelDownloadState.Ready) }
                    moduleInstallClient.unregisterListener(listener)
                    completion.complete(Unit)
                }
                completion.await()
            } catch (error: Exception) {
                val message = error.message ?: "OCR model download failed"
                _ocrStates.update { it + (script to ModelDownloadState.Failed(message)) }
                throw error
            }
        }
        ocrJobs[script] = job
        job.invokeOnCompletion { ocrJobs.remove(script, job) }
        return job
    }

    fun ensureTranslationLanguage(tag: String, forceRetry: Boolean = false): Deferred<Unit> {
        requireNotNull(OfflineLanguageCatalog.target(tag)) { "Unsupported translation language: $tag" }
        val state = _translationStates.value[tag]
        if (!forceRetry && state == ModelDownloadState.Ready) return CompletableDeferred(Unit)
        if (!forceRetry) translationJobs[tag]?.let { return it }

        val job = scope.async {
            _translationStates.update { it + (tag to ModelDownloadState.Queued) }
            try {
                _translationStates.update {
                    it + (tag to ModelDownloadState.Downloading(null, null))
                }
                val model = TranslateRemoteModel.Builder(tag).build()
                remoteModelManager.download(model, DownloadConditions.Builder().build()).await()
                _translationStates.update { it + (tag to ModelDownloadState.Ready) }
            } catch (error: Exception) {
                val message = error.message ?: "Translation model download failed"
                _translationStates.update { it + (tag to ModelDownloadState.Failed(message)) }
                throw error
            }
        }
        translationJobs[tag] = job
        job.invokeOnCompletion { translationJobs.remove(tag, job) }
        return job
    }

    suspend fun awaitTranslationLanguage(tag: String) {
        ensureTranslationLanguage(tag).await()
    }

    private fun ensureCurrentSelection(forceRetry: Boolean = false) {
        SelectionRequirements.resolve(selectedSource.value, selectedTarget.value).forEach { key ->
            when (key) {
                is ModelKey.Ocr -> ensureOcr(key.script, forceRetry)
                is ModelKey.Translation -> ensureTranslationLanguage(key.languageTag, forceRetry)
            }
        }
    }

    private fun refreshAvailability() {
        MultiScriptTextRecognizer.BASELINE_SCRIPTS.forEach { script ->
            _ocrStates.update { it + (script to ModelDownloadState.Ready) }
        }
        (OcrScript.entries - MultiScriptTextRecognizer.BASELINE_SCRIPTS).forEach { script ->
            scope.launch {
                val api = requireNotNull(textRecognizer.optionalApi(script))
                val available = runCatching {
                    moduleInstallClient.areModulesAvailable(api).await().areModulesAvailable()
                }.getOrDefault(false)
                _ocrStates.update {
                    it + (script to if (available) ModelDownloadState.Ready else ModelDownloadState.NotInstalled)
                }
            }
        }
        scope.launch {
            val installed = runCatching {
                remoteModelManager.getDownloadedModels(TranslateRemoteModel::class.java).await()
            }.getOrDefault(emptySet())
            _translationStates.update { current ->
                current + installed.associate { model -> model.language to ModelDownloadState.Ready }
            }
        }
    }
}

private fun OcrScript.displayName(): String = when (this) {
    OcrScript.LATIN -> "Latin"
    OcrScript.CHINESE -> "Chinese"
    OcrScript.DEVANAGARI -> "Devanagari"
    OcrScript.JAPANESE -> "Japanese"
    OcrScript.KOREAN -> "Korean"
}
