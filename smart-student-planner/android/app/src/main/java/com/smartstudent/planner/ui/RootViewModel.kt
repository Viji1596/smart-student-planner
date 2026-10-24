package com.smartstudent.planner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.smartstudent.planner.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** null = still loading, true/false = signed in or not. */
class RootViewModel(container: AppContainer) : ViewModel() {
    val signedIn: StateFlow<Boolean?> = container.tokenStore.accessToken
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { RootViewModel(container) }
        }
    }
}
