package com.wallhub.android.data.update

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

@Singleton
internal class GitHubNetworkClient
    @Inject
    constructor() {
        private val resolverClient =
            OkHttpClient
                .Builder()
                .connectTimeout(DOH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(DOH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(DOH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build()

        private val enhancedDns =
            Dns { hostname ->
                if (hostname.lowercase() in GITHUB_ENHANCED_HOSTS) {
                    resolvePublicIpv4(hostname)
                } else {
                    Dns.SYSTEM.lookup(hostname)
                }
            }

        fun newBuilder(enhanced: Boolean): OkHttpClient.Builder =
            OkHttpClient.Builder().apply {
                if (enhanced) dns(enhancedDns)
            }

        private fun resolvePublicIpv4(hostname: String): List<InetAddress> {
            val url =
                DOH_RESOLVER_URL
                    .toHttpUrl()
                    .newBuilder()
                    .setQueryParameter("name", hostname)
                    .setQueryParameter("type", "A")
                    .build()
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .header("User-Agent", GITHUB_NETWORK_USER_AGENT)
                    .get()
                    .build()
            val addresses =
                try {
                    resolverClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("GitHub DoH HTTP ${response.code}")
                        parseGitHubPublicIpv4Answers(response.peekBody(MAX_DOH_RESPONSE_BYTES).string())
                    }
                } catch (error: IOException) {
                    throw UnknownHostException("GitHub DoH failed for $hostname").apply { initCause(error) }
                }
            if (addresses.isEmpty()) throw UnknownHostException("GitHub DoH returned no public IPv4 for $hostname")
            return addresses.take(MAX_GITHUB_CANDIDATES)
        }

        internal companion object {
            const val GITHUB_API_HOST = "api.github.com"
            const val GITHUB_RELEASE_ASSETS_HOST = "release-assets.githubusercontent.com"
            private const val DOH_RESOLVER_URL = "https://doh.pub/resolve"
            private const val DOH_TIMEOUT_SECONDS = 4L
            private const val MAX_DOH_RESPONSE_BYTES = 64L * 1024L
            private const val MAX_GITHUB_CANDIDATES = 4
            private const val GITHUB_NETWORK_USER_AGENT = "WallHub-Android-GitHub-Network/1.0"
            private val GITHUB_ENHANCED_HOSTS =
                setOf(
                    GITHUB_API_HOST,
                    GITHUB_RELEASE_ASSETS_HOST,
                )
        }
    }

internal fun parseGitHubPublicIpv4Answers(payload: String): List<InetAddress> {
    val root = runCatching { JSONObject(payload) }.getOrNull() ?: return emptyList()
    if (root.optInt("Status", -1) != 0) return emptyList()
    val answers = root.optJSONArray("Answer") ?: return emptyList()
    return buildList {
        repeat(answers.length()) { index ->
            val answer = answers.optJSONObject(index) ?: return@repeat
            if (answer.optInt("type") != 1) return@repeat
            val raw = answer.optString("data")
            val octets = raw.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull() } ?: return@repeat
            if (octets.any { it == null || it !in 0..255 }) return@repeat
            val bytes = octets.map { requireNotNull(it).toByte() }.toByteArray()
            val address =
                runCatching { InetAddress.getByAddress(raw, bytes) }.getOrNull() as? Inet4Address ?: return@repeat
            if (address.isUsableGitHubPublicIpv4()) add(address)
        }
    }.distinctBy(InetAddress::getHostAddress)
}

private fun Inet4Address.isUsableGitHubPublicIpv4(): Boolean {
    val octets = address.map(Byte::toInt).map { it and 0xff }
    if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isMulticastAddress || isSiteLocalAddress) return false
    return when {
        octets[0] == 0 -> false
        octets[0] == 100 && octets[1] in 64..127 -> false
        octets[0] == 169 && octets[1] == 254 -> false
        octets[0] == 192 && octets[1] == 0 && octets[2] in 0..2 -> false
        octets[0] == 192 && octets[1] == 88 && octets[2] == 99 -> false
        octets[0] == 198 && octets[1] in 18..19 -> false
        octets[0] == 198 && octets[1] == 51 && octets[2] == 100 -> false
        octets[0] == 203 && octets[1] == 0 && octets[2] == 113 -> false
        octets[0] >= 224 -> false
        else -> true
    }
}
