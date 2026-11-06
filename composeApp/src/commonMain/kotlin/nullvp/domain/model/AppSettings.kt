package nullvp.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class RoutingStrategy {
    BYPASS_LAN_AND_RU, // Direct for LAN & RU domains/IPs, proxy rest (Default)
    GLOBAL,            // Proxy all traffic
    BYPASS_LAN_ONLY,   // Direct for LAN, proxy rest
    CUSTOM_WHITELIST   // Only custom whitelist domains through proxy
}

@Serializable
data class AppSettings(
    val routingStrategy: RoutingStrategy = RoutingStrategy.BYPASS_LAN_AND_RU,
    val bypassRu: Boolean = true,
    val bypassLan: Boolean = true,
    val blockAds: Boolean = false,
    val remoteDns: String = "https://1.1.1.1/dns-query",
    val directDns: String = "77.88.8.8",
    val customDirectDomains: List<String> = emptyList(),
    val customProxyDomains: List<String> = emptyList(),
    val enableTunMode: Boolean = false, // false = System Proxy, true = TUN
    val appVersion: String = "v1.0.0"
)

