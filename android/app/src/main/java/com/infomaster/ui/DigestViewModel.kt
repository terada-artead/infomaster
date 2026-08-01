package com.infomaster.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.infomaster.data.BudgetSettings
import com.infomaster.data.BudgetState
import com.infomaster.data.Digest
import com.infomaster.data.DigestRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DigestUiState {
    data object Loading : DigestUiState
    data class Ready(
        val digest: Digest,
        val budget: BudgetState,
        val refreshing: Boolean = false,
    ) : DigestUiState

    data class Error(val message: String) : DigestUiState
}

class DigestViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DigestRepository(application)
    private val settings = BudgetSettings(application)

    private val _state = MutableStateFlow<DigestUiState>(DigestUiState.Loading)
    val state: StateFlow<DigestUiState> = _state.asStateFlow()

    /** 設定ダイアログの表示状態。 */
    private val _editingBudget = MutableStateFlow(false)
    val editingBudget: StateFlow<Boolean> = _editingBudget.asStateFlow()

    init {
        load(forceRefresh = false)
    }

    fun refresh() = load(forceRefresh = true)

    fun openBudgetEditor() {
        _editingBudget.value = true
    }

    fun dismissBudgetEditor() {
        _editingBudget.value = false
    }

    /**
     * 購入したクレジットの累計額を保存する。
     * 買い足したときは「今回の額」ではなく「累計」を入れてもらう。
     */
    fun saveBudget(creditUsd: Double, warnBelowRuns: Int) {
        settings.creditUsd = creditUsd
        settings.warnBelowRuns = warnBelowRuns
        _editingBudget.value = false
        // 保存した値をすぐ画面に反映する
        val current = _state.value
        if (current is DigestUiState.Ready) {
            _state.value = current.copy(budget = settings.state(current.digest.budget))
        }
    }

    fun currentSettings(): Pair<Double, Int> =
        settings.creditUsd to settings.warnBelowRuns

    private fun load(forceRefresh: Boolean) {
        viewModelScope.launch {
            // 再取得中も既存の内容は出したままにする（画面が真っ白にならないように）
            val current = _state.value
            if (current is DigestUiState.Ready) {
                _state.value = current.copy(refreshing = true)
            }

            repository.load(forceRefresh = forceRefresh)
                .onSuccess {
                    _state.value = DigestUiState.Ready(it, settings.state(it.budget))
                }
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
