package ch.bigli.passes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.bigli.passes.data.PassRepository
import ch.bigli.passes.data.RefreshResult
import ch.bigli.passes.domain.Pass
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PassDetailViewModel(private val repo: PassRepository, private val passId: String) : ViewModel() {
    private val _pass = MutableStateFlow<Pass?>(null)
    val pass: StateFlow<Pass?> = _pass

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _refreshMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val refreshMessage: SharedFlow<String> = _refreshMessage

    init {
        viewModelScope.launch { _pass.value = repo.getById(passId) }
    }

    /** Polls this pass's webServiceURL for a fresher pkpass; reflects the result on screen. */
    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            val result = repo.refreshPass(passId)
            _pass.value = repo.getById(passId)
            _refreshMessage.tryEmit(
                when (result) {
                    is RefreshResult.Updated -> "Pass updated"
                    RefreshResult.Unchanged -> "Up to date"
                    RefreshResult.Voided -> "This pass has been voided"
                    RefreshResult.NotUpdatable -> "This pass can't be refreshed"
                    is RefreshResult.Error -> "Couldn't refresh"
                },
            )
            _isRefreshing.value = false
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch { repo.delete(passId); onDone() }
    }
}
