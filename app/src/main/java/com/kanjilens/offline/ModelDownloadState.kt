package com.kanjilens.offline

sealed interface ModelDownloadState {
    data object NotInstalled : ModelDownloadState
    data object Queued : ModelDownloadState

    data class Downloading(
        val bytesDownloaded: Long?,
        val totalBytes: Long?,
    ) : ModelDownloadState {
        val fraction: Float?
            get() = if (bytesDownloaded == null || totalBytes == null || totalBytes <= 0) {
                null
            } else {
                (bytesDownloaded.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
            }
    }

    data object Installing : ModelDownloadState
    data object Ready : ModelDownloadState
    data object Paused : ModelDownloadState
    data class Failed(val message: String) : ModelDownloadState
}

sealed interface ModelDownloadEvent {
    data object NotAvailable : ModelDownloadEvent
    data object Enqueued : ModelDownloadEvent
    data class Progress(val bytesDownloaded: Long?, val totalBytes: Long?) : ModelDownloadEvent
    data object Installing : ModelDownloadEvent
    data object Completed : ModelDownloadEvent
    data object Paused : ModelDownloadEvent
    data class Failed(val message: String) : ModelDownloadEvent
    data object Retry : ModelDownloadEvent
}

fun reduceModelState(event: ModelDownloadEvent): ModelDownloadState = when (event) {
    ModelDownloadEvent.NotAvailable -> ModelDownloadState.NotInstalled
    ModelDownloadEvent.Enqueued,
    ModelDownloadEvent.Retry,
    -> ModelDownloadState.Queued
    is ModelDownloadEvent.Progress -> ModelDownloadState.Downloading(
        event.bytesDownloaded,
        event.totalBytes,
    )
    ModelDownloadEvent.Installing -> ModelDownloadState.Installing
    ModelDownloadEvent.Completed -> ModelDownloadState.Ready
    ModelDownloadEvent.Paused -> ModelDownloadState.Paused
    is ModelDownloadEvent.Failed -> ModelDownloadState.Failed(event.message)
}
