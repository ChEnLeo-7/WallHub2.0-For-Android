package com.wallhub.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.wallhub.android.MainActivity
import com.wallhub.android.R
import com.wallhub.android.core.model.SteamVpnPhase
import com.wallhub.android.core.model.SteamVpnState
import com.wallhub.android.data.vpn.FirestackVpnEngine
import com.wallhub.android.data.vpn.FirestackVpnEvent
import com.wallhub.android.data.vpn.ProtectedSocketFactory
import com.wallhub.android.data.vpn.SocketFdProtector
import com.wallhub.android.data.vpn.SocksRelayEvent
import com.wallhub.android.data.vpn.SocksTarget
import com.wallhub.android.data.vpn.SteamVpnSocksServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.SocketChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SteamAccelerationVpnService : VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivityManager by lazy { getSystemService(ConnectivityManager::class.java) }
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    private var tunInterface: ParcelFileDescriptor? = null
    private var socksServer: SteamVpnSocksServer? = null
    private var packetEngine: FirestackVpnEngine? = null
    private var starting = false

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn(SteamVpnPhase.DISABLED)
            return START_NOT_STICKY
        }
        startForegroundNotification()
        if (!starting && packetEngine == null) {
            starting = true
            serviceScope.launch { startVpn() }
        }
        return START_STICKY
    }

    private fun startVpn() {
        SteamVpnRuntime.replace(SteamVpnState(phase = SteamVpnPhase.PREPARING))
        try {
            check(prepare(this) == null) { "VPN permission is not granted" }
            val underlyingNetwork = selectUnderlyingNetwork()
            val mtu = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                connectivityManager.getLinkProperties(underlyingNetwork)?.mtu
                    ?.takeIf { value -> value in MIN_MTU..MAX_MTU }
                    ?: DEFAULT_MTU
            } else {
                DEFAULT_MTU
            }
            val established = establishInterface(mtu, underlyingNetwork)
                ?: error("Android did not establish the VPN interface")
            tunInterface = established

            val directServer = SteamVpnSocksServer(
                socketFactory = DirectSocketFactory(),
                eventListener = ::handleSocksEvent,
            ).also(SteamVpnSocksServer::start)
            socksServer = directServer

            val engine = FirestackVpnEngine(
                fdProtector = SocketFdProtector { fd -> protect(fd) },
                eventListener = ::handleEngineEvent,
            )
            packetEngine = engine
            engine.start(
                tunFd = established.fd,
                mtu = mtu,
                socksPort = directServer.port,
            )
            SteamVpnRuntime.update { current ->
                current.copy(
                    phase = SteamVpnPhase.RUNNING,
                    message = "全设备流量已接入",
                )
            }
            updateNotification()
        } catch (error: Throwable) {
            SteamVpnRuntime.replace(
                SteamVpnState(
                    phase = SteamVpnPhase.FAILED,
                    message = error.message ?: error.javaClass.simpleName,
                ),
            )
            closeResources()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } finally {
            starting = false
        }
    }

    private fun establishInterface(mtu: Int, underlyingNetwork: Network?): ParcelFileDescriptor? {
        val configureIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Builder()
            .setSession(getString(R.string.wallhub_vpn_session_name))
            .setConfigureIntent(configureIntent)
            .setMtu(mtu)
            .addAddress(TUN_IPV4_ADDRESS, TUN_IPV4_PREFIX)
            .addAddress(TUN_IPV6_ADDRESS, TUN_IPV6_PREFIX)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer(TUN_IPV4_DNS)
            .addDnsServer(TUN_IPV6_DNS)
        underlyingNetwork?.let { network -> builder.setUnderlyingNetworks(arrayOf(network)) }
        return builder.establish()
    }

    private fun selectUnderlyingNetwork(): Network? {
        val active = connectivityManager.activeNetwork
        val candidates = connectivityManager.allNetworks.filter { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@filter false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        return candidates.maxByOrNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            var score = 0
            if (network == active) score += 4
            if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) score += 2
            if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true) score += 1
            score
        }
    }

    private inner class DirectSocketFactory : ProtectedSocketFactory {
        override fun connect(target: SocksTarget): Socket {
            val network = selectUnderlyingNetwork()
            val addresses = target.address?.let(::listOf)
                ?: network?.getAllByName(target.host)?.toList()
                ?: error("No underlying network can resolve ${target.host}")
            var lastFailure: Throwable? = null
            for (address in addresses) {
                val socket = SocketChannel.open().socket()
                try {
                    check(protect(socket)) { "Could not protect upstream socket" }
                    network?.bindSocket(socket)
                    socket.connect(InetSocketAddress(address, target.port), CONNECT_TIMEOUT_MS)
                    return socket
                } catch (error: Throwable) {
                    lastFailure = error
                    runCatching { socket.close() }
                }
            }
            throw lastFailure ?: error("No address is available for ${target.host}")
        }
    }

    private fun handleEngineEvent(event: FirestackVpnEvent) {
        when (event) {
            is FirestackVpnEvent.EngineStarted -> SteamVpnRuntime.update { current ->
                current.copy(engineBuild = event.build)
            }

            is FirestackVpnEvent.FlowOpened -> SteamVpnRuntime.update { current ->
                current.copy(activeFlows = event.activeFlows)
            }

            is FirestackVpnEvent.FlowClosed -> SteamVpnRuntime.update { current ->
                current.copy(
                    activeFlows = event.activeFlows,
                    uploadedBytes = event.uploadedBytes,
                    downloadedBytes = event.downloadedBytes,
                )
            }

            is FirestackVpnEvent.EngineFailure -> serviceScope.launch {
                SteamVpnRuntime.update { current ->
                    current.copy(phase = SteamVpnPhase.FAILED, message = event.message)
                }
                stopVpn(SteamVpnPhase.FAILED)
            }
        }
    }

    private fun handleSocksEvent(event: SocksRelayEvent) {
        SteamVpnRuntime.update { current ->
            current.copy(
                fragmentedConnections = current.fragmentedConnections + if (event.fragmentedHost != null) 1 else 0,
                message = when {
                    event.fragmentedHost != null -> "已分片 ${event.fragmentedHost}"
                    event.failure != null -> "转发失败：${event.failure}"
                    else -> current.message
                },
            )
        }
        updateNotification()
    }

    override fun onRevoke() {
        SteamVpnRuntime.update { current ->
            current.copy(phase = SteamVpnPhase.REVOKED, message = "VPN 权限已撤销")
        }
        stopVpn(SteamVpnPhase.REVOKED)
        super.onRevoke()
    }

    override fun onDestroy() {
        closeResources()
        serviceScope.cancel()
        if (SteamVpnRuntime.state.value.phase !in setOf(SteamVpnPhase.FAILED, SteamVpnPhase.REVOKED)) {
            SteamVpnRuntime.replace(SteamVpnState())
        }
        super.onDestroy()
    }

    private fun stopVpn(finalPhase: SteamVpnPhase) {
        closeResources()
        SteamVpnRuntime.replace(
            SteamVpnState(
                phase = finalPhase,
                message = when (finalPhase) {
                    SteamVpnPhase.REVOKED -> "VPN 权限已撤销"
                    SteamVpnPhase.FAILED -> SteamVpnRuntime.state.value.message
                    else -> null
                },
            ),
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeResources() {
        runCatching { packetEngine?.close() }
        runCatching { socksServer?.close() }
        runCatching { tunInterface?.close() }
        packetEngine = null
        socksServer = null
        tunInterface = null
    }

    private fun startForegroundNotification() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.wallhub_vpn_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.wallhub_vpn_channel_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SteamAccelerationVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val state = SteamVpnRuntime.state.value
        val content = if (state.phase == SteamVpnPhase.RUNNING) {
            getString(
                R.string.wallhub_vpn_notification_running,
                state.activeFlows,
                state.fragmentedConnections,
            )
        } else {
            getString(R.string.wallhub_vpn_notification_starting)
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.wallhub_vpn_notification_title))
            .setContentText(content)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.wallhub_vpn_stop), stopIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "com.wallhub.android.vpn.START"
        const val ACTION_STOP = "com.wallhub.android.vpn.STOP"
        private const val NOTIFICATION_CHANNEL_ID = "wallhub_global_vpn"
        private const val NOTIFICATION_ID = 0x5748
        private const val DEFAULT_MTU = 1500
        private const val MIN_MTU = 1280
        private const val MAX_MTU = 9000
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val TUN_IPV4_ADDRESS = "10.111.222.1"
        private const val TUN_IPV4_PREFIX = 24
        private const val TUN_IPV4_DNS = "10.111.222.3"
        private const val TUN_IPV6_ADDRESS = "fd66:f83a:c650::1"
        private const val TUN_IPV6_PREFIX = 120
        private const val TUN_IPV6_DNS = "fd66:f83a:c650::3"
    }
}
