package com.infomaster.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.infomaster.data.Digest
import com.infomaster.data.DigestRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DigestUiState {
    data object Loading : DigestUiState
    data class Ready(val digest: Digest, val refreshing: Boolean = false) : DigestUiState
    data class Error(val message: String) : DigestUiState
}

class DigestViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DigestRepository(application)

    private val _state = MutableStateFlow<DigestUiState>(DigestUiState.Loading)
    val state: StateFlow<DigestUiState> = _state.asStateFlow()

    init {
        load(forceRefresh = false)
    }

    fun refresh() = load(forceRefresh = true)

    private fun load(forceRefresh: Boolean) {
        viewModelScope.launch {
            // 再取得中も既存の内容は出したままにする（画面が真っ白にならないように）
            val current = _state.value
            if (current is DigestUiState.Ready) {
                _state.value = current.copy(refreshing = true)
            }

            repository.load(forceRefresh = forceRefresh)
                .onSuccess { _state.value = DigestUiState.Ready(it) }
                .onFailure { error ->
                    _state.value = if (current is DigestUiState.Ready) {
                        current.copy(refreshing = false)
                    } else {
                        DigestUiState.Error(
                            error.message ?: "ダイジェストを取得できませんでした"
                        )
                    }
                }
        }
    }
}
