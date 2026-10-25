package com.example.offlinetranscription.ui.main

import com.example.offlinetranscription.TranscriptionTestActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.offlinetranscription.engine.TranscriptTurn
import com.example.offlinetranscription.theme.OfflineTranscriptionTheme
import com.example.offlinetranscription.ui.TranscriptionContent
import com.example.offlinetranscription.ui.TranscriptionUiState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MainScreenTest {
    @get:Rule val compose = createAndroidComposeRule<TranscriptionTestActivity>()

    @Test fun recordRequestsPermissionAndUncalibratedConfidenceIsUnavailable() {
        var requested = 0
        compose.setContent {
            OfflineTranscriptionTheme {
                TranscriptionContent(
                    state = TranscriptionUiState(isModelReady = true, completedTurns = listOf(
                        TranscriptTurn(timestamp = "12:00", text = "Saved speech", durationSec = 1f, latencyMs = 10))),
                    rmsLevel = MutableStateFlow(0f), hasRecordPermission = false,
                    onRequestRecordPermission = { requested++ }, onSetThreadCount = {},
                    onClearTranscripts = {}, onToggleRecording = {}, onStartDownload = {},
                    onImportLocalModel = {}, onRunBenchmark = {})
            }
        }
        compose.onNodeWithText("Saved speech").assertExists()
        compose.onNodeWithText("Confidence unavailable").assertExists()
        compose.onNodeWithContentDescription("Start Live Speech").performClick()
        compose.runOnIdle { assertEquals(1, requested) }
    }

    @Test fun benchmarkIsDisabledDuringRecording() {
        compose.setContent {
            OfflineTranscriptionTheme {
                TranscriptionContent(TranscriptionUiState(isModelReady = true, isRecording = true),
                    MutableStateFlow(0f), true, {}, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Run JFK Benchmark Audio").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Stop Recording").assertExists()
    }
}
