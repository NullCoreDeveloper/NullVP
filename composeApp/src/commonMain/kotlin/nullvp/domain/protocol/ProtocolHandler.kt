package nullvp.domain.protocol

import nullvp.domain.model.Profile
import nullvp.domain.model.ProtocolType

data class ProtocolCapabilities(
    val supportsUdp: Boolean = true,
    val requiresTun: Boolean = false,
    val supportsProxyMode: Boolean = true,
    val supportsCaptcha: Boolean = false
)

sealed class ValidationResult {
    data object Valid : ValidationResult()
    data class Invalid(val reason: String) : ValidationResult()
}

/**
 * Universal interface for pluggable protocol handlers.
 * Adding a new protocol requires only implementing this interface and registering it
 * in ProtocolRegistry.
 */
interface ProtocolHandler {
    val protocolType: ProtocolType
    val scheme: String
    val alternativeSchemes: List<String> get() = emptyList()
    val displayName: String
    val capabilities: ProtocolCapabilities

    /**
     * Parses an incoming URI/Link (e.g. "mdns://ey...", "vless://uuid@host:port?...") into a Profile.
     */
    fun parseUri(uri: String): Profile?

    /**
     * Formats a Profile back into a shareable URI.
     */
    fun formatUri(profile: Profile): String?

    /**
     * Validates whether the profile configuration is complete and correct.
     */
    fun validate(profile: Profile): ValidationResult

    /**
     * Generates the Core-compatible JSON representation for launching this profile in Go core.
     */
    fun toCoreConfigJson(profile: Profile): String
}

