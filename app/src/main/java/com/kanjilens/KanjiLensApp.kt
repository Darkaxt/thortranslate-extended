package com.kanjilens

import android.app.Application
import com.kanjilens.data.models.AppSettings
import com.kanjilens.offline.MultiScriptTextRecognizer
import com.kanjilens.offline.OfflineModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class KanjiLensApp : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var settings: AppSettings
        private set
    lateinit var multiScriptTextRecognizer: MultiScriptTextRecognizer
        private set
    lateinit var offlineModelManager: OfflineModelManager
        private set

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        multiScriptTextRecognizer = MultiScriptTextRecognizer()
        offlineModelManager = OfflineModelManager(
            context = this,
            textRecognizer = multiScriptTextRecognizer,
            scope = applicationScope,
            initialSourceTag = settings.sourceLanguage.value,
            initialTargetTag = settings.outputLanguage.value,
        )
    }

    override fun onTerminate() {
        multiScriptTextRecognizer.close()
        applicationScope.cancel()
        super.onTerminate()
    }
}
