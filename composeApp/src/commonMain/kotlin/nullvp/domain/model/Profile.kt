package nullvp.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class ProtocolType {
    VLESS,
    VMESS,
    TROJAN,
    SHADOWSOCKS,
    WIREGUARD,
    HYSTERIA2,
    MASTER_DNS,
    VK_TURN,
    MAILRU,
    AVITO,
    CUPSONLINE,
    CUSTOM
}

@Serializable
data class Profile(
    val id: String,
    val name: String,
    val serverAddress: String,
    val serverPort: Int,
    val protocolType: ProtocolType,
    val protocolRawConfig: String = "",
    val subscriptionId: String? = null,
    val pingMs: Long = -1,
    val pingDetails: String? = null,
    val serverIp: String? = null,
    val serverLocation: String? = null,
    val serverFlag: String? = null,
    val lastConnected: Long = 0,
    val isFavorite: Boolean = false
)

@Serializable
data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    val autoUpdate: Boolean = true,
    val lastUpdated: Long = 0,
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    val totalBytes: Long = 0,
    val expireTimestamp: Long = 0
)

@Serializable
data class TrafficStats(
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val rxSpeedBps: Long = 0,
    val txSpeedBps: Long = 0,
    val activeStreams: Int = 0,
    val isConnected: Boolean = false
)
