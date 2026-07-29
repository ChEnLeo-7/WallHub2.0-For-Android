package com.wallhub.android.data.steamaccess

import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoSniTlsDialerTest {
    @Test
    fun `client hello omits server name extension`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val executor = Executors.newSingleThreadExecutor()
        val captured = executor.submit<ByteArray> {
            server.accept().use { socket ->
                val input = DataInputStream(socket.getInputStream())
                val header = ByteArray(5).also(input::readFully)
                val length = ((header[3].toInt() and 0xff) shl 8) or (header[4].toInt() and 0xff)
                header + ByteArray(length).also(input::readFully)
            }
        }

        val dialer = NoSniTlsDialer(defaultSocketFactory(), exactSanVerifier())
        assertFails {
            dialer.connect(
                hostname = "steamcommunity.com",
                candidates = listOf(InetAddress.getLoopbackAddress()),
                port = server.localPort,
            )
        }

        val clientHello = captured.get(5, TimeUnit.SECONDS)
        assertFalse(hasServerNameExtension(clientHello))
        server.close()
        executor.shutdownNow()
    }

    @Test
    fun `real connection race attributes completed candidate failures`() {
        val privateCa = WallHubPrivateCa()
        val socketFactory = socketFactoryTrusting(privateCa.rootCertificate())
        val dialer = NoSniTlsDialer(socketFactory, exactSanVerifier())
        val server = startTlsServer(privateCa, "steamcommunity.com")
        val failures = mutableListOf<String>()

        val accepted = dialer.connect(
            hostname = "steamcommunity.com",
            candidates = listOf(
                InetAddress.getByName("127.0.0.2"),
                InetAddress.getLoopbackAddress(),
            ),
            port = server.port,
            onFailure = { address, _ -> failures += address.hostAddress },
        )

        assertEquals(InetAddress.getLoopbackAddress().hostAddress, accepted.address.hostAddress)
        assertEquals(listOf("127.0.0.2"), failures)
        accepted.socket.close()
        server.close()
    }

    @Test
    fun `race timeout attributes candidate and closes in-flight socket`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val executor = Executors.newSingleThreadExecutor()
        val closed = executor.submit<Boolean> {
            server.accept().use { socket ->
                while (socket.getInputStream().read() >= 0) Unit
                true
            }
        }
        val failures = mutableListOf<String>()
        val dialer = NoSniTlsDialer(
            socketFactory = defaultSocketFactory(),
            hostnameVerifier = exactSanVerifier(),
            raceDelayMs = 0L,
            connectRaceBudgetMs = 150L,
        )

        assertFails {
            dialer.connect(
                hostname = "steamcommunity.com",
                candidates = listOf(InetAddress.getLoopbackAddress()),
                port = server.localPort,
                onFailure = { address, _ -> failures += address.hostAddress },
            )
        }

        assertEquals(listOf(InetAddress.getLoopbackAddress().hostAddress), failures)
        assertTrue(closed.get(2, TimeUnit.SECONDS))
        server.close()
        executor.shutdownNow()
    }

    @Test
    fun `strict validation accepts original host and rejects another steam host`() {
        val privateCa = WallHubPrivateCa()
        val socketFactory = socketFactoryTrusting(privateCa.rootCertificate())
        val dialer = NoSniTlsDialer(socketFactory, exactSanVerifier())

        val acceptedServer = startTlsServer(privateCa, "steamcommunity.com")
        val accepted = dialer.connect(
            hostname = "steamcommunity.com",
            candidates = listOf(InetAddress.getLoopbackAddress()),
            port = acceptedServer.port,
        )
        assertTrue(accepted.socket.session.peerCertificates.isNotEmpty())
        accepted.socket.close()
        acceptedServer.close()

        val rejectedServer = startTlsServer(privateCa, "steamcommunity.com")
        assertFails {
            dialer.connect(
                hostname = "api.steampowered.com",
                candidates = listOf(InetAddress.getLoopbackAddress()),
                port = rejectedServer.port,
            )
        }
        rejectedServer.close()
    }

    @Test
    fun `domain policy is exact and excludes cdn traffic`() {
        assertTrue(SteamDomainPolicy.supports("steamcommunity.com"))
        assertTrue(SteamDomainPolicy.supports("api.steampowered.com"))
        assertFalse(SteamDomainPolicy.supports("evil.steamcommunity.com"))
        assertFalse(SteamDomainPolicy.supports("steamuserimages-a.akamaihd.net"))
        assertFalse(SteamDomainPolicy.supports("cache1.steamcontent.com"))
    }

    private fun startTlsServer(privateCa: WallHubPrivateCa, certificateHost: String): RunningServer {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            runCatching {
                server.accept().use { socket ->
                    privateCa.createServerSocket(socket, certificateHost).use { tlsSocket ->
                        tlsSocket.startHandshake()
                        tlsSocket.inputStream.read()
                    }
                }
            }
        }
        return RunningServer(server, executor)
    }

    private fun socketFactoryTrusting(certificate: java.security.cert.X509Certificate): SSLSocketFactory {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("test-root", certificate)
        }
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }
        val trustManager = trustManagerFactory.trustManagers.filterIsInstance<X509TrustManager>().single()
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }.socketFactory
    }

    private fun defaultSocketFactory(): SSLSocketFactory = SSLContext.getInstance("TLS").apply {
        init(null, null, SecureRandom())
    }.socketFactory

    private fun exactSanVerifier(): HostnameVerifier = HostnameVerifier { hostname, session ->
        val certificate = session.peerCertificates.firstOrNull() as? java.security.cert.X509Certificate
            ?: return@HostnameVerifier false
        certificate.subjectAlternativeNames.orEmpty().any { name ->
            name.size >= 2 && name[0] == 2 && name[1].toString().equals(hostname, ignoreCase = true)
        }
    }

    private fun hasServerNameExtension(record: ByteArray): Boolean {
        var offset = 5
        assertEquals(1, record[offset].toInt() and 0xff)
        offset += 4
        offset += 2 + 32
        val sessionLength = record[offset].toInt() and 0xff
        offset += 1 + sessionLength
        val cipherLength = readUnsignedShort(record, offset)
        offset += 2 + cipherLength
        val compressionLength = record[offset].toInt() and 0xff
        offset += 1 + compressionLength
        val extensionsLength = readUnsignedShort(record, offset)
        offset += 2
        val extensionsEnd = offset + extensionsLength
        while (offset + 4 <= extensionsEnd) {
            val type = readUnsignedShort(record, offset)
            val length = readUnsignedShort(record, offset + 2)
            if (type == 0) return true
            offset += 4 + length
        }
        return false
    }

    private fun readUnsignedShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private data class RunningServer(
        private val server: ServerSocket,
        private val executor: java.util.concurrent.ExecutorService,
    ) {
        val port: Int
            get() = server.localPort

        fun close() {
            server.close()
            executor.shutdownNow()
        }
    }
}
