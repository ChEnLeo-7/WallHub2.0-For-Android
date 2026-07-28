package com.wallhub.android.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.wallhub.android.core.model.SettingsRepository
import com.wallhub.android.core.model.SteamVpnController
import com.wallhub.android.core.model.SteamVpnPhase
import com.wallhub.android.core.model.SteamVpnState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal object SteamVpnRuntime {
    private val mutableState = MutableStateFlow(SteamVpnState())
    val state: StateFlow<SteamVpnState> = mutableState.asStateFlow()

    fun update(transform: (SteamVpnState) -> SteamVpnState) {
        mutableState.value = transform(mutableState.value).copy(updatedAt = System.currentTimeMillis())
    }

    fun replace(state: SteamVpnState) {
        mutableState.value = state.copy(updatedAt = System.currentTimeMillis())
    }
}

@Singleton
class AndroidSteamVpnController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) : SteamVpnController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val state: StateFlow<SteamVpnState> = SteamVpnRuntime.state

    override fun prepareIntent(): Intent? = VpnService.prepare(context)

    override fun start() {
        if (state.value.isActive) return
        SteamVpnRuntime.replace(SteamVpnState(phase = SteamVpnPhase.PREPARING))
        scope.launch {
            runCatching {
                settingsRepository.setSteamAccessEnabled(false)
                settingsRepository.setDownloadProxyEnabled(false)
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, SteamAccelerationVpnService::class.java).setAction(
                        SteamAccelerationVpnService.ACTION_START,
                    ),
                )
            }.onFailure { error ->
                SteamVpnRuntime.replace(
                    SteamVpnState(
                        phase = SteamVpnPhase.FAILED,
                        message = error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
        }
    }

    override fun stop() {
        if (state.value.phase == SteamVpnPhase.DISABLED) return
        SteamVpnRuntime.update { current -> current.copy(phase = SteamVpnPhase.STOPPING) }
        context.startService(
            Intent(context, SteamAccelerationVpnService::class.java).setAction(
                SteamAccelerationVpnService.ACTION_STOP,
            ),
        )
    }
}
