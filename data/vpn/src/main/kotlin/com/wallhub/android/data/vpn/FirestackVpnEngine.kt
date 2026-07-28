package com.wallhub.android.data.vpn

import android.os.Process
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.DNSOpts
import com.celzero.firestack.backend.DNSSummary
import com.celzero.firestack.backend.Gostr
import com.celzero.firestack.backend.ServerSummary
import com.celzero.firestack.backend.Tab
import com.celzero.firestack.intra.Bridge
import com.celzero.firestack.intra.Intra
import com.celzero.firestack.intra.Mark
import com.celzero.firestack.intra.PreMark
import com.celzero.firestack.intra.SocketSummary
import com.celzero.firestack.intra.Tunnel
import com.celzero.firestack.settings.Settings
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

fun interface SocketFdProtector {
    fun protect(fd: Int): Boolean
}

sealed interface FirestackVpnEvent {
    data class EngineStarted(val build: String) : FirestackVpnEvent
    data class FlowOpened(val activeFlows: Int) : FirestackVpnEvent
    data class FlowClosed(val activeFlows: Int, val uploadedBytes: Long, val downloadedBytes: Long) : FirestackVpnEvent
    data class EngineFailure(val message: String) : FirestackVpnEvent
}

class FirestackVpnEngine(
    private val fdProtector: SocketFdProtector,
    private val eventListener: (FirestackVpnEvent) -> Unit = {},
) : Closeable {
    private val bridge = WallHubFirestackBridge(fdProtector, eventListener)
    private var tunnel: Tunnel? = null

    fun start(
        tunFd: Int,
        mtu: Int,
        socksPort: Int,
        upstreamDnsServers: List<String>,
    ) {
        check(tunnel == null) { "Firestack engine is already running" }
        check(upstreamDnsServers.isNotEmpty()) { "No underlying DNS servers are available" }
        configureFirestack()
        val defaultDns = Intra.newDefaultDNS(
            gostr(Backend.DNS53),
            gostr(upstreamDnsServers.joinToString(",")),
            gostr(""),
        )
        val connected = Intra.connect(
            tunFd.toLong(),
            mtu.toLong(),
            mtu.toLong(),
            TUN_INTERFACE_ADDRESSES,
            TUN_DNS_ADDRESSES,
            defaultDns,
            bridge,
        )
        try {
            Intra.setSystemDNS(
                connected,
                gostr(upstreamDnsServers.joinToString(",")),
            )
            val proxyId = gostr(DIRECT_PROXY_ID)
            val proxyUrl = gostr("socks5://127.0.0.1:$socksPort")
            connected.proxies.addProxy(proxyId, proxyUrl)
            bridge.proxyReady.set(true)
            tunnel = connected
            eventListener(FirestackVpnEvent.EngineStarted(Intra.build(false)))
        } catch (error: Throwable) {
            connected.disconnect()
            throw error
        }
    }

    override fun close() {
        bridge.proxyReady.set(false)
        runCatching { tunnel?.proxies?.removeProxy(gostr(DIRECT_PROXY_ID)) }
        runCatching { tunnel?.disconnect() }
        tunnel = null
    }

    private fun configureFirestack() {
        Settings.setDebug(false)
        Settings.defaultTunMode()
        check(
            Settings.setDialerOpts(
                Settings.SplitNever,
                Settings.RetryNever,
                NO_SOCKET_TIMEOUT_SECONDS,
                true,
            ),
        ) { "Firestack rejected direct dialer options" }
        Settings.setAutoMode(Settings.AutoModeLocal)
        Intra.loopback(false)
        Intra.logLevel(FIRESTACK_ERROR_LOG_LEVEL, FIRESTACK_NO_CONSOLE_LOG_LEVEL)
    }

    private fun gostr(value: String): Gostr = Gostr().apply { s = value }

    companion object {
        const val DIRECT_PROXY_ID = "WallHubDirect"
        private const val NO_SOCKET_TIMEOUT_SECONDS = 0
        private const val FIRESTACK_ERROR_LOG_LEVEL = 5
        private const val FIRESTACK_NO_CONSOLE_LOG_LEVEL = 8
        private const val TUN_INTERFACE_ADDRESSES =
            "10.111.222.1/24,fd66:f83a:c650::1/120"
        private const val TUN_DNS_ADDRESSES =
            "10.111.222.3:53,[fd66:f83a:c650::3]:53"
    }
}

private class WallHubFirestackBridge(
    private val fdProtector: SocketFdProtector,
    private val eventListener: (FirestackVpnEvent) -> Unit,
) : Bridge {
    val proxyReady = AtomicBoolean(false)
    private val nextConnectionId = AtomicLong(1L)
    private val activeConnections = ConcurrentHashMap.newKeySet<String>()
    private val uploadedBytes = AtomicLong(0L)
    private val downloadedBytes = AtomicLong(0L)

    override fun bind4(who: String?, addrport: String?, fd: Long) {
        protect(who, fd)
    }

    override fun bind6(who: String?, addrport: String?, fd: Long) {
        protect(who, fd)
    }

    override fun protect(who: String?, fd: Long) {
        if (fd !in 0..Int.MAX_VALUE || !fdProtector.protect(fd.toInt())) {
            eventListener(FirestackVpnEvent.EngineFailure("protect_failed"))
        }
    }

    override fun preflow(protocol: Int, uid: Int, src: Gostr?, dst: Gostr?): PreMark =
        PreMark().apply {
            setUID(uid.toString())
            setIsUidSelf(uid == Process.myUid())
        }

    override fun flow(
        protocol: Int,
        uid: Int,
        src: Gostr?,
        dst: Gostr?,
        origdsts: Gostr?,
        domains: Gostr?,
        probableDomains: Gostr?,
        blocklists: Gostr?,
    ): Mark {
        val connectionId = "wallhub-${nextConnectionId.getAndIncrement()}"
        val isTunnelDns = dst?.v() in TUN_DNS_DESTINATIONS
        if (!isTunnelDns) {
            activeConnections += connectionId
            eventListener(FirestackVpnEvent.FlowOpened(activeConnections.size))
        }
        return Mark().apply {
            setCID(connectionId)
            setUID(uid.toString())
            setPIDCSV(
                when {
                    isTunnelDns -> Backend.Base
                    protocol == TCP_PROTOCOL && proxyReady.get() -> FirestackVpnEngine.DIRECT_PROXY_ID
                    else -> Backend.Exit
                },
            )
        }
    }

    override fun inflow(protocol: Int, uid: Int, src: Gostr?, dst: Gostr?): Mark = Mark().apply {
        setCID("blocked-inflow-${nextConnectionId.getAndIncrement()}")
        setUID(uid.toString())
        setPIDCSV(Backend.Block)
    }

    override fun postFlow(mark: Mark?) = Unit

    override fun onSocketClosed(summary: SocketSummary?) {
        if (summary == null) return
        activeConnections.remove(summary.id)
        val uploaded = uploadedBytes.addAndGet(summary.tx)
        val downloaded = downloadedBytes.addAndGet(summary.rx)
        eventListener(
            FirestackVpnEvent.FlowClosed(
                activeFlows = activeConnections.size,
                uploadedBytes = uploaded,
                downloadedBytes = downloaded,
            ),
        )
    }

    override fun onQuery(uid: Gostr?, domain: Gostr?, qtyp: Long): DNSOpts = DNSOpts().apply {
        setPIDCSV(Backend.Base)
        setTIDCSV(Backend.System)
    }

    override fun onUpstreamAnswer(summary: DNSSummary?, unmodifiedipcsv: Gostr?): DNSOpts? = null

    override fun onResponse(summary: DNSSummary?) = Unit

    override fun onDNSAdded(id: Gostr?) = Unit

    override fun onDNSRemoved(id: Gostr?) = Unit

    override fun onDNSStopped() = Unit

    override fun onProxyAdded(id: Gostr?) = Unit

    override fun onProxyRemoved(id: Gostr?) = Unit

    override fun onProxyStopped(id: Gostr?) = Unit

    override fun onProxiesStopped() = Unit

    override fun svcRoute(
        sid: String?,
        pid: String?,
        network: String?,
        sipport: String?,
        dipport: String?,
    ): Tab = Tab().apply {
        setCID("blocked-service-${nextConnectionId.getAndIncrement()}")
        setBlock(true)
    }

    override fun onSvcComplete(summary: ServerSummary?) = Unit

    override fun log(level: Int, message: Gostr?) = Unit

    companion object {
        private const val TCP_PROTOCOL = 6
        private val TUN_DNS_DESTINATIONS = setOf(
            "10.111.222.3:53",
            "[fd66:f83a:c650::3]:53",
        )
    }
}
