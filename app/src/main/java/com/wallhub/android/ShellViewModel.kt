package com.wallhub.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wallhub.android.core.model.AppPreferences
import com.wallhub.android.core.model.DiagnosticEvent
import com.wallhub.android.core.model.DiagnosticLevel
import com.wallhub.android.core.model.DiagnosticRepository
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ShellViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val steamSessionRepository: SteamSessionRepository,
    private val diagnosticRepository: DiagnosticRepository,
) : ViewModel() {
    val preferences: StateFlow<AppPreferences> = settingsRepository.preferences.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppPreferences(),
    )

    init {
        viewModelScope.launch {
            runCatching {
                diagnosticRepository.record(
                    DiagnosticEvent(
                        source = "app-shell",
                        level = DiagnosticLevel.INFO,
                        message = "正式应用 Shell 已启动",
                    ),
                )
            }
            steamSessionRepository.restorePersistedSession()
        }
    }

}
