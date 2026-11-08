package nullvp.data.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import nullvp.domain.model.AppSettings
import nullvp.domain.model.CustomRoutingRule
import nullvp.domain.model.OperationMode
import nullvp.domain.model.Profile
import nullvp.domain.model.ProtocolType
import nullvp.domain.model.Subscription
import nullvp.domain.protocol.ProtocolRegistry
import nullvp.i18n.AppLanguage
import nullvp.platform.StorageHelper

@Serializable
data class SavedAppState(
    val profiles: List<Profile> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val selectedProfileId: String? = null,
    val settings: AppSettings = AppSettings()
)

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

data class LogEntry(
    val id: Long = System.nanoTime(),
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    fun toFormattedString(): String {
        val seconds = (timestamp / 1000) % 86400
        val hours = (seconds / 3600) % 24
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        val millis = timestamp % 1000
        val timeStr = "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}.${millis.toString().padStart(3, '0')}"
        return "[$timeStr] [${level.name.padEnd(5)}] [$tag] $message"
    }
}

object AppStateStore {

    private val defaultProfiles: List<Profile> = emptyList()

    private val _profiles = MutableStateFlow<List<Profile>>(defaultProfiles)
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _selectedProfileId = MutableStateFlow<String?>(null)
    val selectedProfileId: StateFlow<String?> = _selectedProfileId.asStateFlow()

    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _routingSettingsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val routingSettingsChanged: SharedFlow<Unit> = _routingSettingsChanged.asSharedFlow()

    private val jsonSerializer = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    init {
        val savedJson = StorageHelper.readStateJson()
        if (!savedJson.isNullOrBlank()) {
            runCatching {
                val loaded = jsonSerializer.decodeFromString(SavedAppState.serializer(), savedJson)
                val cleanedProfiles = deduplicateVkProfiles(loaded.profiles)
                _profiles.value = cleanedProfiles
                _subscriptions.value = loaded.subscriptions
                _selectedProfileId.value = if (cleanedProfiles.any { it.id == loaded.selectedProfileId }) loaded.selectedProfileId else cleanedProfiles.firstOrNull()?.id
                val legacyRuleIds = setOf("rule-ads", "rule-lan", "rule-ru-gov-banks")
                val cleanedRules = loaded.settings.customRules.filterNot { it.id in legacyRuleIds }
                val cleanedSettings = if (cleanedRules.size != loaded.settings.customRules.size) {
                    loaded.settings.copy(customRules = cleanedRules)
                } else {
                    loaded.settings
                }
                _settings.value = cleanedSettings
                if (cleanedProfiles.size != loaded.profiles.size || cleanedRules.size != loaded.settings.customRules.size) {
                    saveState()
                }
            }
        }
    }

    fun deduplicateVkProfiles(profiles: List<Profile>): List<Profile> {
        val mailruCleaned = deduplicateMailruProfiles(profiles)
        val result = mutableListOf<Profile>()
        val seenEndpoints = mutableMapOf<String, Profile>()

        for (profile in mailruCleaned) {
            if (profile.protocolType == ProtocolType.VK_TURN) {
                var vkLink = ""
                var hasSecretKey = false
                var hasWgConf = false
                var hasFreeturnProp = false

                runCatching {
                    val obj = Json.parseToJsonElement(profile.protocolRawConfig).jsonObject
                    vkLink = obj["vkLink"]?.jsonPrimitive?.contentOrNull
                        ?: obj["vk"]?.jsonPrimitive?.contentOrNull
                        ?: ""
                    hasSecretKey = obj.containsKey("secretKey")
                    hasWgConf = obj.containsKey("wgConf")
                    hasFreeturnProp = obj.containsKey("freeturn")
                }

                val endpointKey = if (vkLink.isNotBlank()) {
                    "vk_$vkLink"
                } else {
                    "addr_${profile.serverAddress}:${profile.serverPort}_${profile.name}"
                }

                val existing = seenEndpoints[endpointKey]
                if (existing != null) {
                    val existingHasKeys = existing.protocolRawConfig.contains("secretKey") ||
                            existing.protocolRawConfig.contains("wgConf") ||
                            existing.protocolRawConfig.contains("freeturn")
                    val currentHasKeys = hasSecretKey || hasWgConf || hasFreeturnProp

                    if (currentHasKeys && !existingHasKeys) {
                        val idx = result.indexOf(existing)
                        if (idx != -1) {
                            result[idx] = profile
                        }
                        seenEndpoints[endpointKey] = profile
                    } else if (profile.protocolRawConfig.length > existing.protocolRawConfig.length && !existingHasKeys) {
                        val idx = result.indexOf(existing)
                        if (idx != -1) {
                            result[idx] = profile
                        }
                        seenEndpoints[endpointKey] = profile
                    }
                } else {
                    seenEndpoints[endpointKey] = profile
                    result.add(profile)
                }
            } else {
                result.add(profile)
            }
        }
        return result
    }

    fun deduplicateMailruProfiles(profiles: List<Profile>): List<Profile> {
        val result = mutableListOf<Profile>()
        val seenDocuments = mutableMapOf<String, Profile>()

        for (profile in profiles) {
            if (profile.protocolType == ProtocolType.MAILRU) {
                var docUrl = ""
                var format = ""
                runCatching {
                    val obj = Json.parseToJsonElement(profile.protocolRawConfig).jsonObject
                    docUrl = obj["url"]?.jsonPrimitive?.contentOrNull
                        ?: obj["mailruUrl"]?.jsonPrimitive?.contentOrNull
                        ?: obj["weblink"]?.jsonPrimitive?.contentOrNull
                        ?: ""
                    format = obj["format"]?.jsonPrimitive?.contentOrNull ?: ""
                }

                val docKey = normalizeMailruDocKey(docUrl)
                if (docKey.isBlank()) {
                    result.add(profile)
                    continue
                }

                val existing = seenDocuments[docKey]
                if (existing != null) {
                    var existingFormat = ""
                    runCatching {
                        val obj = Json.parseToJsonElement(existing.protocolRawConfig).jsonObject
                        existingFormat = obj["format"]?.jsonPrimitive?.contentOrNull ?: ""
                    }

                    // If existing is OpenFlux and current is native Mail.ru, swap it to keep Mail.ru!
                    if (existingFormat == "openflux" && format == "mailru") {
                        val idx = result.indexOf(existing)
                        if (idx != -1) {
                            result[idx] = profile
                        }
                        seenDocuments[docKey] = profile
                    } else if (existingFormat == "mailru" && format == "openflux") {
                        // Drop duplicate OpenFlux profile, keeping custom Mail.ru client format!
                        // Do not add to result.
                    } else if (profile.protocolRawConfig.length > existing.protocolRawConfig.length) {
                        val idx = result.indexOf(existing)
                        if (idx != -1) {
                            result[idx] = profile
                        }
                        seenDocuments[docKey] = profile
                    }
                } else {
                    seenDocuments[docKey] = profile
                    result.add(profile)
                }
            } else {
                result.add(profile)
            }
        }
        return result
    }

    private fun normalizeMailruDocKey(url: String): String {
        val trimmed = url.trim()
        val publicIdx = trimmed.indexOf("/public/")
        if (publicIdx != -1) {
            return trimmed.substring(publicIdx + 8).trimEnd('/').lowercase()
        }
        val slashParts = trimmed.split('/')
        if (slashParts.size >= 2) {
            return "${slashParts[slashParts.size - 2]}/${slashParts[slashParts.size - 1]}".lowercase()
        }
        return trimmed.lowercase()
    }

    private fun saveState() {
        val state = SavedAppState(
            profiles = _profiles.value,
            subscriptions = _subscriptions.value,
            selectedProfileId = _selectedProfileId.value,
            settings = _settings.value
        )
        val text = runCatching { jsonSerializer.encodeToString(SavedAppState.serializer(), state) }.getOrNull()
        if (text != null) {
            StorageHelper.writeStateJson(text)
        }
    }

    private val _logs = MutableStateFlow<List<LogEntry>>(
        listOf(
            LogEntry(level = LogLevel.INFO, tag = "CoreEngine", message = "NullVP Client v1.0.0 initialized"),
            LogEntry(level = LogLevel.INFO, tag = "Platform", message = "System environment ready. Proxy and routing configured"),
            LogEntry(level = LogLevel.INFO, tag = "Routing", message = "Bypass rules loaded: RU services and private LAN direct, Ads blocked"),
            LogEntry(level = LogLevel.DEBUG, tag = "DNS", message = "Remote DNS: 1.1.1.1 (DoH), Direct DNS: 77.88.8.8")
        )
    )
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun selectProfile(id: String) {
        _selectedProfileId.value = id
        saveState()
    }

    fun addProfile(profile: Profile) {
        val list = _profiles.value.toMutableList()
        val safeId = if (list.any { it.id == profile.id }) {
            "${profile.id}_${System.currentTimeMillis()}"
        } else {
            profile.id
        }
        val safeProfile = if (safeId != profile.id) profile.copy(id = safeId) else profile
        list.add(0, safeProfile)
        _profiles.value = list
        _selectedProfileId.value = safeProfile.id
        saveState()
        addLog(LogLevel.INFO, "ProfileManager", "Added profile '${safeProfile.name}' (${safeProfile.protocolType})")
    }

    fun updateProfile(profile: Profile) {
        val list = _profiles.value.map { if (it.id == profile.id) profile else it }
        _profiles.value = list
        saveState()
        addLog(LogLevel.INFO, "ProfileManager", "Updated profile '${profile.name}'")
    }

    fun deleteProfile(id: String) {
        val list = _profiles.value.filter { it.id != id }
        _profiles.value = list
        if (_selectedProfileId.value == id) {
            _selectedProfileId.value = list.firstOrNull()?.id
        }
        saveState()
        addLog(LogLevel.INFO, "ProfileManager", "Deleted profile ID: $id")
    }

    fun duplicateProfile(profile: Profile) {
        val copy = profile.copy(
            id = "profile_${System.currentTimeMillis()}_${(100..999).random()}",
            name = "${profile.name} (Copy)"
        )
        addProfile(copy)
    }

    fun addSubscription(sub: Subscription, newProfiles: List<Profile>) {
        val currentSubs = _subscriptions.value.filter { it.id != sub.id }.toMutableList()
        currentSubs.add(0, sub)
        _subscriptions.value = currentSubs

        val otherProfiles = _profiles.value.filter { it.subscriptionId != sub.id }.toMutableList()
        val existingIds = otherProfiles.map { it.id }.toMutableSet()

        val cleanedNew = deduplicateVkProfiles(newProfiles)
        val deduplicatedProfiles = cleanedNew.mapIndexed { idx, p ->
            var candidateId = "${p.id}_${idx}"
            while (existingIds.contains(candidateId)) {
                candidateId = "${p.id}_${idx}_${System.nanoTime().toString(36)}"
            }
            existingIds.add(candidateId)
            p.copy(id = candidateId, subscriptionId = sub.id)
        }

        otherProfiles.addAll(0, deduplicatedProfiles)
        _profiles.value = otherProfiles

        if (_selectedProfileId.value == null || !otherProfiles.any { it.id == _selectedProfileId.value }) {
            _selectedProfileId.value = otherProfiles.firstOrNull()?.id
        }
        saveState()
        addLog(LogLevel.INFO, "SubscriptionManager", "Imported subscription '${sub.name}' with ${deduplicatedProfiles.size} nodes")
    }

    fun renameSubscription(subId: String, newName: String) {
        _subscriptions.value = _subscriptions.value.map {
            if (it.id == subId) it.copy(name = newName) else it
        }
        saveState()
        addLog(LogLevel.INFO, "SubscriptionManager", "Renamed subscription to '$newName'")
    }

    fun deleteSubscription(subId: String) {
        _subscriptions.value = _subscriptions.value.filter { it.id != subId }
        val remainingProfiles = _profiles.value.filter { it.subscriptionId != subId }
        _profiles.value = remainingProfiles
        if (_selectedProfileId.value != null && remainingProfiles.none { it.id == _selectedProfileId.value }) {
            _selectedProfileId.value = remainingProfiles.firstOrNull()?.id
        }
        saveState()
        addLog(LogLevel.INFO, "SubscriptionManager", "Deleted subscription ID: $subId")
    }

    fun updateSettings(newSettings: AppSettings) {
        val old = _settings.value
        val routingChanged = old.routingStrategy != newSettings.routingStrategy ||
                old.blockAds != newSettings.blockAds ||
                old.bypassLan != newSettings.bypassLan ||
                old.bypassRu != newSettings.bypassRu ||
                old.dynamicRuAppScannerEnabled != newSettings.dynamicRuAppScannerEnabled ||
                old.socksPort != newSettings.socksPort ||
                old.httpPort != newSettings.httpPort ||
                old.proxyAuthEnabled != newSettings.proxyAuthEnabled ||
                old.proxyUsername != newSettings.proxyUsername ||
                old.proxyPassword != newSettings.proxyPassword ||
                old.customRules != newSettings.customRules ||
                old.remoteDns != newSettings.remoteDns ||
                old.directDns != newSettings.directDns ||
                old.fakeDns != newSettings.fakeDns ||
                old.sniffingEnabled != newSettings.sniffingEnabled ||
                old.fragmentEnabled != newSettings.fragmentEnabled ||
                old.fragmentPackets != newSettings.fragmentPackets ||
                old.fragmentLength != newSettings.fragmentLength ||
                old.fragmentInterval != newSettings.fragmentInterval ||
                old.muxEnabled != newSettings.muxEnabled ||
                old.muxConcurrency != newSettings.muxConcurrency

        _settings.value = newSettings
        saveState()
        addLog(LogLevel.INFO, "Settings", "Settings updated. Mode: ${newSettings.operationMode}, Routing: ${newSettings.routingStrategy}")
        if (routingChanged) {
            _routingSettingsChanged.tryEmit(Unit)
        }
    }

    fun setLanguage(lang: AppLanguage) {
        _settings.value = _settings.value.copy(language = lang)
        saveState()
        addLog(LogLevel.INFO, "Settings", "Language changed to ${lang.displayName}")
    }

    fun setOperationMode(mode: OperationMode) {
        val oldMode = _settings.value.operationMode
        _settings.value = _settings.value.copy(operationMode = mode)
        saveState()
        addLog(LogLevel.INFO, "Settings", "Operation mode changed to $mode")
        if (oldMode != mode) {
            _routingSettingsChanged.tryEmit(Unit)
        }
    }

    fun addCustomRule(rule: CustomRoutingRule) {
        val updated = _settings.value.customRules.toMutableList()
        updated.add(rule)
        _settings.value = _settings.value.copy(customRules = updated)
        saveState()
        addLog(LogLevel.INFO, "Routing", "Added custom rule '${rule.name}' -> ${rule.outbound}")
        _routingSettingsChanged.tryEmit(Unit)
    }

    fun updateCustomRule(rule: CustomRoutingRule) {
        val updated = _settings.value.customRules.map { if (it.id == rule.id) rule else it }
        _settings.value = _settings.value.copy(customRules = updated)
        saveState()
        addLog(LogLevel.INFO, "Routing", "Updated custom rule '${rule.name}'")
        _routingSettingsChanged.tryEmit(Unit)
    }

    fun deleteCustomRule(ruleId: String) {
        val updated = _settings.value.customRules.filter { it.id != ruleId }
        _settings.value = _settings.value.copy(customRules = updated)
        saveState()
        addLog(LogLevel.INFO, "Routing", "Deleted custom rule ID: $ruleId")
        _routingSettingsChanged.tryEmit(Unit)
    }

    fun addLog(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(level = level, tag = tag, message = message)
        val current = _logs.value.toMutableList()
        if (current.size > 2000) {
            current.removeAt(0)
        }
        current.add(entry)
        _logs.value = current
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun exportLogsToString(): String {
        return _logs.value.joinToString("\n") { it.toFormattedString() }
    }

    fun exportProfileUri(profileId: String): String? {
        val profile = _profiles.value.find { it.id == profileId } ?: return null
        val handler = ProtocolRegistry.getHandler(profile.protocolType) ?: return null
        return handler.formatUri(profile)
    }
}
