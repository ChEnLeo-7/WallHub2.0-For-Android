package com.wallhub.android.data.steam

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.wallhub.android.data.steamaccess.SteamAccessManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

internal data class SteamSessionNetworkReadiness(
    val generation: Long = 0L,
    val isReady: Boolean = false,
    /**
     * The semantic egress the Steam planes currently use, or null while no plan exists at all.
     *
     * It is deliberately not part of [generation]: a generation counts networks, while this value answers
     * "where would a session opened now send its traffic". A session is reopened when this changes, and
     * not when a capability update or a duplicate publication only moves the generation counter.
     */
    val egressIdentity: String? = null,
    /**
     * True while the egress is the baseline published before the enhancement decision for its generation.
     *
     * A reopen waits for the decision instead of running twice: the baseline names the egress requests use
     * right now, and on a network whose decision is the bridge it is a route that cannot serve Steam.
     */
    val egressProvisional: Boolean = false,
)

/**
 * Owns the default-network generation independently from Steam session generations.
 *
 * Capability updates for one default network keep its generation. Losing that network or selecting a different
 * default network advances the generation, fencing any route probe or restore that used the previous network.
 */
internal class SteamSessionNetworkStateOwner<T : Any> {
    private val mutableState = MutableStateFlow(SteamSessionNetworkReadiness())
    val state: StateFlow<SteamSessionNetworkReadiness> = mutableState.asStateFlow()

    private var activeNetwork: T? = null
    private var generation = 0L

    @Synchronized
    fun onAvailable(
        network: T,
        hasInternet: Boolean,
        isValidated: Boolean,
        hasCaptivePortal: Boolean,
    ) {
        if (network != activeNetwork) {
            activeNetwork = network
            generation += 1L
        }
        publish(hasInternet, isValidated, hasCaptivePortal)
    }

    @Synchronized
    fun onCapabilitiesChanged(
        network: T,
        hasInternet: Boolean,
        isValidated: Boolean,
        hasCaptivePortal: Boolean,
    ) {
        if (network != activeNetwork) return
        publish(hasInternet, isValidated, hasCaptivePortal)
    }

    @Synchronized
    fun onLost(network: T) {
        if (network != activeNetwork) return
        activeNetwork = null
        generation += 1L
        mutableState.value = SteamSessionNetworkReadiness(generation = generation, isReady = false)
    }

    private fun publish(
        hasInternet: Boolean,
        isValidated: Boolean,
        hasCaptivePortal: Boolean,
    ) {
        mutableState.value =
            SteamSessionNetworkReadiness(
                generation = generation,
                isReady = hasInternet && isValidated && !hasCaptivePortal,
            )
    }
}

internal interface SteamSessionNetworkReadinessSource {
    val state: StateFlow<SteamSessionNetworkReadiness>
}

/**
 * Emits Android default-network readiness joined with the egress the Steam planes currently use.
 *
 * The two are one snapshot because they answer one question: can this device run a Steam session now,
 * and through what. They keep their own meanings - the generation counts networks, the identity names
 * the route - so a capability update that only moves the counter never looks like a moved route, and a
 * plan republished for the same route never looks like a new network.
 */
@Singleton
internal class AndroidSteamSessionNetworkReadinessSource
    @Inject
    constructor(
        @ApplicationContext context: Context,
        access: SteamAccessManager,
    ) : SteamSessionNetworkReadinessSource {
        private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        private val owner = SteamSessionNetworkStateOwner<Network>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        override val state: StateFlow<SteamSessionNetworkReadiness> =
            combine(owner.state, access.egressPublication) { network, egress ->
                network.copy(egressIdentity = egress.identity, egressProvisional = egress.provisional)
            }.stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue =
                    owner.state.value.copy(
                        egressIdentity = access.egressPublication.value.identity,
                        egressProvisional = access.egressPublication.value.provisional,
                    ),
            )

        init {
            connectivityManager.activeNetwork?.let(::updateAvailable)
            connectivityManager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = updateAvailable(network)

                    override fun onCapabilitiesChanged(
                        network: Network,
                        capabilities: NetworkCapabilities,
                    ) = owner.onCapabilitiesChanged(
                        network = network,
                        hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                        isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        hasCaptivePortal = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
                    )

                    override fun onLost(network: Network) = owner.onLost(network)
                },
            )
        }

        private fun updateAvailable(network: Network) {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            owner.onAvailable(
                network = network,
                hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                isValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                hasCaptivePortal = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
            )
        }
    }
