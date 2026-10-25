package com.wallhub.android.core.model

enum class SteamDohRegion {
    DOMESTIC,
    FOREIGN,
    CUSTOM,
}

enum class SteamDohProtocol {
    JSON,
    WIRE,
    JSON_AND_WIRE,
    AUTO,
}

data class SteamDohEndpoint(
    val provider: String,
    val url: String,
    val region: SteamDohRegion,
    val protocol: SteamDohProtocol,
    val bootstrapAddresses: List<String> = emptyList(),
)

object SteamDohCatalog {
    val foreign: List<SteamDohEndpoint> =
        listOf(
            endpoint("abdullahabas.de", "https://dns.abdullahabas.de/dns-query"),
            endpoint("amlegion.org", "https://mailer.amlegion.org/dns-query"),
            endpoint("aws.ketan.dev", "https://pihole.aws.ketan.dev/dns-query"),
            endpoint("CleanBrowsing", "https://doh.cleanbrowsing.org/doh/family-filter/"),
            endpoint("Danielle McLean", "https://ns.00dani.me/dns-query", SteamDohProtocol.JSON_AND_WIRE),
            endpoint("eranext.net", "https://shield1.eranext.net/dns-query"),
            endpoint("Fancyorg.at", "https://dns.fancyorg.at/dns-query"),
            endpoint(
                "fullaccesstointernet.jp.eu.org",
                "https://dns.fullaccesstointernet.jp.eu.org/dns-query",
            ),
            endpoint("Jackyes.ovh", "https://jackyes.ovh/dns-query"),
            endpoint("lege.despagne.net", "https://adguard.lege.despagne.net/dns-query"),
            endpoint("PersianNIT", "https://darya.persiannit.net/dns-query"),
            endpoint("RaspTV", "https://emby.rasp.tv/dns-query"),
            endpoint("rferee.dev", "https://resolver.rferee.dev/dns-query/"),
            endpoint("shutgaming.net", "https://adguard.shutgaming.net/dns-query"),
            endpoint("Tiarap", "https://doh.tiarap.org/dns-query", SteamDohProtocol.JSON_AND_WIRE),
            endpoint("tooli.ca", "https://dot.tooli.ca/dns-query"),
            endpoint("tzockt.beer", "https://ad.tzockt.beer/dns-query"),
            endpoint("crypto.sx", "https://doh.crypto.sx/dns-query", SteamDohProtocol.JSON_AND_WIRE),
            endpoint("Liberador", "https://dns.liberador.net"),
            endpoint("Neeb.it", "https://dns.neeb.it/dns-query"),
        )

    val domestic: List<SteamDohEndpoint> =
        listOf(
            domestic("Alibaba Cloud AliDNS", "https://dns.alidns.com/dns-query"),
            domestic("Tencent DNSPod", "https://sm2.doh.pub/dns-query", SteamDohProtocol.JSON_AND_WIRE),
            domestic("Qihoo 360", "https://doh.360.cn/dns-query"),
            domestic("C-dns", "https://www.c-dns.com/dns-query"),
            domestic("23-4.cn", "https://dns.23-4.cn/dns-query"),
            domestic("beardic.cn", "https://dns.beardic.cn/dns-query", SteamDohProtocol.JSON_AND_WIRE),
            domestic("mbrjun.cn", "https://dns.mbrjun.cn/dns-query"),
            domestic(
                "18Bit DNS",
                "https://doh.18bit.cn/dns-query",
                bootstrapAddresses = listOf("111.170.6.190"),
            ),
            domestic("Totoro DNS", "https://doh.totoro.pub/dns-query"),
            domestic("hshh.org", "https://hshh.org/dns-query"),
        )

    val builtIn: List<SteamDohEndpoint> = foreign + domestic
    val byUrl: Map<String, SteamDohEndpoint> = builtIn.associateBy(SteamDohEndpoint::url)

    fun endpointFor(url: String): SteamDohEndpoint =
        byUrl[url] ?: SteamDohEndpoint(
            provider = runCatching { java.net.URI(url).host }.getOrNull().orEmpty().ifBlank { url },
            url = url,
            region = SteamDohRegion.CUSTOM,
            protocol = SteamDohProtocol.AUTO,
        )

    private fun endpoint(
        provider: String,
        url: String,
        protocol: SteamDohProtocol = SteamDohProtocol.WIRE,
    ) = SteamDohEndpoint(provider, url, SteamDohRegion.FOREIGN, protocol)

    private fun domestic(
        provider: String,
        url: String,
        protocol: SteamDohProtocol = SteamDohProtocol.WIRE,
        bootstrapAddresses: List<String> = emptyList(),
    ) = SteamDohEndpoint(provider, url, SteamDohRegion.DOMESTIC, protocol, bootstrapAddresses)
}

const val STEAM_ACCESS_DOH_BUILT_IN_LIMIT_PER_REGION = 5
const val STEAM_ACCESS_DOH_ENDPOINT_LIMIT = 64

val DEFAULT_STEAM_ACCESS_DOH_ENDPOINTS: List<String> = SteamDohCatalog.builtIn.map(SteamDohEndpoint::url)
