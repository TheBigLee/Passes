package ch.bigli.passes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.bigli.passes.data.PassRepository
import ch.bigli.passes.domain.Pass
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PassListViewModel(private val repo: PassRepository) : ViewModel() {
    val passes: StateFlow<List<Pass>> =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors

    fun importBytes(bytes: ByteArray, displayName: String) {
        viewModelScope.launch {
            try {
                repo.import(bytes, displayName)
            } catch (e: Exception) {
                _errors.emit(e.message ?: "Import failed")
            }
        }
    }
}
