package nullvp.domain.model

import kotlinx.serialization.Serializable
import nullvp.i18n.AppLanguage

@Serializable
enum class OperationMode {
    TUN,          // Full virtual TUN network adapter
    SYSTEM_PROXY, // OS system proxy (gsettings / registry) - rootless
    PROXY_ONLY    // Local SOCKS5 & HTTP inbound only (manual proxy)
}

@Serializable
enum class RoutingStrategy {
    BYPASS_LAN_AND_RU, // Smart bypass of RU and LAN via geosite & geoip
    GLOBAL,            // Route everything through proxy
    SMART_WHITELIST,   // Proxy only blocked resources (v2whitelist approach)
    CUSTOM             // Fully custom user rules
}

@Serializable
data class CustomRoutingRule(
    val id: String,
    val name: String,
    val outbound: String, // "direct", "proxy", "block"
    val domains: List<String> = emptyList(),
    val ips: List<String> = emptyList(),
    val enabled: Boolean = true
)

@Serializable
data class AppSettings(
    // Language
    val language: AppLanguage = AppLanguage.RU,

    // Mode of operation (SYSTEM_PROXY by default as requested)
    val operationMode: OperationMode = OperationMode.SYSTEM_PROXY,

    // Network & Ports
    val socksPort: Int = 10808,
    val httpPort: Int = 10809,
    val allowLan: Boolean = false,
    val enableIpv6: Boolean = false,
    val tunMtu: Int = 1500,

    // Proxy Authentication (Optional password protection for proxy ports)
    val proxyAuthEnabled: Boolean = false,
    val proxyUsername: String = "",
    val proxyPassword: String = "",

    // Routing Strategy & Quick Toggles
    val routingStrategy: RoutingStrategy = RoutingStrategy.BYPASS_LAN_AND_RU,
    val blockAds: Boolean = true,  // Block ads enabled by default!
    val bypassLan: Boolean = true,
    val bypassRu: Boolean = true,
    val dynamicRuAppScannerEnabled: Boolean = true, // Dynamically scan & bypass RU apps (4PDA forced through VPN)
    val perAppProxyEnabled: Boolean = false,
    val perAppBypassMode: Boolean = true,
    val perAppSelectedPackages: List<String> = emptyList(),

    // Granular Custom Routing Rules (v2dns style)
    val customRules: List<CustomRoutingRule> = defaultRoutingRules(),

    // DNS & Sniffing
    val remoteDns: String = "https://1.1.1.1/dns-query",
    val directDns: String = "77.88.8.8",
    val fakeDns: Boolean = true,
    val sniffingEnabled: Boolean = true,

    // DPI & Mux
    val muxEnabled: Boolean = false,
    val muxConcurrency: Int = 8,
    val fragmentEnabled: Boolean = false,
    val fragmentPackets: String = "tlshello",
    val fragmentLength: String = "100-200",
    val fragmentInterval: String = "10-20",

    // UI & System
    val minimizeToTrayOnClose: Boolean = true,
    val appVersion: String = "v1.0.0"
) {
    companion object {
        fun defaultRoutingRules(): List<CustomRoutingRule> = listOf(
            CustomRoutingRule(
                id = "rule-ads",
                name = "Реклама и аналитика (Adblock)",
                outbound = "block",
                domains = listOf("geosite:category-ads-all"),
                enabled = true
            ),
            CustomRoutingRule(
                id = "rule-lan",
                name = "Локальная сеть (LAN)",
                outbound = "direct",
                ips = listOf("geoip:private", "192.168.0.0/16", "10.0.0.0/8", "172.16.0.0/12", "127.0.0.0/8"),
                enabled = true
            ),
            CustomRoutingRule(
                id = "rule-ru-gov-banks",
                name = "Госсайты и банки РФ (Прямое подключение)",
                outbound = "direct",
                domains = listOf("geosite:category-gov-ru", "geosite:yandex", "geosite:mailru", "geosite:vk", "domain:sberbank.ru", "domain:sber.ru", "domain:tinkoff.ru", "domain:tbank.ru"),
                ips = listOf("geoip:ru"),
                enabled = true
            )
        )
    }
}
