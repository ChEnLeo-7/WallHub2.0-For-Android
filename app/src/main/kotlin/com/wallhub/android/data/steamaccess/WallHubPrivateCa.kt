@file:Suppress("DEPRECATION")

package com.wallhub.android.data.steamaccess

import org.spongycastle.asn1.x509.BasicConstraints
import org.spongycastle.asn1.x509.ExtendedKeyUsage
import org.spongycastle.asn1.x509.GeneralName
import org.spongycastle.asn1.x509.GeneralNames
import org.spongycastle.asn1.x509.KeyPurposeId
import org.spongycastle.asn1.x509.KeyUsage
import org.spongycastle.asn1.x509.X509Extensions
import org.spongycastle.x509.X509V3CertificateGenerator
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

@Singleton
internal class WallHubPrivateCa
    @Inject
    constructor() {
        private data class Identity(
            val keyPair: KeyPair,
            val certificate: X509Certificate,
        )

        private val random = SecureRandom()
        private val root = createRootIdentity()
        private val identities = ConcurrentHashMap<String, Identity>()
        private val privateTrustManager = trustManagerFor(root.certificate)
        private val systemTrustManager = systemTrustManager()

        val clientTrustManager: X509TrustManager =
            object : X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<out X509Certificate>,
                    authType: String,
                ) {
                    systemTrustManager.checkClientTrusted(chain, authType)
                }

                override fun checkServerTrusted(
                    chain: Array<out X509Certificate>,
                    authType: String,
                ) {
                    try {
                        systemTrustManager.checkServerTrusted(chain, authType)
                    } catch (systemFailure: CertificateException) {
                        try {
                            privateTrustManager.checkServerTrusted(chain, authType)
                        } catch (privateFailure: CertificateException) {
                            privateFailure.addSuppressed(systemFailure)
                            throw privateFailure
                        }
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> =
                    systemTrustManager.acceptedIssuers + privateTrustManager.acceptedIssuers
            }

        val clientSocketFactory: SSLSocketFactory =
            SSLContext
                .getInstance("TLS")
                .apply {
                    init(null, arrayOf(clientTrustManager), random)
                }.socketFactory

        fun createServerSocket(
            socket: Socket,
            hostname: String,
        ): SSLSocket {
            val host = SteamDomainPolicy.requireSupported(hostname)
            val identity = identities.computeIfAbsent(host, ::createLeafIdentity)
            val keyStore =
                KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null)
                    setKeyEntry(
                        "wallhub-loopback",
                        identity.keyPair.private,
                        KEY_PASSWORD,
                        arrayOf(identity.certificate, root.certificate),
                    )
                }
            val keyManagerFactory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                    init(keyStore, KEY_PASSWORD)
                }
            val context =
                SSLContext.getInstance("TLS").apply {
                    init(keyManagerFactory.keyManagers, null, random)
                }
            return (
                context.socketFactory.createSocket(
                    socket,
                    socket.inetAddress.hostAddress,
                    socket.port,
                    true,
                ) as SSLSocket
            ).apply {
                useClientMode = false
                enabledProtocols = supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            }
        }

        internal fun rootCertificate(): X509Certificate = root.certificate

        private fun createRootIdentity(): Identity {
            val keyPair = generateKeyPair()
            val now = System.currentTimeMillis()
            val principal = X500Principal("CN=WallHub Process Root, O=WallHub")
            val certificate =
                X509V3CertificateGenerator()
                    .apply {
                        setSerialNumber(serialNumber())
                        setIssuerDN(principal)
                        setSubjectDN(principal)
                        setNotBefore(Date(now - CLOCK_SKEW_MS))
                        setNotAfter(Date(now + ROOT_VALIDITY_MS))
                        setPublicKey(keyPair.public)
                        setSignatureAlgorithm(SIGNATURE_ALGORITHM)
                        addExtension(X509Extensions.BasicConstraints, true, BasicConstraints(0))
                        addExtension(
                            X509Extensions.KeyUsage,
                            true,
                            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign),
                        )
                    }.generate(keyPair.private, random)
            certificate.checkValidity()
            certificate.verify(keyPair.public)
            return Identity(keyPair, certificate)
        }

        private fun createLeafIdentity(hostname: String): Identity {
            val keyPair = generateKeyPair()
            val now = System.currentTimeMillis()
            val certificate =
                X509V3CertificateGenerator()
                    .apply {
                        setSerialNumber(serialNumber())
                        setIssuerDN(root.certificate.subjectX500Principal)
                        setSubjectDN(X500Principal("CN=$hostname, O=WallHub Loopback"))
                        setNotBefore(Date(now - CLOCK_SKEW_MS))
                        setNotAfter(Date(now + LEAF_VALIDITY_MS))
                        setPublicKey(keyPair.public)
                        setSignatureAlgorithm(SIGNATURE_ALGORITHM)
                        addExtension(X509Extensions.BasicConstraints, true, BasicConstraints(false))
                        addExtension(X509Extensions.KeyUsage, true, KeyUsage(KeyUsage.digitalSignature))
                        addExtension(
                            X509Extensions.ExtendedKeyUsage,
                            false,
                            ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth),
                        )
                        addExtension(
                            X509Extensions.SubjectAlternativeName,
                            false,
                            GeneralNames(GeneralName(GeneralName.dNSName, hostname)),
                        )
                    }.generate(root.keyPair.private, random)
            certificate.checkValidity()
            certificate.verify(root.certificate.publicKey)
            return Identity(keyPair, certificate)
        }

        private fun generateKeyPair(): KeyPair =
            KeyPairGenerator
                .getInstance("EC")
                .apply {
                    initialize(ECGenParameterSpec("secp256r1"), random)
                }.generateKeyPair()

        private fun serialNumber(): BigInteger = BigInteger(128, random).setBit(127)

        private fun trustManagerFor(certificate: X509Certificate): X509TrustManager {
            val keyStore =
                KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null)
                    setCertificateEntry("wallhub-process-root", certificate)
                }
            return trustManager(keyStore)
        }

        private fun systemTrustManager(): X509TrustManager = trustManager(null)

        private fun trustManager(keyStore: KeyStore?): X509TrustManager {
            val factory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                    init(keyStore)
                }
            return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        }

        private companion object {
            val KEY_PASSWORD = "wallhub-process".toCharArray()
            const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
            const val CLOCK_SKEW_MS = 5 * 60_000L
            const val LEAF_VALIDITY_MS = 24 * 60 * 60_000L
            const val ROOT_VALIDITY_MS = 48 * 60 * 60_000L
        }
    }
