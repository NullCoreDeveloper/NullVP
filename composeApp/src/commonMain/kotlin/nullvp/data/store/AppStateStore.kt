package nullvp.data.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nullvp.domain.model.AppSettings
import nullvp.domain.model.Profile
import nullvp.domain.model.ProtocolType
import nullvp.domain.model.Subscription
import nullvp.domain.protocol.ProtocolRegistry

object AppStateStore {

    private val defaultProfiles = listOf(
        Profile(
            id = "default_mdns",
            name = "NullDNS Turbo (Russia Bypass)",
            serverAddress = "ns1.tunnel.cloud",
            serverPort = 18000,
            protocolType = ProtocolType.MASTER_DNS,
            protocolRawConfig = "{\"DOMAINS\":[\"ns1.tunnel.cloud\"],\"DATA_ENCRYPTION_METHOD\":1,\"ENCRYPTION_KEY\":\"defaultKey\",\"LISTEN_PORT\":18000}",
            pingMs = 45
        ),
        Profile(
            id = "default_vkturn",
            name = "VK TURN Relay (Opus Audio)",
            serverAddress = "turn.vk.me",
            serverPort = 443,
            protocolType = ProtocolType.VK_TURN,
            protocolRawConfig = "{\"v\":1,\"name\":\"VK TURN Relay\",\"peer\":\"1.2.3.4:443\",\"vk\":\"https://vk.com/call/join/test\",\"n\":3}",
            pingMs = 28
        ),
        Profile(
            id = "default_hy2",
            name = "Hysteria 2 High-Speed Node",
            serverAddress = "hy2.node.vpn",
            serverPort = 8443,
            protocolType = ProtocolType.HYSTERIA2,
            protocolRawConfig = "{\"server\":\"hy2.node.vpn:8443\",\"auth\":\"secretPass\",\"sni\":\"cloudflare.com\",\"insecure\":false}",
            pingMs = 52
        ),
        Profile(
            id = "default_vless",
            name = "Frankfurt Reality (Xray XTLS)",
            serverAddress = "de1.node.vpn",
            serverPort = 443,
            protocolType = ProtocolType.VLESS,
            protocolRawConfig = "{\"uuid\":\"11111111-2222-3333-4444-555555555555\",\"host\":\"de1.node.vpn\",\"port\":443,\"query\":\"security=reality&type=tcp\"}",
            pingMs = 74
        )
    )

    private val _profiles = MutableStateFlow<List<Profile>>(defaultProfiles)
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _selectedProfileId = MutableStateFlow<String?>(defaultProfiles.firstOrNull()?.id)
    val selectedProfileId: StateFlow<String?> = _selectedProfileId.asStateFlow()

    private val _subscriptions = MutableStateFlow<List<Subscription>>(
        listOf(
            Subscription(
                id = "sub_main",
                name = "3x-ui Main Subscription",
                url = "https://vpn.example.com/sub/token123",
                uploadBytes = 2_147_483_648L,   // 2 GB
                downloadBytes = 18_253_611_008L, // ~17 GB
                totalBytes = 107_374_182_400L,   // 100 GB
                expireTimestamp = System.currentTimeMillis() + 86400000L * 25, // 25 days remaining
                lastUpdated = System.currentTimeMillis()
            )
        )
    )
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun selectProfile(id: String) {
        _selectedProfileId.value = id
    }

    fun addProfile(profile: Profile) {
        val list = _profiles.value.toMutableList()
        list.add(0, profile)
        _profiles.value = list
        _selectedProfileId.value = profile.id
    }

    fun updateProfile(profile: Profile) {
        val list = _profiles.value.map { if (it.id == profile.id) profile else it }
        _profiles.value = list
    }

    fun deleteProfile(id: String) {
        val list = _profiles.value.filter { it.id != id }
        _profiles.value = list
        if (_selectedProfileId.value == id) {
            _selectedProfileId.value = list.firstOrNull()?.id
        }
    }

    fun duplicateProfile(profile: Profile) {
        val copy = profile.copy(
            id = "profile_${System.currentTimeMillis()}",
            name = "${profile.name} (Copy)"
        )
        addProfile(copy)
    }

    fun addSubscription(sub: Subscription, newProfiles: List<Profile>) {
        val currentSubs = _subscriptions.value.filter { it.id != sub.id }.toMutableList()
        currentSubs.add(0, sub)
        _subscriptions.value = currentSubs

        // Replace or add subscription profiles
        val otherProfiles = _profiles.value.filter { it.subscriptionId != sub.id }.toMutableList()
        otherProfiles.addAll(0, newProfiles)
        _profiles.value = otherProfiles

        if (_selectedProfileId.value == null || !otherProfiles.any { it.id == _selectedProfileId.value }) {
            _selectedProfileId.value = otherProfiles.firstOrNull()?.id
        }
    }

    fun deleteSubscription(subId: String) {
        _subscriptions.value = _subscriptions.value.filter { it.id != subId }
        val remainingProfiles = _profiles.value.filter { it.subscriptionId != subId }
        _profiles.value = remainingProfiles
        if (_selectedProfileId.value != null && remainingProfiles.none { it.id == _selectedProfileId.value }) {
            _selectedProfileId.value = remainingProfiles.firstOrNull()?.id
        }
    }

    fun updateSettings(newSettings: AppSettings) {
        _settings.value = newSettings
    }

    fun exportProfileUri(profileId: String): String? {
        val profile = _profiles.value.find { it.id == profileId } ?: return null
        val handler = ProtocolRegistry.getHandler(profile.protocolType) ?: return null
        return handler.formatUri(profile)
    }
}

