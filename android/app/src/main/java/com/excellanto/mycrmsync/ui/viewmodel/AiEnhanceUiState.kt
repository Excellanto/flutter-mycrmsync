package com.excellanto.mycrmsync.ui.viewmodel

import com.excellanto.mycrmsync.data.model.CallRecordingTranscribeData

sealed interface AiEnhanceUiState {
    data object Hidden : AiEnhanceUiState

    data class Transcribing(val contactName: String) : AiEnhanceUiState

    data class Ready(
        val row: UiCallLogRow,
        val result: CallRecordingTranscribeData,
    ) : AiEnhanceUiState

    data class Failed(val message: String) : AiEnhanceUiState
}
