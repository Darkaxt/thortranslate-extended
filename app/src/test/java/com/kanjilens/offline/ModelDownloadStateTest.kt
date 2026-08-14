package com.kanjilens.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelDownloadStateTest {

    @Test
    fun `download fraction is calculated and clamped`() {
        assertEquals(0.5f, ModelDownloadState.Downloading(50, 100).fraction)
        assertEquals(1f, ModelDownloadState.Downloading(120, 100).fraction)
        assertEquals(0f, ModelDownloadState.Downloading(-1, 100).fraction)
    }

    @Test
    fun `download without byte totals is indeterminate`() {
        assertNull(ModelDownloadState.Downloading(null, null).fraction)
        assertNull(ModelDownloadState.Downloading(50, 0).fraction)
    }

    @Test
    fun `terminal and retry events produce stable states`() {
        assertEquals(ModelDownloadState.Ready, reduceModelState(ModelDownloadEvent.Completed))
        assertEquals(ModelDownloadState.Queued, reduceModelState(ModelDownloadEvent.Retry))
        assertEquals(ModelDownloadState.Paused, reduceModelState(ModelDownloadEvent.Paused))
        assertEquals(
            ModelDownloadState.Failed("network unavailable"),
            reduceModelState(ModelDownloadEvent.Failed("network unavailable")),
        )
    }
}
