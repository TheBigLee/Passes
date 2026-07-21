package ch.bigli.passes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.bigli.passes.data.PassRepository
import ch.bigli.passes.domain.Pass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PassDetailViewModel(private val repo: PassRepository, private val passId: String) : ViewModel() {
    private val _pass = MutableStateFlow<Pass?>(null)
    val pass: StateFlow<Pass?> = _pass

    init {
        viewModelScope.launch { _pass.value = repo.getById(passId) }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch { repo.delete(passId); onDone() }
    }
}
